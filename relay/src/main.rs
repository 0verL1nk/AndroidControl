//! 节点上的可执行程序：`ac-relay --listen 0.0.0.0:27180`。
//!
//! 部署方式见 `docs/ops/relay-node.md`。

use std::net::SocketAddr;
use std::process::ExitCode;

use ac_relay::LimiterConfig;

const USAGE: &str = "\
配对中继（ac-relay）—— 让两台处于 NAT 后的手机建立连接

用法：
    ac-relay [--listen 0.0.0.0:27180] [限速选项]

选项：
    --listen <地址>          监听地址，默认 0.0.0.0:27180
    --max-fails <次数>       窗口内允许的配对失败次数，默认 10；0 表示关闭限速
    --fail-window <秒>       失败计数的统计窗口，默认 600
    --lock-secs <秒>         超过次数后的锁定时间，默认 600
    -h, --help               显示本帮助

限速只针对配对失败（长辈端不在线 / 开流超时），按口令哈希与来源 IP 分别计数；
锁定期间该哈希的配对请求直接失败。日志里只出现哈希前 8 位，不记录口令本身。
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
    let mut listen = String::from("0.0.0.0:27180");
    let mut limiter = LimiterConfig::default();
    let mut args = std::env::args().skip(1);

    while let Some(arg) = args.next() {
        match arg.as_str() {
            "--listen" => match args.next() {
                Some(value) => listen = value,
                None => {
                    eprintln!("--listen 需要一个地址，例如 0.0.0.0:27180");
                    return ExitCode::FAILURE;
                }
            },
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

    let addr: SocketAddr = match listen.parse() {
        Ok(addr) => addr,
        Err(error) => {
            eprintln!("监听地址不合法（{listen}）：{error}");
            return ExitCode::FAILURE;
        }
    };

    match ac_relay::run_with_config(addr, limiter).await {
        Ok(()) => ExitCode::SUCCESS,
        Err(error) => {
            eprintln!("启动失败：{error}");
            ExitCode::FAILURE
        }
    }
}
