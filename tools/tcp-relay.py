#!/usr/bin/env python3
"""开发用 TCP 中继：把本机端口转发到另一台主机。

典型用途：模拟器没有外部网络时，用
    adb reverse tcp:27185 tcp:27185
把模拟器的 127.0.0.1:27185 接到宿主机，再由本脚本转给真机。

    python tools/tcp-relay.py --listen 27185 --target 10.66.66.3:27185
    python tools/tcp-relay.py --listen 27185,27186 --target 10.66.66.3:27185

注意：`--target` 的端口会按 `--listen` 中每个端口与目标端口的偏移计算——
两个端口一起转发时，目标端口依次 +1。
"""

import argparse
import socket
import threading
import sys


def pump(src: socket.socket, dst: socket.socket) -> None:
    try:
        while True:
            data = src.recv(65536)
            if not data:
                break
            dst.sendall(data)
    except OSError:
        pass
    finally:
        for s in (src, dst):
            try:
                s.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
            try:
                s.close()
            except OSError:
                pass


def serve(listen_port: int, target_host: str, target_port: int) -> None:
    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server.bind(("127.0.0.1", listen_port))
    server.listen(16)
    print(f"[relay] 127.0.0.1:{listen_port} → {target_host}:{target_port}", flush=True)

    while True:
        client, _ = server.accept()
        try:
            upstream = socket.create_connection((target_host, target_port), timeout=5)
        except OSError as exc:
            print(f"[relay] 连接目标失败：{exc}", flush=True)
            client.close()
            continue
        threading.Thread(target=pump, args=(client, upstream), daemon=True).start()
        threading.Thread(target=pump, args=(upstream, client), daemon=True).start()


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--listen", required=True, help="本地端口，可用逗号分隔多个")
    ap.add_argument("--target", required=True, help="目标 host:port")
    args = ap.parse_args()

    host, _, port_text = args.target.partition(":")
    base_port = int(port_text or "27185")
    ports = [int(p) for p in args.listen.split(",") if p.strip()]

    for index, listen_port in enumerate(ports):
        threading.Thread(
            target=serve,
            args=(listen_port, host, base_port + index),
            daemon=True,
        ).start()

    print("[relay] 运行中，Ctrl+C 退出", flush=True)
    try:
        threading.Event().wait()
    except KeyboardInterrupt:
        return 0
    return 0


if __name__ == "__main__":
    sys.exit(main())
