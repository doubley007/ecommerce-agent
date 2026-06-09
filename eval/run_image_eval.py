"""
以图搜图（CLIP 主图召回）评测。

跑两个轨道：
  Track A — Self-recall：100 张商品主图逐一作 query，期望 top-1 = 自身。
            验证向量空间对齐 + Chroma 索引健壮性。
  Track B — 同子类目近邻 Recall@5：top-5 去掉自己后，多少个落在同 sub_category。
            验证 CLIP 视觉表征对子品类的聚类质量。

输出：eval/results/image_eval-<ts>.md

用法（在仓库根目录或 server/ 下都能跑）：
    python eval/run_image_eval.py
"""

from __future__ import annotations

import datetime as dt
import json
import sys
import time
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "server"))

from app.clip_embedder import encode_image, warmup as clip_warmup  # noqa: E402
from app.config import PROJECT_ROOT  # noqa: E402
from app.vector_store import get_or_create_image_collection  # noqa: E402

PRODUCTS_FILE = PROJECT_ROOT / "data" / "products.jsonl"
IMAGES_ROOT = PROJECT_ROOT / "data" / "raw" / "ecommerce_agent_dataset"
RESULTS_DIR = PROJECT_ROOT / "eval" / "results"


def load_products() -> list[dict]:
    out = []
    for line in PRODUCTS_FILE.read_text(encoding="utf-8").splitlines():
        if line.strip():
            out.append(json.loads(line))
    return out


def evaluate() -> dict:
    print("warmup CLIP ...")
    clip_warmup()
    col = get_or_create_image_collection()
    n_in_col = col.count()
    print(f"products_images 集合: {n_in_col} 条")
    if n_in_col == 0:
        raise SystemExit("集合为空，请先跑 server/scripts/build_image_index.py")

    products = load_products()
    pid_meta: dict[str, dict] = {p["product_id"]: p for p in products}

    rows: list[dict] = []
    t0 = time.time()
    for i, p in enumerate(products, 1):
        pid = p["product_id"]
        rel = p.get("image_path")
        if not rel:
            continue
        img_path = IMAGES_ROOT / rel
        if not img_path.exists():
            continue

        qvec = encode_image(img_path.read_bytes())
        res = col.query(
            query_embeddings=[qvec.tolist()],
            n_results=6,  # 多取一个，去掉自己后还有 5
            include=["metadatas", "distances"],
        )
        metas = res["metadatas"][0]
        dists = res["distances"][0]
        ranking = [
            (m["product_id"], 1.0 - float(d))
            for m, d in zip(metas, dists, strict=False)
        ]
        top1_pid, top1_sim = ranking[0]
        # Track B: 去自己的 top-5
        peers = [(rpid, s) for rpid, s in ranking if rpid != pid][:5]
        own_sub = p.get("sub_category", "")
        peer_subs = [pid_meta[rpid].get("sub_category", "") for rpid, _ in peers if rpid in pid_meta]
        same_sub_n = sum(1 for s in peer_subs if s == own_sub)

        rows.append({
            "product_id": pid,
            "title": p["title"][:30],
            "sub_category": own_sub,
            "top1_pid": top1_pid,
            "top1_sim": round(top1_sim, 4),
            "self_recall_ok": top1_pid == pid,
            "peer_top5": [rpid for rpid, _ in peers],
            "peer_subs": peer_subs,
            "same_sub_in_top5": same_sub_n,
            "same_sub_rate": round(same_sub_n / 5.0, 2) if peers else 0.0,
        })

        ok_a = "✓" if rows[-1]["self_recall_ok"] else "✗"
        print(
            f"[{i:03d}/{len(products)}] {pid} ({own_sub}) {ok_a} top1={top1_pid} "
            f"sim={top1_sim:.3f}  peer-sub-hit={same_sub_n}/5"
        )

    elapsed = time.time() - t0

    # 聚合
    n = len(rows)
    self_hits = sum(1 for r in rows if r["self_recall_ok"])
    same_sub_total = sum(r["same_sub_in_top5"] for r in rows)
    n_with_peers = sum(1 for r in rows if r["peer_top5"])
    same_sub_avg = same_sub_total / max(n_with_peers, 1) / 5.0 * 100  # %

    # 按子类目分组看
    by_sub: dict[str, list[dict]] = {}
    for r in rows:
        by_sub.setdefault(r["sub_category"], []).append(r)
    sub_stats = []
    for sub, items in sorted(by_sub.items()):
        nn = len(items)
        sh = sum(1 for r in items if r["self_recall_ok"])
        ss = sum(r["same_sub_in_top5"] for r in items) / nn / 5.0 * 100
        sub_stats.append((sub, nn, sh, round(ss, 1)))

    summary = {
        "n_queries": n,
        "elapsed_sec": round(elapsed, 1),
        "self_recall_top1_rate": round(100.0 * self_hits / max(n, 1), 1),
        "same_sub_recall_at5_rate": round(same_sub_avg, 1),
        "by_sub_category": sub_stats,
    }
    return {"rows": rows, "summary": summary}


def write_report(out: dict) -> Path:
    RESULTS_DIR.mkdir(parents=True, exist_ok=True)
    ts = dt.datetime.now().strftime("%Y%m%d-%H%M%S")
    md = RESULTS_DIR / f"image_eval-{ts}.md"
    s = out["summary"]
    lines = [
        f"# 以图搜图评测 (run @ {dt.datetime.now().strftime('%Y-%m-%d %H:%M:%S')})",
        "",
        f"**评测集**: 商品库主图 {s['n_queries']} 张 / 耗时 {s['elapsed_sec']}s",
        "",
        "## 总体指标",
        "",
        "| 指标 | 值 | 说明 |",
        "| --- | --- | --- |",
        f"| Self-recall @1 | **{s['self_recall_top1_rate']}%** | 主图作 query，top-1 应是自身。验证索引和向量对齐 |",
        f"| 同子类目 Recall@5 | **{s['same_sub_recall_at5_rate']}%** | top-5（去自己）中同 sub_category 占比。衡量视觉聚类质量 |",
        "",
        "## 按子类目",
        "",
        "| 子类目 | 商品数 | self-recall ✓ | 同子类目 R@5 |",
        "| --- | --- | --- | --- |",
    ]
    for sub, nn, sh, ss in s["by_sub_category"]:
        lines.append(f"| {sub or '(空)'} | {nn} | {sh}/{nn} | {ss}% |")

    # self-recall 失败样本
    fails = [r for r in out["rows"] if not r["self_recall_ok"]]
    if fails:
        lines += ["", "## Self-recall 失败用例", ""]
        for r in fails:
            lines.append(
                f"- `{r['product_id']}` ({r['sub_category']}) 标题=「{r['title']}」 "
                f"top1=`{r['top1_pid']}` sim={r['top1_sim']}"
            )
    else:
        lines += ["", "## Self-recall 失败用例", "", "✅ 无失败"]

    # 同子类目最弱的 5 个
    weak = sorted(out["rows"], key=lambda r: r["same_sub_in_top5"])[:5]
    lines += ["", "## 同子类目召回最弱的 5 个 (top-5 邻居最少落在同子类目)", ""]
    for r in weak:
        peer_subs_str = " / ".join(r["peer_subs"])
        lines.append(
            f"- `{r['product_id']}` ({r['sub_category']}) 命中 {r['same_sub_in_top5']}/5  "
            f"邻居子类目: {peer_subs_str}"
        )

    lines += ["", "## 解读（用于答辩）", ""]
    lines += [
        "- **Self-recall @1**：考察“同图必须能被自己命中”的最低底线。"
        "Chinese-CLIP 在归一化向量 + cosine 检索下应当达到 100%——任何低于 100% 都说明索引或编码有 bug。",
        "- **同子类目 Recall@5**：考察 CLIP 学到的视觉特征是否能把同子类目（同款式、同包装风格）拉到一起。"
        "100 件商品分布在十几个子类目里，**随机基线 ≈ 6%**（10 个子类目均匀分布时），"
        "实际值远高于此即说明 CLIP 视觉表征对品类聚类有强信号。",
        "- 子类目下样本少（如某子类目只有 1 件商品）天然 R@5=0，不是模型问题，是评测分母小，可以忽略。",
    ]
    md.write_text("\n".join(lines), encoding="utf-8")
    return md


def main():
    out = evaluate()
    md = write_report(out)
    print()
    print("=" * 60)
    s = out["summary"]
    print(f"Self-recall @1:        {s['self_recall_top1_rate']}%")
    print(f"同子类目 Recall@5:     {s['same_sub_recall_at5_rate']}%")
    print(f"报告: {md}")


if __name__ == "__main__":
    main()
