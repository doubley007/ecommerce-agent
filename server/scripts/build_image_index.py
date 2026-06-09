"""
离线建图像索引：扫 data/products.jsonl，按 image_path 找主图，CLIP 编码后写入 Chroma 新集合 products_images。

运行（在 server/ 下、激活 venv 后）：
    python scripts/build_image_index.py
"""

from __future__ import annotations

import json
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app.clip_embedder import encode_images_batch  # noqa: E402
from app.config import PROJECT_ROOT  # noqa: E402
from app.vector_store import reset_image_collection  # noqa: E402

PRODUCTS_FILE = PROJECT_ROOT / "data" / "products.jsonl"
IMAGES_ROOT = PROJECT_ROOT / "data" / "raw" / "ecommerce_agent_dataset"
BATCH = 8


def main():
    if not PRODUCTS_FILE.exists():
        print(f"找不到 {PRODUCTS_FILE}", file=sys.stderr)
        sys.exit(1)

    products: list[dict] = []
    with open(PRODUCTS_FILE, encoding="utf-8") as f:
        for line in f:
            products.append(json.loads(line))
    print(f"读取 {len(products)} 个商品")

    items: list[tuple[str, Path, dict]] = []
    missing = 0
    for p in products:
        rel = p.get("image_path")
        if not rel:
            missing += 1
            continue
        abs_path = IMAGES_ROOT / rel
        if not abs_path.exists():
            print(f"  ! 缺图: {rel}")
            missing += 1
            continue
        items.append(
            (
                p["product_id"],
                abs_path,
                {
                    "product_id": p["product_id"],
                    "title": p.get("title", ""),
                    "category": p.get("category", ""),
                    "image_path": rel,
                },
            )
        )
    if missing:
        print(f"警告：{missing} 个商品缺主图，已跳过")

    print(f"实际入库 {len(items)} 张主图，开始 CLIP 编码 ...")
    print("重建集合 products_images ...")
    col = reset_image_collection()

    t0 = time.time()
    paths = [p for _, p, _ in items]
    vectors = encode_images_batch(paths, batch_size=BATCH)
    print(f"  编码完成，耗时 {time.time() - t0:.1f}s，shape={vectors.shape}")

    ids = [pid for pid, _, _ in items]
    metas = [meta for _, _, meta in items]
    docs = [meta["title"] for meta in metas]  # 留个 doc 文本方便人眼调试

    col.add(ids=ids, embeddings=vectors.tolist(), metadatas=metas, documents=docs)
    print(f"完成。集合大小 = {col.count()}")


if __name__ == "__main__":
    main()
