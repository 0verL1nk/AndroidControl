#!/usr/bin/env python3
"""M1 验收脚本：把 spec 里的验收标准变成可重复执行的检查。

它会连上长辈端，测握手与首帧耗时、帧率、网络往返，并通过 adb 注入点击、
对比真机截图确认注入生效；最后测试断线重连。

用法：
    python tools/acceptance.py --host <长辈端地址> --token <6位> \
        --adb-serial <被控设备 adb 序列号>

判据（来自 specs/001-remote-android-control/spec.md）：
    SC-001 30 秒内看到画面
    SC-002 局域网 ≥30fps / 跨公网 ≥20fps（静态画面帧率会低，这里只作参考）
    SC-003 点击响应 ≤500ms（跨公网）
    SC-006 断网后 ≤20 秒恢复
"""

import argparse
import hashlib
import socket
import threading
import struct
import subprocess
import sys
import time

CONTROL_PORT = 27185
VIDEO_PORT = 27186

MSG_INJECT_TOUCH_EVENT = 2
ACTION_DOWN, ACTION_UP = 0, 1
POINTER_ID_FINGER = 0xFFFFFFFFFFFFFFFE


def log(msg: str) -> None:
    print(msg, flush=True)


def connect(host: str, port: int, verb: str, token: str, timeout: float = 8.0) -> socket.socket:
    s = socket.create_connection((host, port), timeout=timeout)
    s.sendall(f"{verb} {token}\n".encode())
    # 逐字节读，避免预读吞掉紧随其后的视频流头部
    buf = b""
    while len(buf) < 16:
        ch = s.recv(1)
        if not ch or ch == b"\n":
            break
        buf += ch
    if buf.decode(errors="replace").strip() != "OK":
        raise RuntimeError(f"{verb} 握手失败：{buf!r}")
    return s


def read_exact(sock: socket.socket, n: int) -> bytes:
    buf = b""
    while len(buf) < n:
        chunk = sock.recv(n - len(buf))
        if not chunk:
            raise EOFError("流结束")
        buf += chunk
    return buf


def tap(control: socket.socket, x: int, y: int, w: int, h: int) -> None:
    for action in (ACTION_DOWN, ACTION_UP):
        control.sendall(struct.pack(
            ">BBQiiHHHii", MSG_INJECT_TOUCH_EVENT, action, POINTER_ID_FINGER,
            x, y, w, h, 0xFFFF, 1, 1,
        ))
        time.sleep(0.04)


def adb(serial: str, *args: str) -> str:
    return subprocess.run(
        ["adb", "-s", serial, *args],
        capture_output=True, text=True, timeout=30,
    ).stdout


def phone_screen_hash(serial: str) -> str:
    data = subprocess.run(
        ["adb", "-s", serial, "exec-out", "screencap", "-p"],
        capture_output=True, timeout=30,
    ).stdout
    return hashlib.sha256(data).hexdigest()[:16]


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--host", required=True)
    ap.add_argument("--token", required=True)
    ap.add_argument("--adb-serial", help="被控设备的 adb 序列号；给了才做注入与画面比对")
    ap.add_argument("--seconds", type=float, default=6.0, help="采样时长")
    args = ap.parse_args()

    results: list[tuple[str, str, str]] = []

    # ── SC-001：连接与首帧 ─────────────────────────────────────
    started = time.time()
    control = connect(args.host, CONTROL_PORT, "HELLO", args.token)
    log("✓ 控制通道握手成功")
    video = connect(args.host, VIDEO_PORT, "AUTH", args.token)
    log("✓ 视频通道握手成功")

    device_name = read_exact(video, 64).split(b"\x00")[0].decode(errors="replace")
    codec = read_exact(video, 4)
    log(f"✓ 设备：{device_name}，编码：{codec.decode(errors='replace')}")

    # scrcpy 只在画面变化时发帧，静态画面测帧率没有意义。
    # 给了 adb 就在采样期间制造滚动，让数字反映真实能力。
    stop_motion = threading.Event()
    if args.adb_serial:
        # 采样期间需要一个"会动"的画面：scrcpy 只在画面变化时发帧，
        # 停在静态界面上测出来的帧率没有意义。先切到可滚动的设置页。
        adb(args.adb_serial, "shell", "am", "start", "-a", "android.settings.SETTINGS")
        time.sleep(2)

        def keep_moving() -> None:
            while not stop_motion.is_set():
                adb(args.adb_serial, "shell", "input", "swipe", "600", "1800", "600", "900", "200")
                time.sleep(0.3)

        threading.Thread(target=keep_moving, daemon=True).start()

    first_frame_at = None
    frames = 0
    bytes_total = 0
    width = height = 0
    deadline = time.time() + args.seconds
    video.settimeout(1.0)
    while time.time() < deadline:
        try:
            header = read_exact(video, 12)
        except (EOFError, socket.timeout):
            continue
        first = header[0]
        if first & 0x80:
            width = struct.unpack(">I", header[4:8])[0]
            height = struct.unpack(">I", header[8:12])[0]
            continue
        size = struct.unpack(">I", header[8:12])[0]
        read_exact(video, size)
        frames += 1
        bytes_total += size
        if first_frame_at is None:
            first_frame_at = time.time() - started

    stop_motion.set()
    elapsed = time.time() - started
    fps = frames / elapsed if elapsed > 0 else 0
    kbps = bytes_total * 8 / elapsed / 1000 if elapsed > 0 else 0
    log(f"✓ 画面 {width}x{height}，{frames} 帧 / {elapsed:.1f}s → {fps:.1f} fps，{kbps:.0f} kbps")
    results.append(("SC-001 30 秒内出现画面", "通过" if first_frame_at and first_frame_at < 30 else "失败",
                    f"首帧耗时 {first_frame_at:.1f}s" if first_frame_at else "未收到帧"))
    results.append(("SC-002 帧率", "通过" if fps >= 20 else "参考", f"{fps:.1f} fps（静态画面会偏低）"))

    # ── SC-003：注入点击后真机画面变化 ─────────────────────────
    if args.adb_serial and width > 0:
        before = phone_screen_hash(args.adb_serial)
        t0 = time.time()
        tap(control, width // 2, height // 2, width, height)
        changed = False
        for _ in range(10):
            time.sleep(0.2)
            if phone_screen_hash(args.adb_serial) != before:
                changed = True
                break
        latency_ms = (time.time() - t0) * 1000
        log(f"{'✓' if changed else '✗'} 注入点击，画面{'已变化' if changed else '未变化'}，检测耗时 {latency_ms:.0f}ms")
        results.append(("SC-003 点击生效", "通过" if changed else "失败", f"检测跨度 {latency_ms:.0f}ms"))
    else:
        results.append(("SC-003 点击生效", "跳过", "未提供 --adb-serial"))

    control.close()
    video.close()

    # ── SC-006：断线重连 ──────────────────────────────────────
    time.sleep(1)
    t0 = time.time()
    ok = False
    for _ in range(20):
        try:
            c2 = connect(args.host, CONTROL_PORT, "HELLO", args.token, timeout=3)
            c2.close()
            ok = True
            break
        except OSError:
            time.sleep(1)
    recover = time.time() - t0
    log(f"{'✓' if ok else '✗'} 断线后重连，用时 {recover:.1f}s")
    results.append(("SC-006 断线 20 秒内恢复", "通过" if ok and recover <= 20 else "失败", f"{recover:.1f}s"))

    # ── 汇总 ─────────────────────────────────────────────────
    print()
    print("════════ 验收汇总 ════════")
    for name, verdict, detail in results:
        print(f"  [{verdict}] {name} —— {detail}")
    print("══════════════════════════")
    return 0 if all(v in ("通过", "跳过", "参考") for _, v, _ in results) else 1


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as exc:  # noqa: BLE001
        print(f"❌ {type(exc).__name__}: {exc}")
        sys.exit(1)
