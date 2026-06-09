#!/usr/bin/env bash
# 启动 FastAPI 后端（后台 nohup）。
# 用法：./scripts/start_server.sh
# 日志在 /tmp/shopguide-server.log；停服用 ./scripts/stop_server.sh
set -euo pipefail

PORT=8000
SERVER_DIR="$(cd "$(dirname "$0")/.." && pwd)"
LOG_FILE="/tmp/shopguide-server.log"

cd "$SERVER_DIR"

# 已经在跑就不重启，避免端口冲突
if lsof -nP -iTCP:${PORT} -sTCP:LISTEN -t >/dev/null 2>&1; then
    PID=$(lsof -nP -iTCP:${PORT} -sTCP:LISTEN -t | head -1)
    echo "⚠ 已有服务在 :${PORT} 运行（pid=${PID}）。"
    echo "  要重启：./scripts/stop_server.sh && ./scripts/start_server.sh"
    exit 1
fi

if [ ! -x ".venv/bin/uvicorn" ]; then
    echo "✗ 找不到 .venv/bin/uvicorn。先在 server/ 下建好虚拟环境并安装依赖：" >&2
    echo "    python -m venv .venv && source .venv/bin/activate && pip install -r requirements.txt" >&2
    exit 1
fi

nohup .venv/bin/uvicorn app.main:app --host 0.0.0.0 --port ${PORT} > "$LOG_FILE" 2>&1 &
PID=$!
echo "✓ 已启动 pid=${PID}，监听 0.0.0.0:${PORT}"
echo "  日志：tail -f $LOG_FILE"

# 等服务真正就绪（首次会预热 BM25 + reranker，约 10-20 秒）
echo -n "  等待 /health 就绪 "
for i in $(seq 1 30); do
    if curl -s -m 2 "http://127.0.0.1:${PORT}/health" 2>/dev/null | grep -q '"ok"'; then
        echo
        echo "✓ 健康检查通过"
        IP=$(ipconfig getifaddr en0 2>/dev/null || echo "未取到")
        echo "  局域网 BASE_URL：http://${IP}:${PORT}  （在 APP 齿轮里填这个）"
        exit 0
    fi
    echo -n "."
    sleep 1
done
echo
echo "✗ 30 秒内 /health 未通过。看日志排错：tail -f $LOG_FILE" >&2
exit 1
