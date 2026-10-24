#!/usr/bin/env bash
# 生成 release 签名材料（只在维护者机器上运行一次）。
#
#   bash tools/gen-release-keystore.sh
#
# 产物（都在 .secrets/，已被 .gitignore 忽略）：
#   .secrets/release.jks          签名密钥，**必须备份**
#   .secrets/keystore.properties  构建读取的签名参数
#
# 口令随机生成，不打印到终端。请把整个 .secrets/ 备份到安全的地方：
# 密钥丢失后，已安装的版本将无法再通过覆盖安装升级。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SECRETS="$ROOT/.secrets"
KEYSTORE="$SECRETS/release.jks"
PROPS="$SECRETS/keystore.properties"
ALIAS="androidcontrol"

FORCE=0
[ "${1:-}" = "--force" ] && FORCE=1

if [ -f "$KEYSTORE" ] && [ "$FORCE" -ne 1 ]; then
    echo "已存在 $KEYSTORE；如确实要重新生成，请加 --force（旧版本将无法覆盖升级）" >&2
    exit 1
fi

command -v keytool >/dev/null 2>&1 || {
    echo "找不到 keytool，请安装 JDK 17+ 或把它的 bin 加入 PATH" >&2
    exit 1
}

mkdir -p "$SECRETS"
chmod 700 "$SECRETS"
rm -f "$KEYSTORE" "$PROPS"

STORE_PASS="$(python -c 'import secrets;print(secrets.token_urlsafe(24))')"
KEY_PASS="$STORE_PASS"

keytool -genkeypair \
    -keystore "$KEYSTORE" \
    -alias "$ALIAS" \
    -keyalg RSA \
    -keysize 4096 \
    -validity 10000 \
    -storepass "$STORE_PASS" \
    -keypass "$KEY_PASS" \
    -dname "CN=AndroidControl, OU=Self-hosted, O=AndroidControl, C=CN" \
    >/dev/null

cat > "$PROPS" <<EOF
# release 签名参数（不要提交；storeFile 相对于本文件所在目录）
storeFile=release.jks
storePassword=$STORE_PASS
keyAlias=$ALIAS
keyPassword=$KEY_PASS
EOF
chmod 600 "$KEYSTORE" "$PROPS"

echo "已生成："
echo "  $KEYSTORE"
echo "  $PROPS"
echo
echo "⚠️  请立即备份整个 .secrets/ 目录（密钥丢失后无法覆盖升级已安装的版本）。"
echo "CI 使用：把 release.jks 做 base64 存入 ANDROID_KEYSTORE_BASE64，"
echo "         口令与别名存入 ANDROID_KEYSTORE_PASSWORD / ANDROID_KEY_ALIAS / ANDROID_KEY_PASSWORD。"
