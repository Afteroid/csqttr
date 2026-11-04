use super::*;
use crate::gooby::{GoobyProxy, tests::accept_request};
use tokio::net::TcpListener;

fn endpoint(transport: TurnWireTransport) -> TurnEndpoint {
    TurnEndpoint {
        host: Arc::from("turn-test.invalid"),
        port: 443,
        transport,
    }
}

#[tokio::test]
async fn tls_over_gooby_preserves_sni_and_turn_frames() {
    check_tls(true, false, false).await;
}

#[tokio::test]
async fn tls_over_gooby_still_rejects_untrusted_certificate() {
    check_tls(false, false, false).await;
}

#[tokio::test]
#[ignore = "requires GOOBY_TEST_BIN and GOOBY_TEST_CONFIG (localhost base rule)"]
async fn real_gooby_base_tls_preserves_sni_and_turn_frames() {
    check_tls(true, true, false).await;
}

#[tokio::test]
async fn embedded_gooby_base_tls_preserves_sni_and_turn_frames() {
    check_tls(true, false, true).await;
}

#[tokio::test]
async fn embedded_gooby_turn_preset_tls_preserves_sni_and_rejects_bad_certificate() {
    for preset in [csqtt_gooby::Preset::Current, csqtt_gooby::Preset::Gentle, csqtt_gooby::Preset::Extended] {
        check_tls_strategy(true, false, true, true, preset).await;
        check_tls_strategy(false, false, true, true, preset).await;
    }
}

#[tokio::test]
async fn embedded_gooby_turn_tcp_ip_handles_challenge_retry_and_full_duplex_payload() {
    for preset in [csqtt_gooby::Preset::Current, csqtt_gooby::Preset::Gentle, csqtt_gooby::Preset::Extended] {
    timeout(Duration::from_secs(5), async {
        let runtime =
            csqtt_gooby::Runtime::start(csqtt_gooby::ConfigBundle::builtin_turn().unwrap().with_preset(preset), true)
                .await
                .unwrap();
        let upstream = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let address = upstream.local_addr().unwrap();
        let request = [
            0, 3, 0, 8, 0x21, 0x12, 0xa4, 0x42, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 0x00, 0x19,
            0, 4, 17, 0, 0, 0,
        ];
        let mut response = request;
        response[..2].copy_from_slice(&[0x01, 0x13]);
        response[20..].copy_from_slice(&[0, 9, 0, 4, 0, 0, 4, 1]); // 401 challenge
        let data = [0x40, 0, 0, 3, 1, 2, 3, 0];
        let server = tokio::spawn(async move {
            let (mut socket, _) = upstream.accept().await.unwrap();
            for _ in 0..2 {
                let mut received = [0; 28];
                socket.read_exact(&mut received).await.unwrap();
                assert_eq!(received, request);
                socket.write_all(&response).await.unwrap();
            }
            // Downlink is available before any uplink application data.
            socket.write_all(&data).await.unwrap();
            for _ in 0..2048 {
                let mut received = [0; 8];
                socket.read_exact(&mut received).await.unwrap();
                assert_eq!(received, data);
                socket.write_all(&data).await.unwrap();
            }
        });
        let target = TurnEndpoint {
            host: Arc::from("127.0.0.1"),
            port: address.port(),
            transport: TurnWireTransport::Tcp,
        };
        let (writer, mut reader, _) = connect_inner(
            &target,
            address,
            Some(runtime.address().to_string().parse().unwrap()),
            None,
        )
        .await
        .unwrap();
        let pool = PacketPool::new(4);
        for _ in 0..2 {
            writer.write_control(&request).await.unwrap();
            match reader.read_frame(&pool).await.unwrap() {
                TurnStreamFrame::Control(reply) => assert_eq!(reply, response),
                _ => panic!("expected 401 challenge"),
            }
        }
        assert!(matches!(
            reader.read_frame(&pool).await.unwrap(),
            TurnStreamFrame::Data(_)
        ));
        for _ in 0..2048 {
            writer.write_control(&data).await.unwrap();
            match reader.read_frame(&pool).await.unwrap() {
                TurnStreamFrame::Data(reply) => assert_eq!(reply.as_slice(), data),
                _ => panic!("expected channel data"),
            }
        }
        server.await.unwrap();
        assert_eq!(runtime.stats().turn_allocate_requests, 2);
        assert_eq!(runtime.stats().tls_client_hellos, 0);
        runtime.shutdown().await;
    })
    .await
    .unwrap();
    }
}

#[tokio::test]
async fn embedded_gooby_shutdown_closes_listener_and_connections() {
    let runtime =
        csqtt_gooby::Runtime::start(csqtt_gooby::ConfigBundle::builtin_base().unwrap(), false)
            .await
            .unwrap();
    let address = runtime.address();
    let upstream = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let mut client = crate::gooby::connect(
        address.to_string().parse().unwrap(),
        "127.0.0.1",
        upstream.local_addr().unwrap().port(),
    )
    .await
    .unwrap();
    let (mut remote, _) = upstream.accept().await.unwrap();
    client.write_all(b"hello").await.unwrap();
    let mut data = [0; 5];
    remote.read_exact(&mut data).await.unwrap();
    assert_eq!(&data, b"hello");
    runtime.shutdown().await;
    assert!(TcpStream::connect(address).await.is_err());
    assert_eq!(
        timeout(Duration::from_secs(2), remote.read(&mut data))
            .await
            .unwrap()
            .unwrap(),
        0
    );
    assert_eq!(
        timeout(Duration::from_secs(2), client.read(&mut data))
            .await
            .unwrap()
            .unwrap(),
        0
    );
}

struct GoobyProcess(std::process::Child);

#[tokio::test]
async fn embedded_gooby_turn_shutdown_cancels_wait_for_authenticated_retry() {
    timeout(Duration::from_secs(3), async {
        let runtime = csqtt_gooby::Runtime::start(csqtt_gooby::ConfigBundle::builtin_turn().unwrap(), true).await.unwrap();
        let upstream = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let mut client = crate::gooby::connect(runtime.address().to_string().parse().unwrap(), "127.0.0.1", upstream.local_addr().unwrap().port()).await.unwrap();
        let (mut remote, _) = upstream.accept().await.unwrap();
        let mut request = [0; 20];
        request[..8].copy_from_slice(&[0, 3, 0, 0, 0x21, 0x12, 0xa4, 0x42]);
        client.write_all(&request).await.unwrap();
        let mut received = [0; 20];
        remote.read_exact(&mut received).await.unwrap();
        assert_eq!(received, request);
        runtime.shutdown().await;
        assert_eq!(remote.read(&mut received).await.unwrap(), 0);
        assert_eq!(client.read(&mut received).await.unwrap(), 0);
    }).await.unwrap();
}

impl Drop for GoobyProcess {
    fn drop(&mut self) {
        let _ = self.0.kill();
        let _ = self.0.wait();
    }
}

async fn start_gooby() -> (GoobyProxy, GoobyProcess) {
    let reserved = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let address = reserved.local_addr().unwrap();
    drop(reserved);
    let child =
        std::process::Command::new(std::env::var("GOOBY_TEST_BIN").expect("GOOBY_TEST_BIN"))
            .arg(std::env::var("GOOBY_TEST_CONFIG").expect("GOOBY_TEST_CONFIG"))
            .args(["--listen", &address.to_string(), "--no-disorder"])
            .stdout(std::process::Stdio::null())
            .stderr(std::process::Stdio::null())
            .spawn()
            .unwrap();
    let mut child = GoobyProcess(child);
    timeout(Duration::from_secs(5), async {
        loop {
            assert!(
                child.0.try_wait().unwrap().is_none(),
                "Gooby exited during startup"
            );
            if TcpStream::connect(address).await.is_ok() {
                break;
            }
            tokio::time::sleep(Duration::from_millis(20)).await;
        }
    })
    .await
    .expect("Gooby startup timeout");
    (address.to_string().parse().unwrap(), child)
}

async fn check_tls(trust_certificate: bool, external: bool, embedded: bool) {
    check_tls_preset(trust_certificate, external, embedded, false).await;
}

async fn check_tls_preset(trust_certificate: bool, external: bool, embedded: bool, turn: bool) {
    check_tls_strategy(trust_certificate, external, embedded, turn, csqtt_gooby::Preset::Current).await;
}

async fn check_tls_strategy(trust_certificate: bool, external: bool, embedded: bool, turn: bool, preset: csqtt_gooby::Preset) {
    let host = if external || embedded {
        "localhost"
    } else {
        "turn-test.invalid"
    };
    let cert = rcgen::generate_simple_self_signed(vec![host.to_owned()]).unwrap();
    let key = turn_rustls::pki_types::PrivatePkcs8KeyDer::from(cert.key_pair.serialize_der());
    let server_config = turn_rustls::ServerConfig::builder()
        .with_no_client_auth()
        .with_single_cert(vec![cert.cert.der().clone()], key.into())
        .unwrap();
    let mut roots = RootCertStore::empty();
    if trust_certificate {
        roots.add(cert.cert.der().clone()).unwrap();
    }
    let client_config = Arc::new(
        ClientConfig::builder()
            .with_root_certificates(roots)
            .with_no_client_auth(),
    );
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let address = listener.local_addr().unwrap();
    let embedded_runtime = if embedded {
        Some(
            csqtt_gooby::Runtime::start(
                if turn {
                    csqtt_gooby::ConfigBundle::builtin_turn().unwrap().with_preset(preset)
                } else {
                    csqtt_gooby::ConfigBundle::builtin_base().unwrap()
                },
                true,
            )
            .await
            .unwrap(),
        )
    } else {
        None
    };
    let (proxy, _process) = if let Some(runtime) = &embedded_runtime {
        (runtime.address().to_string().parse().unwrap(), None)
    } else if external {
        let (proxy, process) = start_gooby().await;
        (proxy, Some(process))
    } else {
        (address.to_string().parse::<GoobyProxy>().unwrap(), None)
    };
    let frame = [0x40, 0, 0, 3, 0xa1, 0xb2, 0xc3, 0];
    let server = tokio::spawn(async move {
        let (mut socket, _) = listener.accept().await.unwrap();
        if !external && !embedded {
            accept_request(&mut socket, host, 443).await;
            socket
                .write_all(&[5, 0, 0, 1, 127, 0, 0, 1, 0, 80])
                .await
                .unwrap();
        }
        let result = turn_tokio_rustls::TlsAcceptor::from(Arc::new(server_config))
            .accept(socket)
            .await;
        if !trust_certificate {
            assert!(result.is_err());
            return;
        }
        let mut tls = result.unwrap();
        assert_eq!(tls.get_ref().1.server_name(), Some(host));
        let mut request = [0; 8];
        tls.read_exact(&mut request).await.unwrap();
        assert_eq!(request, frame);
        tls.write_all(&frame).await.unwrap();
    });
    timeout(Duration::from_secs(5), async {
        let target = TurnEndpoint {
            host: Arc::from(host),
            port: if external || embedded {
                address.port()
            } else {
                443
            },
            transport: TurnWireTransport::Tls,
        };
        let result = connect_inner(&target, address, Some(proxy), Some(client_config)).await;
        if trust_certificate {
            let (writer, mut reader, _) = result.unwrap();
            writer.write_control(&frame).await.unwrap();
            match reader.read_frame(&PacketPool::new(2)).await.unwrap() {
                TurnStreamFrame::Data(packet) => assert_eq!(packet.as_slice(), frame),
                _ => panic!("expected TURN ChannelData"),
            }
        } else {
            let error = result
                .err()
                .expect("untrusted certificate must be rejected");
            assert!(format!("{error:#}").contains("certificate"), "{error:#}");
        }
        server.await.unwrap();
    })
    .await
    .unwrap();
}

#[tokio::test(start_paused = true)]
async fn stalled_gooby_negotiation_times_out_and_closes_socket() {
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let address = listener.local_addr().unwrap();
    let proxy = address.to_string().parse().unwrap();
    let connect_task = tokio::spawn(async move {
        connect(&endpoint(TurnWireTransport::Tcp), address, Some(proxy)).await
    });
    let (mut socket, _) = listener.accept().await.unwrap();
    let mut greeting = [0; 3];
    socket.read_exact(&mut greeting).await.unwrap();
    tokio::time::advance(STREAM_CONNECT_TIMEOUT + Duration::from_secs(1)).await;
    let error = connect_task.await.unwrap().err().expect("must time out");
    assert!(format!("{error:#}").contains("timed out"));
    assert_eq!(socket.read(&mut greeting).await.unwrap(), 0);
}

#[tokio::test]
async fn cancelling_gooby_connect_closes_socket() {
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let address = listener.local_addr().unwrap();
    let proxy = address.to_string().parse().unwrap();
    let task = tokio::spawn(async move {
        connect(&endpoint(TurnWireTransport::Tcp), address, Some(proxy)).await
    });
    let (mut socket, _) = listener.accept().await.unwrap();
    let mut greeting = [0; 3];
    socket.read_exact(&mut greeting).await.unwrap();
    task.abort();
    assert!(task.await.is_err());
    assert_eq!(
        timeout(Duration::from_secs(2), socket.read(&mut greeting))
            .await
            .unwrap()
            .unwrap(),
        0
    );
}

#[tokio::test]
async fn unavailable_gooby_does_not_connect_directly() {
    let direct_listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let address = direct_listener.local_addr().unwrap();
    let closed_listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let proxy = closed_listener
        .local_addr()
        .unwrap()
        .to_string()
        .parse()
        .unwrap();
    drop(closed_listener);
    assert!(
        connect(&endpoint(TurnWireTransport::Tcp), address, Some(proxy))
            .await
            .is_err()
    );
    assert!(
        timeout(Duration::from_millis(50), direct_listener.accept())
            .await
            .is_err()
    );
}

#[tokio::test]
async fn udp_cannot_enter_stream_proxy() {
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let address = listener.local_addr().unwrap();
    let proxy = address.to_string().parse().unwrap();
    assert!(
        connect(&endpoint(TurnWireTransport::Udp), address, Some(proxy))
            .await
            .is_err()
    );
    assert!(
        timeout(Duration::from_millis(50), listener.accept())
            .await
            .is_err()
    );
}
