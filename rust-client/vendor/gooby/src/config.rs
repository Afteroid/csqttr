use std::{
    collections::HashMap,
    fs,
    io::{Cursor, Read, Write},
    path::Path,
};

use anyhow::{anyhow, bail, Context, Result};
use base64::{engine::general_purpose, Engine as _};
use rusqlite::Connection;
use serde_json::Value as JsonValue;
use tempfile::NamedTempFile;
use zip::ZipArchive;

use crate::rules::{Rule, RuleSet, Strategy};

const MAX_ZIP_BYTES: usize = 32 * 1024 * 1024;
const MAX_DB_BYTES: usize = 16 * 1024 * 1024;
const MAX_SCRIPT_BYTES: usize = 1024 * 1024;

#[derive(Debug, Clone)]
pub struct ConfigBundle {
    rules: RuleSet,
    scripts: HashMap<String, String>,
    pub(crate) turn_tcp: bool,
    pub(crate) preset: crate::Preset,
}

impl ConfigBundle {
    pub fn with_preset(mut self, preset: crate::Preset) -> Self { self.preset = preset; self }

    pub fn builtin_base() -> Result<Self> {
        Ok(Self {
            rules: RuleSet::new(vec![Rule::new(vec!["*".to_owned()], "base", None)?]),
            scripts: HashMap::new(),
            turn_tcp: false,
            preset: crate::Preset::Current,
        })
    }

    /// CSQTTR preset: TURN Allocate splitting for IPs and domains; TLS uses base.
    /// Original ZIP/base rule semantics remain unchanged.
    pub fn builtin_turn() -> Result<Self> {
        let mut bundle = Self::builtin_base()?;
        bundle.turn_tcp = true;
        Ok(bundle)
    }

    pub fn from_zip_path(path: &Path) -> Result<Self> {
        let bytes = fs::read(path).with_context(|| format!("reading config ZIP {}", path.display()))?;
        if bytes.len() > MAX_ZIP_BYTES {
            bail!("config ZIP is larger than {MAX_ZIP_BYTES} bytes");
        }
        Self::from_zip_bytes(bytes)
    }

    pub fn from_base64(value: &str) -> Result<Self> {
        let cleaned: String = value.chars().filter(|c| !c.is_whitespace()).collect();
        let bytes = general_purpose::STANDARD
            .decode(cleaned.as_bytes())
            .or_else(|_| general_purpose::URL_SAFE_NO_PAD.decode(cleaned.as_bytes()))
            .context("decoding --b64_conf")?;
        if bytes.len() > MAX_ZIP_BYTES {
            bail!("decoded config ZIP is larger than {MAX_ZIP_BYTES} bytes");
        }
        Self::from_zip_bytes(bytes)
    }

    fn from_zip_bytes(bytes: Vec<u8>) -> Result<Self> {
        let cursor = Cursor::new(bytes);
        let mut archive = ZipArchive::new(cursor).context("opening config as ZIP")?;

        let db_bytes = {
            let mut file = archive
                .by_name("rules.db")
                .context("config ZIP must contain rules.db at archive root")?;
            if file.size() as usize > MAX_DB_BYTES {
                bail!("rules.db is larger than {MAX_DB_BYTES} bytes");
            }
            let mut data = Vec::with_capacity(file.size() as usize);
            file.read_to_end(&mut data)?;
            data
        };

        let rules = load_rules_from_sqlite(&db_bytes)?;
        let mut scripts = HashMap::new();

        for index in 0..archive.len() {
            let mut file = archive.by_index(index)?;
            if file.is_dir() {
                continue;
            }
            let name = file.name().replace('\\', "/");
            if !name.starts_with("scripts/") || !name.ends_with(".lua") {
                continue;
            }
            let script_name = &name["scripts/".len()..];
            if script_name.is_empty() || script_name.contains('/') || script_name == "." || script_name == ".." {
                bail!("scripts must be direct children of scripts/: {name}");
            }
            if file.size() as usize > MAX_SCRIPT_BYTES {
                bail!("script {script_name} is larger than {MAX_SCRIPT_BYTES} bytes");
            }
            let mut source = String::new();
            file.read_to_string(&mut source)
                .with_context(|| format!("reading UTF-8 Lua script {name}"))?;
            if scripts.insert(script_name.to_owned(), source).is_some() {
                bail!("duplicate script name {script_name}");
            }
        }

        for rule in rules.iter() {
            if let Strategy::Custom { script } = &rule.strategy {
                if !scripts.contains_key(script) {
                    bail!("rule references scripts/{script}, but that file is absent from ZIP");
                }
            }
        }

        Ok(Self { rules, scripts, turn_tcp: false, preset: crate::Preset::Current })
    }

    pub fn rules(&self) -> &RuleSet {
        &self.rules
    }

    pub fn script(&self, name: &str) -> Option<&str> {
        self.scripts.get(name).map(String::as_str)
    }

    pub fn script_count(&self) -> usize {
        self.scripts.len()
    }
}

fn load_rules_from_sqlite(db_bytes: &[u8]) -> Result<RuleSet> {
    let mut temp = NamedTempFile::new().context("creating temporary SQLite file")?;
    temp.write_all(db_bytes)?;
    temp.flush()?;

    let conn = Connection::open_with_flags(
        temp.path(),
        rusqlite::OpenFlags::SQLITE_OPEN_READ_ONLY | rusqlite::OpenFlags::SQLITE_OPEN_NO_MUTEX,
    )?;
    let table = find_rules_table(&conn)?;
    let quoted = quote_identifier(&table);
    let sql = format!("SELECT domains, strategy, script FROM {quoted}");
    let mut stmt = conn.prepare(&sql)?;
    let mut rows = stmt.query([])?;
    let mut rules = Vec::new();

    while let Some(row) = rows.next()? {
        let domains_json: String = row.get(0)?;
        let strategy: String = row.get(1)?;
        let script: Option<String> = row.get(2)?;

        let value: JsonValue = serde_json::from_str(&domains_json)
            .with_context(|| format!("invalid domains JSON: {domains_json}"))?;
        let array = value
            .as_array()
            .ok_or_else(|| anyhow!("domains must be a JSON array, got {domains_json}"))?;
        let mut domains = Vec::with_capacity(array.len());
        for item in array {
            let domain = item
                .as_str()
                .ok_or_else(|| anyhow!("every domains element must be a string"))?;
            domains.push(domain.to_owned());
        }

        rules.push(Rule::new(domains, &strategy, script)?);
    }

    Ok(RuleSet::new(rules))
}

fn find_rules_table(conn: &Connection) -> Result<String> {
    let mut stmt = conn.prepare(
        "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name",
    )?;
    let names = stmt
        .query_map([], |row| row.get::<_, String>(0))?
        .collect::<rusqlite::Result<Vec<_>>>()?;

    for name in names {
        let pragma = format!("PRAGMA table_info({})", quote_identifier(&name));
        let mut columns_stmt = conn.prepare(&pragma)?;
        let columns = columns_stmt
            .query_map([], |row| row.get::<_, String>(1))?
            .collect::<rusqlite::Result<Vec<_>>>()?;
        let has = |needle: &str| columns.iter().any(|c| c.eq_ignore_ascii_case(needle));
        if has("domains") && has("strategy") && has("script") {
            return Ok(name);
        }
    }

    bail!("rules.db has no table containing domains, strategy, and script columns")
}

fn quote_identifier(name: &str) -> String {
    format!("\"{}\"", name.replace('"', "\"\""))
}
