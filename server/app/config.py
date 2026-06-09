"""
集中式配置：从环境变量读取（dotenv 自动加载 server/.env）。
所有需要密钥/可调项的地方都从这里 import，不要散落硬编码。
"""

from __future__ import annotations

import os
from functools import lru_cache
from pathlib import Path

from dotenv import load_dotenv
from pydantic import BaseModel

# 项目根：ecommerce-agent/
PROJECT_ROOT = Path(__file__).resolve().parents[2]
SERVER_ROOT = Path(__file__).resolve().parents[1]

# 自动加载 server/.env（仅本地开发；生产环境应注入真实环境变量）
load_dotenv(SERVER_ROOT / ".env")


class Settings(BaseModel):
    ark_api_key: str
    ark_base_url: str
    ark_chat_model: str
    ark_embed_model: str  # D3 启用，D2 阶段允许为空
    ark_vision_model: str = ""  # D11 启用，未配置则禁用多模态接口
    # 视觉模型可能挂在另一个工作区/账号下，需要单独的 Key；为空则复用 ark_api_key
    ark_vision_api_key: str = ""

    # 火山引擎语音技术（ASR）独立网关凭据，未配置则禁用 /asr 接口
    # 走 /api/v1/auc/submit + /api/v1/auc/query 提交-轮询模式，单 x-api-key 鉴权
    volc_asr_api_key: str = ""
    volc_asr_submit_url: str = "https://openspeech.bytedance.com/api/v1/auc/submit"
    volc_asr_query_url: str = "https://openspeech.bytedance.com/api/v1/auc/query"
    volc_asr_cluster: str = "volc_auc_common"

    host: str = "0.0.0.0"
    port: int = 8000

    chroma_dir: str = "./chroma_db"

    @property
    def chroma_abs_dir(self) -> Path:
        p = Path(self.chroma_dir)
        return p if p.is_absolute() else SERVER_ROOT / p


@lru_cache(maxsize=1)
def get_settings() -> Settings:
    return Settings(
        ark_api_key=os.environ["ARK_API_KEY"],
        ark_base_url=os.environ.get("ARK_BASE_URL", "https://ark.cn-beijing.volces.com/api/v3"),
        ark_chat_model=os.environ["ARK_CHAT_MODEL"],
        ark_embed_model=os.environ.get("ARK_EMBED_MODEL", ""),
        ark_vision_model=os.environ.get("ARK_VISION_MODEL", ""),
        ark_vision_api_key=os.environ.get("ARK_VISION_API_KEY", ""),
        volc_asr_api_key=os.environ.get("VOLC_ASR_API_KEY", ""),
        host=os.environ.get("HOST", "0.0.0.0"),
        port=int(os.environ.get("PORT", "8000")),
        chroma_dir=os.environ.get("CHROMA_DIR", "./chroma_db"),
    )
