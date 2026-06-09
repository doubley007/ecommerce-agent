"""
离线建索引：读 data/chunks.jsonl，向量化，写入 Chroma。

运行方式（在 server/ 下、激活 venv 后）：
    python scripts/build_index.py
"""

from __future__ import annotations

import json
import sys
import time
from pathlib import Path

# 让 'app' 包可被导入
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app.config import PROJECT_ROOT  # noqa: E402
from app.embedder import embed_documents  # noqa: E402
from app.vector_store import reset_collection  # noqa: E402

CHUNKS_FILE = PROJECT_ROOT / "data" / "chunks.jsonl"
BATCH = 64


def main():
    if not CHUNKS_FILE.exists():
        print(f"找不到 {CHUNKS_FILE}，请先运行 normalize_products.py", file=sys.stderr)
        sys.exit(1)

    chunks: list[dict] = []
    with open(CHUNKS_FILE, encoding="utf-8") as f:
        for line in f:
            chunks.append(json.loads(line))
    print(f"读取 {len(chunks)} 条 chunk")

    print("重建集合 ...")
    col = reset_collection()

    t0 = time.time()
    for i in range(0, len(chunks), BATCH):
        batch = chunks[i : i + BATCH]
        texts = [c["text"] for c in batch]
        vectors = embed_documents(texts)
        col.add(
            ids=[c["chunk_id"] for c in batch],
            documents=texts,
            embeddings=vectors,
            metadatas=[
                {"product_id": c["product_id"], "type": c["type"]}
                for c in batch
            ],
        )
        print(f"  已写入 {min(i + BATCH, len(chunks))}/{len(chunks)}")
    print(f"完成。耗时 {time.time() - t0:.1f}s   集合大小 = {col.count()}")


if __name__ == "__main__":
    main()
