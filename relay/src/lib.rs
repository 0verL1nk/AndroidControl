//! 配对中继：让"隔着 NAT 的两台手机"建立连接，不需要 VPN，也不需要记地址。
//!
//! 为什么需要它
//! ------------
//! 两台手机通常都在 NAT 后面，谁也连不上谁。传统做法是组一个 VPN（WireGuard / Tailscale），
//! 但那要在**老人手机上再装一个 App、再导入一份配置**——这正是我们想避免的负担。
//!
//! 设计：常连接 + 按需开流（v2）
//! ---------------------------
//! v1 的问题是"登记一次就被配对消耗掉"，长辈端要隔一会儿才重新登记，控制端可能等上几十秒。
//! v2 把连接分成两类：
//!
//!   1. **信令连接**（长辈端 ↔ 中继，常驻）：`AGENT <口令哈希> signaling`，
//!      每 10 秒一次 `PING / PONG` 心跳。
//!   2. **数据连接**（按需建立）：控制端来 `FIND <哈希> <角色>` 时，中继通过信令连接
//!      告诉长辈端 `OPEN <角色>`，长辈端**立刻**开一条数据连接上来，两边一对接就开始转发。
//!
//! 于是配对是"毫秒级"的，而不是"等下一次登记"。任何一端掉线都能在心跳周期内被发现并重建。
//!
//! 安全边界
//! --------
//! - 线上只有口令的哈希，没有口令本身；不知道口令就配不上对。
//! - 中继不解析、不落盘内容，只在内存里保存"哈希 → 连接"的映射。
//! - 中继是明文字节转发，请放在自己的机器/内网里；公网暴露时自行加 TLS 或限制来源。
//!
//! 协议细节见 `docs/ops/relay-node.md`。

use std::collections::HashMap;
use std::fmt;
use std::net::{IpAddr, SocketAddr};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use socket2::SockRef;
use tokio::io::{AsyncBufReadExt, AsyncReadExt, AsyncWriteExt, BufReader};
use tokio::net::tcp::{OwnedReadHalf, OwnedWriteHalf};
use tokio::net::{TcpListener, TcpStream};
use tokio::sync::{mpsc, oneshot};

use base64::engine::general_purpose::STANDARD as BASE64;
use base64::Engine as _;
use sha1::{Digest, Sha1};

/// 中继自己不再内嵌前端：网页控制端在 `web/`（Next.js），单独部署到 Vercel 或任意静态托管。
const WEB_HINT: &str = "AndroidControl relay is running.

Web client: deploy web/ (Next.js). WebSocket endpoint: /ws
";

pub const AGENT_VERB: &str = "AGENT";
pub const DATA_VERB: &str = "DATA";
pub const FIND_VERB: &str = "FIND";
pub const ASK_VERB: &str = "ASK";

pub const ROLE_CONTROL: &str = "control";
pub const ROLE_VIDEO: &str = "video";
/// 双向语音：一条连接同时承载两个方向的 PCM 帧。
pub const ROLE_AUDIO: &str = "audio";

/// 双方约定的心跳间隔。
pub const PING_INTERVAL: Duration = Duration::from_secs(10);
/// 超过这么久没心跳就认为信令连接已死。
pub const SIGNALING_TIMEOUT: Duration = Duration::from_secs(35);
/// 等长辈端开出数据连接的最长时间（移动网络要留余量）。
pub const DATA_TIMEOUT: Duration = Duration::from_secs(12);

/// 求助的有效期：过了就当作没按过，避免孩子几小时后打开 App 被吓一跳。
const HELP_TTL_SECS: u64 = 30 * 60;

/// 首行协议行的长度上限，防止坏连接把内存撑爆。
const LINE_LIMIT: usize = 256;

fn is_role(value: &str) -> bool {
    value == ROLE_CONTROL || value == ROLE_VIDEO || value == ROLE_AUDIO
}

/// 日志：节点上交给 journald 收集，这里只保证按行、带时间。
pub fn log(message: impl fmt::Display) {
    println!("[{}] {}", timestamp(), message);
}

fn timestamp() -> String {
    use std::time::{SystemTime, UNIX_EPOCH};
    let secs = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0);
    let (h, m, s) = ((secs / 3600) % 24, (secs / 60) % 60, secs % 60);
    format!("{h:02}:{m:02}:{s:02} UTC")
}

fn short(hash: &str) -> &str {
    hash.get(..8).unwrap_or(hash)
}

/// 信令任务收到的指令。
enum Signal {
    Open(String),
    Close,
}

type SignalTx = mpsc::Sender<Signal>;

struct Waiting {
    id: u64,
    tx: oneshot::Sender<(OwnedReadHalf, OwnedWriteHalf)>,
}

/// 口令猜测限速配置。`max_fails == 0` 表示关闭限速。
#[derive(Clone, Copy, Debug)]
pub struct LimiterConfig {
    pub max_fails: u32,
    pub window_secs: u64,
    pub lock_secs: u64,
}

impl Default for LimiterConfig {
    fn default() -> Self {
        Self {
            max_fails: 10,
            window_secs: 600,
            lock_secs: 600,
        }
    }
}

#[derive(Clone, Copy, Default)]
struct FailRecord {
    count: u32,
    window_started: u64,
    locked_until: u64,
}

/// 按口令哈希（主防线）与直连来源 IP（辅助）统计失败。
///
/// 浏览器经隧道接入时来源 IP 全是 127.0.0.1，因此 IP 维度只对直连有效，
/// 真正的防线是哈希维度——它不受来源地址影响。
#[derive(Default)]
struct RateLimiter {
    config: LimiterConfig,
    by_hash: HashMap<String, FailRecord>,
    by_ip: HashMap<String, FailRecord>,
}

impl RateLimiter {
    fn new(config: LimiterConfig) -> Self {
        Self {
            config,
            ..Default::default()
        }
    }

    fn locked(&self, hash: &str, ip: IpAddr) -> bool {
        if self.config.max_fails == 0 {
            return false;
        }
        let now = epoch_secs();
        let hash_locked = self
            .by_hash
            .get(hash)
            .is_some_and(|record| record.locked_until > now);
        let ip_locked = !ip.is_loopback()
            && self
                .by_ip
                .get(&ip.to_string())
                .is_some_and(|record| record.locked_until > now);
        hash_locked || ip_locked
    }

    /// 记一次失败；返回（累计次数，本次是否刚触发锁定及锁定截止时间）。
    fn record_failure(&mut self, hash: &str, ip: IpAddr) -> (u32, Option<u64>) {
        if self.config.max_fails == 0 {
            return (0, None);
        }
        let now = epoch_secs();
        // 长跑节点上限制表大小：窗口已过且未锁定的记录没有意义
        if self.by_hash.len() > 4096 || self.by_ip.len() > 4096 {
            let window = self.config.window_secs;
            let stale = |record: &FailRecord| {
                record.locked_until <= now && now.saturating_sub(record.window_started) > window
            };
            self.by_hash.retain(|_, record| !stale(record));
            self.by_ip.retain(|_, record| !stale(record));
        }
        let mut locked_until = None;
        let count = bump(
            self.by_hash.entry(hash.to_string()).or_default(),
            now,
            &self.config,
            &mut locked_until,
        );
        if !ip.is_loopback() {
            bump(
                self.by_ip.entry(ip.to_string()).or_default(),
                now,
                &self.config,
                &mut locked_until,
            );
        }
        (count, locked_until)
    }

    /// 配对成功：清掉该哈希与来源 IP 的失败记录。
    ///
    /// IP 也要清——否则同一出口（家里路由器、公司网络）猜对一次也可能被
    /// 之前别人的失败连坐。
    fn clear(&mut self, hash: &str, ip: IpAddr) {
        self.by_hash.remove(hash);
        if !ip.is_loopback() {
            self.by_ip.remove(&ip.to_string());
        }
    }
}

fn bump(
    record: &mut FailRecord,
    now: u64,
    config: &LimiterConfig,
    locked_until: &mut Option<u64>,
) -> u32 {
    if record.locked_until > now {
        return record.count;
    }
    if record.locked_until != 0 {
        // 锁定到期后清零，避免"解锁后手误一次又锁十分钟"
        record.locked_until = 0;
        record.count = 0;
        record.window_started = now;
    }
    if record.window_started == 0 || now.saturating_sub(record.window_started) > config.window_secs
    {
        record.window_started = now;
        record.count = 0;
    }
    record.count += 1;
    if record.count >= config.max_fails {
        record.locked_until = now + config.lock_secs;
        *locked_until = Some(record.locked_until);
    }
    record.count
}

/// 信令连接 + 待配对请求，全部只在内存里。
#[derive(Default)]
pub struct Registry {
    signaling: HashMap<String, SignalTx>,
    waiting: HashMap<(String, String), Waiting>,
    /// 长辈端按下"求助"的时间（Unix 秒）。
    help: HashMap<String, u64>,
    next_id: u64,
    limiter: RateLimiter,
    /// 指标：累计连接与配对成功次数（供 /metrics 使用）。
    connections_total: u64,
    pairs_total: u64,
}

impl Registry {
    fn with_limiter(config: LimiterConfig) -> Self {
        Self {
            limiter: RateLimiter::new(config),
            ..Default::default()
        }
    }
}

impl Registry {
    fn stats(&self) -> String {
        format!(
            "信令 {} 条，待配对 {} 个",
            self.signaling.len(),
            self.waiting.len()
        )
    }
}

/// 开启保活：否则一端异常退出时，另一端可能长时间察觉不到（连接半开）。
/// 信令靠 PING 兜底，数据流没有应用层心跳，只能靠 TCP keepalive。
fn tune(stream: &TcpStream) {
    let _ = stream.set_nodelay(true);
    let keepalive = {
        let ka = socket2::TcpKeepalive::new()
            .with_time(Duration::from_secs(15))
            .with_interval(Duration::from_secs(5));
        #[cfg(any(target_os = "linux", target_os = "android"))]
        let ka = ka.with_retries(3);
        ka
    };
    let _ = SockRef::from(stream).set_tcp_keepalive(&keepalive);
}

/// 逐字节读到换行。
///
/// 这里**不能**用带缓冲的读取：握手行之后紧接着就是二进制数据流，
/// 预读会把流的开头一并吞掉（控制端曾因此出现"设备名乱码、解不出帧"）。
async fn read_line_raw(rd: &mut OwnedReadHalf, limit: usize) -> Option<String> {
    let mut buf = Vec::with_capacity(32);
    loop {
        let byte = rd.read_u8().await.ok()?;
        if byte == b'\n' {
            break;
        }
        if buf.len() >= limit {
            return None;
        }
        buf.push(byte);
    }
    Some(String::from_utf8_lossy(&buf).trim().to_string())
}

/// 处理一条新连接：读首行，按动词分流。
pub async fn handle_connection(
    stream: TcpStream,
    peer: SocketAddr,
    registry: Arc<Mutex<Registry>>,
) {
    tune(&stream);
    lock(&registry).connections_total += 1;
    let (mut rd, wr) = stream.into_split();

    let Some(line) = read_line_raw(&mut rd, LINE_LIMIT).await else {
        return;
    };

    // 浏览器控制端与健康检查：GET/HEAD/OPTIONS 都走 HTTP 分支
    let method = line.split(' ').next().unwrap_or("");
    if matches!(method, "GET" | "HEAD" | "OPTIONS") {
        handle_http(rd, wr, line, peer, registry).await;
        return;
    }

    let parts: Vec<&str> = line.split(' ').collect();
    let verb = parts.first().copied().unwrap_or("").to_uppercase();

    match verb.as_str() {
        AGENT_VERB if parts.len() == 3 && parts[2] == "signaling" => {
            handle_signaling(rd, wr, parts[1].to_string(), peer, registry).await;
        }
        DATA_VERB if parts.len() == 3 && is_role(parts[2]) => {
            handle_data(rd, wr, parts[1].to_string(), parts[2].to_string(), registry).await;
        }
        FIND_VERB if parts.len() == 3 && is_role(parts[2]) => {
            handle_controller(
                rd,
                wr,
                parts[1].to_string(),
                parts[2].to_string(),
                peer,
                registry,
            )
            .await;
        }
        ASK_VERB if parts.len() == 2 => {
            handle_ask(wr, parts[1].to_string(), registry).await;
        }
        _ => log(format!("无法识别的请求：{line:?} 来自 {peer}")),
    }
}

/// 控制端查询"长辈最近有没有按求助"。回复 `HELP <Unix 秒>` 或 `NONE`。
async fn handle_ask(mut wr: OwnedWriteHalf, hash: String, registry: Arc<Mutex<Registry>>) {
    let now = epoch_secs();
    let reply = {
        let mut reg = lock(&registry);
        match reg.help.get(&hash).copied() {
            Some(at) if now.saturating_sub(at) <= HELP_TTL_SECS => format!("HELP {at}\n"),
            _ => {
                reg.help.remove(&hash);
                "NONE\n".to_string()
            }
        }
    };
    let _ = wr.write_all(reply.as_bytes()).await;
}

fn epoch_secs() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0)
}

/// 常驻信令连接：处理心跳，并在控制端到来时通知长辈端开流。
async fn handle_signaling(
    rd: OwnedReadHalf,
    mut wr: OwnedWriteHalf,
    hash: String,
    peer: SocketAddr,
    registry: Arc<Mutex<Registry>>,
) {
    // 必须先应答，长辈端才知道登记成功
    if wr.write_all(b"OK\n").await.is_err() {
        return;
    }

    let (tx, mut rx) = mpsc::channel::<Signal>(8);
    {
        let mut reg = lock(&registry);
        if let Some(old) = reg.signaling.insert(hash.clone(), tx.clone()) {
            let _ = old.try_send(Signal::Close);
            log(format!("信令重复登记（{}…），替换旧连接", short(&hash)));
        }
        log(format!(
            "长辈端信令已连接（{}…）来自 {} —— {}",
            short(&hash),
            peer.ip(),
            reg.stats()
        ));
    }

    let mut lines = BufReader::new(rd).lines();
    let idle = tokio::time::sleep(SIGNALING_TIMEOUT);
    tokio::pin!(idle);

    loop {
        tokio::select! {
            _ = &mut idle => break,
            message = rx.recv() => match message {
                Some(Signal::Open(role)) => {
                    if wr.write_all(format!("OPEN {role}\n").as_bytes()).await.is_err() {
                        break;
                    }
                }
                Some(Signal::Close) | None => break,
            },
            line = lines.next_line() => match line {
                Ok(Some(text)) => {
                    match text.trim() {
                        "PING" => {
                            if wr.write_all(b"PONG\n").await.is_err() {
                                break;
                            }
                        }
                        "HELP" => {
                            // 长辈端按下"求助"：记下时间，控制端下次查询时就能看到。
                            let now = epoch_secs();
                            lock(&registry).help.insert(hash.clone(), now);
                            log(format!("收到求助（{}…）", short(&hash)));
                        }
                        other => log(format!("信令上收到未知消息：{other}")),
                    }
                }
                _ => break,
            },
        }
        idle.as_mut()
            .reset(tokio::time::Instant::now() + SIGNALING_TIMEOUT);
    }

    {
        let mut reg = lock(&registry);
        // 只有"当前登记的仍是我"才注销——重复登记时旧连接不能把新连接抹掉
        if reg
            .signaling
            .get(&hash)
            .is_some_and(|current| current.same_channel(&tx))
        {
            reg.signaling.remove(&hash);
        }
        log(format!("长辈端信令断开（{}…）", short(&hash)));
    }
}

/// 长辈端按需开出的数据连接：交给正在等的控制端。
async fn handle_data(
    rd: OwnedReadHalf,
    mut wr: OwnedWriteHalf,
    hash: String,
    role: String,
    registry: Arc<Mutex<Registry>>,
) {
    let key = (hash.clone(), role.clone());
    let waiting = lock(&registry).waiting.remove(&key);

    if wr.write_all(b"OK\n").await.is_err() {
        return;
    }
    match waiting {
        Some(waiter) => {
            let _ = waiter.tx.send((rd, wr));
        }
        None => {
            log(format!("收到没人等待的 {role} 数据连接，丢弃"));
        }
    }
}

/// 配对结果：长辈端不在线，或开了流但没在时限内连上来。
enum PairError {
    Offline,
    Timeout,
}

/// 通过信令让长辈端开一条数据连接，并等它接入。
///
/// TCP 控制端与浏览器控制端共用这一段——两边只是"把字节交给谁"不同。
async fn pair_agent(
    hash: &str,
    role: &str,
    registry: &Arc<Mutex<Registry>>,
) -> Result<(OwnedReadHalf, OwnedWriteHalf), PairError> {
    let signaling = lock(registry)
        .signaling
        .get(hash)
        .cloned()
        .ok_or(PairError::Offline)?;

    let key = (hash.to_string(), role.to_string());
    let (tx, rx) = oneshot::channel();
    let id = {
        let mut reg = lock(registry);
        reg.next_id += 1;
        let id = reg.next_id;
        if reg
            .waiting
            .insert(key.clone(), Waiting { id, tx })
            .is_some()
        {
            // 同一角色只允许一个等待者：后来的顶掉先来的（旧 waiter 的 sender 被丢弃 → 它立刻失败）
            log(format!("{role} 已有等待者，后来的顶掉先来的"));
        }
        id
    };

    if signaling
        .send(Signal::Open(role.to_string()))
        .await
        .is_err()
    {
        log("通知长辈端失败，连接可能已失效");
        remove_waiting(registry, &key, id);
        return Err(PairError::Offline);
    }

    // 长辈端一般几十毫秒就开好流；等不到就明确失败，不让控制端干等
    match tokio::time::timeout(DATA_TIMEOUT, rx).await {
        Ok(Ok(pair)) => Ok(pair),
        _ => {
            remove_waiting(registry, &key, id);
            Err(PairError::Timeout)
        }
    }
}

/// 控制端找设备：通过信令让长辈端开流，等它连上来后把两条连接对接。
async fn handle_controller(
    rd: OwnedReadHalf,
    mut wr: OwnedWriteHalf,
    hash: String,
    role: String,
    peer: SocketAddr,
    registry: Arc<Mutex<Registry>>,
) {
    if lock(&registry).limiter.locked(&hash, peer.ip()) {
        log(format!(
            "AUDIT pair_blocked hash={} ip={} role={role}",
            short(&hash),
            peer.ip()
        ));
        let _ = wr.write_all(b"ERR\n").await;
        return;
    }

    match pair_agent(&hash, &role, &registry).await {
        Ok((agent_rd, agent_wr)) => {
            {
                let mut reg = lock(&registry);
                reg.limiter.clear(&hash, peer.ip());
                reg.pairs_total += 1;
            }
            if wr.write_all(b"OK\n").await.is_err() {
                return;
            }
            log(format!(
                "配对成功 {role}（{}…）{} -> 长辈端",
                short(&hash),
                peer.ip()
            ));
            splice((rd, wr), (agent_rd, agent_wr)).await;
        }
        Err(PairError::Offline) => {
            note_pair_failure(&registry, &hash, peer.ip(), &role);
            log(format!(
                "控制端找 {role}（{}…）—— 长辈端不在线",
                short(&hash)
            ));
            let _ = wr.write_all(b"ERR\n").await;
        }
        Err(PairError::Timeout) => {
            note_pair_failure(&registry, &hash, peer.ip(), &role);
            log(format!("等长辈端开 {role} 流超时"));
            let _ = wr.write_all(b"ERR\n").await;
        }
    }
}

/// 记一次配对失败；达到阈值时再输出一条锁定审计。
///
/// 只记失败、不记口令本身：日志里是哈希前 8 位与来源 IP。
fn note_pair_failure(registry: &Arc<Mutex<Registry>>, hash: &str, ip: IpAddr, role: &str) {
    let (count, locked_until) = lock(registry).limiter.record_failure(hash, ip);
    log(format!(
        "AUDIT pair_failed hash={} ip={} role={role} count={count}",
        short(hash),
        ip
    ));
    if let Some(until) = locked_until {
        log(format!("AUDIT locked hash={} until={until}", short(hash)));
    }
}

/// 只在登记的仍是我时删除，避免误删后来者的等待位。
fn remove_waiting(registry: &Arc<Mutex<Registry>>, key: &(String, String), id: u64) {
    let mut reg = lock(registry);
    if reg.waiting.get(key).is_some_and(|waiting| waiting.id == id) {
        reg.waiting.remove(key);
    }
}

// ── 浏览器控制端：内嵌页面 + 极简 WebSocket ──────────────────────

/// 单帧上限：视频分片远小于它，超过只可能是坏数据。
const MAX_WS_FRAME: usize = 4 * 1024 * 1024;

const WS_TEXT: u8 = 0x1;
const WS_BINARY: u8 = 0x2;
const WS_CLOSE: u8 = 0x8;
const WS_PING: u8 = 0x9;
const WS_PONG: u8 = 0xA;

/// 处理 HTTP 请求：`GET /` 给页面，带 `Upgrade: websocket` 的升级成 WebSocket。
async fn handle_http(
    mut rd: OwnedReadHalf,
    mut wr: OwnedWriteHalf,
    request_line: String,
    peer: SocketAddr,
    registry: Arc<Mutex<Registry>>,
) {
    let mut headers = Vec::new();
    while let Some(line) = read_line_raw(&mut rd, 4096).await {
        if line.is_empty() {
            break;
        }
        headers.push(line);
    }

    let is_websocket = headers.iter().any(|header| {
        let lower = header.to_ascii_lowercase();
        lower.starts_with("upgrade:") && lower.contains("websocket")
    });

    if is_websocket {
        // 值区分大小写，只能按"原行里冒号之后"取，不能先把整行转小写
        let key = headers
            .iter()
            .find_map(|header| {
                let lower = header.to_ascii_lowercase();
                if lower.starts_with("sec-websocket-key:") {
                    header
                        .find(':')
                        .map(|colon| header[colon + 1..].trim().to_string())
                } else {
                    None
                }
            })
            .unwrap_or_default();
        if key.is_empty() {
            let _ = wr
                .write_all(b"HTTP/1.1 400 Bad Request\r\nConnection: close\r\n\r\n")
                .await;
            return;
        }
        let accept = websocket_accept(&key);
        let response = format!(
            "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: {accept}\r\n\r\n"
        );
        if wr.write_all(response.as_bytes()).await.is_err() {
            return;
        }
        handle_websocket(rd, wr, peer, registry).await;
        return;
    }

    let path = request_line.split(' ').nth(1).unwrap_or("/");
    if path == "/" || path == "/index.html" {
        let body = WEB_HINT.as_bytes();
        let head = format!(
            "HTTP/1.1 200 OK\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: {}\r\nCache-Control: no-store\r\n\r\n",
            body.len()
        );
        let _ = wr.write_all(head.as_bytes()).await;
        let _ = wr.write_all(body).await;
    } else {
        let _ = wr
            .write_all(b"HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
            .await;
    }
}

fn websocket_accept(key: &str) -> String {
    let mut hasher = Sha1::new();
    hasher.update(key.as_bytes());
    hasher.update(b"258EAFA5-E914-47DA-95CA-C5AB0DC85B11");
    BASE64.encode(hasher.finalize())
}

async fn read_ws_frame(rd: &mut OwnedReadHalf) -> Option<(u8, Vec<u8>)> {
    let first = rd.read_u8().await.ok()?;
    let second = rd.read_u8().await.ok()?;
    let opcode = first & 0x0F;
    let masked = second & 0x80 != 0;
    let len = match second & 0x7F {
        126 => {
            let mut bytes = [0u8; 2];
            rd.read_exact(&mut bytes).await.ok()?;
            u16::from_be_bytes(bytes) as usize
        }
        127 => {
            let mut bytes = [0u8; 8];
            rd.read_exact(&mut bytes).await.ok()?;
            u64::from_be_bytes(bytes) as usize
        }
        small => small as usize,
    };
    if len > MAX_WS_FRAME {
        return None;
    }
    let mask = if masked {
        let mut bytes = [0u8; 4];
        rd.read_exact(&mut bytes).await.ok()?;
        Some(bytes)
    } else {
        None
    };
    let mut payload = vec![0u8; len];
    if len > 0 {
        rd.read_exact(&mut payload).await.ok()?;
        if let Some(mask) = mask {
            for (index, byte) in payload.iter_mut().enumerate() {
                *byte ^= mask[index % 4];
            }
        }
    }
    Some((opcode, payload))
}

async fn write_ws_frame(
    wr: &mut OwnedWriteHalf,
    opcode: u8,
    payload: &[u8],
) -> std::io::Result<()> {
    let mut header = [0u8; 10];
    header[0] = 0x80 | opcode;
    let header_len = if payload.len() < 126 {
        header[1] = payload.len() as u8;
        2
    } else if payload.len() <= u16::MAX as usize {
        header[1] = 126;
        header[2..4].copy_from_slice(&(payload.len() as u16).to_be_bytes());
        4
    } else {
        header[1] = 127;
        header[2..10].copy_from_slice(&(payload.len() as u64).to_be_bytes());
        10
    };
    wr.write_all(&header[..header_len]).await?;
    wr.write_all(payload).await?;
    wr.flush().await
}

/// 浏览器控制端：第一条消息是 `video <哈希>` / `control <哈希>`，之后就是同一条数据流。
///
/// 视频方向：长辈端 → WebSocket 二进制帧；控制方向：WebSocket 二进制帧 → 长辈端。
async fn handle_websocket(
    mut rd: OwnedReadHalf,
    mut wr: OwnedWriteHalf,
    peer: SocketAddr,
    registry: Arc<Mutex<Registry>>,
) {
    let Some((opcode, payload)) = read_ws_frame(&mut rd).await else {
        return;
    };
    if opcode != WS_TEXT {
        let _ = write_ws_frame(&mut wr, WS_CLOSE, &[]).await;
        return;
    }
    let text = String::from_utf8_lossy(&payload).trim().to_string();
    let parts: Vec<&str> = text.split(' ').collect();
    if parts.len() != 2 || !is_role(parts[0]) {
        let _ = write_ws_frame(&mut wr, WS_TEXT, b"ERR").await;
        return;
    }
    let role = parts[0].to_string();
    let hash = parts[1].to_string();

    if lock(&registry).limiter.locked(&hash, peer.ip()) {
        log(format!(
            "AUDIT pair_blocked hash={} ip={} role={role}",
            short(&hash),
            peer.ip()
        ));
        let _ = write_ws_frame(&mut wr, WS_TEXT, b"ERR").await;
        return;
    }

    let (mut agent_rd, mut agent_wr) = match pair_agent(&hash, &role, &registry).await {
        Ok(pair) => {
            {
                let mut reg = lock(&registry);
                reg.limiter.clear(&hash, peer.ip());
                reg.pairs_total += 1;
            }
            pair
        }
        Err(PairError::Offline) => {
            note_pair_failure(&registry, &hash, peer.ip(), &role);
            let _ = write_ws_frame(&mut wr, WS_TEXT, b"ERR").await;
            return;
        }
        Err(PairError::Timeout) => {
            note_pair_failure(&registry, &hash, peer.ip(), &role);
            let _ = write_ws_frame(&mut wr, WS_TEXT, b"TIMEOUT").await;
            return;
        }
    };

    // 写端由两个方向共享：上行数据与 pong 都要发，必须串行
    let writer = std::sync::Arc::new(tokio::sync::Mutex::new(wr));
    {
        let mut guard = writer.lock().await;
        if write_ws_frame(&mut guard, WS_TEXT, b"OK").await.is_err() {
            return;
        }
    }
    log(format!("浏览器已接入 {role}（{}…）", short(&hash)));

    let uplink_writer = std::sync::Arc::clone(&writer);
    let uplink = tokio::spawn(async move {
        let mut buffer = vec![0u8; 64 * 1024];
        loop {
            match agent_rd.read(&mut buffer).await {
                Ok(0) | Err(_) => break,
                Ok(read) => {
                    let mut guard = uplink_writer.lock().await;
                    if write_ws_frame(&mut guard, WS_BINARY, &buffer[..read])
                        .await
                        .is_err()
                    {
                        break;
                    }
                }
            }
        }
    });

    loop {
        match read_ws_frame(&mut rd).await {
            Some((WS_BINARY, data)) => {
                if agent_wr.write_all(&data).await.is_err() {
                    break;
                }
            }
            Some((WS_PING, data)) => {
                let mut guard = writer.lock().await;
                let _ = write_ws_frame(&mut guard, WS_PONG, &data).await;
            }
            Some((WS_CLOSE, _)) | None => break,
            Some(_) => {}
        }
    }

    uplink.abort();
    let _ = agent_wr.shutdown().await;
    log(format!("浏览器断开 {role}（{}…）", short(&hash)));
}

/// 两条连接字节级对接，任一方向断开就一起收摊。
async fn splice(
    controller: (OwnedReadHalf, OwnedWriteHalf),
    agent: (OwnedReadHalf, OwnedWriteHalf),
) {
    let (mut controller_rd, mut controller_wr) = controller;
    let (mut agent_rd, mut agent_wr) = agent;

    tokio::select! {
        result = tokio::io::copy(&mut controller_rd, &mut agent_wr) => {
            report_end("控制端→长辈端", result);
        }
        result = tokio::io::copy(&mut agent_rd, &mut controller_wr) => {
            report_end("长辈端→控制端", result);
        }
    }
    // 两组半连接在这里一起析构，两个 socket 都会关闭
}

/// 谁先断开是排查"用着用着就断了"的关键信息，按方向记下来。
fn report_end(direction: &str, result: std::io::Result<u64>) {
    match result {
        Ok(bytes) => log(format!("连接结束（{direction} 先断，共 {bytes} 字节）")),
        Err(error) => log(format!("连接异常（{direction}）：{error}")),
    }
}

fn lock(registry: &Arc<Mutex<Registry>>) -> std::sync::MutexGuard<'_, Registry> {
    registry
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner())
}

/// 在已绑定好的监听器上跑服务（测试里也用它拿到随机端口）。
pub async fn serve(listener: TcpListener) {
    serve_with_config(listener, LimiterConfig::default()).await
}

/// 带限速配置启动；`main` 与测试都走这里。
pub async fn serve_with_config(listener: TcpListener, limiter: LimiterConfig) {
    let registry = Arc::new(Mutex::new(Registry::with_limiter(limiter)));
    log(format!(
        "配对中继已启动（v2：常连接 + 按需开流），监听 {}；配对限速：{}",
        listener
            .local_addr()
            .map(|a| a.to_string())
            .unwrap_or_else(|_| "?".to_string()),
        if limiter.max_fails == 0 {
            "关闭".to_string()
        } else {
            format!(
                "{} 次失败/{}s 锁定 {}s",
                limiter.max_fails, limiter.window_secs, limiter.lock_secs
            )
        }
    ));

    loop {
        let (stream, peer) = match listener.accept().await {
            Ok(pair) => pair,
            Err(error) => {
                log(format!("accept 失败：{error}"));
                tokio::time::sleep(Duration::from_millis(100)).await;
                continue;
            }
        };
        let registry = Arc::clone(&registry);
        tokio::spawn(async move {
            handle_connection(stream, peer, registry).await;
        });
    }
}

/// 绑定地址并进入服务循环。
pub async fn run(addr: SocketAddr) -> std::io::Result<()> {
    run_with_config(addr, LimiterConfig::default()).await
}

pub async fn run_with_config(addr: SocketAddr, limiter: LimiterConfig) -> std::io::Result<()> {
    let listener = TcpListener::bind(addr).await?;
    serve_with_config(listener, limiter).await;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn only_known_roles_are_accepted() {
        assert!(is_role("control"));
        assert!(is_role("video"));
        assert!(is_role("audio"));
        assert!(!is_role("screen"));
        assert!(!is_role(""));
    }

    #[test]
    fn short_hash_never_panics() {
        assert_eq!(short("0123456789abcdef"), "01234567");
        assert_eq!(short("abc"), "abc");
    }

    #[test]
    fn websocket_accept_matches_rfc_example() {
        // RFC 6455 §1.3 的标准示例
        assert_eq!(
            websocket_accept("dGhlIHNhbXBsZSBub25jZQ=="),
            "s3pPLMBiTxaQ9kYGzzhZRbK+xOo="
        );
    }

    fn limiter(max_fails: u32, window_secs: u64, lock_secs: u64) -> RateLimiter {
        RateLimiter::new(LimiterConfig {
            max_fails,
            window_secs,
            lock_secs,
        })
    }

    fn ip(value: &str) -> IpAddr {
        value.parse().unwrap()
    }

    #[test]
    fn limiter_locks_after_threshold() {
        let mut limiter = limiter(3, 60, 60);
        let peer = ip("203.0.113.7");
        assert!(!limiter.locked("abcd", peer));
        assert_eq!(limiter.record_failure("abcd", peer).1, None);
        assert_eq!(limiter.record_failure("abcd", peer).1, None);
        let (count, locked_until) = limiter.record_failure("abcd", peer);
        assert_eq!(count, 3);
        assert!(locked_until.is_some());
        assert!(limiter.locked("abcd", peer));
        // 同一来源的其它口令也会被 IP 维度连带锁住（回环来源除外）
        assert!(limiter.locked("ef01", peer));
    }

    #[test]
    fn limiter_clears_on_success() {
        let mut limiter = limiter(1, 60, 60);
        let peer = ip("203.0.113.7");
        assert!(limiter.record_failure("abcd", peer).1.is_some());
        assert!(limiter.locked("abcd", peer));
        limiter.clear("abcd", peer);
        assert!(!limiter.locked("abcd", peer));
        assert!(limiter.by_hash.is_empty());
    }

    #[test]
    fn limiter_starts_fresh_after_lock_expires() {
        // lock_secs = 0：锁定立即到期，用来验证"到期后重新计数"
        let mut limiter = limiter(2, 60, 0);
        let peer = ip("203.0.113.7");
        limiter.record_failure("abcd", peer);
        let (_, locked_until) = limiter.record_failure("abcd", peer);
        assert_eq!(locked_until, Some(epoch_secs()));
        assert!(!limiter.locked("abcd", peer), "lock_secs=0 时锁定立即过期");
        // 到期后计数归零：再失败一次只是重新开始计数
        let (count, locked_again) = limiter.record_failure("abcd", peer);
        assert_eq!(count, 1);
        assert!(locked_again.is_none());
    }

    #[test]
    fn limiter_ignores_loopback_for_the_ip_dimension() {
        let mut limiter = limiter(2, 60, 600);
        let loopback = ip("127.0.0.1");
        limiter.record_failure("aaaa", loopback);
        limiter.record_failure("aaaa", loopback);
        assert!(limiter.by_ip.is_empty(), "回环来源不参与 IP 维度");
        // 隧道接入时所有请求都来自回环，不能用 IP 维度连带锁定别的口令
        assert!(!limiter.locked("bbbb", loopback));
    }

    #[test]
    fn limiter_can_be_disabled() {
        let mut limiter = limiter(0, 60, 600);
        let peer = ip("203.0.113.7");
        for _ in 0..50 {
            assert_eq!(limiter.record_failure("abcd", peer), (0, None));
        }
        assert!(!limiter.locked("abcd", peer));
        assert!(limiter.by_hash.is_empty());
        assert!(limiter.by_ip.is_empty());
    }

    #[test]
    fn limiter_prunes_stale_records() {
        let mut limiter = limiter(10, 1, 0);
        let peer = ip("203.0.113.7");
        limiter.record_failure("abcd", peer);
        // 人为把窗口推到很久以前，并让表超过清理阈值
        for record in limiter
            .by_hash
            .values_mut()
            .chain(limiter.by_ip.values_mut())
        {
            record.window_started = 1;
        }
        for index in 0..5000 {
            limiter
                .by_hash
                .insert(format!("stale{index}"), FailRecord::default());
        }
        limiter.record_failure("ef01", peer);
        assert!(
            limiter.by_hash.len() < 100,
            "过期记录应被清理，实际 {}",
            limiter.by_hash.len()
        );
    }
}
