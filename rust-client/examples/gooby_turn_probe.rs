//! Manual, bounded live interoperability probe, no credentials or relay traffic.
//! cargo run --example gooby_turn_probe -- IP:PORT [IP:PORT ...]
#[path = "../gooby.rs"]
mod gooby;

use anyhow::{ensure, Context, Result};
use std::{
    net::SocketAddr,
    time::{SystemTime, UNIX_EPOCH},
};
use tokio::{
    io::{AsyncReadExt, AsyncWriteExt},
    net::TcpStream,
    time::{timeout, Duration},
};

fn allocate(sequence: u8) -> Vec<u8> {
    let mut packet = vec![0, 3, 0, 32, 0x21, 0x12, 0xa4, 0x42];
    let nonce = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap()
        .as_nanos()
        .to_be_bytes();
    packet.extend_from_slice(&nonce[4..]);
    packet[19] ^= sequence;
    // Same VK extension, requested UDP relay and IPv4 family as the client.
    packet.extend_from_slice(&[0x80, 0x21, 0, 4, 0xfc, 9, 0xb4, 0x6f]);
    packet.extend_from_slice(&[0, 0x19, 0, 4, 17, 0, 0, 0]);
    packet.extend_from_slice(&[0, 0x17, 0, 4, 1, 0, 0, 0]);
    let fingerprint = crc32fast::hash(&packet) ^ 0x5354_554e;
    packet.extend_from_slice(&[0x80, 0x28, 0, 4]);
    packet.extend_from_slice(&fingerprint.to_be_bytes());
    packet
}

async fn challenge(socket: &mut TcpStream, sequence: u8) -> Result<u16> {
    let request = allocate(sequence);
    socket.write_all(&request).await?;
    let mut header = [0; 20];
    socket.read_exact(&mut header).await?;
    ensure!(
        header[..2] == [1, 0x13] && header[4..] == request[4..20],
        "invalid TURN response/transaction"
    );
    let mut body = vec![0; u16::from_be_bytes([header[2], header[3]]) as usize];
    socket.read_exact(&mut body).await?;
    let mut error = None;
    let mut position = 0;
    while position + 4 <= body.len() {
        let attr = u16::from_be_bytes([body[position], body[position + 1]]);
        let len = u16::from_be_bytes([body[position + 2], body[position + 3]]) as usize;
        let start = position + 4;
        ensure!(start + len <= body.len(), "truncated TURN attribute");
        if attr == 9 && len >= 4 {
            error = Some(body[start + 2] as u16 * 100 + body[start + 3] as u16);
        }
        position = start + (len + 3) / 4 * 4;
    }
    error.context("missing TURN error code")
}

#[tokio::main]
async fn main() -> Result<()> {
    let addresses = std::env::args()
        .skip(1)
        .map(|s| s.parse::<SocketAddr>())
        .collect::<Result<Vec<_>, _>>()?;
    ensure!(
        !addresses.is_empty() && addresses.len() <= 4,
        "supply 1..4 known TURN IP:port endpoints"
    );
    let runtime =
        csqtt_gooby::Runtime::start(csqtt_gooby::ConfigBundle::builtin_turn()?, true).await?;
    for address in addresses {
        for split in [false, true] {
            let result = timeout(Duration::from_secs(8), async {
                let mut socket = if split {
                    gooby::connect(
                        runtime.address().to_string().parse()?,
                        &address.ip().to_string(),
                        address.port(),
                    )
                    .await?
                } else {
                    TcpStream::connect(address).await?
                };
                let first = challenge(&mut socket, 0).await?;
                let second = challenge(&mut socket, 1).await?;
                ensure!(
                    first == 401 && second == 401,
                    "expected unauthenticated 401 challenges"
                );
                Ok::<_, anyhow::Error>((first, second))
            })
            .await;
            match result {
                Ok(Ok((first, second))) => {
                    println!("{address} split={split} TURN_challenges={first},{second}")
                }
                other => anyhow::bail!("{address} split={split} failed: {other:?}"),
            }
        }
    }
    println!(
        "Applied: {:?}. No authenticated allocation or speed test.",
        runtime.stats()
    );
    runtime.shutdown().await;
    Ok(())
}
