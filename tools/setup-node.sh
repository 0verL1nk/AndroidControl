#!/usr/bin/env bash
# 节点一键搭建：WireGuard hub + 两个对端（控制端 / 长辈端）
#
# 用法：
#   sudo bash tools/setup-node.sh                      # 初始化：装依赖、生成服务端与两个对端
#   sudo bash tools/setup-node.sh --peer <name>        # 追加一个对端（如另一台控制设备）
#   sudo bash tools/setup-node.sh --endpoint <host>    # 指定节点公网地址（默认自动探测）
#
# 产物默认落在 /root/wg-clients/（目录 700，文件 600）。
# 该目录中的内容属于机密：不要提交到仓库、不要发到聊天工具。

set -euo pipefail

WG_DIR=/etc/wireguard
CLIENT_DIR=/root/wg-clients
IFACE=wg0
SUBNET_PREFIX="10.66.66"
LISTEN_PORT=51820
ENDPOINT=""
NEW_PEER=""

log()  { printf '\033[1;32m==>\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m[!]\033[0m %s\n' "$*"; }
die()  { printf '\033[1;31m[x]\033[0m %s\n' "$*" >&2; exit 1; }

while [ $# -gt 0 ]; do
  case "$1" in
    --peer)     NEW_PEER="${2:-}"; shift 2 ;;
    --endpoint) ENDPOINT="${2:-}"; shift 2 ;;
    -h|--help)  sed -n '2,12p' "$0"; exit 0 ;;
    *) die "未知参数：$1" ;;
  esac
done

[ "$(id -u)" = "0" ] || die "请用 root 运行（sudo bash tools/setup-node.sh）"
command -v apt-get >/dev/null || die "本脚本目前只支持 Debian/Ubuntu"

# ---------- 依赖 ----------
log "安装依赖"
export DEBIAN_FRONTEND=noninteractive
apt-get update -qq
apt-get install -y -qq wireguard-tools qrencode curl >/dev/null

# ---------- 转发 ----------
if ! sysctl -n net.ipv4.ip_forward | grep -q 1; then
  log "开启 IPv4 转发"
  echo 'net.ipv4.ip_forward=1' > /etc/sysctl.d/99-wireguard.conf
  sysctl -q -w net.ipv4.ip_forward=1
fi

umask 077
mkdir -p "$WG_DIR" "$CLIENT_DIR"

# ---------- 探测公网地址 ----------
if [ -z "$ENDPOINT" ]; then
  ENDPOINT=$(curl -s --max-time 8 https://api.ipify.org || true)
  [ -n "$ENDPOINT" ] || die "无法自动探测公网 IP，请用 --endpoint <host> 指定"
fi
log "节点公网地址：$ENDPOINT"

gen_key() { # $1 = 名称，$2 = 目录
  local name="$1" dir="$2"
  [ -f "$dir/$name.key" ] && { warn "$name 已存在，跳过生成"; return; }
  wg genkey | tee "$dir/$name.key" | wg pubkey > "$dir/$name.pub"
}

write_client_conf() { # $1 = 名称，$2 = 隧道 IP
  local name="$1" ip4="$2"
  cat > "$CLIENT_DIR/$name.conf" <<CONF
[Interface]
PrivateKey = $(cat "$CLIENT_DIR/$name.key")
Address    = $ip4/24
DNS        = 223.5.5.5
MTU        = 1380

[Peer]
PublicKey  = $(cat "$CLIENT_DIR/server.pub")
Endpoint   = $ENDPOINT:$LISTEN_PORT
AllowedIPs = $SUBNET_PREFIX.0/24
PersistentKeepalive = 25
CONF
  chmod 600 "$CLIENT_DIR/$name.conf"
  qrencode -o "$CLIENT_DIR/$name.png" -r "$CLIENT_DIR/$name.conf"
  log "已生成 $name.conf 与 $name.png（隧道地址 $ip4）"
}

add_peer_to_server() { # $1 = 名称，$2 = 隧道 IP
  local name="$1" ip4="$2"
  grep -q "$(cat "$CLIENT_DIR/$name.pub")" "$WG_DIR/$IFACE.conf" 2>/dev/null && {
    warn "$name 已在服务端配置中，跳过"; return; }
  cat >> "$WG_DIR/$IFACE.conf" <<CONF

[Peer]
# $name
PublicKey  = $(cat "$CLIENT_DIR/$name.pub")
AllowedIPs = $ip4/32
CONF
}

# ---------- 初始化 ----------
if [ ! -f "$WG_DIR/$IFACE.conf" ]; then
  log "生成服务端密钥与配置"
  gen_key server "$CLIENT_DIR"
  cat > "$WG_DIR/$IFACE.conf" <<CONF
[Interface]
Address    = $SUBNET_PREFIX.1/24
ListenPort = $LISTEN_PORT
PrivateKey = $(cat "$CLIENT_DIR/server.key")
CONF
  chmod 600 "$WG_DIR/$IFACE.conf"

  log "生成对端：controller / elder"
  gen_key controller "$CLIENT_DIR"
  gen_key elder      "$CLIENT_DIR"
  add_peer_to_server controller "$SUBNET_PREFIX.2"
  add_peer_to_server elder      "$SUBNET_PREFIX.3"
  write_client_conf  controller "$SUBNET_PREFIX.2"
  write_client_conf  elder      "$SUBNET_PREFIX.3"

  log "启动隧道"
  systemctl enable --now "wg-quick@$IFACE"
  sleep 1
  wg show
else
  log "检测到已有配置，进入追加模式"
fi

# ---------- 追加对端 ----------
if [ -n "$NEW_PEER" ]; then
  used=$(grep -oE "$SUBNET_PREFIX\.[0-9]+/32" "$WG_DIR/$IFACE.conf" | grep -oE '[0-9]+/32' | tr -d '/32' | sort -n | tail -1)
  next=$(( ${used:-1} + 1 ))
  [ "$next" -le 254 ] || die "隧道网段已满"
  log "追加对端 $NEW_PEER → $SUBNET_PREFIX.$next"
  gen_key "$NEW_PEER" "$CLIENT_DIR"
  add_peer_to_server "$NEW_PEER" "$SUBNET_PREFIX.$next"
  write_client_conf  "$NEW_PEER" "$SUBNET_PREFIX.$next"
  systemctl restart "wg-quick@$IFACE"
  wg show
fi

cat <<EOF

────────────────────────────────────────
完成。下一步：

1) 两台手机安装 WireGuard App，扫描二维码导入配置：
     qrencode -t ansiutf8 < $CLIENT_DIR/controller.conf
     qrencode -t ansiutf8 < $CLIENT_DIR/elder.conf
   （或用文件 $CLIENT_DIR/*.png）

2) 在控制端手机上验证隧道：
     ping $SUBNET_PREFIX.3

3) 按 docs/ops/elder-device-setup.md 部署长辈端。

提醒：$CLIENT_DIR 内全部是机密，不要提交、不要外发。
────────────────────────────────────────
EOF
