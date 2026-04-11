"""
Reborn — 生成式建築設計引擎（實驗性套件）

版本：0.1.0-exp
分支：claude/reborn-architecture-engine-YBI9k

四階段架構：
  Stage 1  VoxelMassing    — Minecraft 體素 → 佔用網格
  Stage 2  TopologyOptimizer — FNO-SIMP 拓撲最佳化
  Stage 3  StyleSkin        — 高第/札哈 SDF 風格化
  Stage 4  NurbsBridge      — SDF → STEP/NURBS 輸出

論文級創新貢獻：
  1. 解耦結構-美學最佳化（「理性骨架 + 感性皮膚」）
  2. FNO 引導 SIMP（O(N log N) 代理模型取代 O(N³) FEM）
  3. HYBR 超網路風格條件化（CP 分解頻譜域調製，零重新訓練）
  4. 體素原生 CAD 管線（Minecraft → STEP，< 60s CPU）

快速開始：
    from reborn import RebornPipeline, RebornConfig
    pipeline = RebornPipeline(RebornConfig(verbose=True))
    session  = pipeline.run_from_test("cantilever", size=16, style="gaudi")
    print(session.get_summary())
"""
from __future__ import annotations

__version__ = "0.1.0-exp"
__author__  = "Block Reality Reborn Team"

import sys
from pathlib import Path


def _setup_paths() -> None:
    """確保 brml、BR-NeXT、HYBR 均可 import。"""
    # experimental/reborn/ → Block Reality/ → repo root
    _repo_root = Path(__file__).resolve().parent.parent.parent.parent

    for pkg_dir in ("brml", "BR-NeXT", "HYBR"):
        pkg_path = str(_repo_root / pkg_dir)
        if pkg_path not in sys.path:
            sys.path.insert(0, pkg_path)


_setup_paths()

# 公開接口
from .config import (
    RebornConfig, SimPConfig, StyleConfig, NurbsConfig,
    HYBRConfig, FNOProxyConfig,
    DEFAULT_CONFIG, PAPER_CONFIG,
)
from .pipeline import RebornPipeline
from .session import RebornSession
from .stages import (
    VoxelMassing, VoxelMassingResult,
    TopologyOptimizer, TopologyResult,
    StyleSkin, StyleResult,
    NurbsBridge, NurbsResult,
)
from .models import FNOProxy, GaudiStyle, ZahaStyle, HYBRProxy

__all__ = [
    # 主管線
    "RebornPipeline", "RebornSession",
    # 設定
    "RebornConfig", "SimPConfig", "StyleConfig", "NurbsConfig",
    "HYBRConfig", "FNOProxyConfig", "DEFAULT_CONFIG", "PAPER_CONFIG",
    # 階段
    "VoxelMassing", "VoxelMassingResult",
    "TopologyOptimizer", "TopologyResult",
    "StyleSkin", "StyleResult",
    "NurbsBridge", "NurbsResult",
    # 模型
    "FNOProxy", "GaudiStyle", "ZahaStyle", "HYBRProxy",
    # 版本
    "__version__",
]
