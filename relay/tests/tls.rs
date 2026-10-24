//! TLS 端到端：自签证书 + 中继走同一套协议；指纹不对（换一份 CA）必须握手失败。

use std::sync::Arc;
use std::time::Duration;

use rcgen::{BasicConstraints, CertificateParams, DistinguishedName, DnType, IsCa, KeyPair};
use rustls::pki_types::{CertificateDer, PrivateKeyDer, ServerName};
use rustls::RootCertStore;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpListener, TcpStream};
use tokio_rustls::TlsAcceptor;

const HASH: &str = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
const HOST: &str = "localhost";

/// 自签 CA + 由它签发的服务端证书（SAN = localhost）。
struct TestPki {
    ca_der: CertificateDer<'static>,
    cert_der: CertificateDer<'static>,
    key_der: PrivateKeyDer<'static>,
    cert_pem: String,
    key_pem: String,
}

fn make_pki() -> TestPki {
    let mut ca_params = CertificateParams::new(Vec::<String>::new()).unwrap();
    ca_params.is_ca = IsCa::Ca(BasicConstraints::Unconstrained);
    let mut name = DistinguishedName::new();
    name.push(DnType::CommonName, "ac-relay test CA");
    ca_params.distinguished_name = name;
    let ca_key = KeyPair::generate().unwrap();
    let ca_cert = ca_params.self_signed(&ca_key).unwrap();

    let leaf_params = CertificateParams::new(vec![HOST.to_string()]).unwrap();
    let leaf_key = KeyPair::generate().unwrap();
    let leaf_cert = leaf_params
        .signed_by(&leaf_key, &ca_cert, &ca_key)
        .unwrap();

    TestPki {
        ca_der: ca_cert.der().clone(),
        cert_der: leaf_cert.der().clone(),
        key_der: PrivateKeyDer::Pkcs8(leaf_key.serialize_der().into()),
        cert_pem: leaf_cert.pem(),
        key_pem: leaf_key.serialize_pem(),
    }
}

async fn start_tls(pki: &TestPki) -> std::net::SocketAddr {
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let addr = listener.local_addr().unwrap();
    let config = rustls::ServerConfig::builder()
        .with_no_client_auth()
        .with_single_cert(vec![pki.cert_der.clone()], pki.key_der.clone_key())
        .unwrap();
    let acceptor = TlsAcceptor::from(Arc::new(config));
    let registry = ac_relay::new_registry(ac_relay::LimiterConfig::default());
    tokio::spawn(ac_relay::serve_tls(listener, acceptor, registry));
    addr
}

/// 只信任 `ca` 的客户端；CA 不对时握手会失败。
async fn tls_connect(
    addr: std::net::SocketAddr,
    ca: CertificateDer<'static>,
) -> std::io::Result<tokio_rustls::client::TlsStream<TcpStream>> {
    let mut roots = RootCertStore::empty();
    roots.add(ca).map_err(|error| std::io::Error::other(error.to_string()))?;
    let config = rustls::ClientConfig::builder()
        .with_root_certificates(roots)
        .with_no_client_auth();
    let connector = tokio_rustls::TlsConnector::from(Arc::new(config));
    let tcp = TcpStream::connect(addr).await?;
    connector
        .connect(ServerName::try_from(HOST).unwrap(), tcp)
        .await
        .map_err(std::io::Error::other)
}

async fn read_line<S>(stream: &mut S) -> String
where
    S: tokio::io::AsyncRead + Unpin,
{
    let mut buf = Vec::new();
    loop {
        let mut byte = [0u8; 1];
        let read = stream.read(&mut byte).await.expect("读取失败");
        assert_eq!(read, 1, "连接提前结束");
        if byte[0] == b'\n' {
            break;
        }
        buf.push(byte[0]);
    }
    String::from_utf8_lossy(&buf).trim().to_string()
}

async fn read_line_or_timeout<S>(stream: &mut S) -> String
where
    S: tokio::io::AsyncRead + Unpin,
{
    tokio::time::timeout(Duration::from_secs(5), read_line(stream))
        .await
        .expect("等一行超时")
}

#[tokio::test]
async fn pairing_works_over_tls() {
    let pki = make_pki();
    let addr = start_tls(&pki).await;

    let mut signaling = tls_connect(addr, pki.ca_der.clone())
        .await
        .expect("TLS 握手应成功");
    signaling
        .write_all(format!("AGENT {HASH} signaling\n").as_bytes())
        .await
        .unwrap();
    assert_eq!(read_line_or_timeout(&mut signaling).await, "OK");

    let mut controller = tls_connect(addr, pki.ca_der.clone())
        .await
        .expect("TLS 握手应成功");
    controller
        .write_all(format!("FIND {HASH} control\n").as_bytes())
        .await
        .unwrap();
    assert_eq!(read_line_or_timeout(&mut signaling).await, "OPEN control");

    let mut agent_data = tls_connect(addr, pki.ca_der.clone())
        .await
        .expect("TLS 握手应成功");
    agent_data
        .write_all(format!("DATA {HASH} control\n").as_bytes())
        .await
        .unwrap();
    assert_eq!(read_line_or_timeout(&mut agent_data).await, "OK");
    assert_eq!(read_line_or_timeout(&mut controller).await, "OK");

    controller.write_all(b"hello").await.unwrap();
    let mut buf = [0u8; 5];
    agent_data.read_exact(&mut buf).await.unwrap();
    assert_eq!(&buf, b"hello");
}

#[tokio::test]
async fn client_with_the_wrong_ca_is_rejected() {
    let pki = make_pki();
    let addr = start_tls(&pki).await;

    // 另一份自签 CA：等于长辈端把指纹配错了
    let other = make_pki();
    let result = tls_connect(addr, other.ca_der).await;
    assert!(result.is_err(), "信任了错误的 CA 时握手必须失败");
}

#[tokio::test]
async fn pem_files_can_be_loaded_from_disk() {
    let pki = make_pki();
    let dir = std::env::temp_dir().join(format!("ac-relay-pem-{}", std::process::id()));
    std::fs::create_dir_all(&dir).unwrap();
    let cert_path = dir.join("cert.pem");
    let key_path = dir.join("key.pem");

    // 与文档里 openssl 的产物同为 PEM 文本
    std::fs::write(&cert_path, pki.cert_pem.as_str()).unwrap();
    std::fs::write(&key_path, pki.key_pem.as_str()).unwrap();

    let config = ac_relay::load_tls_server_config(&cert_path, &key_path);
    assert!(config.is_ok(), "应能从 PEM 文件加载：{:?}", config.err());

    let _ = std::fs::remove_dir_all(&dir);
}
