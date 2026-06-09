"""
检索层：给定 query，从向量库召回 chunk，并按商品聚合。

D16 起默认走混合检索 + 重排（vector + BM25 + cross-encoder），
原纯向量路径作为 ablation 的 vector_only baseline 保留在 hybrid_retriever 里。

设计要点：
  1) 召回阶段先以较大 pool（30）取，避免同一商品的多 chunk 把 top_k 占满
  2) 按 product_id 聚合：每个商品取得分最好的 chunk 作代表，最多附 3 个相关 chunk 作上下文
  3) 返回结构里同时给出 product 元信息（标题/价格/品牌/图片），方便商品卡片渲染
"""

from __future__ import annotations

import json
from functools import lru_cache

from .config import PROJECT_ROOT
from .hybrid_retriever import RetrievalMode, retrieve_hybrid, retrieve_hybrid_with_trace

PRODUCTS_FILE = PROJECT_ROOT / "data" / "products.jsonl"


@lru_cache(maxsize=1)
def load_products() -> dict[str, dict]:
    """product_id -> 商品 dict"""
    out: dict[str, dict] = {}
    with open(PRODUCTS_FILE, encoding="utf-8") as f:
        for line in f:
            p = json.loads(line)
            out[p["product_id"]] = p
    return out


def retrieve(query: str, top_k: int = 5, mode: RetrievalMode = "hybrid_rerank") -> list[dict]:
    """
    返回 top_k 个商品（已聚合），每个元素：
        {
          "product_id": "...",
          "score": 0.83,
          "product": {商品基础字段},
          "matched_chunks": [
              {"type": "marketing", "text": "...", "score": 0.83},
              ...
          ]
        }

    mode:
      - "vector_only"   纯向量（旧版行为，作 ablation baseline）
      - "hybrid"        向量 + BM25，RRF 融合
      - "hybrid_rerank" hybrid + cross-encoder 重排（默认）
    """
    return retrieve_hybrid(query, top_k=top_k, mode=mode)


def retrieve_with_trace(
    query: str, top_k: int = 5, mode: RetrievalMode = "hybrid_rerank"
) -> tuple[list[dict], list[dict]]:
    """带阶段耗时 trace 的检索入口，供"思考过程"面板可视化用。
    返回 (retrieved, trace)，trace 元素见 hybrid_retriever.retrieve_hybrid_with_trace 注释。
    """
    return retrieve_hybrid_with_trace(query, top_k=top_k, mode=mode)


def format_context_for_prompt(retrieved: list[dict]) -> str:
    """把检索结果格式化为供 system prompt 引用的上下文字符串。"""
    if not retrieved:
        return "（暂无相关商品）"
    blocks = []
    for r in retrieved:
        p = r["product"]
        head = (
            f"### 商品 {p['product_id']}\n"
            f"标题：{p['title']}\n"
            f"品牌：{p.get('brand', '')}  类目：{p.get('category', '')}/{p.get('sub_category', '')}\n"
            f"基准价：¥{p.get('base_price')}\n"
        )
        skus = p.get("skus", [])
        if skus:
            sku_lines = []
            for sku in skus:
                props = "/".join(f"{k}:{v}" for k, v in sku.get("properties", {}).items())
                sku_lines.append(f"  - {props}  ¥{sku.get('price')}")
            head += "规格：\n" + "\n".join(sku_lines) + "\n"
        snippets = "\n".join(
            f"  · [{c['type']}] {c['text']}" for c in r["matched_chunks"]
        )
        blocks.append(head + "相关片段：\n" + snippets)
    return "\n\n".join(blocks)
