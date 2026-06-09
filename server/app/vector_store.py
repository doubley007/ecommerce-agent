"""
Chroma 向量库封装。

持久化存储路径：server/chroma_db/
集合名：products_chunks
"""

from __future__ import annotations

from functools import lru_cache

import chromadb

from .config import get_settings

COLLECTION_NAME = "products_chunks"
IMAGE_COLLECTION_NAME = "products_images"


@lru_cache(maxsize=1)
def get_chroma():
    s = get_settings()
    s.chroma_abs_dir.mkdir(parents=True, exist_ok=True)
    return chromadb.PersistentClient(path=str(s.chroma_abs_dir))


def get_or_create_collection():
    client = get_chroma()
    # cosine 距离与归一化向量配合，得分范围 [0,2]，0 最相似
    return client.get_or_create_collection(
        name=COLLECTION_NAME,
        metadata={"hnsw:space": "cosine"},
    )


def reset_collection():
    """重建集合：删除旧的、新建空的。建索引脚本调用。"""
    client = get_chroma()
    try:
        client.delete_collection(COLLECTION_NAME)
    except Exception:
        pass
    return client.create_collection(
        name=COLLECTION_NAME,
        metadata={"hnsw:space": "cosine"},
    )


def get_or_create_image_collection():
    """CLIP 图像向量集合。维度 512（chinese-clip-vit-base-patch16）。"""
    client = get_chroma()
    return client.get_or_create_collection(
        name=IMAGE_COLLECTION_NAME,
        metadata={"hnsw:space": "cosine"},
    )


def reset_image_collection():
    client = get_chroma()
    try:
        client.delete_collection(IMAGE_COLLECTION_NAME)
    except Exception:
        pass
    return client.create_collection(
        name=IMAGE_COLLECTION_NAME,
        metadata={"hnsw:space": "cosine"},
    )
