#!/bin/sh
# 启用仓库自带的 git hooks（hooks 不会被 clone，需要每人跑一次）。
set -e
cd "$(git rev-parse --show-toplevel)"
git config core.hooksPath tools/git-hooks
chmod +x tools/git-hooks/*
echo "已启用仓库 hooks：tools/git-hooks"
echo "  pre-commit → tools/check-personal-data.sh（阻止个人信息进入提交）"
