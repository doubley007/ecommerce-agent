"""
端到端评测脚本。

读取 eval_set.jsonl + eval_multiturn.jsonl，逐条调用后端 /chat/stream，统计：
  - retrieval@k：gold_ids 在检索结果 top-k 中的命中率
  - tag_coverage：模型回复中是否包含 [[PRODUCT:xxx]] 标签
  - constraint_compliance：价格上限 / 品牌排除 / 品牌限定 是否被违反
  - refuse_rate：expected=refuse 的 query 模型是否拒答
  - ask_back_rate：expected=ask_back 的 query 模型是否反问
  - groundedness（D16 新增）：把回复切句，每句反查与召回 chunk 的最大余弦相似度，
    < 阈值算 hallucination；输出 grounded_sentence_rate

支持 ablation 模式（D16 新增）：
  --mode vector_only     纯向量召回（旧 baseline）
  --mode hybrid          向量 + BM25 RRF 融合
  --mode hybrid_rerank   hybrid + cross-encoder 重排（默认，最强）
  --mode no_rag          关 use_rag，验证"无 RAG 时模型会编造商品"
  --ablation             一键依次跑上面 4 种模式 + 输出对比表

输出：
  - 控制台：每条 query 的得分明细
  - eval/results/run-<timestamp>.md：完整 markdown 报告
  - eval/results/run-<timestamp>.jsonl：每条 raw 数据
  - --ablation 模式下额外输出 ablation-<timestamp>.md 对比表
"""
from __future__ import annotations

import argparse
import datetime as dt
import json
import re
import sys
import time
from pathlib import Path

import httpx

ROOT = Path(__file__).resolve().parent.parent
EVAL_FILE = ROOT / "eval" / "eval_set.jsonl"
EVAL_MULTITURN = ROOT / "eval" / "eval_multiturn.jsonl"
PRODUCTS_FILE = ROOT / "data" / "products.jsonl"
RESULTS_DIR = ROOT / "eval" / "results"

PRODUCT_TAG_RE = re.compile(r"\[\[PRODUCT:([a-zA-Z0-9_]+)\]\]")
COMPARE_DATA_PREFIX_RE = re.compile(r"\[\[COMPARE_DATA:")
REFUSE_KEYS = ("暂无", "没有", "不在", "不到", "无法", "未找到", "没找到")
ASK_BACK_HINTS = ("？", "?", "请问", "请告诉", "可以告诉", "想先", "想了解", "想确认", "什么呢", "哪类")

# 只在第一次需要时加载 BGE，避免 no_rag 模式下白等
_embedder = None
_retriever = None


def _get_embedder():
    """懒加载 BGE，给 groundedness 用。"""
    global _embedder
    if _embedder is None:
        sys.path.insert(0, str(ROOT / "server"))
        from app.embedder import get_model
        _embedder = get_model()
    return _embedder


def _get_retriever_fn():
    """懒加载本地 retriever，让评测脚本能直接拿到 chunk text 给 groundedness 用。"""
    global _retriever
    if _retriever is None:
        sys.path.insert(0, str(ROOT / "server"))
        from app.hybrid_retriever import retrieve_hybrid
        _retriever = retrieve_hybrid
    return _retriever


def load_products() -> dict[str, dict]:
    out = {}
    for line in PRODUCTS_FILE.read_text(encoding="utf-8").splitlines():
        if line.strip():
            p = json.loads(line)
            out[p["product_id"]] = p
    return out


def load_eval() -> list[dict]:
    rows = []
    if EVAL_FILE.exists():
        for l in EVAL_FILE.read_text(encoding="utf-8").splitlines():
            if l.strip():
                row = json.loads(l)
                row["messages"] = [{"role": "user", "content": row["query"]}]
                rows.append(row)
    if EVAL_MULTITURN.exists():
        for l in EVAL_MULTITURN.read_text(encoding="utf-8").splitlines():
            if l.strip():
                row = json.loads(l)
                row["query"] = row["messages"][-1]["content"]
                rows.append(row)
    return rows


def parse_sse(raw: str) -> tuple[list[dict], str]:
    """从原始 SSE 文本里抽出 retrieved 数组 + 模型完整文本。"""
    retrieved: list[dict] = []
    tokens: list[str] = []
    cur_event: str | None = None
    for line in raw.splitlines():
        if line.startswith("event: "):
            cur_event = line[7:].strip()
        elif line.startswith("data: "):
            data = line[6:]
            try:
                obj = json.loads(data)
            except Exception:
                continue
            if cur_event == "retrieved" and isinstance(obj, list):
                retrieved = obj
            elif cur_event == "token" and isinstance(obj, dict) and "text" in obj:
                tokens.append(obj["text"])
    return retrieved, "".join(tokens)


def call_backend(
    base_url: str,
    messages: list[dict],
    mode: str,
    timeout: float = 120.0,
) -> tuple[list[dict], str]:
    payload: dict = {"messages": messages}
    if mode == "no_rag":
        payload["use_rag"] = False
    else:
        payload["retrieval_mode"] = mode
    with httpx.Client(timeout=timeout) as cli:
        r = cli.post(
            f"{base_url}/chat/stream",
            json=payload,
            headers={"Accept": "text/event-stream"},
        )
        r.raise_for_status()
        return parse_sse(r.text)


def score_retrieval(gold: list[str], retrieved: list[dict], k: int = 5) -> dict:
    if not gold:
        return {"applicable": False}
    top_ids = [r.get("product_id") for r in retrieved[:k]]
    hits = [g for g in gold if g in top_ids]
    return {
        "applicable": True,
        "gold_n": len(gold),
        "hits_n": len(hits),
        "any_hit": len(hits) > 0,
        "all_hit": len(hits) == len(gold),
        "top_ids": top_ids,
    }


def score_constraints(reply_tags: list[str], checks: dict, products: dict) -> dict:
    if not checks:
        return {"applicable": False}
    violations = []
    for pid in reply_tags:
        p = products.get(pid)
        if not p:
            violations.append(f"{pid}: 不在商品库")
            continue
        bp = p.get("base_price") or 0
        skus = p.get("skus") or []
        sku_min = min((s.get("price") for s in skus if s.get("price")), default=bp)
        min_price = min(bp, sku_min) if sku_min else bp
        brand = p.get("brand")
        if "max_price" in checks and min_price > checks["max_price"]:
            violations.append(f"{pid}({brand} ¥{min_price}): 超过上限 ¥{checks['max_price']}")
        if "brand_not_in" in checks and brand in checks["brand_not_in"]:
            violations.append(f"{pid}({brand}): 命中排除品牌")
        if "brand_in" in checks and brand not in checks["brand_in"]:
            violations.append(f"{pid}({brand}): 不在限定品牌 {checks['brand_in']}")
        if "category_match" in checks and p.get("sub_category") != checks["category_match"]:
            violations.append(f"{pid}: 子类目 {p.get('sub_category')} ≠ {checks['category_match']}")
    return {
        "applicable": True,
        "n_tags": len(reply_tags),
        "n_violations": len(violations),
        "ok": len(violations) == 0 and len(reply_tags) >= checks.get("min_tags", 0),
        "violations": violations,
    }


def score_brand_exclude_compliance(reply_tags: list[str], checks: dict, products: dict) -> dict:
    """单独衡量"反选/排除"语义被遵守的程度。
    仅当 checks 里有 brand_not_in 时 applicable。
    指标：tag 列表里没有任何被排除品牌即 ok。
    """
    if not checks or "brand_not_in" not in checks:
        return {"applicable": False}
    excludes = checks["brand_not_in"]
    bad = []
    for pid in reply_tags:
        p = products.get(pid)
        if not p:
            continue
        b = p.get("brand") or ""
        if any(ex == b or ex in b or b in ex for ex in excludes if ex):
            bad.append(f"{pid}({b})")
    return {"applicable": True, "ok": not bad, "violations": bad}


def score_price_max_compliance(reply_tags: list[str], checks: dict, products: dict) -> dict:
    """单独衡量"价格上限"硬约束被遵守的程度。
    指标：所有推荐商品的最低价 ≤ checks.max_price 即 ok。
    """
    if not checks or "max_price" not in checks:
        return {"applicable": False}
    cap = checks["max_price"]
    bad = []
    for pid in reply_tags:
        p = products.get(pid)
        if not p:
            continue
        bp = p.get("base_price") or 0
        skus = p.get("skus") or []
        sku_min = min((s.get("price") for s in skus if s.get("price")), default=bp)
        min_price = min(bp, sku_min) if sku_min else bp
        if min_price > cap:
            bad.append(f"{pid}(¥{min_price})")
    return {"applicable": True, "ok": not bad, "violations": bad}


def score_compare_json(reply_text: str) -> dict:
    """检查 [[COMPARE_DATA:{...}]] 块是否合法且字段一致。
    用花括号配平算 JSON 边界（同客户端解析逻辑），避免被字符串里的 `]` 误断。
    指标：
      - present       — 文本里有 COMPARE_DATA 前缀
      - parsed        — JSON 能解析
      - schema_ok     — products / scores / rows 长度一致 + dimensions 一致
      - radar_ok      — scores 全是 0..5 整数（雷达图能渲染）
    顶层 ok = parsed && schema_ok && radar_ok。
    """
    out = {
        "applicable": True,
        "present": False,
        "parsed": False,
        "schema_ok": False,
        "radar_ok": False,
        "ok": False,
        "reason": "",
    }
    m = COMPARE_DATA_PREFIX_RE.search(reply_text)
    if not m:
        out["reason"] = "no [[COMPARE_DATA:"
        return out
    out["present"] = True
    open_idx = reply_text.find("{", m.end())
    if open_idx < 0:
        out["reason"] = "no opening brace"
        return out
    depth = 0
    in_str = False
    esc = False
    end_idx = -1
    for i in range(open_idx, len(reply_text)):
        c = reply_text[i]
        if esc:
            esc = False
            continue
        if c == "\\":
            esc = True
            continue
        if c == '"':
            in_str = not in_str
            continue
        if in_str:
            continue
        if c == "{":
            depth += 1
        elif c == "}":
            depth -= 1
            if depth == 0:
                end_idx = i
                break
    if end_idx < 0:
        out["reason"] = "unterminated json"
        return out
    raw_json = reply_text[open_idx:end_idx + 1]
    try:
        d = json.loads(raw_json)
    except Exception as e:
        out["reason"] = f"json error: {e}"
        return out
    out["parsed"] = True
    products = d.get("products") or []
    dims = d.get("dimensions") or []
    scores = d.get("scores") or []
    rows = d.get("rows") or []
    n_p = len(products)
    n_d = len(dims)
    if n_p < 2 or n_d < 2:
        out["reason"] = f"too small: products={n_p} dims={n_d}"
        return out
    if len(scores) != n_p or len(rows) != n_p:
        out["reason"] = "scores/rows length != products"
        return out
    if any(len(s) != n_d for s in scores) or any(len(r) != n_d for r in rows):
        out["reason"] = "inner row length != dims"
        return out
    out["schema_ok"] = True
    if all(isinstance(v, int) and 0 <= v <= 5 for s in scores for v in s):
        out["radar_ok"] = True
    out["ok"] = out["schema_ok"] and out["radar_ok"]
    return out


def score_refusal(reply_text: str, reply_tags: list[str]) -> dict:
    no_tags = len(reply_tags) == 0
    has_kw = any(k in reply_text for k in REFUSE_KEYS)
    return {"ok": no_tags and has_kw, "no_tags": no_tags, "has_kw": has_kw}


def score_ask_back(reply_text: str, reply_tags: list[str]) -> dict:
    no_tags = len(reply_tags) == 0
    has_q = any(h in reply_text for h in ASK_BACK_HINTS)
    return {"ok": no_tags and has_q, "no_tags": no_tags, "has_question": has_q}


_SENT_SPLIT_RE = re.compile(r"(?<=[。！？!?；;\n])\s*")


def _split_sentences(text: str) -> list[str]:
    """中文+英文混合句切分，过滤空串和过短行。"""
    # 先剥商品标签，否则 [[PRODUCT:p_xxx]] 会污染句子向量
    text = PRODUCT_TAG_RE.sub("", text)
    parts = _SENT_SPLIT_RE.split(text)
    out = []
    for p in parts:
        p = p.strip(" 　-·•*#>")
        # 过滤过短的（"好的"、"嗯"）和纯标点
        if len(p) >= 6 and any(c.isalnum() or "一" <= c <= "鿿" for c in p):
            out.append(p)
    return out


def score_groundedness(
    reply_text: str,
    retrieval_query: str,
    threshold: float = 0.4,
    top_k_chunks: int = 8,
) -> dict:
    """
    把回复切句，每句计算与召回 chunk 的最大余弦相似度。
    < threshold 算 hallucination（"凭空说出"）。

    用本地 BGE 算 sentence embedding，与 chunk 的 embedding 取 cos sim。
    chunk 走 hybrid_rerank 召回（评测时不影响生成，只是再跑一次取 chunk 文本）。
    """
    sentences = _split_sentences(reply_text)
    if not sentences:
        return {"applicable": False}
    retriever = _get_retriever_fn()
    model = _get_embedder()
    # 用 hybrid_rerank 取 top-k chunks 当事实支撑面
    retrieved = retriever(retrieval_query, top_k=top_k_chunks, mode="hybrid_rerank")
    chunk_texts: list[str] = []
    for r in retrieved:
        for c in r.get("matched_chunks", []):
            chunk_texts.append(c["text"])
    if not chunk_texts:
        return {"applicable": False}
    # 编码（doc 侧不加 query 前缀；chunk 文本自带类型标签足够区分）
    sent_vecs = model.encode(sentences, normalize_embeddings=True, show_progress_bar=False)
    chunk_vecs = model.encode(chunk_texts, normalize_embeddings=True, show_progress_bar=False)
    # 余弦 = 内积（已 normalize）
    sims = sent_vecs @ chunk_vecs.T  # [n_sent, n_chunk]
    max_sims = sims.max(axis=1).tolist()
    grounded_flags = [s >= threshold for s in max_sims]
    grounded_n = sum(1 for g in grounded_flags if g)
    return {
        "applicable": True,
        "n_sentences": len(sentences),
        "n_grounded": grounded_n,
        "grounded_rate": round(100.0 * grounded_n / len(sentences), 1),
        "min_sim": round(min(max_sims), 3),
        "avg_sim": round(sum(max_sims) / len(max_sims), 3),
        "ungrounded_examples": [
            {"sentence": s, "max_sim": round(m, 3)}
            for s, m, g in zip(sentences, max_sims, grounded_flags, strict=False)
            if not g
        ][:3],  # 最多留 3 条样本
    }


def evaluate(
    base_url: str,
    mode: str = "hybrid_rerank",
    k: int = 5,
    do_groundedness: bool = True,
) -> dict:
    products = load_products()
    eval_set = load_eval()
    print(f"加载 {len(eval_set)} 条评测 query, {len(products)} 件商品")
    print(f"后端: {base_url}  mode: {mode}\n")

    rows = []
    t0 = time.time()
    for i, item in enumerate(eval_set, 1):
        q = item["query"]
        expected = item["expected"]
        n_turns = len(item.get("messages", []))
        turn_tag = f"({n_turns}轮)" if n_turns > 1 else ""
        print(f"[{i:02d}/{len(eval_set)}] {item['id']} ({item['category']}){turn_tag} | {q}")
        try:
            retrieved, reply_text = call_backend(base_url, item["messages"], mode=mode)
        except Exception as e:
            print(f"   ✗ 请求失败: {e}")
            rows.append({**item, "error": str(e)})
            continue

        reply_tags = PRODUCT_TAG_RE.findall(reply_text)
        result = {
            "id": item["id"],
            "category": item["category"],
            "query": q,
            "expected": expected,
            "gold_ids": item.get("gold_ids", []),
            "reply_text": reply_text,
            "reply_tags": reply_tags,
            "retrieved_top_ids": [r.get("product_id") for r in retrieved],
        }

        if expected == "recommend":
            result["retrieval"] = score_retrieval(item.get("gold_ids", []), retrieved, k=k)
            result["constraints"] = score_constraints(reply_tags, item.get("checks", {}), products)
            # 细分指标 — 单独看反选/价格遵守度，便于做加分项展示
            result["brand_exclude"] = score_brand_exclude_compliance(
                reply_tags, item.get("checks", {}), products,
            )
            result["price_max"] = score_price_max_compliance(
                reply_tags, item.get("checks", {}), products,
            )
            # 对比模式：上下文里有"对比/比较/vs"等关键词的 case 才适用
            last_user_text = next(
                (m["content"] for m in reversed(item["messages"]) if m["role"] == "user"),
                "",
            )
            if re.search(r"(对比|比较|区别|差别|哪个更|哪款更|哪个好|哪款好|vs|VS)", last_user_text):
                result["compare_json"] = score_compare_json(reply_text)
            ok = (
                result["constraints"].get("ok", False) if result["constraints"]["applicable"]
                else len(reply_tags) > 0
            )
        elif expected == "refuse":
            result["refusal"] = score_refusal(reply_text, reply_tags)
            ok = result["refusal"]["ok"]
        elif expected == "ask_back":
            result["ask_back"] = score_ask_back(reply_text, reply_tags)
            ok = result["ask_back"]["ok"]
        else:
            ok = False
        result["pass"] = ok

        # groundedness 只对 expected=recommend 且模型有实质回复的算
        if do_groundedness and expected == "recommend" and reply_text.strip():
            recent_user = [m["content"] for m in item["messages"] if m["role"] == "user"][-3:]
            retrieval_query = " ".join(recent_user)
            try:
                result["groundedness"] = score_groundedness(reply_text, retrieval_query)
            except Exception as e:
                result["groundedness"] = {"applicable": False, "error": str(e)}

        gflag = ""
        if "groundedness" in result and result["groundedness"].get("applicable"):
            gflag = f"  G={result['groundedness']['grounded_rate']}%"
        print(f"   {'✓' if ok else '✗'} tags={reply_tags or '∅'}{gflag}  reply[:60]={reply_text[:60].replace(chr(10), ' ')}")
        rows.append(result)

    elapsed = time.time() - t0
    summary = aggregate(rows)
    summary["elapsed_sec"] = round(elapsed, 1)
    summary["eval_size"] = len(eval_set)
    summary["mode"] = mode
    return {"rows": rows, "summary": summary}


def aggregate(rows: list[dict]) -> dict:
    by_cat: dict[str, list[dict]] = {}
    for r in rows:
        by_cat.setdefault(r.get("category", "?"), []).append(r)

    def pct(num: int, den: int) -> float:
        return round(100.0 * num / den, 1) if den else 0.0

    summary = {"by_category": {}, "overall": {}}

    total = len(rows)
    passed = sum(1 for r in rows if r.get("pass"))
    summary["overall"]["pass_rate"] = pct(passed, total)
    summary["overall"]["n_total"] = total
    summary["overall"]["n_passed"] = passed

    rec = [r for r in rows if r.get("retrieval", {}).get("applicable")]
    any_hit = sum(1 for r in rec if r["retrieval"]["any_hit"])
    summary["overall"]["retrieval_any_hit_rate"] = pct(any_hit, len(rec))
    summary["overall"]["retrieval_n_eligible"] = len(rec)
    # top-1 命中率（gold_id 出现在 top-1 的占比）：衡量重排效果
    top1_hit = sum(
        1 for r in rec
        if r["retrieval"]["top_ids"]
        and r["retrieval"]["top_ids"][0] in r.get("gold_ids", [])
    )
    summary["overall"]["retrieval_top1_hit_rate"] = pct(top1_hit, len(rec))

    recd = [r for r in rows if r.get("expected") == "recommend"]
    has_tag = sum(1 for r in recd if r.get("reply_tags"))
    summary["overall"]["tag_coverage_rate"] = pct(has_tag, len(recd))

    con = [r for r in rows if r.get("constraints", {}).get("applicable")]
    con_ok = sum(1 for r in con if r["constraints"]["ok"])
    summary["overall"]["constraint_ok_rate"] = pct(con_ok, len(con))

    # 反选/价格上限/对比 JSON 三个细分指标（"对话智能 4.3"加分项专用）
    be = [r for r in rows if r.get("brand_exclude", {}).get("applicable")]
    be_ok = sum(1 for r in be if r["brand_exclude"]["ok"])
    summary["overall"]["brand_exclude_compliance_rate"] = pct(be_ok, len(be))
    summary["overall"]["brand_exclude_n_eligible"] = len(be)

    pm = [r for r in rows if r.get("price_max", {}).get("applicable")]
    pm_ok = sum(1 for r in pm if r["price_max"]["ok"])
    summary["overall"]["price_max_compliance_rate"] = pct(pm_ok, len(pm))
    summary["overall"]["price_max_n_eligible"] = len(pm)

    cj = [r for r in rows if r.get("compare_json", {}).get("applicable")]
    cj_ok = sum(1 for r in cj if r["compare_json"]["ok"])
    summary["overall"]["compare_json_valid_rate"] = pct(cj_ok, len(cj))
    summary["overall"]["compare_json_n_eligible"] = len(cj)

    ref = [r for r in rows if r.get("expected") == "refuse"]
    ref_ok = sum(1 for r in ref if r.get("refusal", {}).get("ok"))
    summary["overall"]["refuse_rate"] = pct(ref_ok, len(ref))

    ab = [r for r in rows if r.get("expected") == "ask_back"]
    ab_ok = sum(1 for r in ab if r.get("ask_back", {}).get("ok"))
    summary["overall"]["ask_back_rate"] = pct(ab_ok, len(ab))

    # groundedness：sentence 级聚合
    gr = [r for r in rows if r.get("groundedness", {}).get("applicable")]
    if gr:
        n_sent = sum(r["groundedness"]["n_sentences"] for r in gr)
        n_grounded = sum(r["groundedness"]["n_grounded"] for r in gr)
        summary["overall"]["grounded_sentence_rate"] = pct(n_grounded, n_sent)
        summary["overall"]["groundedness_n_sentences"] = n_sent
        summary["overall"]["groundedness_n_queries"] = len(gr)
    else:
        summary["overall"]["grounded_sentence_rate"] = None
        summary["overall"]["groundedness_n_sentences"] = 0
        summary["overall"]["groundedness_n_queries"] = 0

    for cat, items in by_cat.items():
        n = len(items)
        passed = sum(1 for r in items if r.get("pass"))
        summary["by_category"][cat] = {"n": n, "passed": passed, "pass_rate": pct(passed, n)}
    return summary


def write_report(out: dict, out_md: Path, out_jsonl: Path) -> None:
    s = out["summary"]
    o = s["overall"]
    grounded_str = (
        f"{o['grounded_sentence_rate']}% (n_sent={o['groundedness_n_sentences']}, n_q={o['groundedness_n_queries']})"
        if o.get("grounded_sentence_rate") is not None
        else "n/a"
    )
    lines = [
        f"# 评测报告 (run @ {dt.datetime.now().strftime('%Y-%m-%d %H:%M:%S')})",
        "",
        f"**评测集**: {s['eval_size']} 条 / 耗时 {s['elapsed_sec']}s / mode=`{s.get('mode', '?')}`",
        "",
        "## 总体指标",
        "",
        f"| 指标 | 值 |",
        f"| --- | --- |",
        f"| 综合通过率 | **{o['pass_rate']}%** ({o['n_passed']}/{o['n_total']}) |",
        f"| Retrieval any-hit @5 | {o['retrieval_any_hit_rate']}% (n={o['retrieval_n_eligible']}) |",
        f"| Retrieval top-1 hit | {o['retrieval_top1_hit_rate']}% |",
        f"| 商品标签覆盖率 (推荐题) | {o['tag_coverage_rate']}% |",
        f"| 约束合规率 | {o['constraint_ok_rate']}% |",
        f"| ↳ 反选/排除遵守率 | {o['brand_exclude_compliance_rate']}% (n={o['brand_exclude_n_eligible']}) |",
        f"| ↳ 价格上限遵守率 | {o['price_max_compliance_rate']}% (n={o['price_max_n_eligible']}) |",
        f"| ↳ 对比 JSON 合法率 | {o['compare_json_valid_rate']}% (n={o['compare_json_n_eligible']}) |",
        f"| 拒答正确率 | {o['refuse_rate']}% |",
        f"| 反问正确率 | {o['ask_back_rate']}% |",
        f"| 句级 Groundedness | {grounded_str} |",
        "",
        "## 按类别",
        "",
        "| 类别 | 通过 / 总数 | 通过率 |",
        "| --- | --- | --- |",
    ]
    for cat, c in s["by_category"].items():
        lines.append(f"| {cat} | {c['passed']}/{c['n']} | {c['pass_rate']}% |")

    lines += ["", "## 失败用例明细", ""]
    fails = [r for r in out["rows"] if not r.get("pass")]
    if not fails:
        lines.append("✅ 无失败用例")
    for r in fails:
        lines.append(f"### ✗ {r['id']} ({r['category']}) — `{r['query']}`")
        lines.append("")
        lines.append(f"- expected: **{r['expected']}**")
        lines.append(f"- reply tags: `{r.get('reply_tags')}`")
        if r.get("constraints", {}).get("violations"):
            lines.append(f"- 约束违反: {r['constraints']['violations']}")
        if r.get("retrieval", {}).get("applicable"):
            lines.append(f"- retrieval: gold={r.get('gold_ids', [])} / top={r['retrieval']['top_ids']}")
        snippet = r.get("reply_text", "")[:200].replace("\n", " ")
        lines.append(f"- reply 摘要: {snippet}")
        lines.append("")

    # groundedness 不达标的样本
    weak = [
        r for r in out["rows"]
        if r.get("groundedness", {}).get("applicable")
        and r["groundedness"]["grounded_rate"] < 80.0
    ]
    if weak:
        lines += ["", "## Groundedness 弱项 (<80%)", ""]
        for r in weak:
            g = r["groundedness"]
            lines.append(
                f"- **{r['id']}** ({r['category']}) `{r['query']}` → "
                f"{g['grounded_rate']}% ({g['n_grounded']}/{g['n_sentences']}) avg_sim={g['avg_sim']}"
            )
            for ex in g.get("ungrounded_examples", []):
                lines.append(f"  - sim={ex['max_sim']}: {ex['sentence'][:80]}")

    lines += ["", "## 全部用例（含通过）", ""]
    for r in out["rows"]:
        flag = "✅" if r.get("pass") else "❌"
        g = r.get("groundedness", {})
        gtag = f" G={g['grounded_rate']}%" if g.get("applicable") else ""
        lines.append(f"- {flag} **{r['id']}** ({r['category']}) `{r['query']}` → tags={r.get('reply_tags')}{gtag}")

    out_md.write_text("\n".join(lines), encoding="utf-8")
    out_jsonl.write_text("\n".join(json.dumps(r, ensure_ascii=False) for r in out["rows"]), encoding="utf-8")


def write_ablation_report(runs: dict[str, dict], out_md: Path) -> None:
    """跨 mode 对比表。runs: {mode_name: {summary: {...}}}"""
    lines = [
        f"# Ablation 对比报告 (run @ {dt.datetime.now().strftime('%Y-%m-%d %H:%M:%S')})",
        "",
        "对同一评测集（{n} 条）依次跑 4 种检索/生成配置，验证混合检索 + 重排相对纯向量、无 RAG 的增益。".format(
            n=next(iter(runs.values()))["summary"]["eval_size"]
        ),
        "",
        "## 总览",
        "",
        "| 指标 \\ 模式 | " + " | ".join(f"`{m}`" for m in runs) + " |",
        "| --- | " + " | ".join("---" for _ in runs) + " |",
    ]

    def row(label: str, key: str, suffix: str = "%"):
        cells = []
        for m in runs:
            v = runs[m]["summary"]["overall"].get(key)
            if v is None:
                cells.append("n/a")
            else:
                cells.append(f"{v}{suffix}")
        return f"| {label} | " + " | ".join(cells) + " |"

    lines += [
        row("综合通过率", "pass_rate"),
        row("Retrieval any-hit @5", "retrieval_any_hit_rate"),
        row("Retrieval top-1 hit", "retrieval_top1_hit_rate"),
        row("商品标签覆盖率", "tag_coverage_rate"),
        row("约束合规率", "constraint_ok_rate"),
        row("拒答正确率", "refuse_rate"),
        row("反问正确率", "ask_back_rate"),
        row("句级 Groundedness", "grounded_sentence_rate"),
    ]
    lines += ["", "## 解读模板（用于答辩）", ""]
    lines += [
        "- **no_rag vs vector_only**：体现 RAG 必要性——无知识库时模型会编造商品/参数，"
        "标签覆盖率与 groundedness 都会大幅下降。",
        "- **vector_only vs hybrid**：BM25 补足字面命中（价格数字、品牌名等），"
        "对约束类查询的 retrieval@5 提升明显。",
        "- **hybrid vs hybrid_rerank**：cross-encoder 把语义最相关的 chunk 拉到 top-1，"
        "top-1 hit rate 提升说明重排有效。",
    ]
    out_md.write_text("\n".join(lines), encoding="utf-8")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base-url", default="http://127.0.0.1:8000")
    ap.add_argument("--k", type=int, default=5)
    ap.add_argument(
        "--mode",
        choices=["vector_only", "hybrid", "hybrid_rerank", "no_rag"],
        default="hybrid_rerank",
        help="检索/生成模式",
    )
    ap.add_argument("--no-groundedness", action="store_true", help="跳过 groundedness 计算（更快）")
    ap.add_argument("--ablation", action="store_true", help="一次性跑 4 种 mode 出对比表")
    args = ap.parse_args()

    RESULTS_DIR.mkdir(parents=True, exist_ok=True)
    do_g = not args.no_groundedness
    ts = dt.datetime.now().strftime("%Y%m%d-%H%M%S")

    if args.ablation:
        modes = ["no_rag", "vector_only", "hybrid", "hybrid_rerank"]
        runs: dict[str, dict] = {}
        for m in modes:
            print(f"\n{'='*60}\n>>> 跑 mode={m}\n{'='*60}")
            out = evaluate(args.base_url, mode=m, k=args.k, do_groundedness=do_g)
            out_md = RESULTS_DIR / f"run-{ts}-{m}.md"
            out_jsonl = RESULTS_DIR / f"run-{ts}-{m}.jsonl"
            write_report(out, out_md, out_jsonl)
            print(f"  报告: {out_md}")
            runs[m] = out
        ablation_md = RESULTS_DIR / f"ablation-{ts}.md"
        write_ablation_report(runs, ablation_md)
        print(f"\nAblation 对比表: {ablation_md}")
        return

    out = evaluate(args.base_url, mode=args.mode, k=args.k, do_groundedness=do_g)
    out_md = RESULTS_DIR / f"run-{ts}-{args.mode}.md"
    out_jsonl = RESULTS_DIR / f"run-{ts}-{args.mode}.jsonl"
    write_report(out, out_md, out_jsonl)

    print()
    print("=" * 60)
    print(f"总体指标 (mode={args.mode})")
    print("=" * 60)
    o = out["summary"]["overall"]
    for k, v in o.items():
        print(f"  {k}: {v}")
    print()
    print(f"报告: {out_md}")
    print(f"原始: {out_jsonl}")


if __name__ == "__main__":
    main()
