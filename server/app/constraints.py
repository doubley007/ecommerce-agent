"""
统一约束抽取与硬过滤。

把分散的"价格上限/排除品牌/排除属性/必含品牌"正则做成一站式管线：
  raw query  ──►  ConstraintSet  ──►  apply_to(retrieved)  ──►  filtered candidates
                       │
                       └─► 同时下发给客户端 (event=constraints)，
                           前端 ConstraintTracker 渲染成可视化 chip。

设计原则：
  1) 只做"代码可证伪"的硬约束 — 价格 / 字面排除 / 字面必含；
     更模糊的偏好（"轻便"/"清爽"等）依然交给 LLM 软对齐。
  2) 多轮累计：抽取的输入是最近 N 句 user 拼接的 query，
     避免最后一句"再便宜点的"丢前面的品类约束。
  3) 撤销机制：客户端可以发"忽略 ¥1000 上限"这类指令，
     抽取时识别"忽略|算了|不要|取消" + 既有约束语义 → 该轮跳过此约束。
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field


# ---------- 正则 ----------

# 价格上限：200以内 / 不超过200 / 预算 200
_PRICE_MAX_RE = re.compile(
    r"(?:不超过|低于|预算|不超|少于|≤|<=|<)\s*(\d+)\s*(?:元|块|¥)?"
    r"|(\d+)\s*(?:元|块|¥)?\s*(?:以内|以下|内)"
)
# 价格下限：100以上 / 至少100 / 高于100 / 起步100
_PRICE_MIN_RE = re.compile(
    r"(?:不低于|至少|高于|起步|起|≥|>=|>)\s*(\d+)\s*(?:元|块|¥)?"
    r"|(\d+)\s*(?:元|块|¥)?\s*(?:以上|起)"
)
# 价格区间：500-1000 / 500到1000 / 500至1000
_PRICE_RANGE_RE = re.compile(
    r"(\d+)\s*(?:元|块|¥)?\s*(?:[-到至~—]|到|至)\s*(\d+)\s*(?:元|块|¥)?\s*(?:之间|区间)?"
)
# 价格目标 ±20%："约 X 元 / 大概 X 元 / 差不多 X 块 / X 元左右"
_PRICE_TARGET_RE = re.compile(
    r"(?:约|大概|大约|差不多|大致)\s*(\d+)\s*(?:元|块|¥)?"
    r"|(\d+)\s*(?:元|块|¥)?\s*(?:左右|上下)"
)
PRICE_TARGET_TOLERANCE = 0.2  # ±20%

# 属性排除（成分/特征不要含 X）：抓 1~4 字
# "不含X / 不要含X" 优先匹配，X 后面常跟"的/，"等收尾词，用前瞻断尾
_EXCL_ATTR_RE = re.compile(
    r"(?:不含|不要含|无)\s*([一-龥A-Za-z]{1,4})(?=的|，|,|。|！|!|\s|$)"
)

# 品牌排除（不要 X / 除了 X / 排除 X / 不喜欢 X / 讨厌 X）
# 排除词最多 4 字。非贪婪 `{2,4}?` 避免把后面的"的"也吃进 token。
_EXCL_BRAND_RE = re.compile(
    r"(?:不要|除了|排除|不喜欢|讨厌)\s*([一-龥A-Za-z]{2,4}?)(?=的|还|和|或|也|吧|了|呢|啊|，|,|。|！|!|\s|$)"
)

# 必含品牌（指定 X / 只要 X / 想要 X / 我要 X 的）。
# 不抓单字"要"，避免"不要"前缀误命中；后面必须跟"的/品牌"
_REQUIRED_BRAND_RE = re.compile(
    r"(?:指定|只要|想要|我要)\s*([一-龥A-Za-z]{2,5})\s*(?:的|品牌)"
)

# 取消/忽略既有约束 — 命中则视为本轮重置该类约束
# 价格类："不限价格 / 不限预算 / 算了不限价 / 价格随便 / 多少钱都行 / 不在意价格"
_CANCEL_PRICE_RE = re.compile(
    r"(?:不限价格|不限预算|不要预算|不要价格|算了不限价|取消价格|忽略价格|"
    r"价格随便|价格不限|不在意价格|多少钱都行|多少钱都可以|不管价格|不考虑价格)"
)
# 品牌类："不限品牌 / 任何品牌 / 不挑品牌 / 什么品牌都行"
_CANCEL_BRAND_RE = re.compile(
    r"(?:不限品牌|任何品牌|不挑品牌|什么品牌都行|品牌不限|不在意品牌|不管品牌|不考虑品牌)"
)
_CANCEL_EXCLUDE_RE = re.compile(r"(?:可以接受|不排除|算了.*?也行)")

# 相对降价表达 — 命中后若客户端传入 prior_price_max，把上限按系数压低
_PRICE_DOWN_RE = re.compile(r"(?:再便宜|更便宜|再低|再降|便宜点|便宜些|实惠点)")
# 相对涨价（不常见但要兜底，避免被降价误抓） — 命中则跳过自动降价
_PRICE_UP_RE = re.compile(r"(?:再贵|更贵|高端点|贵点|预算高点|提高预算)")
PRICE_DOWN_RATIO = 0.7  # 每次"再便宜点"按 70% 压低
PRICE_DOWN_FLOOR = 10.0  # 最低不会压到 10 元以下
PRICE_UP_RATIO = 1.4    # "再贵点" 按 140% 抬高
PRICE_UP_CEIL = 100000.0  # 防止失控


# ---------- 品牌/品类同义词 ----------

# 用户口语 → 数据里实际存在的若干品牌名。
# 用于：
#   1) 反选——"不要日系" / "不要耐克" 时，把所有 alias 都加入排除集
#   2) 必选——"我要 Apple 的" 时，把 Apple/苹果/Apple 苹果 都视为命中
#   3) 字面过滤——商品 brand 字段或 title 含任一 alias 即视为该家族
#
# 维护原则：仅当多个商品 brand 字面差异较大、用户又常用其中之一指代时才加。
# 单字面差异（"耐克"↔"Nike"）走 _BRAND_LITERAL_ALIAS；语义群组（"日系"/"国货"）走 _BRAND_GROUP_ALIAS。
_BRAND_LITERAL_ALIAS: dict[str, list[str]] = {
    # 数码
    "苹果": ["Apple 苹果", "Apple", "苹果", "iPhone", "iPad", "Mac"],
    "apple": ["Apple 苹果", "Apple", "苹果"],
    "耐克": ["Nike", "耐克"],
    "nike": ["Nike", "耐克"],
    "阿迪": ["阿迪达斯"],
    "adidas": ["阿迪达斯"],
    "北面": ["The North Face", "北面"],
    "tnf": ["The North Face", "北面"],
    "始祖鸟": ["始祖鸟", "Arc'teryx"],
    # 美妆
    "欧莱雅": ["巴黎欧莱雅"],
    "skii": ["SK-II"],
    "sk2": ["SK-II"],
    # 食饮
    "可乐": ["可口可乐"],
}

# "日系/韩系/国货/国产/国际大牌" 这种语义群组：用户说"不要日系" 期望把若干日系品牌全过滤。
# 这些 group 里的品牌名必须严格出自数据集，否则过滤无效。
_BRAND_GROUP_ALIAS: dict[str, list[str]] = {
    "日系": ["资生堂", "SK-II", "芳珂", "珊珂", "安热沙"],
    "美系": ["雅诗兰黛", "科颜氏", "Apple 苹果", "Nike", "The North Face", "The Ordinary", "HOKA"],
    "国货": [
        "完美日记", "花西子", "珀莱雅", "薇诺娜", "方里",
        "华为", "小米", "OPPO", "vivo", "联想",
        "李宁", "安踏", "特步", "迪卡侬",
        "三只松鼠", "百草味", "良品铺子", "三顿半", "东鹏", "元气森林", "农夫山泉",
        "东方树叶", "纯甄", "金典", "蒙牛", "伊利", "康师傅", "统一", "红牛",
        "可口可乐", "李锦记", "海天",
    ],
    "国产": [
        "完美日记", "花西子", "珀莱雅", "薇诺娜", "方里",
        "华为", "小米", "OPPO", "vivo", "联想",
        "李宁", "安踏", "特步", "迪卡侬",
        "三只松鼠", "百草味", "良品铺子", "三顿半", "东鹏", "元气森林", "农夫山泉",
        "东方树叶", "纯甄", "金典", "蒙牛", "伊利", "康师傅", "统一", "红牛",
        "可口可乐", "李锦记", "海天",
    ],
    "法系": ["巴黎欧莱雅", "兰蔻", "理肤泉"],
}


def expand_brand_aliases(token: str) -> list[str]:
    """把用户输入的品牌词展开成数据集里所有可能的字面形式。
    输入"耐克" → ["Nike", "耐克"]；输入"日系" → 一组日系品牌名。
    输入未在表里 → 原样返回，让后续逻辑按字面 substring 匹配。
    """
    if not token:
        return []
    key = token.strip().lower()
    if key in _BRAND_LITERAL_ALIAS:
        return _BRAND_LITERAL_ALIAS[key]
    if token in _BRAND_LITERAL_ALIAS:
        return _BRAND_LITERAL_ALIAS[token]
    if token in _BRAND_GROUP_ALIAS:
        return _BRAND_GROUP_ALIAS[token]
    if key in _BRAND_GROUP_ALIAS:
        return _BRAND_GROUP_ALIAS[key]
    return [token]


# ---------- 数据结构 ----------

@dataclass
class ConstraintSet:
    """本轮抽取出的所有结构化约束。
    None / 空集合表示"该约束未提及"，不影响候选；
    空 list 与 None 在 UI 显示上等价（不渲染 chip）。
    """
    price_max: float | None = None
    price_min: float | None = None
    brand_excludes: list[str] = field(default_factory=list)
    attr_excludes: list[str] = field(default_factory=list)  # 暂与 brand_excludes 共用一组关键词，过滤时双路尝试
    attr_required: list[str] = field(default_factory=list)  # 正面属性约束（"防水/无糖/纯素"）— 候选必须命中
    brand_required: str | None = None
    is_compare: bool = False
    # 用户指定的对比维度（"按音质/续航对比"）— 进对比模式时用来约束 LLM 选维度
    compare_dimensions: list[str] = field(default_factory=list)

    def to_dict(self) -> dict:
        """SSE 下发用。None 字段省略以便前端 if-let 风格判断。"""
        d: dict = {}
        if self.price_max is not None:
            d["price_max"] = self.price_max
        if self.price_min is not None:
            d["price_min"] = self.price_min
        if self.brand_excludes:
            d["brand_excludes"] = self.brand_excludes
        if self.attr_excludes:
            d["attr_excludes"] = self.attr_excludes
        if self.attr_required:
            d["attr_required"] = self.attr_required
        if self.brand_required:
            d["brand_required"] = self.brand_required
        if self.is_compare:
            d["is_compare"] = True
        return d

    def is_empty(self) -> bool:
        return not self.to_dict()

    def to_prompt_notes(self) -> list[str]:
        """喂给 LLM 的硬约束告知文本，每条作为 system content 的一行。"""
        notes: list[str] = []
        if self.price_max is not None:
            notes.append(
                f"用户的价格上限是 ¥{self.price_max:.0f}，候选商品已按此硬过滤；"
                "若过滤后【候选商品】为空，必须直接告知用户暂无满足条件的商品，不得推荐超价位商品。"
            )
        if self.price_min is not None:
            notes.append(
                f"用户的价格下限是 ¥{self.price_min:.0f}，候选商品已按此硬过滤；"
                "不得推荐低于该价位的商品。"
            )
        if self.brand_excludes:
            notes.append(
                "用户明确排除以下品牌：" + " / ".join(self.brand_excludes) +
                "；候选商品已按此硬过滤，不得推荐被排除品牌。"
            )
        if self.attr_excludes:
            notes.append(
                "用户明确排除以下属性/成分：" + " / ".join(self.attr_excludes) +
                "；推荐时若商品营销描述/FAQ 命中其中任一关键词，请避开或显式声明不含该成分。"
            )
        if self.attr_required:
            notes.append(
                "用户明确要求商品具备以下属性：" + " / ".join(self.attr_required) +
                "；候选商品已按此硬过滤，不得推荐不具备该属性的商品。"
            )
        if self.brand_required:
            notes.append(
                f"用户指定品牌：{self.brand_required}；"
                "候选库中若无该品牌商品，应直接告知暂无该品牌，不得擅自推荐替代品牌。"
            )
        if self.is_compare:
            notes.append("识别到对比意图，请进入【对比模式】。")
        if self.compare_dimensions:
            notes.append(
                "用户指定的对比维度：" + " / ".join(self.compare_dimensions) +
                "；【对比模式】下 dimensions 数组必须包含上述维度（其余维度可补足到 3-5 个）。"
            )
        return notes


# ---------- 抽取 ----------

# 已知类目词，用来从"不要含酒精的洗面奶"里把"洗面奶"剔出避免被当作排除词
_CATEGORY_TOKENS = {
    "洗面奶", "面霜", "精华", "口红", "粉底", "眼影", "防晒", "面膜", "卸妆",
    "跑鞋", "篮球鞋", "运动鞋", "卫衣", "T恤", "外套", "瑜伽裤",
    "手机", "平板", "笔记本", "耳机", "音箱", "相机",
    "咖啡", "零食", "坚果", "辣条", "饼干", "牛奶",
}

# 正面属性词典：用户口语 → 在商品 marketing_description / chunk 里要搜的关键词集合。
# 用法：用户说"我要防水的"，就要求候选 haystack 含 防水/防泼水/IPX 任一。
# 维护原则：仅放广义属性，避免把推荐变得过于刚性。
_ATTR_REQUIRED_ALIAS: dict[str, list[str]] = {
    "防水": ["防水", "防泼水", "IPX", "ipx"],
    "无糖": ["无糖", "0糖", "0卡", "零糖"],
    "低糖": ["低糖", "少糖"],
    "纯素": ["纯素", "素食", "vegan", "Vegan"],
    "无酒精": ["无酒精", "不含酒精", "0酒精"],
    "无香精": ["无香精", "不含香精"],
    "敏感肌": ["敏感肌", "舒缓", "温和"],
    "孕妇可用": ["孕妇", "孕期"],
    "可降解": ["可降解", "环保"],
    "降噪": ["降噪", "主动降噪", "ANC"],
    "长续航": ["长续航", "长待机", "大电池"],
    "快充": ["快充", "闪充"],
    "5G": ["5G", "5g"],
    "不辣": ["不辣", "微辣"],
    "辣": ["辣"],
}

# 正面属性的"我要 X" 模式。X 限制 1-4 字防误抓。
# "想要X的" / "要X的" / "得是X" / "必须X" / "支持X" / "带X" / "有X"
_REQUIRED_ATTR_RE = re.compile(
    r"(?:想要|得是|必须|得有|要有|支持|带|有|要)\s*"
    r"([一-龥A-Za-z0-9]{1,5}?)"
    r"(?=的|！|!|，|,|。|\s|$)"
)

# 对比维度抽取：用户说 "按 X 和 Y 对比" / "对比一下 X / Y"
# 我们维护一组已知维度词，从 query 里 substring 命中。
_KNOWN_COMPARE_DIMENSIONS = [
    "音质", "续航", "降噪", "通话", "佩戴", "舒适度",
    "性价比", "价格", "性能", "拍照", "相机", "屏幕", "外观", "做工",
    "保湿", "成分", "功效", "刺激性", "温和度", "肤感", "持妆",
    "甜度", "口感", "热量", "营养",
    "防水", "重量", "鞋底", "缓震", "支撑", "透气",
]


def extract_constraints(
    query: str,
    retrieved: list[dict] | None = None,
    prior_price_max: float | None = None,
) -> ConstraintSet:
    """从拼接后的多轮 query 抽出所有结构化约束。
    retrieved 用于"对比意图"二段判定（必须有 ≥2 款候选品牌出现在 query 里）。
    prior_price_max 是"上一轮已识别的价格上限"，用于"再便宜点"这类相对降价。
    """
    cs = ConstraintSet()
    if not query:
        return cs

    cancel_price = bool(_CANCEL_PRICE_RE.search(query))
    cancel_brand = bool(_CANCEL_BRAND_RE.search(query))

    # 1) 价格区间 / 上下限 / 目标价（命中"取消价格"则全跳过）
    if not cancel_price:
        # 1.0) 价格区间："500-1000 之间" — 优先匹配，否则它的两个数会被 MAX/MIN 各自抓走
        rng = _PRICE_RANGE_RE.search(query)
        if rng:
            lo = float(rng.group(1))
            hi = float(rng.group(2))
            if lo > hi:
                lo, hi = hi, lo
            cs.price_min = lo
            cs.price_max = hi
        # 1.0.b) 目标价 ±20%：若没识别到区间，再看目标价
        if cs.price_max is None and cs.price_min is None:
            tgt_m = _PRICE_TARGET_RE.search(query)
            if tgt_m:
                num = tgt_m.group(1) or tgt_m.group(2)
                if num:
                    target = float(num)
                    cs.price_min = target * (1 - PRICE_TARGET_TOLERANCE)
                    cs.price_max = target * (1 + PRICE_TARGET_TOLERANCE)
        # 1.0.c) 单独的上限
        if cs.price_max is None:
            candidates: list[float] = []
            for m in _PRICE_MAX_RE.finditer(query):
                num = m.group(1) or m.group(2)
                if num:
                    candidates.append(float(num))
            if candidates:
                cs.price_max = min(candidates)
        # 1.0.d) 单独的下限
        if cs.price_min is None:
            candidates_min: list[float] = []
            for m in _PRICE_MIN_RE.finditer(query):
                num = m.group(1) or m.group(2)
                if num:
                    candidates_min.append(float(num))
            if candidates_min:
                cs.price_min = max(candidates_min)
        # 1.b) 相对降价 — 文本没具体数字、上一轮已知上限、说了"再便宜点"
        # 必须放在所有具体数字识别之后；只有 price_max 仍为 None 才生效
        if (
            cs.price_max is None
            and prior_price_max is not None
            and _PRICE_DOWN_RE.search(query)
            and not _PRICE_UP_RE.search(query)
        ):
            new_max = prior_price_max * PRICE_DOWN_RATIO
            new_max = max(round(new_max / 10.0) * 10.0, PRICE_DOWN_FLOOR)
            cs.price_max = new_max
        # 1.c) 相对涨价 — "再贵点" / "提高预算"，按上一轮上限 ×1.4 抬高
        if (
            cs.price_max is None
            and prior_price_max is not None
            and _PRICE_UP_RE.search(query)
            and not _PRICE_DOWN_RE.search(query)
        ):
            new_max = prior_price_max * PRICE_UP_RATIO
            new_max = min(round(new_max / 10.0) * 10.0, PRICE_UP_CEIL)
            cs.price_max = new_max

    # 2.a) 属性排除（不含 X / 不要含 X / 无 X）
    for m in _EXCL_ATTR_RE.finditer(query):
        tok = m.group(1).strip()
        if not tok or tok in _CATEGORY_TOKENS or tok.isdigit():
            continue
        if tok in {"预算", "价格", "贵", "便宜"}:
            continue
        if tok not in cs.attr_excludes:
            cs.attr_excludes.append(tok)

    # 2.b) 品牌排除（不要 X / 除了 X / 不喜欢 X）— 命中"不限品牌"则跳过
    _ATTR_HINTS = {"酒精", "香精", "防腐剂", "麸质", "乳糖", "色素", "尼古丁"}
    if not cancel_brand:
        for m in _EXCL_BRAND_RE.finditer(query):
            tok = m.group(1).strip()
            if not tok or tok in _CATEGORY_TOKENS or tok.isdigit():
                continue
            if tok in {"预算", "价格", "贵", "便宜"}:
                continue
            # 若 token 跟某个已抓属性重叠（如 attr=酒精，brand 路径抓到"含酒精"），按属性归并不重复
            if any((tok == a) or (a in tok) or (tok in a) for a in cs.attr_excludes):
                continue
            if any(h in tok for h in _ATTR_HINTS):
                if tok not in cs.attr_excludes:
                    cs.attr_excludes.append(tok)
            else:
                # 群组展开（"日系/国货" → 多个具体品牌名），并去重
                aliases = expand_brand_aliases(tok)
                for alias in aliases:
                    if alias not in cs.brand_excludes:
                        cs.brand_excludes.append(alias)

    # 3) 必含品牌（命中"不限品牌"则跳过）
    if not cancel_brand:
        m = _REQUIRED_BRAND_RE.search(query)
        if m:
            brand = m.group(1).strip()
            # 排除"要 100 块的"这种数字
            if brand and not brand.isdigit() and brand not in _CATEGORY_TOKENS:
                cs.brand_required = brand

    # 4) 必含属性（"想要防水的" / "要无糖的" / "支持快充" / "得是纯素"）
    for m in _REQUIRED_ATTR_RE.finditer(query):
        tok = m.group(1).strip().lower()
        if not tok or tok.isdigit() or tok in _CATEGORY_TOKENS:
            continue
        # 价格/品牌相关词不进属性
        if tok in {"预算", "价格", "贵", "便宜", "品牌"}:
            continue
        # 仅识别在白名单里的属性词（避免"我要苹果的"被抓成属性"苹果"）
        canonical = None
        for k, aliases in _ATTR_REQUIRED_ALIAS.items():
            if tok == k or tok in (a.lower() for a in aliases):
                canonical = k
                break
        if canonical and canonical not in cs.attr_required:
            cs.attr_required.append(canonical)

    # 5) 用户指定的对比维度（从 _KNOWN_COMPARE_DIMENSIONS 子串命中）
    if has_compare_hint(query):
        for dim in _KNOWN_COMPARE_DIMENSIONS:
            if dim in query and dim not in cs.compare_dimensions:
                cs.compare_dimensions.append(dim)

    # 4) 对比意图 — 文本命中 + 候选品牌（大小写不敏感、≥2 字子串）在 query 里出现 ≥2 个不同品牌
    # 关键修复：旧版 `brand in query` 要求"完整 brand 名"+"大小写一致"。
    # 实测 brand="SK-II" 对 query "sk-II" 不命中，brand="巴黎欧莱雅" 对 query "欧莱雅" 不命中。
    # 新版：lower() 比较 + brand 任意 ≥2 字滑窗子串命中也算。
    if retrieved and has_compare_hint(query):
        q_lower = query.lower()
        seen_brands: set[str] = set()
        for r in retrieved:
            brand = (r["product"].get("brand") or "").strip().lower()
            if not brand:
                continue
            if _brand_mentioned_in(brand, q_lower):
                seen_brands.add(brand)
        if len(seen_brands) >= 2:
            cs.is_compare = True

    return cs


def _brand_mentioned_in(brand: str, query_lower: str, min_len: int = 2) -> bool:
    """品牌是否被 query 字面提及。
    完整 brand 名包含 → 命中；否则滑窗找 ≥min_len 字的子串（跳过纯标点/空白），任一子串在 query 中也算命中。
    例：brand='巴黎欧莱雅' / query='欧莱雅' → 通过 '欧莱雅' 子串命中。
    """
    if not brand:
        return False
    if brand in query_lower:
        return True
    n = len(brand)
    for L in range(min_len, n + 1):
        for i in range(0, n - L + 1):
            seg = brand[i:i + L]
            if not any(c.isalnum() for c in seg):
                continue
            if seg in query_lower:
                return True
    return False


def has_compare_hint(query: str) -> bool:
    """对比意图初判（只看文本，不依赖检索结果）。用于扩 top_k 召回池。"""
    return bool(re.search(r"(对比|比较|区别|差别|哪个更|哪款更|哪个好|哪款好|\bvs\b|VS)", query))


# ---------- 硬过滤 ----------

def apply_constraints(retrieved: list[dict], cs: ConstraintSet) -> list[dict]:
    """按约束依次硬过滤候选。先价格区间、再品牌排除、再属性排除、再正面属性、再品牌必含。"""
    items = retrieved
    if cs.price_max is not None:
        items = _filter_by_price(items, cs.price_max)
    if cs.price_min is not None:
        items = _filter_by_price_min(items, cs.price_min)
    if cs.brand_excludes:
        items = _filter_by_brand_excludes(items, cs.brand_excludes)
    if cs.attr_excludes:
        items = _filter_by_attr_excludes(items, cs.attr_excludes)
    if cs.attr_required:
        items = _filter_by_attr_required(items, cs.attr_required)
    if cs.brand_required:
        items = _filter_by_brand_required(items, cs.brand_required)
    return items


def _filter_by_price(retrieved: list[dict], price_max: float) -> list[dict]:
    kept: list[dict] = []
    for r in retrieved:
        p = r["product"]
        price = p.get("base_price")
        if price is None:
            sku_prices = [
                sku.get("price") for sku in p.get("skus", []) if sku.get("price") is not None
            ]
            price = min(sku_prices) if sku_prices else None
        if price is not None and price <= price_max:
            kept.append(r)
    return kept


def _filter_by_price_min(retrieved: list[dict], price_min: float) -> list[dict]:
    kept: list[dict] = []
    for r in retrieved:
        p = r["product"]
        price = p.get("base_price")
        if price is None:
            sku_prices = [
                sku.get("price") for sku in p.get("skus", []) if sku.get("price") is not None
            ]
            # 下限用最大 SKU 价：如果该商品最贵的 SKU 也低于 min，整商品丢弃
            price = max(sku_prices) if sku_prices else None
        if price is not None and price >= price_min:
            kept.append(r)
    return kept


def _filter_by_brand_excludes(retrieved: list[dict], excludes: list[str]) -> list[dict]:
    """品牌字面包含被排除词则剔除。
    每个排除词额外做大小写不敏感比较，覆盖 "Nike" vs "nike" 这类情况。
    扩展过的群组（"日系" → 多个品牌）由 extract_constraints 阶段已展开成具体品牌名。
    """
    kept: list[dict] = []
    for r in retrieved:
        brand = (r["product"].get("brand") or "").strip()
        title = (r["product"].get("title") or "").strip()
        b_low = brand.lower()
        t_low = title.lower()
        hit = False
        for ex in excludes:
            if not ex:
                continue
            ex_low = ex.lower()
            if ex in brand or ex in title or ex_low in b_low or ex_low in t_low:
                hit = True
                break
        if not hit:
            kept.append(r)
    return kept


def _filter_by_attr_required(retrieved: list[dict], required: list[str]) -> list[dict]:
    """正面属性约束：候选商品的 marketing_description / matched_chunks 必须命中
    所有 required 属性（每个属性按 alias 任一即可）。
    缺一个就剔除——这是"硬要求"，避免推荐没有该卖点的商品。
    """
    kept: list[dict] = []
    for r in retrieved:
        p = r["product"]
        haystack = (p.get("marketing_description") or "")
        for chunk in r.get("matched_chunks", []):
            haystack += " " + (chunk.get("text") or "")
        haystack += " " + (p.get("title") or "")
        haystack_low = haystack.lower()
        ok = True
        for canonical in required:
            aliases = _ATTR_REQUIRED_ALIAS.get(canonical, [canonical])
            if not any(a in haystack or a.lower() in haystack_low for a in aliases):
                ok = False
                break
        if ok:
            kept.append(r)
    return kept


def _filter_by_attr_excludes(retrieved: list[dict], excludes: list[str]) -> list[dict]:
    """属性排除：在 marketing_description / matched_chunks 里全文搜，命中即剔除。
    对比品牌排除更宽松——属性词通常埋在卖点描述里。
    """
    kept: list[dict] = []
    for r in retrieved:
        p = r["product"]
        haystack = (p.get("marketing_description") or "")
        for chunk in r.get("matched_chunks", []):
            haystack += " " + (chunk.get("text") or "")
        hit = False
        for ex in excludes:
            if ex and ex in haystack:
                hit = True
                break
        if not hit:
            kept.append(r)
    return kept


def suggest_relaxations(
    raw_retrieved: list[dict],
    cs: ConstraintSet,
) -> list[str]:
    """当 apply_constraints 后 0 件，做"对比留 1 去 1"分析：
    每次只去掉一个约束看还剩几件，给用户具体的"放宽哪条"建议。

    返回若干条人话，例如 ["放宽价格上限到 ¥800 还有 3 款", "去掉品牌排除还有 5 款"]。
    没有可用建议（所有单约束都过不去）时返回空。

    入参 raw_retrieved 必须是 apply_constraints 之前的原始候选。
    """
    suggestions: list[str] = []

    def try_count(cs2: ConstraintSet) -> int:
        return len(apply_constraints(raw_retrieved, cs2))

    # 价格上限放宽：试着用候选最低价做新上限
    if cs.price_max is not None and raw_retrieved:
        cs2 = ConstraintSet(
            brand_excludes=cs.brand_excludes,
            attr_excludes=cs.attr_excludes,
            attr_required=cs.attr_required,
            brand_required=cs.brand_required,
        )
        prices = []
        for r in raw_retrieved:
            p = r["product"].get("base_price")
            if p is not None:
                prices.append(p)
        if prices:
            min_price = min(prices)
            if min_price > cs.price_max:
                cs2.price_max = min_price * 1.05
                cnt = try_count(cs2)
                if cnt > 0:
                    suggestions.append(
                        f"放宽价格上限到 ¥{cs2.price_max:.0f} 后还有 {cnt} 款可选"
                    )

    if cs.brand_excludes:
        cs2 = ConstraintSet(
            price_max=cs.price_max,
            attr_excludes=cs.attr_excludes,
            attr_required=cs.attr_required,
            brand_required=cs.brand_required,
        )
        cnt = try_count(cs2)
        if cnt > 0:
            suggestions.append(f"去掉品牌排除还有 {cnt} 款可选")

    if cs.attr_excludes:
        cs2 = ConstraintSet(
            price_max=cs.price_max,
            brand_excludes=cs.brand_excludes,
            attr_required=cs.attr_required,
            brand_required=cs.brand_required,
        )
        cnt = try_count(cs2)
        if cnt > 0:
            suggestions.append(
                "放宽属性排除（" + " / ".join(cs.attr_excludes) + f"）还有 {cnt} 款可选"
            )

    if cs.attr_required:
        cs2 = ConstraintSet(
            price_max=cs.price_max,
            brand_excludes=cs.brand_excludes,
            attr_excludes=cs.attr_excludes,
            brand_required=cs.brand_required,
        )
        cnt = try_count(cs2)
        if cnt > 0:
            suggestions.append(
                "放宽属性要求（" + " / ".join(cs.attr_required) + f"）还有 {cnt} 款可选"
            )

    if cs.brand_required:
        cs2 = ConstraintSet(
            price_max=cs.price_max,
            brand_excludes=cs.brand_excludes,
            attr_excludes=cs.attr_excludes,
            attr_required=cs.attr_required,
        )
        cnt = try_count(cs2)
        if cnt > 0:
            suggestions.append(
                f"换个品牌（不要求 {cs.brand_required}）还有 {cnt} 款可选"
            )

    return suggestions


def _filter_by_brand_required(retrieved: list[dict], required: str) -> list[dict]:
    """必含品牌：用户口语 → expand_brand_aliases → 任一 alias 命中即保留。
    例：用户说"我要 Apple 的"，候选 brand="Apple 苹果" 也要保留。
    """
    aliases = expand_brand_aliases(required)
    kept: list[dict] = []
    for r in retrieved:
        brand = (r["product"].get("brand") or "").strip()
        title = (r["product"].get("title") or "").strip()
        b_low = brand.lower()
        t_low = title.lower()
        for alias in aliases:
            a_low = alias.lower()
            if alias in brand or alias in title or a_low in b_low or a_low in t_low:
                kept.append(r)
                break
    return kept
