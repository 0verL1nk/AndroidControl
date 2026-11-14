#!/usr/bin/env bash
# 把 relay/ 部署到节点：上传源码 → 节点上编译 → 安装 → 清理编译环境。
#
# 用法：
#   NODE=root@<节点> bash tools/deploy-relay.sh
#   NODE=root@<节点> PORT=27180 bash tools/deploy-relay.sh
#   NODE=root@<节点> TLS_PORT=27443 bash tools/deploy-relay.sh   # 公网只开 TLS
#
# 设了 TLS_PORT 时：自签证书放在 /etc/ac-relay/（首次生成，之后沿用），
# 明文监听收紧到 127.0.0.1（只给 Cloudflare 隧道/本机用），TLS 监听 0.0.0.0，
# 证书通过 systemd 的 LoadCredential 交给服务，私钥不必对任何用户可读。
#
# 为什么要在节点上编译：从 Windows 交叉编译 Linux 二进制需要额外的链接器/工具链，
# 而节点本身装一次 Rust 就能得到与运行环境完全匹配的产物。代价是 Rust 编译缓存很占磁盘，
# 所以脚本结束时会把工具链和 target/ 都清掉，只留下约 500 KB 的二进制。
set -euo pipefail

NODE="${NODE:-}"
PORT="${PORT:-27180}"
TLS_PORT="${TLS_PORT:-}"
LISTEN_ADDR="0.0.0.0:${PORT}"
if [ -n "$TLS_PORT" ]; then
    LISTEN_ADDR="127.0.0.1:${PORT}"
fi

if [ -z "$NODE" ]; then
    echo "用法：NODE=root@<节点> [PORT=27180] bash tools/deploy-relay.sh" >&2
    exit 1
fi

cd "$(dirname "$0")/.."

echo "→ 上传 relay/ 源码"
tar -czf - relay | ssh "$NODE" "rm -rf /opt/ac-build && mkdir -p /opt/ac-build && tar -xzf - -C /opt/ac-build"

echo "→ 节点上编译并安装（明文 $LISTEN_ADDR${TLS_PORT:+，TLS 0.0.0.0:$TLS_PORT}）"
ssh "$NODE" "bash -s -- '$LISTEN_ADDR' '$TLS_PORT'" <<'REMOTE'
set -euo pipefail
LISTEN_ADDR="$1"
TLS_PORT="${2:-}"
export DEBIAN_FRONTEND=noninteractive

installed_gcc=0
if ! command -v cc >/dev/null 2>&1; then
    apt-get update -qq
    apt-get install -y -qq --no-install-recommends gcc libc6-dev >/dev/null
    installed_gcc=1
fi

installed_rust=0
if [ ! -x /root/.cargo/bin/cargo ]; then
    curl -sSf --max-time 120 https://sh.rustup.rs -o /tmp/rustup.sh
    sh /tmp/rustup.sh -y --profile minimal --default-toolchain stable >/dev/null 2>&1
    installed_rust=1
fi

cd /opt/ac-build/relay
~/.cargo/bin/cargo build --release
install -m755 target/release/ac-relay /usr/local/bin/ac-relay

# 管理令牌：首次部署生成一次，之后沿用；只给 root 可读，经 EnvironmentFile 交给服务
mkdir -p /etc/ac-relay
chmod 700 /etc/ac-relay
command -v openssl >/dev/null 2>&1 || apt-get install -y -qq --no-install-recommends openssl >/dev/null
ADMIN_ENV=/etc/ac-relay/admin.env
if [ ! -s "$ADMIN_ENV" ]; then
    umask 077
    echo "AC_ADMIN_TOKEN=$(openssl rand -hex 24)" > "$ADMIN_ENV"
    chmod 600 "$ADMIN_ENV"
    echo "已生成管理令牌（不打印到日志）。查看：ssh <节点> 'sudo grep AC_ADMIN_TOKEN /etc/ac-relay/admin.env'"
else
    echo "管理令牌已存在：$ADMIN_ENV（查看：grep AC_ADMIN_TOKEN $ADMIN_ENV）"
fi

# TLS：自签证书只生成一次；私钥经 systemd LoadCredential 传给服务，不设全局可读
TLS_ARGS=""
TLS_CREDENTIALS=""
if [ -n "$TLS_PORT" ]; then
    command -v openssl >/dev/null 2>&1 || apt-get install -y -qq --no-install-recommends openssl >/dev/null
    mkdir -p /etc/ac-relay
    chmod 700 /etc/ac-relay
    if [ ! -s /etc/ac-relay/cert.pem ] || [ ! -s /etc/ac-relay/key.pem ]; then
        openssl req -x509 -newkey rsa:3072 -nodes -sha256 -days 3650 \
            -keyout /etc/ac-relay/key.pem -out /etc/ac-relay/cert.pem \
            -subj "/CN=ac-relay" \
            -addext "subjectAltName=DNS:relay.local,IP:127.0.0.1" >/dev/null 2>&1
        chmod 600 /etc/ac-relay/key.pem /etc/ac-relay/cert.pem
    fi
    TLS_ARGS="--tls-listen 0.0.0.0:${TLS_PORT} --tls-cert %d/cert --tls-key %d/key"
    TLS_CREDENTIALS="LoadCredential=cert:/etc/ac-relay/cert.pem
LoadCredential=key:/etc/ac-relay/key.pem"
fi

cat > /etc/systemd/system/ac-relay.service <<UNIT
[Unit]
Description=AndroidControl relay
After=network.target

[Service]
ExecStart=/usr/local/bin/ac-relay --listen ${LISTEN_ADDR} --state /var/lib/ac-relay/devices.tsv ${TLS_ARGS}
EnvironmentFile=/etc/ac-relay/admin.env
${TLS_CREDENTIALS}
StateDirectory=ac-relay
Restart=always
RestartSec=5
DynamicUser=yes
NoNewPrivileges=true
ProtectSystem=strict
ProtectHome=true
PrivateTmp=true

[Install]
WantedBy=multi-user.target
UNIT

systemctl daemon-reload
systemctl enable ac-relay
systemctl restart ac-relay

# 确认真的起来了（只看 is-active 会漏掉"启动即崩但 systemd 还在重试"的情况）
PORT="${LISTEN_ADDR##*:}"
ready=0
for _ in $(seq 1 20); do
    if systemctl is-active --quiet ac-relay && ss -ltn | grep -q ":${PORT} "; then
        ready=1
        break
    fi
    sleep 0.5
done
if [ "$ready" -ne 1 ]; then
    echo "服务未能就绪，最近日志：" >&2
    journalctl -u ac-relay -n 30 --no-pager -o cat >&2
    exit 1
fi
if [ -n "$TLS_PORT" ] && ! ss -ltn | grep -q ":${TLS_PORT} "; then
    echo "TLS 端口 ${TLS_PORT} 没有在监听" >&2
    journalctl -u ac-relay -n 30 --no-pager -o cat >&2
    exit 1
fi
echo "服务状态：$(systemctl is-active ac-relay)"
journalctl -u ac-relay -n 3 --no-pager -o cat

if [ -n "$TLS_PORT" ]; then
    echo "TLS 端口：$TLS_PORT"
    echo -n "证书 SHA-256 指纹："
    openssl x509 -in /etc/ac-relay/cert.pem -noout -fingerprint -sha256 | sed 's/^.*=//'
fi

echo "→ 清理编译环境"
rm -rf /opt/ac-build
if [ "$installed_rust" = "1" ]; then
    rm -rf /root/.cargo /root/.rustup /tmp/rustup.sh
fi
if [ "$installed_gcc" = "1" ]; then
    apt-get remove --purge -y -qq gcc libc6-dev >/dev/null 2>&1 || true
    apt-get autoremove --purge -y -qq >/dev/null 2>&1 || true
    apt-get clean
    rm -rf /var/lib/apt/lists/*
fi
df -h / | tail -1
REMOTE

echo "✅ 部署完成：$NODE:$PORT"
