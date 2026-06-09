"""
将 data/raw 下的原始商品 JSON 规整为统一结构，并生成 RAG 用 chunk。

输入: data/raw/ecommerce_agent_dataset/<类目>/data/*.json
输出:
    data/products.jsonl     —— 每行一条商品（结构化字段，给客户端卡片渲染用）
    data/chunks.jsonl       —— 每行一条 chunk（粒度切分后的文本，给向量库用）

设计要点（这是答辩时要能讲清的）：
  1) 商品的不同信息块 召回价值不同，所以分别 chunk：
       - meta_chunk      : 标题/品牌/类目/价格/规格 一条 —— 保证"价格/品牌"类查询能直接命中
       - marketing_chunk : 营销描述按句切（约 150 字一段）—— 卖点、成分、人群匹配
       - faq_chunk       : 每个 Q&A 一条 —— 命中专业问题
       - review_chunk    : 每条评价一条 —— 命中"真实使用感"类提问
  2) 每个 chunk 都带 product_id，检索后聚合回商品，避免同一商品多 chunk 占满 top_k。
  3) chunk text 里前置 [商品名][片段类型] 标签，便于模型在生成时引用。
"""

import json
import os
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]  # ecommerce-agent/
RAW_DIR = ROOT / "data" / "raw" / "ecommerce_agent_dataset"
OUT_DIR = ROOT / "data"

PRODUCTS_OUT = OUT_DIR / "products.jsonl"
CHUNKS_OUT = OUT_DIR / "chunks.jsonl"


def split_sentences(text: str) -> list[str]:
    # 中文分句：按 。！？; 切分，再合并到约 150 字段落
    parts = re.split(r"(?<=[。！？!?；;])\s*", text.strip())
    parts = [p.strip() for p in parts if p.strip()]
    chunks: list[str] = []
    buf = ""
    for p in parts:
        if len(buf) + len(p) > 180 and buf:
            chunks.append(buf)
            buf = p
        else:
            buf += p
    if buf:
        chunks.append(buf)
    return chunks


def build_meta_text(p: dict) -> str:
    sku_lines = []
    for sku in p.get("skus", []):
        props = "/".join(f"{k}:{v}" for k, v in sku.get("properties", {}).items())
        sku_lines.append(f"{props} 价格 ¥{sku.get('price')}")
    sku_str = "；".join(sku_lines) if sku_lines else "（无规格信息）"
    return (
        f"[商品][基础信息] {p['title']}\n"
        f"品牌:{p.get('brand', '未知')} 类目:{p.get('category', '')}/{p.get('sub_category', '')} "
        f"基准价:¥{p.get('base_price')}\n"
        f"规格与价格: {sku_str}"
    )


def normalize_one(raw: dict) -> tuple[dict, list[dict]]:
    pid = raw["product_id"]
    title = raw["title"]
    product = {
        "product_id": pid,
        "title": title,
        "brand": raw.get("brand"),
        "category": raw.get("category"),
        "sub_category": raw.get("sub_category"),
        "base_price": raw.get("base_price"),
        "image_path": raw.get("image_path"),
        "skus": raw.get("skus", []),
        "marketing_description": raw.get("rag_knowledge", {}).get("marketing_description", ""),
    }

    chunks: list[dict] = []

    chunks.append({
        "chunk_id": f"{pid}::meta",
        "product_id": pid,
        "type": "meta",
        "text": build_meta_text(raw),
    })

    desc = raw.get("rag_knowledge", {}).get("marketing_description", "")
    for i, seg in enumerate(split_sentences(desc)):
        chunks.append({
            "chunk_id": f"{pid}::desc::{i}",
            "product_id": pid,
            "type": "marketing",
            "text": f"[商品][卖点描述]《{title}》\n{seg}",
        })

    for i, qa in enumerate(raw.get("rag_knowledge", {}).get("official_faq", [])):
        chunks.append({
            "chunk_id": f"{pid}::faq::{i}",
            "product_id": pid,
            "type": "faq",
            "text": f"[商品][官方问答]《{title}》\n问:{qa['question']}\n答:{qa['answer']}",
        })

    for i, rv in enumerate(raw.get("rag_knowledge", {}).get("user_reviews", [])):
        chunks.append({
            "chunk_id": f"{pid}::review::{i}",
            "product_id": pid,
            "type": "review",
            "text": f"[商品][用户评价]《{title}》\n用户:{rv.get('nickname', '匿名')} 评分:{rv.get('rating')}/5\n{rv.get('content', '')}",
        })

    return product, chunks


def main():
    json_files = sorted(RAW_DIR.glob("*/data/*.json"))
    if not json_files:
        print(f"没在 {RAW_DIR} 下找到任何 JSON，请先解压数据集。", file=sys.stderr)
        sys.exit(1)

    products: list[dict] = []
    all_chunks: list[dict] = []
    for fp in json_files:
        with open(fp, encoding="utf-8") as f:
            raw = json.load(f)
        prod, chunks = normalize_one(raw)
        products.append(prod)
        all_chunks.extend(chunks)

    OUT_DIR.mkdir(parents=True, exist_ok=True)
    with open(PRODUCTS_OUT, "w", encoding="utf-8") as f:
        for p in products:
            f.write(json.dumps(p, ensure_ascii=False) + "\n")
    with open(CHUNKS_OUT, "w", encoding="utf-8") as f:
        for c in all_chunks:
            f.write(json.dumps(c, ensure_ascii=False) + "\n")

    type_counts: dict[str, int] = {}
    for c in all_chunks:
        type_counts[c["type"]] = type_counts.get(c["type"], 0) + 1
    print(f"商品数: {len(products)}  →  {PRODUCTS_OUT.relative_to(ROOT)}")
    print(f"chunk 数: {len(all_chunks)}  →  {CHUNKS_OUT.relative_to(ROOT)}")
    print(f"chunk 类型分布: {type_counts}")


if __name__ == "__main__":
    main()
