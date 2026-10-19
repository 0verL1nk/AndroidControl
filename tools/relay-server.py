#!/usr/bin/env python3
"""配对中继：让"隔着 NAT 的两台手机"建立连接，不需要 VPN，也不需要记地址。

为什么需要它
------------
两台手机通常都在 NAT 后面，谁也连不上谁。传统做法是组一个 VPN（WireGuard/Tailscale），
但那要在**老人手机上再装一个 App、再导入一份配置**——这正是我们想避免的负担。

设计：常连接 + 按需开流（v2）
---------------------------
v1 的问题是"登记一次就被配对消耗掉"，长辈端要隔一会儿才重新登记，控制端可能等上几十秒。
v2 把连接分成两类：

  1. **信令连接**（长辈端 ↔ 中继，常驻）：`AGENT <口令哈希> signaling`，
     每 10 秒一次 `PING/PONG` 心跳。
  2. **数据连接**（按需建立）：控制端来 `FIND <哈希> <角色>` 时，中继通过信令连接
     告诉长辈端 `OPEN <角色>`，长辈端**立刻**开一条数据连接上来，两边一对接就开始转发。

于是配对是"毫秒级"的，而不是"等下一次登记"。任何一端掉线都能在心跳周期内被发现并重建。

安全边界
--------
- 线上只有口令的哈希，没有口令本身；不知道口令就配不上对。
- 中继不解析、不落盘内容，只在内存里保存"哈希 → 连接"的映射。
- 中继是明文字节转发，请放在自己的机器/内网里；公网暴露时自行加 TLS 或限制来源。

用法
----
    python tools/relay-server.py --listen 0.0.0.0:27180
"""

import argparse
import socket
import threading
import time

AGENT_VERB = "AGENT"
DATA_VERB = "DATA"
FIND_VERB = "FIND"
ROLES = ("control", "video")

PING_INTERVAL = 10          # 双方约定的心跳间隔
SIGNALING_TIMEOUT = 35      # 超过这么久没心跳就认为信令连接已死
DATA_TIMEOUT = 5            # 等长辈端开出数据连接的最长时间


def _force_utf8() -> None:
    """Windows 控制台默认 GBK，日志里的箭头/中文会直接抛异常把服务搞崩。"""
    import sys
    for stream in (sys.stdout, sys.stderr):
        try:
            stream.reconfigure(encoding="utf-8", errors="replace")
        except Exception:
            pass


def log(*args: object) -> None:
    print(time.strftime("[%H:%M:%S]"), *args, flush=True)


def close_quietly(*sockets: socket.socket) -> None:
    for s in sockets:
        try:
            s.shutdown(socket.SHUT_RDWR)
        except OSError:
            pass
        try:
            s.close()
        except OSError:
            pass


def tune(sock: socket.socket) -> None:
    """开启保活：否则一端异常退出时，另一端可能长时间察觉不到（连接半开）。"""
    sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_KEEPALIVE, 1)
    for opt, value in (("TCP_KEEPIDLE", 15), ("TCP_KEEPINTVL", 5), ("TCP_KEEPCNT", 3)):
        if hasattr(socket, opt):
            try:
                sock.setsockopt(socket.IPPROTO_TCP, getattr(socket, opt), value)
            except OSError:
                pass


def read_line(sock: socket.socket, limit: int = 256) -> str | None:
    """逐字节读到换行——不能预读，后面紧接着可能是二进制流。"""
    buf = bytearray()
    while len(buf) < limit:
        try:
            ch = sock.recv(1)
        except OSError:
            return None
        if not ch:
            return None
        if ch == b"\n":
            return buf.decode(errors="replace").strip()
        buf += ch
    return None


class Waiting:
    """一个正在等长辈端开数据连接的控制端连接。"""

    def __init__(self, controller: socket.socket) -> None:
        self.controller = controller
        self.ready = threading.Event()
        self.data_socket: socket.socket | None = None

    def deliver(self, data: socket.socket) -> None:
        self.data_socket = data
        self.ready.set()


class Registry:
    """信令连接 + 待配对请求。全部只在内存里。"""

    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._signaling: dict[str, socket.socket] = {}
        self._waiting: dict[tuple[str, str], Waiting] = {}

    # ── 信令 ───────────────────────────────────────────────
    def put_signaling(self, token_hash: str, sock: socket.socket) -> socket.socket | None:
        with self._lock:
            old = self._signaling.get(token_hash)
            self._signaling[token_hash] = sock
        return old

    def drop_signaling(self, token_hash: str, sock: socket.socket) -> None:
        with self._lock:
            if self._signaling.get(token_hash) is sock:
                del self._signaling[token_hash]

    def signaling_of(self, token_hash: str) -> socket.socket | None:
        with self._lock:
            return self._signaling.get(token_hash)

    # ── 配对 ───────────────────────────────────────────────
    def park_controller(self, token_hash: str, role: str, controller: socket.socket) -> Waiting:
        waiting = Waiting(controller)
        with self._lock:
            old = self._waiting.get((token_hash, role))
            self._waiting[(token_hash, role)] = waiting
        if old is not None:
            # 同一角色只允许一个等待者：后来的顶掉先来的
            old.ready.set()
        return waiting

    def take_waiting(self, token_hash: str, role: str) -> Waiting | None:
        with self._lock:
            return self._waiting.pop((token_hash, role), None)

    def stats(self) -> str:
        with self._lock:
            return f"信令 {len(self._signaling)} 条，待配对 {len(self._waiting)} 个"


REGISTRY = Registry()


def splice(a: socket.socket, b: socket.socket) -> None:
    """两条连接字节级对接，任一方向断开就一起收摊。"""
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
            close_quietly(src, dst)

    threading.Thread(target=pump, args=(a, b), daemon=True).start()
    threading.Thread(target=pump, args=(b, a), daemon=True).start()


def handle_signaling(client: socket.socket, addr: tuple[str, int], token_hash: str) -> None:
    """常驻信令连接：处理心跳，并在控制端到来时通知长辈端开流。"""
    client.settimeout(SIGNALING_TIMEOUT)
    # 必须先应答，长辈端才知道登记成功（v2 初版漏了这一步，长辈端等 8 秒超时反复重连）
    client.sendall(b"OK\n")
    old = REGISTRY.put_signaling(token_hash, client)
    if old is not None:
        log(f"信令重复登记（{token_hash[:8]}…），替换旧连接")
        close_quietly(old)
    log(f"长辈端信令已连接（{token_hash[:8]}…）来自 {addr[0]} —— {REGISTRY.stats()}")

    try:
        while True:
            line = read_line(client)
            if line is None:
                break
            if line == "PING":
                client.sendall(b"PONG\n")
            else:
                log(f"信令上收到未知消息：{line[:40]!r}")
    except OSError:
        pass
    finally:
        REGISTRY.drop_signaling(token_hash, client)
        close_quietly(client)
        log(f"长辈端信令断开（{token_hash[:8]}…）")


def handle_data(client: socket.socket, addr: tuple[str, int], token_hash: str, role: str) -> None:
    """长辈端按需开出的数据连接：交给正在等的控制端。"""
    waiting = REGISTRY.take_waiting(token_hash, role)
    if waiting is None:
        log(f"收到没人等待的 {role} 数据连接，丢弃")
        close_quietly(client)
        return
    waiting.deliver(client)


def handle_controller(client: socket.socket, addr: tuple[str, int], token_hash: str, role: str) -> None:
    signaling = REGISTRY.signaling_of(token_hash)
    if signaling is None:
        log(f"控制端找 {role}（{token_hash[:8]}…）—— 长辈端不在线")
        client.sendall(b"ERR\n")
        close_quietly(client)
        return

    waiting = REGISTRY.park_controller(token_hash, role, client)
    try:
        signaling.sendall(f"OPEN {role}\n".encode())
    except OSError:
        log("通知长辈端失败，连接可能已失效")
        REGISTRY.take_waiting(token_hash, role)
        client.sendall(b"ERR\n")
        close_quietly(client)
        return

    # 长辈端一般几十毫秒就开好流；等不到就明确失败，不让控制端干等
    if not waiting.ready.wait(DATA_TIMEOUT) or waiting.data_socket is None:
        log(f"等长辈端开 {role} 流超时")
        close_quietly(client)
        return

    client.sendall(b"OK\n")
    log(f"配对成功 {role}（{token_hash[:8]}…）{addr[0]} -> 长辈端")
    splice(client, waiting.data_socket)


def handle(client: socket.socket, addr: tuple[str, int]) -> None:
    tune(client)
    line = read_line(client)
    if line is None:
        close_quietly(client)
        return

    parts = line.split(" ")
    verb = parts[0].upper() if parts else ""

    if verb == AGENT_VERB and len(parts) == 3 and parts[2] == "signaling":
        handle_signaling(client, addr, parts[1])
        return

    if verb == DATA_VERB and len(parts) == 3 and parts[2] in ROLES:
        client.sendall(b"OK\n")
        handle_data(client, addr, parts[1], parts[2])
        return

    if verb == FIND_VERB and len(parts) == 3 and parts[2] in ROLES:
        handle_controller(client, addr, parts[1], parts[2])
        return

    log(f"无法识别的请求：{line[:60]!r}")
    close_quietly(client)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--listen", default="0.0.0.0:27180", help="监听地址，默认 0.0.0.0:27180")
    args = ap.parse_args()

    host, _, port_text = args.listen.partition(":")
    port = int(port_text or "27180")

    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server.bind((host, port))
    server.listen(64)
    log(f"配对中继已启动（v2：常连接 + 按需开流），监听 {host}:{port}")

    while True:
        try:
            client, addr = server.accept()
        except KeyboardInterrupt:
            return 0
        threading.Thread(target=handle, args=(client, addr), daemon=True).start()


if __name__ == "__main__":
    _force_utf8()
    raise SystemExit(main())
