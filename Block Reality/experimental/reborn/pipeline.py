"""
pipeline.py — RebornPipeline 主協調器

端到端管線入口，協調四個階段的依序執行：
  Stage 1: VoxelMassing   → VoxelMassingResult
  Stage 2: TopologyOptimizer → TopologyResult
  Stage 3: StyleSkin      → StyleResult
  Stage 4: NurbsBridge    → NurbsResult

快速開始：
    from reborn import RebornPipeline, RebornConfig
    pipeline = RebornPipeline(RebornConfig())
    result = pipeline.run_from_test("cantilever", size=16, style="gaudi")
"""
from __future__ import annotations
import time
from pathlib import Path
from typing import Any
import numpy as np

from .config import RebornConfig
from .session import RebornSession
from .stages.voxel_massing import VoxelMassing, VoxelMassingResult
from .stages.topo_optimizer import TopologyOptimizer
from .stages.style_skin import StyleSkin
from .stages.nurbs_bridge import NurbsBridge
from .models.fno_proxy import FNOProxy


class RebornPipeline:
    """
    Reborn 生成式建築設計引擎主管線。

    設計理念：
      - 單一入口點，隱藏四個階段的協調細節
      - 支援快取：若 SIMP 已完成，可直接跳到 StyleSkin
      - 所有階段輸出持久化於 session 目錄
    """

    def __init__(self, config: RebornConfig | None = None):
        self.config = config or RebornConfig()

        # 共用 FNO 代理（避免重複載入 ONNX 模型）
        self._fno = FNOProxy(
            mode=self.config.fno.backend,
            verbose=self.config.verbose,
        )

        # 初始化各階段
        self._massstage  = VoxelMassing(self.config)
        self._topostage  = TopologyOptimizer(self.config, self._fno)
        self._stylestage = StyleSkin(self.config)
        self._nurbsstage = NurbsBridge(self.config)

    # -----------------------------------------------------------------------
    # 主要入口點
    # -----------------------------------------------------------------------

    def run_from_test(
        self,
        structure: str = "cantilever",
        size: int = 16,
        material: str = "CONCRETE",
        style: str | None = None,
        session_id: str | None = None,
    ) -> RebornSession:
        """
        從合成測試結構執行完整管線（最快的測試路徑）。

        Args:
            structure:  "cantilever" / "beam" / "tower"
            size:       網格尺寸（單邊體素數）
            material:   材料 ID（見 DefaultMaterial 枚舉）
            style:      風格覆蓋（None = 使用 config.style.mode）
            session_id: 工作階段 ID（None = 自動生成）

        Returns:
            RebornSession — 含所有中間結果
        """
        if style is not None:
            # 臨時覆蓋風格設定
            from dataclasses import replace
            self.config = replace(
                self.config,
                style=replace(self.config.style, mode=style),
            )
            self._stylestage = StyleSkin(self.config)

        session = RebornSession(self.config, session_id)
        t0 = time.time()

        # Stage 1
        if self.config.verbose:
            print(f"\n{'='*50}")
            print(f"[Reborn] 第一階段：體素量體輸入")
        massing = self._massstage.from_test_structure(structure, size, material)
        session.set_massing(massing)

        if self.config.verbose:
            summary = self._massstage.get_summary(massing)
            print(f"  網格：{summary['grid_shape']}，"
                  f"固體：{summary['n_solid']}，"
                  f"體積分率：{summary['volume_frac']}")

        return self._run_from_massing(massing, session, t0)

    def run_from_blueprint(
        self,
        blueprint_path: str | Path,
        style: str | None = None,
        session_id: str | None = None,
    ) -> RebornSession:
        """
        從 Blueprint JSON 文件執行完整管線。

        支援快取：若相同 session_id 已有 SIMP 結果，跳過拓撲最佳化。
        """
        if style is not None:
            from dataclasses import replace
            self.config = replace(
                self.config,
                style=replace(self.config.style, mode=style),
            )
            self._stylestage = StyleSkin(self.config)

        session = RebornSession(self.config, session_id)
        t0 = time.time()

        if self.config.verbose:
            print(f"\n{'='*50}")
            print(f"[Reborn] 第一階段：Blueprint 輸入 → {blueprint_path}")

        massing = self._massstage.from_blueprint_json(blueprint_path)
        session.set_massing(massing)
        return self._run_from_massing(massing, session, t0)

    def restyle(
        self,
        session: RebornSession,
        new_style: str,
    ) -> RebornSession:
        """
        對已完成 SIMP 的工作階段重新應用不同風格。

        無需重新執行 SIMP（直接使用快取的密度場）。
        """
        from dataclasses import replace
        self.config = replace(
            self.config,
            style=replace(self.config.style, mode=new_style),
        )
        self._stylestage = StyleSkin(self.config)

        if session.topology is None:
            if not session.load_topology_from_cache():
                raise RuntimeError("無法載入拓撲快取，請先執行完整管線")

        if self.config.verbose:
            print(f"\n[Reborn] 重新風格化：{new_style}")

        # Stage 3（跳過 SIMP）
        style_result = self._stylestage.apply(session.topology)
        session.set_style(style_result)

        # Stage 4
        nurbs_result = self._nurbsstage.export(style_result, session.session_id)
        session.set_nurbs(nurbs_result)

        return session

    # -----------------------------------------------------------------------
    # 內部執行流程
    # -----------------------------------------------------------------------

    def _run_from_massing(
        self,
        massing: VoxelMassingResult,
        session: RebornSession,
        t0: float,
    ) -> RebornSession:
        """從量體輸入繼續執行剩餘三個階段"""

        # Stage 2：拓撲最佳化
        if self.config.verbose:
            print(f"\n[Reborn] 第二階段：FNO-SIMP 拓撲最佳化（模式：{self._fno.mode}）")

        t1 = time.time()
        topo_result = self._topostage.optimize(massing)
        session.set_topology(topo_result)

        if self.config.verbose:
            print(f"  完成：{topo_result.n_iterations} 次迭代，"
                  f"{'收斂' if topo_result.converged else '未收斂'}，"
                  f"最終合規性={topo_result.final_compliance:.4e}，"
                  f"耗時={time.time()-t1:.1f}s")

        # Stage 3：風格皮膚
        if self.config.verbose:
            print(f"\n[Reborn] 第三階段：風格皮膚（{self.config.style.mode}）")

        t2 = time.time()
        style_result = self._stylestage.apply(topo_result)
        session.set_style(style_result)

        if self.config.verbose:
            print(f"  完成：SDF 範圍=[{style_result.sdf.min():.2f}, {style_result.sdf.max():.2f}]，"
                  f"耗時={time.time()-t2:.1f}s")

        # Stage 4：NURBS 輸出
        if self.config.verbose:
            print(f"\n[Reborn] 第四階段：NURBS 輸出")

        t3 = time.time()
        nurbs_result = self._nurbsstage.export(style_result, session.session_id)
        session.set_nurbs(nurbs_result)

        total_s = time.time() - t0
        if self.config.verbose:
            print(f"\n{'='*50}")
            print(f"[Reborn] 管線完成，總耗時：{total_s:.1f}s")
            print(f"  STEP 輸出：{'成功 → ' + str(nurbs_result.step_path) if nurbs_result.export_success else '降級（SDF .npy）'}")
            print(f"  工作目錄：{session.work_dir}")

        return session

    # -----------------------------------------------------------------------
    # 資訊查詢
    # -----------------------------------------------------------------------

    def get_status(self) -> dict:
        """回傳管線狀態（FNO 模式、sidecar 狀態等）"""
        return {
            "fno_mode":      self._fno.mode,
            "style_mode":    self.config.style.mode,
            "sidecar":       self._nurbsstage.get_sidecar_status(),
            "hybr_enabled":  self.config.hybr.enabled,
        }
