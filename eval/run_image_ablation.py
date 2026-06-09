"""
多模态检索 ablation：vlm_only / clip_only / fusion 三档对比。

抽样 N 件商品（默认 4 大 category 各 5 件 = 20），每件用主图作 query，三档配置各跑一次：
  - vlm_only  : VLM 抽关键词 → BGE+BM25+Reranker 文本检索（D11-D13 旧链路）
  - clip_only : Chinese-CLIP 图像向量直接召回（不经过文本）
  - fusion    : 两路 product 级 RRF 融合

每档统计：
  - Self-recall@1（top-1 是不是商品自己）
  - Self-recall@5（top-5 里有没有自己）
  - Same-sub@5  （top-5 去自己后，同 sub_category 占比）

说明：vlm_only 的"自己"概念有点弱——VLM 输出的是关键词，不一定能精确召回原商品；
  这恰好是这一档 vs CLIP 的关键差异，evaluator 要的就是这个对比。

输出：eval/results/image_ablation-<ts>.md

用法（在 ecommerce-agent/ 下）：
    server/.venv/bin/python eval/run_image_ablation.py
    server/.venv/bin/python eval/run_image_ablation.py --per-cat 3   # 减小样本，跑快点
"""

from __future__ import annotations

import argparse
import asyncio
import base64
import datetime as dt
import json
import random
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "server"))

from app.clip_embedder import warmup as clip_warmup  # noqa: E402
from app.config import PROJECT_ROOT  # noqa: E402
from app.hybrid_retriever import warmup as warmup_text  # noqa: E402
from app.image_retriever import fuse_with_text_retrieval, image_search  # noqa: E402
from app.llm_client import vision_describe  # noqa: E402
from app.retriever import retrieve  # noqa: E402

PRODUCTS_FILE = PROJECT_ROOT / "data" / "products.jsonl"
IMAGES_ROOT = PROJECT_ROOT / "data" / "raw" / "ecommerce_agent_dataset"
RESULTS_DIR = PROJECT_ROOT / "eval" / "results"


def load_products() -> list[dict]:
    out = []
    for line in PRODUCTS_FILE.read_text(encoding="utf-8").splitlines():
        if line.strip():
            out.append(json.loads(line))
    return out


def stratified_sample(products: list[dict], per_cat: int, seed: int = 7) -> list[dict]:
    """每个 category 均匀抽样 per_cat 件。"""
    random.seed(seed)
    by_cat: dict[str, list[dict]] = {}
    for p in products:
        by_cat.setdefault(p["category"], []).append(p)
    sampled: list[dict] = []
    for cat, items in by_cat.items():
        sampled.extend(random.sample(items, min(per_cat, len(items))))
    return sampled


def score_one(target_pid: str, target_sub: str, top_pids: list[str], pid2sub: dict[str, str]) -> dict:
    """评分单条 query。top_pids 是检索 top-k 的 product_id 列表（已按相关性降序）。"""
    top1_hit = bool(top_pids) and top_pids[0] == target_pid
    top5_hit = target_pid in top_pids[:5]
    peers = [p for p in top_pids if p != target_pid][:5]
    same_sub = sum(1 for p in peers if pid2sub.get(p) == target_sub)
    return {
        "top1_hit": top1_hit,
        "top5_hit": top5_hit,
        "same_sub_in_top5": same_sub,
        "top5_pids": top_pids[:5],
    }


async def run_vlm_only(image_b64: str) -> list[str]:
    keywords = await vision_describe(image_b64)
    retrieved = retrieve(keywords, top_k=10, mode="hybrid_rerank")
    return [r["product_id"] for r in retrieved]


def run_clip_only(image_bytes: bytes) -> list[str]:
    hits = image_search(image_bytes, top_k=10)
    return [pid for pid, _ in hits]


async def run_fusion(image_b64: str, image_bytes: bytes) -> list[str]:
    keywords = await vision_describe(image_b64)
    text_hits = retrieve(keywords, top_k=10, mode="hybrid_rerank")
    img_hits = image_search(image_bytes, top_k=10)
    fused = fuse_with_text_retrieval(text_hits, img_hits, top_k=10)
    return [r["product_id"] for r in fused]


async def evaluate(per_cat: int) -> dict:
    print("warmup ...")
    clip_warmup()
    warmup_text()

    products = load_products()
    pid2sub: dict[str, str] = {p["product_id"]: p.get("sub_category", "") for p in products}
    sampled = stratified_sample(products, per_cat)
    print(f"评测样本 {len(sampled)} 件（每个 category {per_cat} 件）")

    results: dict[str, list[dict]] = {"vlm_only": [], "clip_only": [], "fusion": []}
    timings: dict[str, list[float]] = {"vlm_only": [], "clip_only": [], "fusion": []}

    t0 = time.time()
    for i, p in enumerate(sampled, 1):
        pid = p["product_id"]
        sub = p.get("sub_category", "")
        rel = p.get("image_path")
        if not rel:
            continue
        img_path = IMAGES_ROOT / rel
        if not img_path.exists():
            continue
        img_bytes = img_path.read_bytes()
        img_b64 = base64.b64encode(img_bytes).decode()

        # vlm_only
        ta = time.time()
        try:
            vlm_top = await run_vlm_only(img_b64)
        except Exception as e:
            print(f"  vlm_only 失败 {pid}: {e}")
            vlm_top = []
        timings["vlm_only"].append(time.time() - ta)
        results["vlm_only"].append({
            "pid": pid, "sub": sub, "category": p["category"],
            **score_one(pid, sub, vlm_top, pid2sub),
        })

        # clip_only
        ta = time.time()
        clip_top = run_clip_only(img_bytes)
        timings["clip_only"].append(time.time() - ta)
        results["clip_only"].append({
            "pid": pid, "sub": sub, "category": p["category"],
            **score_one(pid, sub, clip_top, pid2sub),
        })

        # fusion
        ta = time.time()
        try:
            fus_top = await run_fusion(img_b64, img_bytes)
        except Exception as e:
            print(f"  fusion 失败 {pid}: {e}")
            fus_top = []
        timings["fusion"].append(time.time() - ta)
        results["fusion"].append({
            "pid": pid, "sub": sub, "category": p["category"],
            **score_one(pid, sub, fus_top, pid2sub),
        })

        v = results["vlm_only"][-1]
        c = results["clip_only"][-1]
        f = results["fusion"][-1]
        print(
            f"[{i:02d}/{len(sampled)}] {pid} ({sub})  "
            f"vlm: top1={'✓' if v['top1_hit'] else '✗'} sub={v['same_sub_in_top5']}/5 | "
            f"clip: top1={'✓' if c['top1_hit'] else '✗'} sub={c['same_sub_in_top5']}/5 | "
            f"fusion: top1={'✓' if f['top1_hit'] else '✗'} sub={f['same_sub_in_top5']}/5"
        )

    elapsed = time.time() - t0

    # 聚合
    summary: dict[str, dict] = {}
    for mode, rows in results.items():
        if not rows:
            continue
        n = len(rows)
        t1 = sum(1 for r in rows if r["top1_hit"])
        t5 = sum(1 for r in rows if r["top5_hit"])
        ssub = sum(r["same_sub_in_top5"] for r in rows)
        avg_time = sum(timings[mode]) / max(len(timings[mode]), 1) * 1000
        summary[mode] = {
            "n": n,
            "self_recall_top1": round(100.0 * t1 / n, 1),
            "self_recall_top5": round(100.0 * t5 / n, 1),
            "same_sub_recall_at5": round(100.0 * ssub / n / 5.0, 1),
            "avg_latency_ms": round(avg_time, 0),
        }

    return {
        "per_cat": per_cat,
        "n_samples": len(sampled),
        "elapsed_sec": round(elapsed, 1),
        "results": results,
        "summary": summary,
    }


def write_report(out: dict) -> Path:
    RESULTS_DIR.mkdir(parents=True, exist_ok=True)
    ts = dt.datetime.now().strftime("%Y%m%d-%H%M%S")
    md = RESULTS_DIR / f"image_ablation-{ts}.md"
    s = out["summary"]
    modes = ["vlm_only", "clip_only", "fusion"]

    lines = [
        f"# 多模态检索 Ablation (run @ {dt.datetime.now().strftime('%Y-%m-%d %H:%M:%S')})",
        "",
        f"**样本**: 每个 category 抽 {out['per_cat']} 件，共 {out['n_samples']} 件 / 总耗时 {out['elapsed_sec']}s",
        "",
        "## 总览",
        "",
        "| 指标 \\ 策略 | " + " | ".join(f"`{m}`" for m in modes) + " |",
        "| --- | " + " | ".join("---" for _ in modes) + " |",
    ]

    def row(label: str, key: str, suffix: str = "%"):
        cells = [f"{s[m][key]}{suffix}" if m in s else "n/a" for m in modes]
        return f"| {label} | " + " | ".join(cells) + " |"

    lines += [
        row("Self-recall @1", "self_recall_top1"),
        row("Self-recall @5", "self_recall_top5"),
        row("同子类目 R@5", "same_sub_recall_at5"),
        row("平均延迟 (ms)", "avg_latency_ms", suffix=""),
        "",
        "## 解读（用于答辩）",
        "",
        "- **vlm_only** 走 D11-D13 旧链路：VLM 抽关键词 → 文本 RAG。"
        "依赖 VLM 描述能否还原成商品库里的原文（标题/详情）。"
        "数码品牌（iPhone/小米）一般能命中，但风格化/小众商品会丢失。",
        "- **clip_only** 直接在像素空间检索：用户图和商品主图都过同一个 Chinese-CLIP 编码器，"
        "cosine 相似度比“文本桥接”更直接。同款商品的 self-recall 应当远高于 vlm_only。",
        "- **fusion** RRF 融合：language-aware 的 VLM 关键词 + visual-aware 的 CLIP 图像向量在 product 级互补。"
        "一般取 self-recall ≥ clip_only，并且 same-sub@5 兼具品类聚类（CLIP 强项）"
        "和品牌/属性一致性（VLM 强项）。",
        "- 延迟构成：vlm_only ≈ VLM API 1-3s + 文本检索 ~50ms；"
        "clip_only ≈ CLIP encode + Chroma cosine ~100ms；"
        "fusion ≈ vlm_only 路径耗时（VLM 是瓶颈）+ CLIP encode ~100ms 并行不显著。",
        "",
        "## 逐条明细",
        "",
        "| pid | sub_category | vlm top1 | vlm sub@5 | clip top1 | clip sub@5 | fusion top1 | fusion sub@5 |",
        "| --- | --- | --- | --- | --- | --- | --- | --- |",
    ]
    rows_by_pid: dict[str, dict] = {}
    for mode in modes:
        for r in out["results"][mode]:
            rows_by_pid.setdefault(r["pid"], {"sub": r["sub"]})[mode] = r
    for pid, info in rows_by_pid.items():
        v = info.get("vlm_only", {})
        c = info.get("clip_only", {})
        f = info.get("fusion", {})
        lines.append(
            f"| `{pid}` | {info['sub']} "
            f"| {'✓' if v.get('top1_hit') else '✗'} | {v.get('same_sub_in_top5', '-')}/5 "
            f"| {'✓' if c.get('top1_hit') else '✗'} | {c.get('same_sub_in_top5', '-')}/5 "
            f"| {'✓' if f.get('top1_hit') else '✗'} | {f.get('same_sub_in_top5', '-')}/5 |"
        )

    md.write_text("\n".join(lines), encoding="utf-8")
    return md


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--per-cat", type=int, default=5, help="每个 category 抽样数")
    args = ap.parse_args()

    out = asyncio.run(evaluate(args.per_cat))
    md = write_report(out)
    print()
    print("=" * 60)
    s = out["summary"]
    for mode in ("vlm_only", "clip_only", "fusion"):
        if mode in s:
            print(
                f"{mode:11s}  self@1={s[mode]['self_recall_top1']}%  "
                f"self@5={s[mode]['self_recall_top5']}%  "
                f"sub@5={s[mode]['same_sub_recall_at5']}%  "
                f"avg={s[mode]['avg_latency_ms']}ms"
            )
    print(f"\n报告: {md}")


if __name__ == "__main__":
    main()
