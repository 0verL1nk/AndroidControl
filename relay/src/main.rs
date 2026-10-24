//! 节点上的可执行程序：`ac-relay --listen 0.0.0.0:27180`。
//!
//! 部署方式见 `docs/ops/relay-node.md`。

use std::net::SocketAddr;
use std::path::Path;
use std::process::ExitCode;

use ac_relay::LimiterConfig;
use tokio::net::TcpListener;
use tokio_rustls::TlsAcceptor;

const USAGE: &str = "\
配对中继（ac-relay）—— 让两台处于 NAT 后的手机建立连接

用法：
    ac-relay [--listen 0.0.0.0:27180] [限速选项] [TLS 选项]

选项：
    --listen <地址>          明文监听地址，默认 0.0.0.0:27180
    --max-fails <次数>       窗口内允许的配对失败次数，默认 10；0 表示关闭限速
    --fail-window <秒>       失败计数的统计窗口，默认 600
    --lock-secs <秒>         超过次数后的锁定时间，默认 600
    --tls-listen <地址>      TLS 监听地址（例如 0.0.0.0:27443），不填则不启用
    --tls-cert <路径>        PEM 证书链（自签即可）
    --tls-key <路径>         PEM 私钥
    -h, --help               显示本帮助

限速只针对配对失败（长辈端不在线 / 开流超时），按口令哈希与来源 IP 分别计数；
锁定期间该哈希的配对请求直接失败。日志里只出现哈希前 8 位，不记录口令本身。

TLS 与明文共用同一份配对表：长辈端连哪个端口都能和另一侧配上对。
证书指纹（SHA-256）由长辈端配置并固定，不依赖公共 CA。
";

/// 解析一个非负整数参数。
fn parse_num(name: &str, value: Option<String>) -> Result<u64, String> {
    let Some(value) = value else {
        return Err(format!("{name} 需要一个数字"));
    };
    value
        .parse::<u64>()
        .map_err(|error| format!("{name} 的值不合法（{value}）：{error}"))
}

#[tokio::main]
async fn main() -> ExitCode {
    let mut listen: Option<String> = None;
    let mut limiter = LimiterConfig::default();
    let mut tls_listen: Option<String> = None;
    let mut tls_cert: Option<String> = None;
    let mut tls_key: Option<String> = None;
    let mut args = std::env::args().skip(1);

    macro_rules! take {
        ($name:expr, $target:expr) => {
            match args.next() {
                Some(value) => $target = Some(value),
                None => {
                    eprintln!("{} 需要一个参数", $name);
                    return ExitCode::FAILURE;
                }
            }
        };
    }

    while let Some(arg) = args.next() {
        match arg.as_str() {
            "--listen" => take!("--listen", listen),
            "--tls-listen" => take!("--tls-listen", tls_listen),
            "--tls-cert" => take!("--tls-cert", tls_cert),
            "--tls-key" => take!("--tls-key", tls_key),
            "--max-fails" => match parse_num("--max-fails", args.next()) {
                Ok(value) => limiter.max_fails = value.min(u32::MAX as u64) as u32,
                Err(message) => {
                    eprintln!("{message}");
                    return ExitCode::FAILURE;
                }
            },
            "--fail-window" => match parse_num("--fail-window", args.next()) {
                Ok(value) => limiter.window_secs = value,
                Err(message) => {
                    eprintln!("{message}");
                    return ExitCode::FAILURE;
                }
            },
            "--lock-secs" => match parse_num("--lock-secs", args.next()) {
                Ok(value) => limiter.lock_secs = value,
                Err(message) => {
                    eprintln!("{message}");
                    return ExitCode::FAILURE;
                }
            },
            "-h" | "--help" => {
                print!("{USAGE}");
                return ExitCode::SUCCESS;
            }
            other => {
                eprintln!("未知参数：{other}\n\n{USAGE}");
                return ExitCode::FAILURE;
            }
        }
    }

    let listen = listen.unwrap_or_else(|| "0.0.0.0:27180".to_string());
    let addr: SocketAddr = match listen.parse() {
        Ok(addr) => addr,
        Err(error) => {
            eprintln!("明文监听地址不合法（{listen}）：{error}");
            return ExitCode::FAILURE;
        }
    };

    let tls = match (tls_listen, tls_cert, tls_key) {
        (None, None, None) => None,
        (Some(listen), Some(cert), Some(key)) => {
            let tls_addr: SocketAddr = match listen.parse() {
                Ok(addr) => addr,
                Err(error) => {
                    eprintln!("TLS 监听地址不合法（{listen}）：{error}");
                    return ExitCode::FAILURE;
                }
            };
            Some((tls_addr, cert, key))
        }
        _ => {
            eprintln!("启用 TLS 需要同时给出 --tls-listen、--tls-cert、--tls-key");
            return ExitCode::FAILURE;
        }
    };

    let registry = ac_relay::new_registry(limiter);

    let listener = match TcpListener::bind(addr).await {
        Ok(listener) => listener,
        Err(error) => {
            eprintln!("明文监听失败（{addr}）：{error}");
            return ExitCode::FAILURE;
        }
    };

    let Some((tls_addr, cert, key)) = tls else {
        ac_relay::serve_with(listener, registry).await;
        return ExitCode::SUCCESS;
    };

    let config = match ac_relay::load_tls_server_config(Path::new(&cert), Path::new(&key)) {
        Ok(config) => config,
        Err(message) => {
            eprintln!("TLS 配置失败：{message}");
            return ExitCode::FAILURE;
        }
    };
    let tls_listener = match TcpListener::bind(tls_addr).await {
        Ok(listener) => listener,
        Err(error) => {
            eprintln!("TLS 监听失败（{tls_addr}）：{error}");
            return ExitCode::FAILURE;
        }
    };
    let acceptor = TlsAcceptor::from(config);

    tokio::join!(
        ac_relay::serve_with(listener, registry.clone()),
        ac_relay::serve_tls(tls_listener, acceptor, registry)
    );
    ExitCode::SUCCESS
}
