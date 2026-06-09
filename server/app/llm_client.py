"""
火山方舟豆包 LLM 客户端。

火山方舟兼容 OpenAI 协议，所以直接用 openai SDK 切换 base_url。
对外暴露:
    - chat_once(messages)        : 一次性返回完整回复，调试/评测用
    - chat_stream(messages)      : 异步生成器，逐 token 流式吐出（驱动 SSE）
"""

from __future__ import annotations

from collections.abc import AsyncIterator

from openai import AsyncOpenAI

from .config import get_settings


_client: AsyncOpenAI | None = None
_vision_client: AsyncOpenAI | None = None


def get_client() -> AsyncOpenAI:
    """文本模型 client：用 ARK_API_KEY（公司账号）调 ARK_CHAT_MODEL。"""
    global _client
    if _client is None:
        s = get_settings()
        _client = AsyncOpenAI(api_key=s.ark_api_key, base_url=s.ark_base_url)
    return _client


def get_vision_client() -> AsyncOpenAI:
    """视觉模型 client：endpoint 可能挂在另一个账号下，单独走 ARK_VISION_API_KEY；为空时复用主 Key。"""
    global _vision_client
    if _vision_client is None:
        s = get_settings()
        key = s.ark_vision_api_key or s.ark_api_key
        _vision_client = AsyncOpenAI(api_key=key, base_url=s.ark_base_url)
    return _vision_client


async def chat_once(messages: list[dict], temperature: float = 0.6) -> str:
    """非流式调用，返回整段文本。用于联调与评测。"""
    s = get_settings()
    resp = await get_client().chat.completions.create(
        model=s.ark_chat_model,
        messages=messages,
        temperature=temperature,
        stream=False,
    )
    return resp.choices[0].message.content or ""


async def chat_stream(
    messages: list[dict],
    temperature: float = 0.6,
) -> AsyncIterator[str]:
    """流式调用，逐 chunk 产出文本片段（仅 delta.content，不含元数据）。"""
    s = get_settings()
    stream = await get_client().chat.completions.create(
        model=s.ark_chat_model,
        messages=messages,
        temperature=temperature,
        stream=True,
    )
    async for chunk in stream:
        if not chunk.choices:
            continue
        delta = chunk.choices[0].delta
        piece = getattr(delta, "content", None)
        if piece:
            yield piece


async def vision_describe(image_base64: str, hint_text: str = "") -> str:
    """
    调用豆包视觉模型，把图片转成"用于商品检索"的关键词文本。
    返回单行短文本，例如："白色无线蓝牙耳机 入耳式 圆形充电盒"。
    image_base64 期望已是 data URL 格式的纯 base64 部分（不含前缀）；MIME 默认 jpeg。
    """
    s = get_settings()
    if not s.ark_vision_model:
        raise RuntimeError("ARK_VISION_MODEL 未配置：在 server/.env 中填入豆包视觉模型 endpoint ID")

    user_text = (
        "你是商品图片识别助手。请从这张图里提取核心商品信息，输出一行用于电商检索的短关键词，"
        "格式为：[品类] [品牌（如能识别）] [关键外观/材质特征] [颜色] [使用场景]。"
        "不要说多余的话，不要列项，不要 markdown，只输出一行不超过 30 字的中文关键词。"
    )
    if hint_text:
        user_text += f" 用户补充：{hint_text}"

    resp = await get_vision_client().chat.completions.create(
        model=s.ark_vision_model,
        messages=[
            {
                "role": "user",
                "content": [
                    {"type": "image_url", "image_url": {"url": f"data:image/jpeg;base64,{image_base64}"}},
                    {"type": "text", "text": user_text},
                ],
            }
        ],
        temperature=0.2,
        stream=False,
    )
    return (resp.choices[0].message.content or "").strip()
