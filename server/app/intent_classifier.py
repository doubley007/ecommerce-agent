"""
主动澄清模块。

设计目标：
  对话首轮太短 / 缺关键属性时，不要直接把"推荐手机"硬塞给 RAG —
  那种 query 召回噪声大、推出来用户也未必想要。改为先抛 1~2 个轻量澄清问题
  + 快捷气泡选项，让用户两秒点完，再用拼好的完整 query 走 RAG。

打分语义（注意命名）：
  ambiguity_score 在本模块是"明确度分数"——越低越歧义。
  score < AMBIGUITY_THRESHOLD（默认 0.6）触发澄清，>= 阈值直接走 RAG。
  这与外部接口约定一致：调用方只关心 should_clarify(query) 这个布尔。

打分维度（满分 1.0）：
  - 词数 >= 6（含中文字符）          +0.3
  - 命中品类/子品类关键词             +0.3
  - 命中价格/预算表达                 +0.2
  - 命中场景/属性词（油皮/通勤/送礼） +0.2

阈值 / 关键词均集中在本文件顶部，方便答辩演示时调。
"""

from __future__ import annotations

import json
import os
import re
from dataclasses import dataclass

from .llm_client import chat_once

# ---------- 可调参数 ----------

# 明确度低于此值触发澄清。可通过环境变量 AMBIGUITY_THRESHOLD 覆盖。
AMBIGUITY_THRESHOLD: float = float(os.environ.get("AMBIGUITY_THRESHOLD", "0.6"))

# 词数下限：低于此 → 词数维度给 0 分
MIN_TOKEN_COUNT: int = 6

# ---------- 关键词词典（与 data/products.jsonl 实际品类对齐） ----------

# 品类 / 子品类关键词。命中任意一个即视为"已给具体品类"。
_CATEGORY_KEYWORDS: tuple[str, ...] = (
    # 数码电子
    "手机", "智能手机", "iPhone", "安卓", "平板", "iPad", "笔记本", "笔记本电脑", "电脑",
    "耳机", "蓝牙耳机", "无线耳机", "真无线", "降噪耳机", "音箱",
    # 美妆护肤
    "面霜", "精华", "化妆水", "爽肤水", "乳液", "洁面", "洗面奶", "卸妆", "防晒", "粉底",
    "口红", "唇釉", "眉笔", "睫毛", "眼霜", "蜜粉", "腮红", "护肤", "彩妆",
    # 服饰运动
    "T恤", "短袖", "卫衣", "外套", "瑜伽裤", "户外裤", "裤子", "帽子", "背包",
    "跑鞋", "跑步鞋", "篮球鞋", "徒步鞋", "运动鞋", "鞋",
    # 食品饮料
    "咖啡", "速溶咖啡", "牛奶", "茶", "茶饮", "饮料", "功能饮料", "碳酸饮料",
    "坚果", "零食", "糕点", "辣条", "方便食品", "速食", "调味品",
)

# 价格/预算表达。命中即视为"给了价格区间或上限"。
_PRICE_PATTERN: re.Pattern[str] = re.compile(
    r"(?:不超过|低于|预算|不超|少于|大概|约|差不多|至少|高于|起步|"
    r"以下|以内|内|以上|起|左右|上下|之间|区间|≤|<=|<|≥|>=|>)"
    r"|\d+\s*(?:元|块|¥)"
    r"|\d+\s*[-到至~—]\s*\d+"
)

# 场景 / 使用人群 / 属性偏好关键词
_SCENE_KEYWORDS: tuple[str, ...] = (
    # 肤质 / 人群
    "油皮", "干皮", "敏感肌", "混油", "混干", "学生", "男生", "女生", "孕妇",
    # 使用场景 / 用途
    "日常", "通勤", "上班", "出差", "户外", "徒步", "登山", "送礼", "礼物",
    "拍照", "游戏", "办公", "学习", "训练", "跑步", "健身", "瑜伽", "篮球",
    "约会", "上学", "上课", "旅行", "夜跑", "睡前", "晨间",
    # 抽象属性偏好
    "轻便", "便携", "续航", "降噪", "保湿", "美白", "抗老", "祛痘", "控油",
    "性价比", "高端", "便宜", "贵", "实惠", "结实", "耐用", "舒适", "好看",
)


# ---------- 数据结构 ----------


@dataclass
class ClarificationQuestion:
    """单个澄清问题。text 是问题文本，options 是 2~4 个供用户点选的快捷选项。"""
    text: str
    options: list[str]

    def to_dict(self) -> dict:
        return {"text": self.text, "options": self.options}


@dataclass
class ClarificationResult:
    """should_clarify=True 时 questions 才有意义。score 仅用于日志/调试。"""
    should_clarify: bool
    score: float
    questions: list[ClarificationQuestion]
    missing: list[str]  # 缺失维度名列表，给 LLM prompt + 调试日志用

    def to_payload(self) -> dict:
        """转成 SSE event=clarification 的 data JSON。"""
        return {
            "type": "clarification",
            "score": round(self.score, 3),
            "missing": self.missing,
            "questions": [q.to_dict() for q in self.questions],
        }


# ---------- 打分 ----------


def _count_tokens(text: str) -> int:
    """中文按字符 + 英文按单词计数。"推荐手机" = 4。"""
    cn_chars = len(re.findall(r"[一-鿿]", text))
    en_words = len(re.findall(r"[A-Za-z]+", text))
    return cn_chars + en_words


def _hit_any(text: str, keywords: tuple[str, ...]) -> bool:
    return any(kw in text for kw in keywords)


def compute_ambiguity_score(query: str) -> tuple[float, list[str]]:
    """
    返回 (clarity_score, missing_dimensions)。
    score 越低 → 越歧义；missing 列出缺失的维度名（"category"/"price"/"scene"/"length"）。
    """
    q = query.strip()
    score = 0.0
    missing: list[str] = []

    if _count_tokens(q) >= MIN_TOKEN_COUNT:
        score += 0.3
    else:
        missing.append("length")

    if _hit_any(q, _CATEGORY_KEYWORDS):
        score += 0.3
    else:
        missing.append("category")

    if _PRICE_PATTERN.search(q):
        score += 0.2
    else:
        missing.append("price")

    if _hit_any(q, _SCENE_KEYWORDS):
        score += 0.2
    else:
        missing.append("scene")

    return score, missing


def should_clarify(query: str) -> tuple[bool, float, list[str]]:
    """对外暴露的判定入口。返回 (是否需要澄清, score, 缺失维度)。"""
    score, missing = compute_ambiguity_score(query)
    return score < AMBIGUITY_THRESHOLD, score, missing


# ---------- LLM 生成澄清问题 ----------


_CLARIFY_PROMPT_TEMPLATE = """你是电商导购的对话路由助手。用户刚刚发来一句话，但信息不足以直接推荐商品。
请你针对【缺失的维度】，向用户**抛出 1~2 个最关键的澄清问题**，每个问题给 **2~4 个快捷选项**。
用户后续会点选项，因此选项必须**互斥、覆盖最常见的回答、文字短**（每个 ≤6 个汉字）。

【用户原话】
{query}

【缺失维度】
{missing}

【硬性输出格式 — 必须严格遵守】
只输出一个 JSON 对象，**不要 markdown 代码围栏，不要任何解释、问候或前言**。schema：
{{
  "questions": [
    {{
      "text": "你想用它做什么？",
      "options": ["日常通勤", "拍照旅行", "重度游戏", "学生网课"]
    }}
  ]
}}
约束：
1. questions 长度 1~2，超过 2 个的直接砍掉。
2. 每个 options 长度 2~4，必须互斥，每项 ≤6 字。
3. 问题必须紧扣【缺失维度】，已给出的信息不要再问。
4. 用中文，问题以问号结尾。
5. 选项不要写"其他/都行/随便"——这种选了等于没选。
"""


_FALLBACK_QUESTIONS: dict[str, ClarificationQuestion] = {
    "category": ClarificationQuestion(
        text="想买的是哪一类商品？",
        options=["数码电子", "美妆护肤", "服饰运动", "食品饮料"],
    ),
    "price": ClarificationQuestion(
        text="预算大概是多少？",
        options=["¥200 以内", "¥200-500", "¥500-1000", "¥1000 以上"],
    ),
    "scene": ClarificationQuestion(
        text="主要在什么场景用？",
        options=["日常通勤", "户外运动", "送礼自用", "专业重度"],
    ),
    "length": ClarificationQuestion(
        text="能再多说一些需求吗？",
        options=["要性价比", "要高端款", "要送礼", "想看新品"],
    ),
}


def _fallback_questions(missing: list[str]) -> list[ClarificationQuestion]:
    """LLM 调用失败 / 返回不合规时的兜底：从 missing 维度按顺序拿模板，最多 2 题。"""
    seen: list[ClarificationQuestion] = []
    for dim in missing:
        q = _FALLBACK_QUESTIONS.get(dim)
        if q and q.text not in {x.text for x in seen}:
            seen.append(q)
        if len(seen) >= 2:
            break
    if not seen:
        seen.append(_FALLBACK_QUESTIONS["scene"])
    return seen


def _parse_questions(raw: str) -> list[ClarificationQuestion] | None:
    """容忍 LLM 偶尔输出代码围栏 / 前后多余字符。截取首个 `{` 到末尾配平的 `}`。"""
    s = raw.strip()
    open_idx = s.find("{")
    if open_idx < 0:
        return None
    depth = 0
    in_str = False
    escape = False
    end_idx = -1
    for i in range(open_idx, len(s)):
        c = s[i]
        if escape:
            escape = False
            continue
        if c == "\\":
            escape = True
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
        return None
    try:
        obj = json.loads(s[open_idx : end_idx + 1])
    except json.JSONDecodeError:
        return None
    items = obj.get("questions") if isinstance(obj, dict) else None
    if not isinstance(items, list) or not items:
        return None
    parsed: list[ClarificationQuestion] = []
    for it in items[:2]:
        if not isinstance(it, dict):
            continue
        text = str(it.get("text", "")).strip()
        opts_raw = it.get("options")
        if not text or not isinstance(opts_raw, list):
            continue
        opts = [str(o).strip() for o in opts_raw if str(o).strip()]
        opts = [o for o in opts if len(o) <= 8][:4]
        if len(opts) < 2:
            continue
        parsed.append(ClarificationQuestion(text=text, options=opts))
    return parsed or None


async def generate_clarification(query: str, missing: list[str]) -> list[ClarificationQuestion]:
    """
    调 LLM 生成澄清问题。任何失败（网络/超时/解析错）都会兜底到模板，
    保证客户端永远能拿到至少 1 题，演示链路不阻塞。
    """
    prompt = _CLARIFY_PROMPT_TEMPLATE.format(
        query=query.strip(),
        missing=", ".join(missing) if missing else "（未具体列出，请你判断）",
    )
    try:
        # 用低温度 + 短 prompt 让模型更稳。一次性接口，不流式 — 客户端只渲染最终结果。
        raw = await chat_once(
            messages=[{"role": "user", "content": prompt}],
            temperature=0.2,
        )
        parsed = _parse_questions(raw)
        if parsed:
            return parsed
    except Exception:
        # LLM 不可用 / 网络错 — 不抛，直接兜底
        pass
    return _fallback_questions(missing)


async def maybe_build_clarification(query: str) -> ClarificationResult | None:
    """一站式入口：query → 命中阈值则返回完整 ClarificationResult，否则 None。"""
    need, score, missing = should_clarify(query)
    if not need:
        return None
    questions = await generate_clarification(query, missing)
    return ClarificationResult(
        should_clarify=True,
        score=score,
        questions=questions,
        missing=missing,
    )
