#!/usr/bin/env python3
"""配对中继：让"隔着 NAT 的两台手机"建立连接，不需要 VPN，也不需要记地址。

为什么需要它
------------
两台手机通常都在 NAT 后面，谁也连不上谁。传统做法是组一个 VPN（WireGuard/Tailscale），
但那要在**老人手机上再装一个 App、再导入一份配置**——这正是我们想避免的负担。

这个中继很小：它只负责"把两个已经连上来的连接对接起来"，不解析、不存储任何内容。

工作方式
--------
1. 长辈端 App 主动连上来，登记自己会提供哪两条流：
       AGENT <口令哈希> control
       AGENT <口令哈希> video
2. 控制端连上来，声明自己要找谁：
       FIND <口令哈希> control
   中继按哈希配对，配对成功回 OK，然后把两条连接**字节级对接**。

安全边界
--------
- 线上只有哈希，没有口令本身；不知道口令就配不上对。
- 中继转发的是 WireGuard 之外的另一条路径，因此**必须**放在可信主机上；
  若要跨公网使用，建议中继只在内网/隧道内监听，或自行加 TLS。
- 中继不落盘、不记录内容，只在内存里保存"哈希 → 连接"的映射。

用法
----
    python tools/relay-server.py --listen 0.0.0.0:27180
"""

import argparse
import socket
import threading
import time


# 登记后的连接靠这个事件"挂着"，直到进程退出
stop = threading.Event()

AGENT_VERB = "AGENT"
FIND_VERB = "FIND"
ROLES = ("control", "video")


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


class Registry:
    """哈希 → {角色: 连接} 的登记表。只在内存里。"""

    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._agents: dict[tuple[str, str], socket.socket] = {}

    def register(self, token_hash: str, role: str, sock: socket.socket) -> None:
        with self._lock:
            old = self._agents.get((token_hash, role))
            self._agents[(token_hash, role)] = sock
        if old is not None and old is not sock:
            # 同一台设备重连：旧连接直接放弃，避免"半死"连接一直占位
            log(f"  同一设备重新登记 {role}，替换旧连接")
            close_quietly(old)

    def take(self, token_hash: str, role: str) -> socket.socket | None:
        """取走并移除一条登记（一条连接只服务一次会话）。"""
        with self._lock:
            return self._agents.pop((token_hash, role), None)

    def drop_if_same(self, token_hash: str, role: str, sock: socket.socket) -> None:
        with self._lock:
            if self._agents.get((token_hash, role)) is sock:
                del self._agents[(token_hash, role)]

    def stats(self) -> str:
        with self._lock:
            return f"{len(self._agents)} 条待配对连接"


REGISTRY = Registry()


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

    t1 = threading.Thread(target=pump, args=(a, b), daemon=True)
    t2 = threading.Thread(target=pump, args=(b, a), daemon=True)
    t1.start()
    t2.start()


def tune(sock: socket.socket) -> None:
    """开启保活：否则一端异常退出时，另一端可能长时间察觉不到（连接半开）。"""
    sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_KEEPALIVE, 1)
    for opt, value in (("TCP_KEEPIDLE", 30), ("TCP_KEEPINTVL", 10), ("TCP_KEEPCNT", 3)):
        if hasattr(socket, opt):
            try:
                sock.setsockopt(socket.IPPROTO_TCP, getattr(socket, opt), value)
            except OSError:
                pass


def handle(client: socket.socket, addr: tuple[str, int]) -> None:
    tune(client)
    line = read_line(client)
    if line is None:
        close_quietly(client)
        return

    parts = line.split(" ")
    verb = parts[0].upper() if parts else ""

    # ── 长辈端登记 ──────────────────────────────────────────
    if verb == AGENT_VERB and len(parts) == 3 and parts[2] in ROLES:
        token_hash, role = parts[1], parts[2]
        client.sendall(b"OK\n")
        log(f"长辈端登记 {role}（{token_hash[:8]}…）来自 {addr[0]} —— 当前 {REGISTRY.stats()}")
        REGISTRY.register(token_hash, role, client)

        # 只把连接"挂"在这里，**不要**读它：
        # 配对后由 splice 的两个线程接管收发，这里再读就会两边抢同一个 socket。
        # 对端断开不需要在这里发现——配对时写入失败会立即收摊。
        stop.wait()
        return

    # ── 控制端找设备 ────────────────────────────────────────
    if verb == FIND_VERB and len(parts) == 3 and parts[2] in ROLES:
        token_hash, role = parts[1], parts[2]
        agent = REGISTRY.take(token_hash, role)
        if agent is None:
            log(f"控制端找 {role}（{token_hash[:8]}…）—— 没有匹配的长辈端")
            client.sendall(b"ERR\n")
            close_quietly(client)
            return
        client.sendall(b"OK\n")
        # 先告诉长辈端"真的有人来了"：它收到后才会去接本地流并开始转发。
        # 否则长辈端会在没人连的时候就占住本地接收方，状态也会误报"正在被协助"。
        try:
            agent.sendall(b"PAIRED\n")
        except OSError:
            log("通知长辈端失败，连接可能已失效")
            close_quietly(client, agent)
            return
        log(f"配对成功 {role}（{token_hash[:8]}…）{addr[0]} -> 长辈端")
        splice(client, agent)
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
    log(f"配对中继已启动，监听 {host}:{port}")

    while True:
        try:
            client, addr = server.accept()
        except KeyboardInterrupt:
            return 0
        threading.Thread(target=handle, args=(client, addr), daemon=True).start()


if __name__ == "__main__":
    _force_utf8()
    raise SystemExit(main())
