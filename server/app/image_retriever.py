"""
以图搜图（image-to-image）检索。

链路：
  用户图 ──► Chinese-CLIP encode_image ──► 512 维向量
                                          └──► Chroma `products_images` 集合 cosine 召回
                                                └──► [(product_id, sim), ...] (product 粒度)

为什么独立成模块：
  1) 文本路径（hybrid_retriever）输出 chunk 级，CLIP 路径天然 product 级，两者聚合时机不同
  2) 多模态分支可以选 image_only / vlm_only / 融合 三档跑 ablation，独立模块切换更清晰
"""

from __future__ import annotations

import logging

from .clip_embedder import encode_image
from .vector_store import get_or_create_image_collection

log = logging.getLogger(__name__)


def image_search(image_bytes: bytes, top_k: int = 10) -> list[tuple[str, float]]:
    """
    输入图片字节流，返回 [(product_id, similarity), ...] 按相似度降序，长度 ≤ top_k。
    similarity = 1 - cosine_distance ∈ [0, 2]，1 最相似（同向归一化向量内积）。
    """
    col = get_or_create_image_collection()
    if col.count() == 0:
        log.warning("[image_search] products_images 集合为空，先跑 build_image_index.py")
        return []

    qvec = encode_image(image_bytes)
    res = col.query(
        query_embeddings=[qvec.tolist()],
        n_results=top_k,
        include=["metadatas", "distances"],
    )
    metas = res["metadatas"][0]
    dists = res["distances"][0]
    out: list[tuple[str, float]] = []
    for meta, d in zip(metas, dists, strict=False):
        pid = meta.get("product_id")
        if not pid:
            continue
        out.append((pid, 1.0 - float(d)))
    return out


def fuse_with_text_retrieval(
    text_results: list[dict],
    image_results: list[tuple[str, float]],
    top_k: int,
    rrf_k: int = 60,
) -> list[dict]:
    """
    product 级 RRF 融合。
      text_results: retriever.retrieve 返回的 list[dict]，已按 score 降序
      image_results: image_search 返回的 [(pid, sim), ...]，已按 sim 降序

    RRF 在 product 级别的两路 ranking 上跑，比 chunk 级稳——
    免去文本侧"同商品多个 chunk 重复打分"问题。

    返回结构与 retriever.retrieve 一致：
      [{"product_id", "score" (RRF), "product", "matched_chunks"}]
    其中：
      - 文本侧命中商品保留 matched_chunks（人眼调试和上下文都用得上）
      - 仅 CLIP 命中的商品 matched_chunks 给一条 type=image_match 的占位（说明这是图像匹配进来的）
    """
    from .retriever import load_products

    products = load_products()
    rrf_score: dict[str, float] = {}
    text_index: dict[str, dict] = {}

    for rank, r in enumerate(text_results):
        pid = r["product_id"]
        rrf_score[pid] = rrf_score.get(pid, 0.0) + 1.0 / (rrf_k + rank + 1)
        text_index[pid] = r

    image_index: dict[str, float] = {}
    for rank, (pid, sim) in enumerate(image_results):
        rrf_score[pid] = rrf_score.get(pid, 0.0) + 1.0 / (rrf_k + rank + 1)
        image_index[pid] = sim

    fused = sorted(rrf_score.items(), key=lambda kv: kv[1], reverse=True)[:top_k]

    out: list[dict] = []
    for pid, score in fused:
        in_text = pid in text_index
        in_image = pid in image_index
        # 来源标签：客户端用来给商品卡贴角标
        if in_text and in_image:
            source = "both"
        elif in_image:
            source = "image_only"
        else:
            source = "text_only"

        if in_text:
            entry = dict(text_index[pid])
            entry["score"] = float(score)
            entry["match_source"] = source
            if in_image:
                entry.setdefault("matched_chunks", []).insert(
                    0,
                    {
                        "type": "image_match",
                        "text": f"主图 CLIP 相似度 {image_index[pid]:.3f}",
                        "score": round(float(image_index[pid]), 4),
                    },
                )
            out.append(entry)
        elif pid in products:
            out.append(
                {
                    "product_id": pid,
                    "score": float(score),
                    "match_source": source,
                    "product": products[pid],
                    "matched_chunks": [
                        {
                            "type": "image_match",
                            "text": f"主图 CLIP 相似度 {image_index[pid]:.3f}",
                            "score": round(float(image_index[pid]), 4),
                        }
                    ],
                }
            )
    return out
