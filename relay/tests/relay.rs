//! 端到端测试：起一个真实的中继，模拟"长辈端 + 控制端"走完整配对。

use std::net::SocketAddr;
use std::time::Duration;

use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpListener, TcpStream};

const HASH: &str = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

async fn start() -> SocketAddr {
    start_with_config(ac_relay::LimiterConfig::default()).await
}

async fn start_with_config(limiter: ac_relay::LimiterConfig) -> SocketAddr {
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let addr = listener.local_addr().unwrap();
    tokio::spawn(ac_relay::serve_with_config(listener, limiter));
    addr
}

async fn connect(addr: SocketAddr) -> TcpStream {
    TcpStream::connect(addr).await.unwrap()
}

/// 逐字节读一行：不能预读，否则会把紧随其后的二进制流一起吞掉。
async fn read_line(stream: &mut TcpStream) -> String {
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

async fn read_line_or_timeout(stream: &mut TcpStream) -> String {
    tokio::time::timeout(Duration::from_secs(3), read_line(stream))
        .await
        .expect("等一行超时")
}

#[tokio::test]
async fn pairs_controller_with_agent_and_relays_bytes_both_ways() {
    let addr = start().await;

    // 长辈端：登记常驻信令
    let mut signaling = connect(addr).await;
    signaling
        .write_all(format!("AGENT {HASH} signaling\n").as_bytes())
        .await
        .unwrap();
    assert_eq!(read_line_or_timeout(&mut signaling).await, "OK");

    // 控制端：按口令哈希找设备
    let mut controller = connect(addr).await;
    controller
        .write_all(format!("FIND {HASH} control\n").as_bytes())
        .await
        .unwrap();

    // 中继应通过信令让长辈端开流
    assert_eq!(read_line_or_timeout(&mut signaling).await, "OPEN control");

    // 长辈端立刻开出数据连接
    let mut agent_data = connect(addr).await;
    agent_data
        .write_all(format!("DATA {HASH} control\n").as_bytes())
        .await
        .unwrap();
    assert_eq!(read_line_or_timeout(&mut agent_data).await, "OK");

    // 控制端这时才拿到 OK，之后两端就是一条透明管道
    assert_eq!(read_line_or_timeout(&mut controller).await, "OK");

    controller.write_all(b"hello").await.unwrap();
    let mut buf = [0u8; 5];
    agent_data.read_exact(&mut buf).await.unwrap();
    assert_eq!(&buf, b"hello");

    agent_data.write_all(b"world").await.unwrap();
    let mut buf = [0u8; 5];
    controller.read_exact(&mut buf).await.unwrap();
    assert_eq!(&buf, b"world");
}

#[tokio::test]
async fn every_role_is_an_independent_stream() {
    let addr = start().await;

    let mut signaling = connect(addr).await;
    signaling
        .write_all(format!("AGENT {HASH} signaling\n").as_bytes())
        .await
        .unwrap();
    assert_eq!(read_line_or_timeout(&mut signaling).await, "OK");

    for role in ["control", "video", "audio"] {
        let mut controller = connect(addr).await;
        controller
            .write_all(format!("FIND {HASH} {role}\n").as_bytes())
            .await
            .unwrap();
        assert_eq!(
            read_line_or_timeout(&mut signaling).await,
            format!("OPEN {role}")
        );

        let mut agent_data = connect(addr).await;
        agent_data
            .write_all(format!("DATA {HASH} {role}\n").as_bytes())
            .await
            .unwrap();
        assert_eq!(read_line_or_timeout(&mut agent_data).await, "OK");
        assert_eq!(read_line_or_timeout(&mut controller).await, "OK");

        // 两个角色各自独立：发给自己这一条流的数据不能跑到另一条去
        controller.write_all(role.as_bytes()).await.unwrap();
        let mut buf = vec![0u8; role.len()];
        agent_data.read_exact(&mut buf).await.unwrap();
        assert_eq!(buf, role.as_bytes());
    }
}

#[tokio::test]
async fn help_request_can_be_queried_by_controller() {
    let addr = start().await;

    // 没按过求助时是 NONE
    let mut ask = connect(addr).await;
    ask.write_all(format!("ASK {HASH}\n").as_bytes())
        .await
        .unwrap();
    assert_eq!(read_line_or_timeout(&mut ask).await, "NONE");

    // 长辈端登录信令后按"求助"
    let mut signaling = connect(addr).await;
    signaling
        .write_all(format!("AGENT {HASH} signaling\n").as_bytes())
        .await
        .unwrap();
    assert_eq!(read_line_or_timeout(&mut signaling).await, "OK");
    signaling.write_all(b"HELP\n").await.unwrap();
    tokio::time::sleep(Duration::from_millis(150)).await;

    let mut ask = connect(addr).await;
    ask.write_all(format!("ASK {HASH}\n").as_bytes())
        .await
        .unwrap();
    let reply = read_line_or_timeout(&mut ask).await;
    assert!(reply.starts_with("HELP "), "实际回复：{reply}");
}

#[tokio::test]
async fn controller_gets_err_when_agent_is_offline() {
    let addr = start().await;

    let mut controller = connect(addr).await;
    controller
        .write_all(format!("FIND {HASH} control\n").as_bytes())
        .await
        .unwrap();
    assert_eq!(read_line_or_timeout(&mut controller).await, "ERR");
}

/// 口令猜错太多次后，即使长辈端随后上线也拒绝配对；锁定到期后恢复。
#[tokio::test]
async fn too_many_failures_lock_the_pairing_code() {
    let addr = start_with_config(ac_relay::LimiterConfig {
        max_fails: 2,
        window_secs: 60,
        lock_secs: 1,
    })
    .await;

    // 长辈端还没上线：两次失败把哈希锁住
    for _ in 0..2 {
        let mut controller = connect(addr).await;
        controller
            .write_all(format!("FIND {HASH} control\n").as_bytes())
            .await
            .unwrap();
        assert_eq!(read_line_or_timeout(&mut controller).await, "ERR");
    }

    // 长辈端上线
    let mut signaling = connect(addr).await;
    signaling
        .write_all(format!("AGENT {HASH} signaling\n").as_bytes())
        .await
        .unwrap();
    assert_eq!(read_line_or_timeout(&mut signaling).await, "OK");

    // 锁定期间：即使长辈端在线也不配对，且不通知它开流
    let mut blocked = connect(addr).await;
    blocked
        .write_all(format!("FIND {HASH} control\n").as_bytes())
        .await
        .unwrap();
    assert_eq!(read_line_or_timeout(&mut blocked).await, "ERR");
    let notified =
        tokio::time::timeout(Duration::from_millis(500), read_line(&mut signaling)).await;
    assert!(notified.is_err(), "锁定期间不应再通知长辈端开流");

    // 锁定到期（lock_secs = 1）后恢复正常
    tokio::time::sleep(Duration::from_millis(1200)).await;
    let mut controller = connect(addr).await;
    controller
        .write_all(format!("FIND {HASH} control\n").as_bytes())
        .await
        .unwrap();
    assert_eq!(read_line_or_timeout(&mut signaling).await, "OPEN control");

    let mut agent_data = connect(addr).await;
    agent_data
        .write_all(format!("DATA {HASH} control\n").as_bytes())
        .await
        .unwrap();
    assert_eq!(read_line_or_timeout(&mut agent_data).await, "OK");
    assert_eq!(read_line_or_timeout(&mut controller).await, "OK");
}

#[tokio::test]
async fn health_and_metrics_endpoints_answer_probes() {
    let addr = start().await;

    let mut health = connect(addr).await;
    health
        .write_all(b"GET /healthz HTTP/1.1\r\nHost: relay\r\n\r\n")
        .await
        .unwrap();
    let mut response = String::new();
    health.read_to_string(&mut response).await.unwrap();
    assert!(response.starts_with("HTTP/1.1 200 OK"), "实际：{response}");
    assert!(response.contains("\"status\":\"ok\""), "实际：{response}");
    assert!(response.contains("uptime_seconds"));

    let mut metrics = connect(addr).await;
    metrics
        .write_all(b"GET /metrics HTTP/1.1\r\nHost: relay\r\n\r\n")
        .await
        .unwrap();
    let mut response = String::new();
    metrics.read_to_string(&mut response).await.unwrap();
    assert!(response.starts_with("HTTP/1.1 200 OK"), "实际：{response}");
    for metric in [
        "ac_relay_uptime_seconds",
        "ac_relay_signaling",
        "ac_relay_waiting",
        "ac_relay_help_pending",
        "ac_relay_connections_total",
        "ac_relay_pairs_total",
    ] {
        assert!(response.contains(metric), "缺少 {metric}：{response}");
    }
    // Prometheus 文本格式不接受缩进：指标行必须以 # 或字母开头
    let body = response.split("\r\n\r\n").nth(1).unwrap_or("");
    for line in body.lines() {
        assert!(
            !line.starts_with(' ') && !line.starts_with('\t'),
            "指标行不应有缩进：{line:?}"
        );
    }

    // HEAD 只回头，不带正文（负载均衡/隧道探测常用）
    let mut head = connect(addr).await;
    head.write_all(b"HEAD /healthz HTTP/1.1\r\nHost: relay\r\n\r\n")
        .await
        .unwrap();
    let mut response = String::new();
    head.read_to_string(&mut response).await.unwrap();
    assert!(response.starts_with("HTTP/1.1 200 OK"));
    assert!(
        !response.contains("\"status\""),
        "HEAD 不应带正文：{response}"
    );
}

#[tokio::test]
async fn new_signaling_replaces_the_old_one() {
    let addr = start().await;

    let mut first = connect(addr).await;
    first
        .write_all(format!("AGENT {HASH} signaling\n").as_bytes())
        .await
        .unwrap();
    assert_eq!(read_line_or_timeout(&mut first).await, "OK");

    let mut second = connect(addr).await;
    second
        .write_all(format!("AGENT {HASH} signaling\n").as_bytes())
        .await
        .unwrap();
    assert_eq!(read_line_or_timeout(&mut second).await, "OK");

    // 旧连接应被服务端关闭（读到 EOF）
    let mut buf = [0u8; 1];
    let closed = tokio::time::timeout(Duration::from_secs(3), first.read(&mut buf)).await;
    assert!(
        matches!(closed, Ok(Ok(0))),
        "旧信令连接应被关闭，实际：{closed:?}"
    );

    // 新连接仍能正常收到 OPEN
    let mut controller = connect(addr).await;
    controller
        .write_all(format!("FIND {HASH} video\n").as_bytes())
        .await
        .unwrap();
    assert_eq!(read_line_or_timeout(&mut second).await, "OPEN video");
}
