"""
从已存在的 run-<ts>-<mode>.jsonl 重新聚合，合成 ablation 对比表。
用于上次 ablation 跑到一半被 kill、对比表没写出的场景。
"""
from __future__ import annotations

import argparse
import datetime as dt
import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
RESULTS_DIR = ROOT / "eval" / "results"

sys.path.insert(0, str(ROOT / "eval"))
from run_eval import EVAL_FILE, EVAL_MULTITURN, aggregate, write_ablation_report  # noqa: E402


def load_jsonl(path: Path) -> list[dict]:
    return [json.loads(l) for l in path.read_text(encoding="utf-8").splitlines() if l.strip()]


def _load_gold_map() -> dict[str, list[str]]:
    """id -> gold_ids，从 eval_set / eval_multiturn 反查，给历史 jsonl 补齐字段。"""
    out: dict[str, list[str]] = {}
    for f in (EVAL_FILE, EVAL_MULTITURN):
        if not f.exists():
            continue
        for line in f.read_text(encoding="utf-8").splitlines():
            if line.strip():
                row = json.loads(line)
                out[row["id"]] = row.get("gold_ids", [])
    return out


def _backfill_gold(rows: list[dict]) -> list[dict]:
    gold = _load_gold_map()
    for r in rows:
        if "gold_ids" not in r and r.get("id") in gold:
            r["gold_ids"] = gold[r["id"]]
    return rows


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--ts", required=True, help="时间戳前缀，如 20260525-171634")
    args = ap.parse_args()

    modes = ["no_rag", "vector_only", "hybrid", "hybrid_rerank"]
    runs = {}
    for m in modes:
        f = RESULTS_DIR / f"run-{args.ts}-{m}.jsonl"
        if not f.exists():
            print(f"[skip] {f.name} 不存在")
            continue
        rows = _backfill_gold(load_jsonl(f))
        summary = aggregate(rows)
        summary["eval_size"] = len(rows)
        summary["mode"] = m
        runs[m] = {"rows": rows, "summary": summary}
        print(f"[ok]  {m}: {len(rows)} 条 / pass={summary['overall']['pass_rate']}%")

    if not runs:
        print("没有可用的 jsonl，退出")
        return

    out_md = RESULTS_DIR / f"ablation-{args.ts}.md"
    write_ablation_report(runs, out_md)
    print(f"\n对比表: {out_md}")


if __name__ == "__main__":
    main()
