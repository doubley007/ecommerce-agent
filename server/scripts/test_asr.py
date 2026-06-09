"""
ASR 烟囱测试：拿一个 wav 文件直接走 asr_client.recognize，5 秒判断秘钥与网关连通性。

用法（在 server/ 下，激活 venv）：
    python scripts/test_asr.py path/to/sample.wav

无样本时随便录一段 16kHz/mono/16bit WAV，例如 macOS:
    sox -d -r 16000 -c 1 -b 16 /tmp/hi.wav trim 0 3
"""
from __future__ import annotations

import asyncio
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from app.asr_client import recognize  # noqa: E402
from app.config import get_settings  # noqa: E402


async def main() -> None:
    if len(sys.argv) < 2:
        print("用法: python scripts/test_asr.py <audio.wav>")
        sys.exit(1)
    audio_path = Path(sys.argv[1])
    if not audio_path.exists():
        print(f"文件不存在: {audio_path}")
        sys.exit(1)

    s = get_settings()
    print(f"VOLC_ASR_API_KEY set: {bool(s.volc_asr_api_key)}")

    audio = audio_path.read_bytes()
    fmt = audio_path.suffix.lstrip(".").lower() or "wav"
    text = await recognize(audio, audio_format=fmt)
    print("识别结果：", text)


if __name__ == "__main__":
    asyncio.run(main())
