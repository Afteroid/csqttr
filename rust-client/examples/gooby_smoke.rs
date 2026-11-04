//! Local-only Android/desktop smoke check for the bundled engine.
//! Usage: gooby_smoke [original-gooby-config.zip]
#[path = "../gooby.rs"]
mod gooby;

use anyhow::{ensure, Result};
use tokio::{
    io::{AsyncReadExt, AsyncWriteExt},
    net::{TcpListener, TcpStream},
    time::{timeout, Duration},
};

#[tokio::main]
async fn main() -> Result<()> {
    timeout(Duration::from_secs(5), check()).await??;
    println!("GOOBY_EMBEDDED_OK: loopback transfer and shutdown");
    Ok(())
}

async fn check() -> Result<()> {
    let custom = std::env::args().nth(1);
    let bundle = match &custom {
        Some(path) => csqtt_gooby::ConfigBundle::from_zip_path(std::path::Path::new(&path))?,
        None => csqtt_gooby::ConfigBundle::builtin_turn()?,
    };
    let runtime = csqtt_gooby::Runtime::start(bundle, true).await?;
    let proxy = runtime.address();
    let listener = TcpListener::bind("127.0.0.1:0").await?;
    let port = listener.local_addr()?.port();
    let server = tokio::spawn(async move {
        let (mut socket, _) = listener.accept().await?;
        let mut bytes = vec![0; 16384];
        socket.read_exact(&mut bytes).await?;
        socket.write_all(&bytes).await?;
        Ok::<_, anyhow::Error>(())
    });
    let mut socket = gooby::connect(proxy.to_string().parse()?, if custom.is_some() { "localhost" } else { "127.0.0.1" }, port).await?;
    let mut payload = vec![0x42; 16384];
    payload[..8].copy_from_slice(&[0, 3, 0, 8, 0x21, 0x12, 0xa4, 0x42]);
    socket.write_all(&payload).await?;
    let mut reply = vec![0; payload.len()];
    socket.read_exact(&mut reply).await?;
    ensure!(reply == payload, "Gooby changed the payload");
    if custom.is_none() { ensure!(runtime.stats().turn_allocate_requests == 1, "TURN strategy did not apply to an IP target"); }
    server.await??;
    runtime.shutdown().await;
    ensure!(
        TcpStream::connect(proxy).await.is_err(),
        "Gooby listener leaked"
    );
    Ok(())
}
