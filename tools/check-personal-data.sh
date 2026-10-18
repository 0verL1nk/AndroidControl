#!/usr/bin/env bash
# 提交前扫描：确保仓库里没有个人/环境信息。
#
# 用法：
#   bash tools/check-personal-data.sh            # 扫描工作区（git 已跟踪 + 未跟踪但会入库的文件）
#   bash tools/check-personal-data.sh --staged   # 只扫描暂存区（pre-commit hook 用）
#
# 安装为 pre-commit hook：
#   ln -sf ../../tools/check-personal-data.sh .git/hooks/pre-commit
#
# 允许列表写在 .personal-data-allowlist（每行一个正则，命中即跳过）。
# 若某行确实需要出现一个地址，在该行末尾加注释 `# nodata-allow` 即可豁免该行。

set -uo pipefail

MODE="worktree"
[ "${1:-}" = "--staged" ] && MODE="staged"

cd "$(dirname "$0")/.." || exit 1

ALLOWLIST_FILE=".personal-data-allowlist"
ALLOWLIST=$( [ -f "$ALLOWLIST_FILE" ] && cat "$ALLOWLIST_FILE" || true )

if [ "$MODE" = "staged" ]; then
  FILES=$(git diff --cached --name-only --diff-filter=ACM)
else
  FILES=$(git ls-files --cached --others --exclude-standard)
fi

# 只看文本文件，跳过机密目录与二进制
FILES=$(printf '%s\n' "$FILES" | grep -vE '^\.secrets/' | grep -vE '\.(png|jpg|jpeg|apk|jar|so|zip|webp|ico)$' || true)
[ -z "$FILES" ] && exit 0

fail=0

report() { # $1=文件 $2=行号 $3=原因 $4=内容
  printf '\033[1;31m[x]\033[0m %s:%s   %s\n    %s\n' "$1" "$2" "$3" "$4"
  fail=1
}

is_allowed() { # $1 = 整行内容
  local line="$1"
  echo "$line" | grep -q '# nodata-allow' && return 0
  while IFS= read -r pat; do
    [ -z "$pat" ] && continue
    echo "$line" | grep -qE "$pat" && return 0
  done <<< "$ALLOWLIST"
  return 1
}

# ---------- 1. 私钥块 ----------
while IFS= read -r f; do
  [ -f "$f" ] || continue
  while IFS=: read -r ln _; do
    report "$f" "$ln" "包含私钥内容" "$(sed -n "${ln}p" "$f" | cut -c1-60)"
  done < <(grep -nE 'BEGIN (RSA |EC |OPENSSH |PGP )?PRIVATE KEY' "$f" 2>/dev/null)
done <<< "$FILES"

# ---------- 2. IPv4 地址 ----------
# 允许：回环、链路本地、文档示例段、隧道网段示例、常见公共 DNS
IP_ALLOW='^(0\.0\.0\.0|127\.0\.0\.1|10\.66\.66\.[0-9]+|192\.0\.2\.[0-9]+|198\.51\.100\.[0-9]+|203\.0\.113\.[0-9]+|223\.5\.5\.5|119\.29\.29\.29|8\.8\.8\.8|1\.1\.1\.1|114\.114\.114\.114|180\.76\.76\.76)$'

while IFS= read -r f; do
  [ -f "$f" ] || continue
  while IFS=: read -r ln rest; do
    line=$(sed -n "${ln}p" "$f")
    is_allowed "$line" && continue
    for ip in $(echo "$rest" | grep -oE '(25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])(\.(25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])){3}' | sort -u); do
      echo "$ip" | grep -qE "$IP_ALLOW" && continue
      report "$f" "$ln" "出现非示例 IP：$ip" "$(echo "$line" | cut -c1-70)"
    done
  done < <(grep -nE '(25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])(\.(25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])){3}' "$f" 2>/dev/null)
done <<< "$FILES"

# ---------- 3. 已知的个人标识 ----------
# 在这里补充需要屏蔽的词（账号名、密钥注释、主机名等），只保留通用模式
PERSONAL_PATTERNS='(ssh-(ed25519|rsa) AAAA|@[A-Za-z0-9._-]+\.(local|lan|top|xyz))'
while IFS= read -r f; do
  [ -f "$f" ] || continue
  while IFS=: read -r ln _; do
    line=$(sed -n "${ln}p" "$f")
    is_allowed "$line" && continue
    report "$f" "$ln" "疑似密钥注释或个人主机名" "$(echo "$line" | cut -c1-70)"
  done < <(grep -nE "$PERSONAL_PATTERNS" "$f" 2>/dev/null)
done <<< "$FILES"

if [ "$fail" = "1" ]; then
  cat <<'EOF'

────────────────────────────────────────
发现可能泄露个人信息的内容。

处理办法（三选一）：
  1) 改成占位符：<NODE_PUBLIC_IP>、<DEVICE_LAN_IP>、<YOUR_DOMAIN>
  2) 把真实值移到 .secrets/（已被 .gitignore 忽略）
  3) 该行确实需要保留时，行末加注释 `# nodata-allow`

不要用 `git commit --no-verify` 绕过本检查。
────────────────────────────────────────
EOF
  exit 1
fi

echo "✅ 未发现个人/环境信息"
