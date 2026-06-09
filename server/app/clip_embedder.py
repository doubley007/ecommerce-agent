"""
中文 CLIP 图像 / 文本编码器（OFA-Sys/chinese-clip-vit-base-patch16）。

用途：把"用户拍的图"和"商品库主图"映射到同一向量空间，做图×图检索。
对比 D11-D13 的"VLM 看图说关键词→文本 RAG"链路，CLIP 是真正的多模态语义表示——
不依赖文本桥接，直接对像素做 contrastive 表征。

懒加载：模块导入时不下模型，第一次调 encode_image 才加载（首次 ~600MB / 5min）。
"""

from __future__ import annotations

import io
import logging
from pathlib import Path
from threading import Lock
from typing import Iterable

import numpy as np

log = logging.getLogger(__name__)

_MODEL_NAME = "OFA-Sys/chinese-clip-vit-base-patch16"

# 全局单例 + 锁，避免并发请求触发重复加载
_lock = Lock()
_model = None
_image_processor = None
_tokenizer = None
_device = None


def _ensure_loaded() -> None:
    global _model, _image_processor, _tokenizer, _device
    if _model is not None:
        return
    with _lock:
        if _model is not None:
            return
        # 延迟 import 避免顶层依赖
        import torch
        from transformers import BertTokenizer, ChineseCLIPImageProcessor, ChineseCLIPModel

        _device = "cuda" if torch.cuda.is_available() else "cpu"
        log.info("[clip] loading %s on %s ...", _MODEL_NAME, _device)
        # transformers 5.x 下 ChineseCLIPProcessor.from_pretrained 会因仓库
        # preprocessor_config.json 缺 image_processor_type 报错；手动分别加载
        # ChineseCLIPImageProcessor + BertTokenizer 绕过这个 bug。
        _image_processor = ChineseCLIPImageProcessor.from_pretrained(_MODEL_NAME)
        _tokenizer = BertTokenizer.from_pretrained(_MODEL_NAME)
        _model = ChineseCLIPModel.from_pretrained(_MODEL_NAME).to(_device).eval()
        log.info("[clip] loaded")


def _load_pil(path_or_bytes):
    from PIL import Image
    if isinstance(path_or_bytes, (str, Path)):
        return Image.open(path_or_bytes).convert("RGB")
    if isinstance(path_or_bytes, (bytes, bytearray)):
        return Image.open(io.BytesIO(path_or_bytes)).convert("RGB")
    if hasattr(path_or_bytes, "convert"):  # 已是 PIL.Image
        return path_or_bytes.convert("RGB")
    raise TypeError(f"unsupported image input: {type(path_or_bytes)}")


def _unwrap_features(out):
    # transformers 5.x 下 get_{image,text}_features 返回 BaseModelOutputWithPooling，
    # 512 维投影向量在 pooler_output；旧版本直接返回张量。两种都兼容。
    if hasattr(out, "pooler_output"):
        return out.pooler_output
    return out


def encode_image(path_or_bytes) -> np.ndarray:
    """单张图编码 → 512 维 L2 归一化向量。"""
    _ensure_loaded()
    import torch

    img = _load_pil(path_or_bytes)
    inputs = _image_processor(images=img, return_tensors="pt").to(_device)
    with torch.no_grad():
        feat = _unwrap_features(_model.get_image_features(**inputs))
    feat = feat / feat.norm(dim=-1, keepdim=True)  # L2 归一化，用 cosine 时直接内积即可
    return feat[0].cpu().numpy().astype(np.float32)


def encode_images_batch(paths: Iterable, batch_size: int = 8) -> np.ndarray:
    """批量编码 → (N, 512) 矩阵。CPU 上 batch=8 比 batch=1 快约 3 倍。"""
    _ensure_loaded()
    import torch

    paths = list(paths)
    if not paths:
        return np.zeros((0, 512), dtype=np.float32)

    out = []
    for i in range(0, len(paths), batch_size):
        chunk = [_load_pil(p) for p in paths[i : i + batch_size]]
        inputs = _image_processor(images=chunk, return_tensors="pt").to(_device)
        with torch.no_grad():
            feats = _unwrap_features(_model.get_image_features(**inputs))
        feats = feats / feats.norm(dim=-1, keepdim=True)
        out.append(feats.cpu().numpy().astype(np.float32))
    return np.vstack(out)


def encode_text(text: str) -> np.ndarray:
    """文本编码 → 512 维 L2 归一化向量。当前未在主链路使用，留作后续 ablation 备选。"""
    _ensure_loaded()
    import torch

    inputs = _tokenizer([text], return_tensors="pt", padding=True, truncation=True).to(_device)
    with torch.no_grad():
        feat = _unwrap_features(_model.get_text_features(**inputs))
    feat = feat / feat.norm(dim=-1, keepdim=True)
    return feat[0].cpu().numpy().astype(np.float32)


def warmup() -> None:
    """供 FastAPI 启动钩子调用，把模型加载提前。"""
    _ensure_loaded()
