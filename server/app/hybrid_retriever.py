"""
混合检索 + 重排。

链路：
  query
    ├─► 向量召回（BGE-base-zh-v1.5 + Chroma）  top-N_vec
    ├─► BM25 召回（jieba 分词）               top-N_bm25
    └─► RRF 融合 → top-N_pool
        └─► CrossEncoder 重排（bge-reranker-base）→ top-K（chunk）
             └─► 按 product_id 聚合 → top-k 商品

为什么这么设计：
1) BM25 + 向量互补：BGE 强在语义（"敏感肌"≈"过敏肤质"），BM25 强在精确字面（"500 元"、"李宁"）
2) RRF (Reciprocal Rank Fusion) 不需要分数标定，对两种异构得分鲁棒
3) Reranker 是 cross-encoder（query+doc 拼起来过 BERT），精度远超双塔 cosine，但慢——只对 top-N 跑
4) 离线增量构建 BM25：第一次调用懒加载，进程内缓存
"""

from __future__ import annotations

import json
import time
from collections import defaultdict
from functools import lru_cache
from typing import Literal

import jieba
from rank_bm25 import BM25Okapi
from sentence_transformers import CrossEncoder

from .config import PROJECT_ROOT
from .embedder import embed_query
from .vector_store import get_or_create_collection

CHUNKS_FILE = PROJECT_ROOT / "data" / "chunks.jsonl"
PRODUCTS_FILE = PROJECT_ROOT / "data" / "products.jsonl"

RERANKER_MODEL = "BAAI/bge-reranker-base"

# 召回池大小（chunk 级，融合前各路独立召回这么多）
_VECTOR_POOL = 30
_BM25_POOL = 30
# 融合后送给 reranker 的 chunk 数（cross-encoder 慢，控制在 30 以内）
_FUSION_POOL = 30
# RRF 常数，论文经验值 60
_RRF_K = 60


@lru_cache(maxsize=1)
def _load_chunks() -> list[dict]:
    out: list[dict] = []
    with open(CHUNKS_FILE, encoding="utf-8") as f:
        for line in f:
            out.append(json.loads(line))
    return out


@lru_cache(maxsize=1)
def _load_products() -> dict[str, dict]:
    out: dict[str, dict] = {}
    with open(PRODUCTS_FILE, encoding="utf-8") as f:
        for line in f:
            p = json.loads(line)
            out[p["product_id"]] = p
    return out


def _tokenize(text: str) -> list[str]:
    """jieba 分词 + 去标点空白；过滤长度=0 的 token。"""
    return [t for t in jieba.lcut(text) if t.strip()]


@lru_cache(maxsize=1)
def _get_bm25() -> tuple[BM25Okapi, list[str]]:
    """构建 BM25 索引。返回 (索引, chunk_id 顺序数组)。"""
    chunks = _load_chunks()
    corpus = [_tokenize(c["text"]) for c in chunks]
    chunk_ids = [c["chunk_id"] for c in chunks]
    return BM25Okapi(corpus), chunk_ids


@lru_cache(maxsize=1)
def _get_reranker() -> CrossEncoder:
    """懒加载 cross-encoder。首次调用约 1-2s 加载模型。"""
    return CrossEncoder(RERANKER_MODEL, max_length=512)


@lru_cache(maxsize=1)
def _chunk_index() -> dict[str, dict]:
    """chunk_id -> chunk dict，方便从 BM25 召回的 chunk_id 还原文本和 product_id。"""
    return {c["chunk_id"]: c for c in _load_chunks()}


def _vector_recall(query: str, n: int) -> list[tuple[str, float]]:
    """返回 [(chunk_id, score), ...]，score = 1 - cosine_distance（越大越相似）。"""
    col = get_or_create_collection()
    if col.count() == 0:
        return []
    qvec = embed_query(query)
    res = col.query(
        query_embeddings=[qvec],
        n_results=n,
        include=["metadatas", "distances"],
    )
    ids = res["ids"][0]
    dists = res["distances"][0]
    return [(cid, 1.0 - d) for cid, d in zip(ids, dists, strict=False)]


def _bm25_recall(query: str, n: int) -> list[tuple[str, float]]:
    """BM25 召回 [(chunk_id, raw_score), ...]。"""
    bm25, chunk_ids = _get_bm25()
    q_tokens = _tokenize(query)
    if not q_tokens:
        return []
    scores = bm25.get_scores(q_tokens)
    # argsort 降序取前 n
    top_idx = sorted(range(len(scores)), key=lambda i: scores[i], reverse=True)[:n]
    return [(chunk_ids[i], float(scores[i])) for i in top_idx if scores[i] > 0]


def _rrf_fuse(
    rankings: list[list[tuple[str, float]]],
    pool: int,
) -> list[str]:
    """
    Reciprocal Rank Fusion：对每个 ranking 给每个 doc 一个 1/(K+rank) 的分。
    返回融合后 top-pool 的 chunk_id 列表。
    """
    rrf_score: dict[str, float] = defaultdict(float)
    for ranking in rankings:
        for rank, (cid, _score) in enumerate(ranking):
            rrf_score[cid] += 1.0 / (_RRF_K + rank + 1)
    fused = sorted(rrf_score.items(), key=lambda kv: kv[1], reverse=True)
    return [cid for cid, _ in fused[:pool]]


def _rerank(query: str, chunk_ids: list[str], top_n: int) -> list[tuple[str, float]]:
    """CrossEncoder 重排，返回 [(chunk_id, ce_score), ...] 按分降序，长度 ≤ top_n。"""
    if not chunk_ids:
        return []
    idx = _chunk_index()
    pairs = [(query, idx[cid]["text"]) for cid in chunk_ids if cid in idx]
    valid_ids = [cid for cid in chunk_ids if cid in idx]
    if not pairs:
        return []
    ce = _get_reranker()
    scores = ce.predict(pairs, show_progress_bar=False).tolist()
    ranked = sorted(zip(valid_ids, scores, strict=False), key=lambda kv: kv[1], reverse=True)
    return ranked[:top_n]


def _aggregate_by_product(
    ranked_chunks: list[tuple[str, float]],
    top_k: int,
) -> list[dict]:
    """按 product_id 聚合，每个商品取得分最好的 chunk 作代表，最多附 3 个 chunk 作上下文。"""
    idx = _chunk_index()
    products = _load_products()
    by_pid: dict[str, dict] = {}
    order: list[str] = []
    for cid, score in ranked_chunks:
        chunk = idx.get(cid)
        if not chunk:
            continue
        pid = chunk["product_id"]
        if pid not in products:
            continue
        if pid not in by_pid:
            by_pid[pid] = {
                "product_id": pid,
                "score": float(score),
                "product": products[pid],
                "matched_chunks": [],
            }
            order.append(pid)
        if len(by_pid[pid]["matched_chunks"]) < 3:
            by_pid[pid]["matched_chunks"].append(
                {"type": chunk["type"], "text": chunk["text"], "score": round(float(score), 4)}
            )
    return [by_pid[pid] for pid in order[:top_k]]


RetrievalMode = Literal["vector_only", "hybrid", "hybrid_rerank"]


def retrieve_hybrid(
    query: str,
    top_k: int = 5,
    mode: RetrievalMode = "hybrid_rerank",
) -> list[dict]:
    """
    多策略检索入口。
      - vector_only   : 纯向量召回（与原 retriever.retrieve 行为一致，作为 ablation 基线）
      - hybrid        : 向量 + BM25，RRF 融合
      - hybrid_rerank : hybrid 融合后再过 CrossEncoder 重排（默认，最强）

    返回结构与 retriever.retrieve 完全一致：
      [{"product_id", "score", "product", "matched_chunks": [{"type","text","score"}]}]
    """
    if mode == "vector_only":
        v = _vector_recall(query, _VECTOR_POOL)
        # 直接按 vector 分聚合到商品
        ranked = v  # 已按 score 降序
        return _aggregate_by_product(ranked, top_k)

    v = _vector_recall(query, _VECTOR_POOL)
    b = _bm25_recall(query, _BM25_POOL)
    fused_ids = _rrf_fuse([v, b], _FUSION_POOL)

    if mode == "hybrid":
        # 用 RRF 分数（重新算）作 chunk 级 score
        rrf: dict[str, float] = defaultdict(float)
        for ranking in [v, b]:
            for rank, (cid, _s) in enumerate(ranking):
                rrf[cid] += 1.0 / (_RRF_K + rank + 1)
        ranked = [(cid, rrf[cid]) for cid in fused_ids]
        return _aggregate_by_product(ranked, top_k)

    # hybrid_rerank
    ranked = _rerank(query, fused_ids, top_n=_FUSION_POOL)
    return _aggregate_by_product(ranked, top_k)


def retrieve_hybrid_with_trace(
    query: str,
    top_k: int = 5,
    mode: RetrievalMode = "hybrid_rerank",
) -> tuple[list[dict], list[dict]]:
    """
    与 retrieve_hybrid 行为一致，但同时返回各阶段耗时 trace，供"思考过程"面板可视化。
    trace 元素结构：{"step": str, "detail": str, "duration_ms": int}
      - step: vector_recall / bm25_recall / rrf_fuse / rerank / aggregate
      - detail: 一行人话描述这一步做了什么、产出多少
      - duration_ms: 该阶段毫秒耗时
    """
    trace: list[dict] = []

    if mode == "vector_only":
        t0 = time.perf_counter()
        v = _vector_recall(query, _VECTOR_POOL)
        trace.append({
            "step": "vector_recall",
            "detail": f"BGE 向量召回 {len(v)} 条 chunk",
            "duration_ms": int((time.perf_counter() - t0) * 1000),
        })
        t1 = time.perf_counter()
        result = _aggregate_by_product(v, top_k)
        trace.append({
            "step": "aggregate",
            "detail": f"按 product_id 聚合到 {len(result)} 款商品",
            "duration_ms": int((time.perf_counter() - t1) * 1000),
        })
        return result, trace

    t0 = time.perf_counter()
    v = _vector_recall(query, _VECTOR_POOL)
    trace.append({
        "step": "vector_recall",
        "detail": f"BGE 向量召回 {len(v)} 条 chunk",
        "duration_ms": int((time.perf_counter() - t0) * 1000),
    })

    t1 = time.perf_counter()
    b = _bm25_recall(query, _BM25_POOL)
    trace.append({
        "step": "bm25_recall",
        "detail": f"BM25 字面召回 {len(b)} 条 chunk",
        "duration_ms": int((time.perf_counter() - t1) * 1000),
    })

    t2 = time.perf_counter()
    fused_ids = _rrf_fuse([v, b], _FUSION_POOL)
    trace.append({
        "step": "rrf_fuse",
        "detail": f"RRF 融合 → {len(fused_ids)} 条候选",
        "duration_ms": int((time.perf_counter() - t2) * 1000),
    })

    if mode == "hybrid":
        rrf: dict[str, float] = defaultdict(float)
        for ranking in [v, b]:
            for rank, (cid, _s) in enumerate(ranking):
                rrf[cid] += 1.0 / (_RRF_K + rank + 1)
        ranked = [(cid, rrf[cid]) for cid in fused_ids]
        t3 = time.perf_counter()
        result = _aggregate_by_product(ranked, top_k)
        trace.append({
            "step": "aggregate",
            "detail": f"按 product_id 聚合到 {len(result)} 款商品",
            "duration_ms": int((time.perf_counter() - t3) * 1000),
        })
        return result, trace

    # hybrid_rerank
    t3 = time.perf_counter()
    ranked = _rerank(query, fused_ids, top_n=_FUSION_POOL)
    trace.append({
        "step": "rerank",
        "detail": f"CrossEncoder 重排 top-{len(ranked)}",
        "duration_ms": int((time.perf_counter() - t3) * 1000),
    })

    t4 = time.perf_counter()
    result = _aggregate_by_product(ranked, top_k)
    trace.append({
        "step": "aggregate",
        "detail": f"按 product_id 聚合到 {len(result)} 款商品",
        "duration_ms": int((time.perf_counter() - t4) * 1000),
    })
    return result, trace


def warmup() -> None:
    """启动时预热：加载 BM25 索引、reranker 模型、Chroma collection。
    Chroma 必须在并发首请求之前完成单线程初始化 — 否则多 worker 同时
    PersistentClient(path=...) 会抢同一份 Rust bindings 出现
    "RustBindingsAPI has no attribute 'bindings'" / "tenant not found"。
    /chat/stream/scene 走 asyncio.gather 并行召回，正是这个高风险场景。
    """
    _get_bm25()
    _get_reranker()
    # 触发 lru_cache 内的 chroma client 初始化，避免并发首调用竞争
    get_or_create_collection()
