use anyhow::{anyhow, bail, Context, Result};
use mlua::{Function, Lua, String as LuaString, Table, Value};

use crate::tls::fragment_tls_sni_record;

const MAX_OUTPUT_BYTES: usize = 1024 * 1024;
const MAX_OUTPUT_CHUNKS: usize = 4096;

#[derive(Debug, Clone)]
pub struct TransformPlan {
    pub chunks: Vec<Vec<u8>>,
    pub low_ttl_first: bool,
    pub delay_ms: u64,
}

impl TransformPlan {
    pub fn passthrough(data: &[u8]) -> Self {
        Self {
            chunks: vec![data.to_vec()],
            low_ttl_first: false,
            delay_ms: 0,
        }
    }
}

/// One Lua VM per custom-strategy TCP connection. This lets scripts keep state
/// in globals/upvalues between packets/chunks without sharing state across users.
pub struct LuaProcessor {
    lua: Lua,
    process: Function,
    host: String,
    port: u16,
    sequence: u64,
}

impl LuaProcessor {
    pub fn new(source: &str, script_name: &str, host: &str, port: u16) -> Result<Self> {
        let lua = Lua::new();
        install_gooby_api(&lua)?;

        lua.load(source)
            .set_name(script_name)
            .exec()
            .with_context(|| format!("executing {script_name}"))?;

        let process: Function = lua
            .globals()
            .get("process")
            .with_context(|| format!("{script_name} must define function process(packet, ctx)"))?;

        Ok(Self {
            lua,
            process,
            host: host.to_owned(),
            port,
            sequence: 0,
        })
    }

    pub fn process(&mut self, packet: &[u8], first: bool) -> Result<TransformPlan> {
        let ctx = self.lua.create_table()?;
        ctx.set("host", self.host.as_str())?;
        ctx.set("port", self.port)?;
        ctx.set("direction", "client_to_server")?;
        ctx.set("first", first)?;
        ctx.set("sequence", self.sequence)?;

        let packet_value = self.lua.create_string(packet)?;
        let value: Value = self.process.call((packet_value, ctx))?;
        self.sequence = self.sequence.saturating_add(1);
        parse_return(value, packet)
    }
}

fn install_gooby_api(lua: &Lua) -> Result<()> {
    let api = lua.create_table()?;
    let fragment = lua.create_function(|lua, (packet, chunk_size): (LuaString, Option<usize>)| {
        let bytes = packet.as_bytes();
        let Some(chunks) = fragment_tls_sni_record(bytes.as_ref(), chunk_size.unwrap_or(2)) else {
            return Ok(Value::Nil);
        };
        let table = lua.create_table()?;
        for (index, chunk) in chunks.into_iter().enumerate() {
            table.set(index + 1, lua.create_string(&chunk)?)?;
        }
        Ok(Value::Table(table))
    })?;
    api.set("fragment_tls_sni", fragment)?;
    lua.globals().set("gooby", api)?;
    Ok(())
}

fn parse_return(value: Value, original: &[u8]) -> Result<TransformPlan> {
    match value {
        Value::Nil => Ok(TransformPlan::passthrough(original)),
        Value::String(s) => Ok(TransformPlan {
            chunks: vec![s.as_bytes().to_vec()],
            low_ttl_first: false,
            delay_ms: 0,
        }),
        Value::Table(table) => parse_table(table, original),
        other => bail!(
            "process() must return nil, a binary string, or a table; got {}",
            other.type_name()
        ),
    }
}

fn parse_table(table: Table, original: &[u8]) -> Result<TransformPlan> {
    if table.get::<Option<bool>>("drop")?.unwrap_or(false) {
        return Ok(TransformPlan {
            chunks: Vec::new(),
            low_ttl_first: false,
            delay_ms: 0,
        });
    }

    let low_ttl_first = table
        .get::<Option<bool>>("low_ttl_first")?
        .unwrap_or(false);
    let delay_ms = table
        .get::<Option<u64>>("delay_ms")?
        .unwrap_or(0)
        .min(5_000);

    let chunks_table = table.get::<Option<Table>>("chunks")?;
    let has_explicit_chunks = chunks_table.is_some();
    let source_table = chunks_table.unwrap_or_else(|| table.clone());
    let mut chunks = Vec::new();
    let mut total = 0usize;

    for value in source_table.sequence_values::<Value>() {
        if chunks.len() >= MAX_OUTPUT_CHUNKS {
            bail!("Lua plan exceeds {MAX_OUTPUT_CHUNKS} chunks");
        }
        let value = value?;
        let Value::String(s) = value else {
            bail!("every Lua chunk must be a binary string");
        };
        let bytes = s.as_bytes();
        total = total
            .checked_add(bytes.len())
            .ok_or_else(|| anyhow!("Lua output length overflow"))?;
        if total > MAX_OUTPUT_BYTES {
            bail!("Lua plan exceeds {MAX_OUTPUT_BYTES} output bytes");
        }
        chunks.push(bytes.to_vec());
    }

    if chunks.is_empty() {
        if has_explicit_chunks {
            return Ok(TransformPlan {
                chunks,
                low_ttl_first,
                delay_ms,
            });
        }
        return Ok(TransformPlan::passthrough(original));
    }

    Ok(TransformPlan {
        chunks,
        low_ttl_first,
        delay_ms,
    })
}
