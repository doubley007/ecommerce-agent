"""一次性脚本：试 .env 里的 ARK_VISION_MODEL 是否真的可用。"""
import asyncio
import base64
import sys
from pathlib import Path

# 让脚本能从 server 根目录跑
sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from app.llm_client import vision_describe
from app.config import get_settings


async def main(image_path: str):
    s = get_settings()
    print(f"[config] ARK_VISION_MODEL = {s.ark_vision_model!r}")
    print(f"[config] ARK_BASE_URL    = {s.ark_base_url!r}")
    img_bytes = Path(image_path).read_bytes()
    b64 = base64.b64encode(img_bytes).decode()
    print(f"[input] image = {image_path} ({len(img_bytes)/1024:.1f} KB)")

    try:
        kw = await vision_describe(b64, hint_text="")
        print(f"[OK] keywords = {kw!r}")
    except Exception as e:
        print(f"[FAIL] {type(e).__name__}: {e}")
        raise


if __name__ == "__main__":
    img = sys.argv[1] if len(sys.argv) > 1 else "../data/raw/ecommerce_agent_dataset/1_美妆护肤/images/p_beauty_019_live.jpg"
    asyncio.run(main(img))
