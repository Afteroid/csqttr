use super::*;
use tokio::{
    net::TcpListener,
    time::{Duration, timeout},
};

pub(crate) async fn accept_request(socket: &mut TcpStream, host: &str, port: u16) {
    let mut greeting = [0; 3];
    socket.read_exact(&mut greeting).await.unwrap();
    assert_eq!(greeting, [5, 1, 0]);
    socket.write_all(&[5, 0]).await.unwrap();
    let expected = connect_request(host, port).unwrap();
    let mut actual = vec![0; expected.len()];
    socket.read_exact(&mut actual).await.unwrap();
    assert_eq!(actual, expected);
}

#[test]
fn proxy_is_explicit_local_endpoint() {
    for value in ["127.0.0.1:1080", "[::1]:1080"] {
        assert_eq!(value.parse::<GoobyProxy>().unwrap().to_string(), value);
    }
    for value in [
        "",
        "127.0.0.1:0",
        "0.0.0.0:1080",
        "192.0.2.1:1080",
        "socks5://127.0.0.1:1080",
        "[::]:1080",
    ] {
        assert!(value.parse::<GoobyProxy>().is_err(), "{value}");
    }
}

#[test]
fn destination_wire_encoding_preserves_domains_and_ip_families() {
    assert_eq!(
        connect_request("turn.example", 443).unwrap(),
        [b"\x05\x01\x00\x03\x0cturn.example".as_slice(), &[1, 187]].concat()
    );
    assert_eq!(
        connect_request("192.0.2.1", 3478).unwrap(),
        [5, 1, 0, 1, 192, 0, 2, 1, 13, 150]
    );
    let v6 = connect_request("::1", 443).unwrap();
    assert_eq!(v6[..4], [5, 1, 0, 4]);
    assert_eq!(&v6[4..20], &std::net::Ipv6Addr::LOCALHOST.octets());
    for host in ["", "bad\nhost", "bad host", "не-ascii.example"] {
        assert!(connect_request(host, 443).is_err());
    }
    assert!(connect_request(&"a".repeat(256), 443).is_err());
    assert!(connect_request("turn.example", 0).is_err());
}

#[tokio::test]
async fn fragmented_replies_and_coalesced_payload_preserve_byte_stream() {
    for reply in [
        vec![5, 0, 0, 1, 127, 0, 0, 1, 0, 80],
        [vec![5, 0, 0, 4], vec![0; 16], vec![0, 80]].concat(),
        vec![5, 0, 0, 3, 3, b'a', b'b', b'c', 0, 80],
    ] {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let proxy = GoobyProxy(listener.local_addr().unwrap());
        let server = tokio::spawn(async move {
            let (mut socket, _) = listener.accept().await.unwrap();
            accept_request(&mut socket, "unresolvable.invalid", 443).await;
            for byte in &reply[..reply.len() - 1] {
                socket.write_all(&[*byte]).await.unwrap();
                tokio::task::yield_now().await;
            }
            let mut tail = vec![*reply.last().unwrap()];
            tail.extend_from_slice(b"server payload");
            socket.write_all(&tail).await.unwrap();
            let mut outbound = Vec::new();
            socket.read_to_end(&mut outbound).await.unwrap();
            assert_eq!(outbound, b"client payload");
            socket.shutdown().await.unwrap();
        });
        timeout(Duration::from_secs(3), async {
            let mut socket = connect(proxy, "unresolvable.invalid", 443).await.unwrap();
            socket.write_all(b"client payload").await.unwrap();
            socket.shutdown().await.unwrap();
            let mut incoming = Vec::new();
            socket.read_to_end(&mut incoming).await.unwrap();
            assert_eq!(incoming, b"server payload");
            server.await.unwrap();
        })
        .await
        .unwrap();
    }
}

#[tokio::test]
async fn rejects_bad_or_truncated_connect_replies() {
    for reply in [
        vec![5, 5, 0, 1],      // CONNECT refused
        vec![4, 0, 0, 1],      // version
        vec![5, 0, 1, 1],      // reserved
        vec![5, 0, 0, 2],      // address type
        vec![5, 0, 0, 3, 0],   // empty domain
        vec![5, 0, 0, 1, 127], // truncated bound address
    ] {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let proxy = GoobyProxy(listener.local_addr().unwrap());
        let server = tokio::spawn(async move {
            let (mut socket, _) = listener.accept().await.unwrap();
            accept_request(&mut socket, "turn.example", 443).await;
            socket.write_all(&reply).await.unwrap();
        });
        assert!(
            timeout(Duration::from_secs(3), connect(proxy, "turn.example", 443))
                .await
                .unwrap()
                .is_err()
        );
        server.await.unwrap();
    }
}

#[tokio::test]
async fn rejects_authentication_methods_instead_of_sending_turn_credentials() {
    for method in [[5, 2], [5, 255], [4, 0]] {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let proxy = GoobyProxy(listener.local_addr().unwrap());
        let server = tokio::spawn(async move {
            let (mut socket, _) = listener.accept().await.unwrap();
            let mut greeting = [0; 3];
            socket.read_exact(&mut greeting).await.unwrap();
            socket.write_all(&method).await.unwrap();
            let mut tail = Vec::new();
            socket.read_to_end(&mut tail).await.unwrap();
            assert!(tail.is_empty());
        });
        assert!(
            timeout(Duration::from_secs(3), connect(proxy, "turn.example", 443))
                .await
                .unwrap()
                .is_err()
        );
        timeout(Duration::from_secs(3), server)
            .await
            .unwrap()
            .unwrap();
    }
}
