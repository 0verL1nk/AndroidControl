#!/usr/bin/env python3
"""开发用探针客户端：直接连长辈端的两条流，验证协议与画面。

用途（在实现 Android 控制端之前，先用它把链路验证清楚）：
  python tools/probe-client.py --host <DEVICE_LAN_IP> --token 123456 --tap 600 1300

它会：
  1. 连接控制通道并握手；
  2. 连接视频通道并握手；
  3. 解析 scrcpy 视频包头，把 H.264 裸流写成文件；
  4. 可选：发一次点击，用来验证注入链路。

退出码：0 成功；非 0 表示失败（便于脚本化验收）。
"""

import argparse
import socket
import struct
import sys
import time

CONTROL_PORT = 27185
VIDEO_PORT = 27186

# scrcpy 控制消息类型（app/src/control_msg.h）
MSG_INJECT_TOUCH_EVENT = 2
MSG_INJECT_SCROLL_EVENT = 3
MSG_INJECT_KEYCODE = 0

# android.view.MotionEvent 的 action
ACTION_DOWN = 0
ACTION_UP = 1

# android.view.KeyEvent 的 action
KEY_ACTION_DOWN = 0
KEY_ACTION_UP = 1

POINTER_ID_FINGER = 0xFFFFFFFFFFFFFFFF - 1  # SC_POINTER_ID_GENERIC_FINGER = -2


def connect(host: str, port: int, verb: str, token: str) -> socket.socket:
    s = socket.create_connection((host, port), timeout=10)
    s.sendall(f"{verb} {token}\n".encode())
    reply = s.recv(16).decode(errors="replace").strip()
    if reply != "OK":
        raise RuntimeError(f"{verb} 握手失败：{reply!r}")
    return s


def build_touch(action: int, x: int, y: int, width: int, height: int) -> bytes:
    return struct.pack(
        ">BBQiiHHHii",
        MSG_INJECT_TOUCH_EVENT,
        action,
        POINTER_ID_FINGER,
        x,
        y,
        width & 0xFFFF,
        height & 0xFFFF,
        0xFFFF,  # pressure = 1.0
        1,       # action button: primary
        1,       # buttons: primary
    )


def build_key(action: int, keycode: int) -> bytes:
    return struct.pack(">BBIII", MSG_INJECT_KEYCODE, action, keycode, 0, 0)


def read_exact(sock: socket.socket, n: int) -> bytes:
    buf = b""
    while len(buf) < n:
        chunk = sock.recv(n - len(buf))
        if not chunk:
            raise EOFError(f"连接提前结束（已读 {len(buf)}/{n} 字节）")
        buf += chunk
    return buf


def _force_utf8_output() -> None:
    """Windows 控制台默认 GBK，会把设备名/编码打印成乱码甚至直接抛异常。"""
    for stream in (sys.stdout, sys.stderr):
        try:
            stream.reconfigure(encoding="utf-8", errors="replace")
        except Exception:
            pass


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--host", required=True)
    ap.add_argument("--relay", help="中继模式：填中继地址 host:port，程序用口令哈希去找设备")
    ap.add_argument("--token", required=True)
    ap.add_argument("--control-port", type=int, default=CONTROL_PORT)
    ap.add_argument("--video-port", type=int, default=VIDEO_PORT)
    ap.add_argument("--out", default="stream.h264")
    ap.add_argument("--seconds", type=float, default=3.0)
    ap.add_argument("--tap", nargs=2, type=int, metavar=("X", "Y"))
    ap.add_argument("--key", type=int, help="发送一次按键（keycode），如 3=主页 4=返回")
    ap.add_argument("--swipe", nargs=4, type=int, metavar=("X1", "Y1", "X2", "Y2"),
                    help="发送一次滑动")
    args = ap.parse_args()

    if args.relay:
        # 中继模式：两台设备都在 NAT 后面时走这条。
        # 只发口令哈希，中继负责把它和长辈端登记好的连接对接起来。
        import hashlib
        relay_host, _, relay_port_text = args.relay.partition(":")
        relay_port = int(relay_port_text or "27180")
        token_hash = hashlib.sha256(args.token.encode()).hexdigest()

        print(f"→ 通过中继 {relay_host}:{relay_port} 查找设备")
        # 长辈端在会话结束后会重新登记（几十秒一次心跳），这期间中继可能暂时找不到它。
        # 真实控制端会自动重试，这里保持一致，避免探针"一次不成就报错"。
        control = None
        for attempt in range(1, 13):
            try:
                control = connect(relay_host, relay_port, f"FIND {token_hash}", "control")
                break
            except RuntimeError as exc:
                if "ERR" not in str(exc):
                    raise
                print(f"  第 {attempt} 次没找到设备，2 秒后重试…")
                time.sleep(2)
        if control is None:
            raise RuntimeError("中继上始终没有找到这台设备（长辈端可能没在运行）")
        print("  控制通道已配对")
        video = connect(relay_host, relay_port, f"FIND {token_hash}", "video")
        print("  视频通道已配对")
    else:
        print(f"→ 连接控制通道 {args.host}:{args.control_port}")
        control = connect(args.host, args.control_port, "HELLO", args.token)
        print("  控制通道 OK")

        print(f"→ 连接视频通道 {args.host}:{args.video_port}")
        video = connect(args.host, args.video_port, "AUTH", args.token)
        print("  视频通道 OK")

    device_name = read_exact(video, 64).split(b"\x00")[0].decode(errors="replace")
    codec = read_exact(video, 4)
    print(f"  设备名：{device_name}")
    print(f"  编码：{codec.decode(errors='replace')}")
    if codec != b"h264":
        print("  ⚠️ 编码不是 h264，本探针只解析 h264")
        return 2

    deadline = time.time() + args.seconds
    frames = 0
    keyframes = 0
    configs = 0
    total_bytes = 0
    width = height = 0

    with open(args.out, "wb") as out:
        video.settimeout(1.0)
        while time.time() < deadline:
            try:
                header = read_exact(video, 12)
            except (EOFError, socket.timeout):
                continue

            first = header[0]
            if first & 0x80:
                # session packet：宽高是**32 位整数**，分别在偏移 4 与 8
                # （见 Streamer.writeSessionMeta：putInt(flags) + putInt(w) + putInt(h)）
                width = struct.unpack(">I", header[4:8])[0]
                height = struct.unpack(">I", header[8:12])[0]
                print(f"  [会话] 画面尺寸 {width}x{height}")
                continue

            is_config = bool(first & 0x40)
            is_key = bool(first & 0x20)
            size = struct.unpack(">I", header[8:12])[0]
            payload = read_exact(video, size)
            out.write(payload)
            frames += 1
            total_bytes += size
            configs += 1 if is_config else 0
            keyframes += 1 if is_key else 0

    print(f"  收到 {frames} 个包（配置 {configs}，关键帧 {keyframes}），共 {total_bytes} 字节 → {args.out}")

    if args.tap:
        x, y = args.tap
        print(f"→ 注入点击 ({x}, {y})")
        control.sendall(build_touch(ACTION_DOWN, x, y, width or 1080, height or 2400))
        time.sleep(0.05)
        control.sendall(build_touch(ACTION_UP, x, y, width or 1080, height or 2400))
        print("  已发送")

    if args.swipe:
        x1, y1, x2, y2 = args.swipe
        w, h = width or 1080, height or 2400
        print(f"→ 注入滑动 ({x1},{y1}) → ({x2},{y2})")
        control.sendall(build_touch(ACTION_DOWN, x1, y1, w, h))
        steps = 12
        for i in range(1, steps):
            xi = x1 + (x2 - x1) * i // steps
            yi = y1 + (y2 - y1) * i // steps
            control.sendall(build_touch(2, xi, yi, w, h))  # ACTION_MOVE = 2
            time.sleep(0.012)
        control.sendall(build_touch(ACTION_UP, x2, y2, w, h))
        print("  已发送")

    if args.key is not None:
        print(f"→ 注入按键 {args.key}")
        control.sendall(build_key(KEY_ACTION_DOWN, args.key))
        time.sleep(0.05)
        control.sendall(build_key(KEY_ACTION_UP, args.key))
        print("  已发送")

    control.close()
    video.close()

    if frames == 0:
        print("❌ 没有收到任何视频包")
        return 3
    print("✅ 通过")
    return 0


if __name__ == "__main__":
    _force_utf8_output()
    try:
        sys.exit(main())
    except Exception as exc:  # noqa: BLE001 - 探针要把失败原因原样打出来
        print(f"❌ {type(exc).__name__}: {exc}")
        sys.exit(1)
