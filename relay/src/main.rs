//! 节点上的可执行程序：`ac-relay --listen 0.0.0.0:27180`。
//!
//! 部署方式见 `docs/ops/relay-node.md`。

use std::net::SocketAddr;
use std::process::ExitCode;

const USAGE: &str = "\
配对中继（ac-relay）—— 让两台处于 NAT 后的手机建立连接

用法：
    ac-relay [--listen 0.0.0.0:27180]

选项：
    --listen <地址>   监听地址，默认 0.0.0.0:27180
    -h, --help        显示本帮助
";

#[tokio::main]
async fn main() -> ExitCode {
    let mut listen = String::from("0.0.0.0:27180");
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

    match ac_relay::run(addr).await {
        Ok(()) => ExitCode::SUCCESS,
        Err(error) => {
            eprintln!("启动失败：{error}");
            ExitCode::FAILURE
        }
    }
}
