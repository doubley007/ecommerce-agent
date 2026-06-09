"""
火山引擎"豆包·语音识别"（录音文件识别）客户端。

不走 Ark OpenAI 兼容协议，独立网关：openspeech.bytedance.com
鉴权也独立：单 x-api-key（与 Ark 的 ark-xxx 互不通用）。

API 是 submit/query 异步两步：
  1) POST /api/v1/auc/submit  → 拿 task id
  2) POST /api/v1/auc/query   → 轮询直到 status 完成

短音频经 base64 内联在 audio.data 提交，无需对象存储。
"""

from __future__ import annotations

import asyncio
import base64
import logging

import httpx

log = logging.getLogger(__name__)

from .config import get_settings


class ASRError(RuntimeError):
    """识别失败，message 里带响应体便于排查。"""


_POLL_INTERVAL_SEC = 0.5
_POLL_TIMEOUT_SEC = 30.0


async def recognize(audio_bytes: bytes, audio_format: str = "wav", sample_rate: int = 16000) -> str:
    """
    录音文件识别。短音频 ≤60s 直传 base64 即可。

    audio_format: "wav" | "mp3" | "ogg"，需与客户端实际编码一致
    sample_rate: 采样率 Hz（客户端走 16000）
    """
    s = get_settings()
    if not s.volc_asr_api_key:
        raise ASRError("ASR 未配置：在 server/.env 中填入 VOLC_ASR_API_KEY")

    headers = {
        "x-api-key": s.volc_asr_api_key,
        "Content-Type": "application/json",
    }

    submit_payload = {
        "app": {"cluster": s.volc_asr_cluster},
        "user": {"uid": "shopguide-android"},
        "audio": {
            "data": base64.b64encode(audio_bytes).decode("ascii"),
            "format": audio_format,
            "rate": sample_rate,
        },
        "additions": {
            "language": "zh-CN",
            "use_itn": "True",
            "with_speaker_info": "False",
        },
    }

    async with httpx.AsyncClient(timeout=30.0) as cli:
        # —— 1) submit
        try:
            r = await cli.post(s.volc_asr_submit_url, headers=headers, json=submit_payload)
        except httpx.HTTPError as e:
            raise ASRError(f"ASR submit 网络异常：{e}") from e
        if r.status_code != 200:
            raise ASRError(f"ASR submit HTTP {r.status_code}：{r.text[:200]}")
        try:
            sub = r.json()
        except Exception as e:
            raise ASRError(f"ASR submit 响应不是 JSON：{r.text[:200]}") from e

        task_id = (sub.get("resp") or sub).get("id") or sub.get("id")
        if not task_id:
            raise ASRError(f"ASR submit 未返回 task id：{sub}")

        # —— 2) poll query
        # 火山 v1 实测状态码（与文档措辞略不同，以下以实际响应为准）：
        #   2000 = 中间态（如 "Aed is finished. The next step is asr"），text 还为空，继续轮询
        #   1000 = 成功（msg=Success，text 已就绪），直接返回
        #   其它 = 失败
        query_payload = {"appid": "", "cluster": s.volc_asr_cluster, "id": task_id}
        intermediate_codes = {2000, "2000"}
        success_codes = {1000, "1000"}
        elapsed = 0.0
        while elapsed < _POLL_TIMEOUT_SEC:
            await asyncio.sleep(_POLL_INTERVAL_SEC)
            elapsed += _POLL_INTERVAL_SEC
            try:
                qr = await cli.post(s.volc_asr_query_url, headers=headers, json=query_payload)
            except httpx.HTTPError as e:
                raise ASRError(f"ASR query 网络异常：{e}") from e
            if qr.status_code != 200:
                raise ASRError(f"ASR query HTTP {qr.status_code}：{qr.text[:200]}")
            try:
                q = qr.json()
            except Exception as e:
                raise ASRError(f"ASR query 响应不是 JSON：{qr.text[:200]}") from e

            resp = q.get("resp") or q
            code = resp.get("code")
            text = resp.get("text") or ""
            log.debug("[asr] poll t=%.1fs code=%s msg=%s text=%r", elapsed, code, resp.get("message"), text)
            if code in success_codes and text:
                return text.strip()
            if code in intermediate_codes:
                continue
            if code in success_codes:
                # success 但 text 暂空，再轮一次
                continue
            if code is None:
                continue
            raise ASRError(f"ASR 识别失败：code={code} msg={resp.get('message')} resp={resp}")
        raise ASRError(f"ASR 超时（{_POLL_TIMEOUT_SEC}s 仍未返回结果）：task_id={task_id}")
