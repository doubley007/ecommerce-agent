"""
场景化组合推荐 — 场景识别 + 子类目拆解。

职责：
  1) 把一句话用户输入识别成场景类型 SceneType（trip / gift / daily_routine / workout / null）
  2) 抽场景属性：destination / occasion / budget_total / gender_hint
  3) 把场景拆成 2~4 个子类目检索 query（如"三亚度假" → ["防晒霜", "帽子", "短袖T恤", "背包"]）

设计原则：
  - 子类目要贴向 data/products.jsonl 里实际存在的 sub_category，否则召回为 0。
    本模块的 _SCENE_TEMPLATES 是离线整理过的"子类目 → 实际数据集 sub_category"映射表，
    维护时建议先 grep sub_category 字段确认覆盖再加。
  - 场景识别用关键词正则就够了，不调 LLM —— 一是首响要快，二是规则更可解释。
    LLM 后面只用于"把多组检索结果编排成搭配"，不参与场景判定。
  - 总预算抽取直接复用 constraints 模块的正则口径（≤ X 元 / X 元以内 / 不超过 X），
    保留与单品推荐同一套约束语义，避免双轨。
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field
from typing import Literal

from .constraints import _PRICE_MAX_RE, _PRICE_RANGE_RE


SceneType = Literal["trip", "gift", "daily_routine", "workout"]


# ---------- 子类目模板：场景 → 子类目查询列表 ----------
#
# 每条子类目同时给一个 "label"（前端品类徽标）和 "query"（送给 RAG 的检索词）。
# query 用通俗中文，BM25/向量双路都能打中；label 用更短的 UI 友好字符串。
#
# 模板里 query 故意做得"具象"（如"防晒霜"而不是"护肤品"）— 这样 hybrid 检索时
# top-k 大概率就只剩同类目，避免不同子类目互相挤位次。

@dataclass
class SubQuery:
    label: str   # UI 显示的品类徽标，如 "防晒"
    query: str   # 送给 retriever 的查询词

    def to_dict(self) -> dict:
        return {"label": self.label, "query": self.query}


# 海岛/海滩度假
_TRIP_BEACH = [
    SubQuery("防晒", "防晒霜 SPF 高倍防晒"),
    SubQuery("T恤", "速干 短袖T恤 透气"),
    SubQuery("帽子", "遮阳帽 户外帽子 防晒"),
    SubQuery("背包", "出行 背包 旅行 轻便"),
]

# 户外徒步 / 登山
_TRIP_HIKE = [
    SubQuery("徒步鞋", "徒步鞋 登山鞋 户外"),
    SubQuery("户外裤", "户外裤 速干 防风"),
    SubQuery("背包", "登山包 户外背包 大容量"),
    SubQuery("功能饮料", "功能饮料 运动补给 能量"),
]

# 一般出行 / 城市旅游
_TRIP_CITY = [
    SubQuery("背包", "背包 通勤 出行 旅行"),
    SubQuery("T恤", "短袖T恤 透气 日常穿搭"),
    SubQuery("帽子", "棒球帽 鸭舌帽 出行"),
    SubQuery("方便食品", "方便食品 路上吃 速食"),
]

# 送礼 — 美妆护肤礼盒方向
_GIFT_BEAUTY = [
    SubQuery("精华", "精华液 抗老 保湿 礼盒"),
    SubQuery("面霜", "保湿面霜 修护 高端"),
    SubQuery("眼霜", "眼霜 抗皱 紧致"),
    SubQuery("唇釉", "唇釉 口红 显气色"),
]

# 送礼 — 数码方向
_GIFT_DIGITAL = [
    SubQuery("耳机", "真无线耳机 降噪 高端"),
    SubQuery("平板", "平板电脑 便携 高性能"),
    SubQuery("智能手机", "智能手机 旗舰 拍照"),
    SubQuery("背包", "笔记本电脑 包 商务"),
]

# 送礼 — 食品/年货方向
_GIFT_FOOD = [
    SubQuery("茶饮", "茶 礼盒 高端"),
    SubQuery("咖啡", "精品咖啡 礼盒 挂耳"),
    SubQuery("坚果", "坚果 零食 礼盒"),
    SubQuery("牛奶", "牛奶 高端 营养"),
]

# 日常护肤搭配
_DAILY_SKINCARE = [
    SubQuery("洁面", "洁面 洗面奶 温和"),
    SubQuery("化妆水", "化妆水 爽肤水 保湿"),
    SubQuery("精华", "精华 修护 抗老"),
    SubQuery("面霜", "面霜 保湿 锁水"),
]

# 日常通勤穿搭
_DAILY_OUTFIT = [
    SubQuery("T恤", "短袖T恤 通勤 简约"),
    SubQuery("卫衣", "卫衣 日常 春秋"),
    SubQuery("跑鞋", "运动鞋 跑步鞋 日常通勤"),
    SubQuery("背包", "背包 通勤 双肩"),
]

# 跑步训练
_WORKOUT_RUN = [
    SubQuery("跑鞋", "跑步鞋 缓震 轻量 日常训练"),
    SubQuery("T恤", "速干 短袖T恤 透气 运动"),
    SubQuery("运动短裤", "运动短裤 速干 透气"),
    SubQuery("功能饮料", "功能饮料 电解质 补给"),
]

# 健身房力量训练
_WORKOUT_GYM = [
    SubQuery("篮球鞋", "运动鞋 训练鞋 健身房"),
    SubQuery("T恤", "速干 短袖T恤 训练"),
    SubQuery("运动长裤", "运动长裤 训练 透气"),
    SubQuery("功能饮料", "功能饮料 蛋白 补给"),
]

# 瑜伽
_WORKOUT_YOGA = [
    SubQuery("瑜伽裤", "瑜伽裤 高弹 紧身"),
    SubQuery("T恤", "速干 短袖T恤 训练"),
    SubQuery("背包", "运动背包 轻便"),
    SubQuery("功能饮料", "功能饮料 运动补给"),
]


# ---------- 关键词触发表 ----------

# 命中即视为该 scene_type；越具体的目的地优先级越高（先于通用关键词匹配）
_TRIP_KEYWORDS = (
    "三亚", "海南", "海岛", "海边", "沙滩", "马尔代夫", "巴厘岛", "普吉",
    "度假", "出行", "出差", "旅游", "旅行", "出去玩", "出门",
    "登山", "徒步", "爬山", "户外",
)
_BEACH_HINTS = ("三亚", "海南", "海边", "沙滩", "马尔代夫", "巴厘岛", "普吉", "海岛", "度假")
_HIKE_HINTS = ("登山", "徒步", "爬山", "户外", "野营")

_GIFT_KEYWORDS = ("送礼", "礼物", "礼盒", "送给", "送朋友", "送闺蜜", "送男友", "送女友",
                  "送爸妈", "送老婆", "送老公", "送老板", "送同事", "节日礼物", "生日礼物",
                  "结婚礼物", "纪念日")

_DAILY_KEYWORDS = ("日常", "日常搭配", "日常穿搭", "通勤穿搭", "上班穿", "搭配", "成套",
                   "一套", "搭一套", "穿搭", "穿什么")
_DAILY_SKIN_HINTS = ("护肤", "保养", "肌肤", "敏感肌", "干皮", "油皮", "护肤套装", "护肤搭配")

_WORKOUT_KEYWORDS = ("健身", "运动", "训练", "跑步", "夜跑", "晨跑", "跑马拉松", "马拉松",
                     "瑜伽", "撸铁", "健身房")

# gender_hint
_GENDER_FEMALE = ("送女友", "送女朋友", "送老婆", "送女生", "送闺蜜", "送女同事", "送妈妈",
                  "女生", "女士", "女款", "她的")
_GENDER_MALE = ("送男友", "送男朋友", "送老公", "送男生", "送爸爸", "送哥哥", "送弟弟",
                "男生", "男士", "男款", "他的")


# ---------- 数据结构 ----------


@dataclass
class SceneInfo:
    """场景识别结果。is_scene=False 时上层走普通单品 RAG 路径。"""
    is_scene: bool
    scene_type: SceneType | None = None
    scene_label: str = ""              # 前端展示用："🏖️ 三亚度假搭配"
    destination: str | None = None
    occasion: str | None = None
    budget_total: float | None = None
    gender_hint: str | None = None
    sub_queries: list[SubQuery] = field(default_factory=list)

    def to_dict(self) -> dict:
        return {
            "is_scene": self.is_scene,
            "scene_type": self.scene_type,
            "scene_label": self.scene_label,
            "destination": self.destination,
            "occasion": self.occasion,
            "budget_total": self.budget_total,
            "gender_hint": self.gender_hint,
            "sub_queries": [s.to_dict() for s in self.sub_queries],
        }


# ---------- 主入口 ----------


def _hit_any(text: str, keywords: tuple[str, ...]) -> str | None:
    for kw in keywords:
        if kw in text:
            return kw
    return None


def _extract_budget(text: str) -> float | None:
    """复用 constraints 的价格上限正则；同时兼容"总预算 1000"形式。
    注意 _PRICE_MAX_RE 命中两个 group（"不超过 X" / "X 以内"），取非空者。
    """
    m = _PRICE_MAX_RE.search(text)
    if m:
        for g in m.groups():
            if g:
                try:
                    return float(g)
                except ValueError:
                    pass
    # 区间："500到1000" → 上限 1000
    m2 = _PRICE_RANGE_RE.search(text)
    if m2:
        try:
            return float(m2.group(2))
        except (ValueError, IndexError):
            pass
    # "总预算 X" / "整体预算 X" 兜底
    m3 = re.search(r"(?:总预算|整体预算|套装预算|搭配预算)\s*(\d+)", text)
    if m3:
        try:
            return float(m3.group(1))
        except ValueError:
            pass
    return None


def _detect_gender(text: str) -> str | None:
    if _hit_any(text, _GENDER_FEMALE):
        return "female"
    if _hit_any(text, _GENDER_MALE):
        return "male"
    return None


def detect_scene(query: str) -> SceneInfo:
    """
    主入口：query → SceneInfo。
    is_scene=False 时调用方应继续走普通单品 RAG 流程。
    """
    q = query.strip()
    if not q:
        return SceneInfo(is_scene=False)

    budget = _extract_budget(q)
    gender = _detect_gender(q)

    # —— 场景判定：travel > gift > workout > daily ——
    # 优先级反映"哪个最像组合搭配场景"。daily 放最后，因为关键词最泛。

    if _hit_any(q, _TRIP_KEYWORDS):
        sub_queries: list[SubQuery]
        beach_hit = _hit_any(q, _BEACH_HINTS)
        hike_hit = _hit_any(q, _HIKE_HINTS)
        if beach_hit:
            sub_queries = list(_TRIP_BEACH)
            label = f"🏖️ {beach_hit}度假搭配"
            destination = beach_hit
            occasion = "海岛度假"
        elif hike_hit:
            sub_queries = list(_TRIP_HIKE)
            label = "🥾 户外徒步装备"
            destination = None
            occasion = "户外徒步"
        else:
            sub_queries = list(_TRIP_CITY)
            label = "✈️ 出行装备搭配"
            destination = None
            occasion = "城市出行"
        return SceneInfo(
            is_scene=True, scene_type="trip", scene_label=label,
            destination=destination, occasion=occasion,
            budget_total=budget, gender_hint=gender,
            sub_queries=sub_queries,
        )

    if _hit_any(q, _GIFT_KEYWORDS):
        # 送礼细分：含数码关键词 → 数码；含食品/茶/咖啡 → 食品；其余默认美妆
        if any(kw in q for kw in ("耳机", "手机", "平板", "笔记本", "数码", "电子")):
            sub_queries = list(_GIFT_DIGITAL); label = "🎁 数码礼物搭配"
        elif any(kw in q for kw in ("茶", "咖啡", "零食", "坚果", "酒", "牛奶", "礼盒食品", "年货")):
            sub_queries = list(_GIFT_FOOD); label = "🎁 礼盒食品搭配"
        else:
            sub_queries = list(_GIFT_BEAUTY); label = "🎁 美妆礼盒搭配"
        return SceneInfo(
            is_scene=True, scene_type="gift", scene_label=label,
            destination=None, occasion="送礼",
            budget_total=budget, gender_hint=gender,
            sub_queries=sub_queries,
        )

    if _hit_any(q, _WORKOUT_KEYWORDS):
        if any(kw in q for kw in ("跑步", "夜跑", "晨跑", "马拉松")):
            sub_queries = list(_WORKOUT_RUN); label = "🏃 跑步训练装备"
            occasion = "跑步训练"
        elif any(kw in q for kw in ("瑜伽",)):
            sub_queries = list(_WORKOUT_YOGA); label = "🧘 瑜伽运动搭配"
            occasion = "瑜伽"
        else:
            sub_queries = list(_WORKOUT_GYM); label = "💪 健身训练装备"
            occasion = "健身训练"
        return SceneInfo(
            is_scene=True, scene_type="workout", scene_label=label,
            destination=None, occasion=occasion,
            budget_total=budget, gender_hint=gender,
            sub_queries=sub_queries,
        )

    if _hit_any(q, _DAILY_KEYWORDS):
        if _hit_any(q, _DAILY_SKIN_HINTS):
            sub_queries = list(_DAILY_SKINCARE); label = "🧴 日常护肤搭配"
            occasion = "日常护肤"
        else:
            sub_queries = list(_DAILY_OUTFIT); label = "👕 日常穿搭组合"
            occasion = "日常通勤"
        return SceneInfo(
            is_scene=True, scene_type="daily_routine", scene_label=label,
            destination=None, occasion=occasion,
            budget_total=budget, gender_hint=gender,
            sub_queries=sub_queries,
        )

    return SceneInfo(is_scene=False)
