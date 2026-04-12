"""
reborn-ml — StyleConditionedSSGO 風格條件化頻譜神經算子訓練包

純 ML 訓練包，無 Minecraft / Forge 依賴。
可在 A100/H100 伺服器上獨立執行。

快速開始：
    from reborn_ml.config import TrainingConfig, A100_TRAINING_CONFIG
    from reborn_ml.training.style_trainer import RebornStyleTrainer

    trainer = RebornStyleTrainer(A100_TRAINING_CONFIG)
    params, model, history = trainer.run()
"""
from __future__ import annotations

__version__ = "0.2.0"
__author__ = "Block Reality Research"

import sys
from pathlib import Path

# ---------------------------------------------------------------------------
# 自動發現相鄰包（HYBR / BR-NeXT / brml）
#
# 策略（依序嘗試）：
#   1. 若已可匯入（已在 sys.path）→ 直接使用
#   2. 相鄰包在本包父目錄的父目錄中（monorepo 結構）→ 加入 sys.path
#   3. 都找不到 → 發出警告，不拋出例外（避免 import 時崩潰）
# ---------------------------------------------------------------------------

def _bootstrap_sibling_packages() -> None:
    """將 monorepo 相鄰包加入 sys.path。"""
    # 嘗試確認 hybr 是否已可用
    try:
        import hybr  # noqa: F401
        return  # 已在 sys.path，不需要額外設定
    except ImportError:
        pass

    # 往上找 monorepo 根目錄：reborn-ml/src/reborn_ml/__init__.py
    # → 根目錄在 __init__.py 上四層
    _here = Path(__file__).resolve()
    # 嘗試多個可能的相對路徑（安裝位置不同時均可處理）
    candidates = [
        _here.parent.parent.parent.parent,  # editable install: repo_root/reborn-ml/src/reborn_ml
        _here.parent.parent.parent,         # 其他安裝方式
    ]

    _sibling_dirs = {"HYBR": "hybr", "BR-NeXT": "brnext", "brml": "brml"}
    found_root = None

    for root in candidates:
        if all((root / pkg_dir).exists() for pkg_dir in _sibling_dirs.keys()):
            found_root = root
            break

    if found_root is None:
        import warnings
        warnings.warn(
            "reborn-ml: 無法自動發現 HYBR / BR-NeXT / brml 相鄰包。\n"
            "請確認在 monorepo 根目錄下安裝，或手動設定 PYTHONPATH：\n"
            "  export PYTHONPATH=/path/to/HYBR:/path/to/BR-NeXT:/path/to/brml:$PYTHONPATH",
            ImportWarning,
            stacklevel=2,
        )
        return

    for pkg_dir, _module_name in _sibling_dirs.items():
        pkg_path = str(found_root / pkg_dir)
        if pkg_path not in sys.path:
            sys.path.insert(0, pkg_path)


_bootstrap_sibling_packages()

# ---------------------------------------------------------------------------
# 公開 API
# ---------------------------------------------------------------------------

_HAS_JAX = False
_HAS_TRAINING = False

try:
    import jax  # noqa: F401
    import flax  # noqa: F401
    import optax  # noqa: F401
    _HAS_JAX = True
except ImportError:
    pass

if _HAS_JAX:
    try:
        from reborn_ml.config import TrainingConfig, A100_TRAINING_CONFIG  # noqa: F401
        from reborn_ml.models.style_net import (  # noqa: F401
            StyleConditionedSSGO,
            StyleEmbedding,
            StyleDiscriminator,
        )
        from reborn_ml.training.style_trainer import RebornStyleTrainer  # noqa: F401
        _HAS_TRAINING = True
    except Exception:
        pass  # 允許部分匯入失敗（如 HYBR 未安裝時）

__all__ = [
    "__version__",
    "_HAS_JAX",
    "_HAS_TRAINING",
]

if _HAS_TRAINING:
    __all__ += [
        "TrainingConfig",
        "A100_TRAINING_CONFIG",
        "StyleConditionedSSGO",
        "StyleEmbedding",
        "StyleDiscriminator",
        "RebornStyleTrainer",
    ]
