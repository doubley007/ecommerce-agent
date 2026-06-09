"""
本地 Embedding 封装。

选型：BAAI/bge-base-zh-v1.5
  - 中文检索 SOTA（C-MTEB 榜单常年 Top）
  - 768 维，平衡精度与体积（~400MB）
  - 离线运行，规避火山方舟 RPM 限流

为什么不用豆包 embedding：
  - 100 条商品 1199 chunk 完全用不上重型 embedding，本地小模型够用且更准
  - 演示现场零网络依赖，更稳
  - 多模态加分项（拍照找货）届时另接 vision 通道，与本地文本通道独立
"""

from __future__ import annotations

from functools import lru_cache

from sentence_transformers import SentenceTransformer

MODEL_NAME = "BAAI/bge-base-zh-v1.5"
# bge 中文模型推荐给 query 加这个前缀，能提升检索准确率
QUERY_PREFIX = "为这个句子生成表示以用于检索相关文章："


@lru_cache(maxsize=1)
def get_model() -> SentenceTransformer:
    return SentenceTransformer(MODEL_NAME)


def embed_documents(texts: list[str]) -> list[list[float]]:
    """对文档/chunk 做向量化。文档侧不需要前缀。"""
    model = get_model()
    vectors = model.encode(texts, normalize_embeddings=True, show_progress_bar=False)
    return vectors.tolist()


def embed_query(text: str) -> list[float]:
    """对用户 query 做向量化，加 BGE 推荐的检索前缀。"""
    model = get_model()
    vectors = model.encode([QUERY_PREFIX + text], normalize_embeddings=True, show_progress_bar=False)
    return vectors[0].tolist()
