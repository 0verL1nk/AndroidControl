//! 流级端到端：脚本化长辈端经中继发送"真实的"视频/控制字节，控制端逐字节核对。
//!
//! 与 `relay.rs` 的区别：这里发的是贴合线上格式的数据（68 字节设备头、会话包、
//! 配置包、关键帧、32 字节触摸消息），并覆盖跨 TCP 分片与大块数据。

use std::net::SocketAddr;
use std::time::Duration;

use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpListener, TcpStream};

const HASH: &str = "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210";

async fn start() -> SocketAddr {
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let addr = listener.local_addr().unwrap();
    tokio::spawn(ac_relay::serve(listener));
    addr
}

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
    tokio::time::timeout(Duration::from_secs(5), read_line(stream))
        .await
        .expect("等一行超时")
}

/// 让长辈端登记信令；返回它的信令连接。
async fn register_agent(addr: SocketAddr) -> TcpStream {
    let mut signaling = TcpStream::connect(addr).await.unwrap();
    signaling
        .write_all(format!("AGENT {HASH} signaling\n").as_bytes())
        .await
        .unwrap();
    assert_eq!(read_line_or_timeout(&mut signaling).await, "OK");
    signaling
}

/// 控制端 FIND 一个角色，长辈端立刻开 DATA 连接；返回（控制端, 长辈端数据端）。
async fn pair(addr: SocketAddr, signaling: &mut TcpStream, role: &str) -> (TcpStream, TcpStream) {
    let mut controller = TcpStream::connect(addr).await.unwrap();
    controller
        .write_all(format!("FIND {HASH} {role}\n").as_bytes())
        .await
        .unwrap();
    assert_eq!(
        read_line_or_timeout(signaling).await,
        format!("OPEN {role}"),
        "中继应通知长辈端开 {role} 流"
    );

    let mut agent_data = TcpStream::connect(addr).await.unwrap();
    agent_data
        .write_all(format!("DATA {HASH} {role}\n").as_bytes())
        .await
        .unwrap();
    assert_eq!(read_line_or_timeout(&mut agent_data).await, "OK");
    assert_eq!(read_line_or_timeout(&mut controller).await, "OK");
    (controller, agent_data)
}

fn device_header(name: &str) -> Vec<u8> {
    let mut header = vec![0u8; 68];
    let bytes = name.as_bytes();
    header[..bytes.len().min(64)].copy_from_slice(&bytes[..bytes.len().min(64)]);
    header[64..68].copy_from_slice(b"h264");
    header
}

fn session_packet(width: u32, height: u32) -> Vec<u8> {
    let mut packet = vec![0u8; 12];
    packet[0] = 0x80;
    packet[4..8].copy_from_slice(&width.to_be_bytes());
    packet[8..12].copy_from_slice(&height.to_be_bytes());
    packet
}

fn media_packet(flags: u8, payload: &[u8]) -> Vec<u8> {
    let mut packet = vec![0u8; 12];
    packet[0] = flags;
    packet[8..12].copy_from_slice(&(payload.len() as u32).to_be_bytes());
    packet.extend_from_slice(payload);
    packet
}

#[tokio::test]
async fn video_stream_is_relayed_byte_for_byte() {
    let addr = start().await;
    let mut signaling = register_agent(addr).await;
    let (mut controller, mut agent_data) = pair(addr, &mut signaling, "video").await;

    let sps_pps = [0u8, 0, 0, 1, 0x67, 0x42, 0x00, 0x1e, 0, 0, 0, 1, 0x68, 0xce];
    let idr = [0u8, 0, 0, 1, 0x65, 0x88, 0x84, 0x00, 0x21, 0xff];
    let stream = [
        device_header("elder-phone"),
        session_packet(1080, 2400),
        media_packet(0x40, &sps_pps),
        media_packet(0x20, &idr),
    ]
    .concat();

    // 故意在包头中间切断：中继必须原样转发，不做任何重排或合并
    let split = 68 + 6;
    agent_data.write_all(&stream[..split]).await.unwrap();
    agent_data.flush().await.unwrap();
    agent_data.write_all(&stream[split..]).await.unwrap();
    agent_data.flush().await.unwrap();

    let mut received = vec![0u8; stream.len()];
    tokio::time::timeout(Duration::from_secs(5), controller.read_exact(&mut received))
        .await
        .expect("等视频字节超时")
        .expect("读取失败");
    assert_eq!(received, stream);
    assert_eq!(&received[..10], b"elder-phon");
    assert_eq!(&received[64..68], b"h264");
}

#[tokio::test]
async fn control_messages_are_relayed_byte_for_byte() {
    let addr = start().await;
    let mut signaling = register_agent(addr).await;
    let (mut controller, mut agent_data) = pair(addr, &mut signaling, "control").await;

    // 32 字节触摸按下：与 App / 网页端发送的布局一致
    let mut touch = [0u8; 32];
    touch[0] = 2;
    touch[1] = 0;
    touch[2..10].copy_from_slice(&(-2i64).to_be_bytes()); // 通用手指指针 id
    touch[10..14].copy_from_slice(&540i32.to_be_bytes());
    touch[14..18].copy_from_slice(&1200i32.to_be_bytes());
    touch[18..20].copy_from_slice(&1080u16.to_be_bytes());
    touch[20..22].copy_from_slice(&2400u16.to_be_bytes());
    touch[22..24].copy_from_slice(&0xffffu16.to_be_bytes());
    touch[24..28].copy_from_slice(&1i32.to_be_bytes());
    touch[28..32].copy_from_slice(&1i32.to_be_bytes());

    // 14 字节按键（主页）
    let mut keycode = [0u8; 14];
    keycode[0] = 0;
    keycode[1] = 0;
    keycode[2..6].copy_from_slice(&3i32.to_be_bytes());

    controller.write_all(&touch).await.unwrap();
    controller.write_all(&keycode).await.unwrap();

    let mut received = vec![0u8; touch.len() + keycode.len()];
    tokio::time::timeout(Duration::from_secs(5), agent_data.read_exact(&mut received))
        .await
        .expect("等控制字节超时")
        .expect("读取失败");
    assert_eq!(&received[..touch.len()], &touch);
    assert_eq!(&received[touch.len()..], &keycode);
}

#[tokio::test]
async fn a_large_payload_survives_the_relay() {
    let addr = start().await;
    let mut signaling = register_agent(addr).await;
    let (mut controller, mut agent_data) = pair(addr, &mut signaling, "video").await;

    // 1 MiB 的伪随机帧：覆盖"读一半就写"的场景
    let payload: Vec<u8> = (0..1024 * 1024)
        .map(|index| (index * 31 % 251) as u8)
        .collect();
    let packet = media_packet(0x20, &payload);
    let expected_len = packet.len();
    let expected = packet.clone();

    let writer = tokio::spawn(async move {
        agent_data.write_all(&packet).await.unwrap();
        agent_data.flush().await.unwrap();
    });

    let mut received = vec![0u8; expected_len];
    tokio::time::timeout(
        Duration::from_secs(20),
        controller.read_exact(&mut received),
    )
    .await
    .expect("等大块数据超时")
    .expect("读取失败");
    writer.await.unwrap();
    assert_eq!(received, expected);
}
