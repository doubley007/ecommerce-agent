#!/usr/bin/env bash
# 停掉占用 :8000 的 FastAPI 后端。
# 用法：./scripts/stop_server.sh        优雅停止（SIGTERM）
#       ./scripts/stop_server.sh -9     强杀（SIGKILL，前一种没杀掉时再用）
set -euo pipefail

PORT=8000
SIGNAL="${1:-}"   # 默认空 => kill 默认 SIGTERM；传 -9 走 SIGKILL

PIDS=$(lsof -nP -iTCP:${PORT} -sTCP:LISTEN -t 2>/dev/null || true)
if [ -z "$PIDS" ]; then
    echo "ℹ :${PORT} 上没有监听进程，无需停止。"
    exit 0
fi

if [ "$SIGNAL" = "-9" ]; then
    echo "$PIDS" | xargs kill -9
    echo "✓ 已强杀 pid: $(echo "$PIDS" | tr '\n' ' ')"
else
    echo "$PIDS" | xargs kill
    echo "✓ 已发送 SIGTERM 到 pid: $(echo "$PIDS" | tr '\n' ' ')"
    # 等最多 5 秒确认进程退出
    for i in 1 2 3 4 5; do
        sleep 1
        if ! lsof -nP -iTCP:${PORT} -sTCP:LISTEN -t >/dev/null 2>&1; then
            echo "✓ 端口 :${PORT} 已释放"
            exit 0
        fi
    done
    echo "⚠ 5 秒后仍占用，可重试：./scripts/stop_server.sh -9" >&2
    exit 1
fi
