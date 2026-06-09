"""
检索质量手测脚本：跑 5 条典型 query，看 top-5 召回。
"""

from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app.retriever import retrieve  # noqa: E402

QUERIES = [
    "推荐一款适合油皮的洗面奶",
    "200元以下的蓝牙耳机",
    "要轻便的跑鞋，预算500以内",
    "保湿面霜哪款好，不要含酒精",
    "拍照好的旗舰手机",
]


def main():
    for q in QUERIES:
        print("=" * 70)
        print("Q:", q)
        results = retrieve(q, top_k=5)
        for i, r in enumerate(results, 1):
            p = r["product"]
            print(f"  [{i}] {p['title'][:40]}  品牌={p.get('brand')}  ¥{p.get('base_price')}  score={r['score']:.3f}")
            for c in r["matched_chunks"][:1]:
                snippet = c["text"].replace("\n", " ")[:80]
                print(f"       {c['type']}: {snippet}...")
        print()


if __name__ == "__main__":
    main()
