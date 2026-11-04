//! Optional local Gooby SOCKS5 connector. No proxy process or configuration is
//! managed here; Gooby retains its own CLI, rule archive and Lua API.
use anyhow::{Context, Result, bail};
use std::{
    fmt,
    net::{IpAddr, SocketAddr},
    str::FromStr,
};
use tokio::{
    io::{AsyncReadExt, AsyncWriteExt},
    net::TcpStream,
};

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct GoobyProxy(SocketAddr);

impl GoobyProxy {
    pub fn address(self) -> SocketAddr {
        self.0
    }
}

impl FromStr for GoobyProxy {
    type Err = anyhow::Error;

    fn from_str(value: &str) -> Result<Self> {
        let address: SocketAddr = value
            .parse()
            .context("Gooby: expected a local IP:port, e.g. 127.0.0.1:1080")?;
        if !address.ip().is_loopback() || address.port() == 0 {
            bail!("Gooby: use a loopback address and a nonzero port");
        }
        Ok(Self(address))
    }
}

impl fmt::Display for GoobyProxy {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        self.0.fmt(f)
    }
}

fn connect_request(host: &str, port: u16) -> Result<Vec<u8>> {
    if port == 0 {
        bail!("Gooby: destination port cannot be zero");
    }
    let mut request = vec![5, 1, 0]; // SOCKS5 CONNECT, reserved byte
    match host.parse::<IpAddr>() {
        Ok(IpAddr::V4(ip)) => {
            request.push(1);
            request.extend_from_slice(&ip.octets());
        }
        Ok(IpAddr::V6(ip)) => {
            request.push(4);
            request.extend_from_slice(&ip.octets());
        }
        Err(_) => {
            if host.is_empty()
                || host.len() > 255
                || !host.is_ascii()
                || host
                    .bytes()
                    .any(|b| b.is_ascii_control() || b.is_ascii_whitespace())
            {
                bail!("Gooby: destination must be an IP or ASCII DNS name of 1..255 bytes");
            }
            request.extend_from_slice(&[3, host.len() as u8]);
            request.extend_from_slice(host.as_bytes());
        }
    }
    request.extend_from_slice(&port.to_be_bytes());
    Ok(request)
}

/// The caller bounds the entire connect + negotiation operation with a timeout.
/// Read exact reply lengths so coalesced TURN/TLS bytes remain in the socket.
pub async fn connect(proxy: GoobyProxy, host: &str, port: u16) -> Result<TcpStream> {
    let request = connect_request(host, port)?;
    let mut socket = TcpStream::connect(proxy.address())
        .await
        .with_context(|| format!("Gooby: connecting to {proxy}"))?;
    socket.set_nodelay(true)?;
    socket
        .write_all(&[5, 1, 0])
        .await
        .context("Gooby: sending SOCKS5 greeting")?;
    let mut method = [0; 2];
    socket
        .read_exact(&mut method)
        .await
        .context("Gooby: reading SOCKS5 method")?;
    if method != [5, 0] {
        bail!("Gooby: SOCKS5 no-auth method rejected ({method:02x?})");
    }
    socket
        .write_all(&request)
        .await
        .context("Gooby: sending SOCKS5 CONNECT")?;
    let mut header = [0; 4];
    socket
        .read_exact(&mut header)
        .await
        .context("Gooby: reading SOCKS5 CONNECT reply")?;
    if header[0] != 5 || header[2] != 0 {
        bail!("Gooby: malformed SOCKS5 reply header");
    }
    if header[1] != 0 {
        bail!(
            "Gooby: SOCKS5 CONNECT rejected: {} (0x{:02x})",
            reply_error(header[1]),
            header[1]
        );
    }
    let address_len = match header[3] {
        1 => 4,
        4 => 16,
        3 => {
            let len = socket
                .read_u8()
                .await
                .context("Gooby: reading bound hostname length")?;
            if len == 0 {
                bail!("Gooby: empty bound hostname");
            }
            usize::from(len)
        }
        kind => bail!("Gooby: unsupported SOCKS5 bound address type {kind}"),
    };
    let mut bound = [0; 257]; // at most 255 bytes of name + port
    socket
        .read_exact(&mut bound[..address_len + 2])
        .await
        .context("Gooby: reading SOCKS5 bound address")?;
    Ok(socket)
}

fn reply_error(code: u8) -> &'static str {
    match code {
        1 => "general failure",
        2 => "connection not allowed",
        3 => "network unreachable",
        4 => "host unreachable",
        5 => "connection refused",
        6 => "TTL expired",
        7 => "CONNECT not supported",
        8 => "address type not supported",
        _ => "unknown error",
    }
}

#[cfg(test)]
#[path = "gooby_tests.rs"]
pub(crate) mod tests;
