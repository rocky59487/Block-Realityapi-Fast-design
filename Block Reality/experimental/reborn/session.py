"""
session.py — RebornSession 工作階段狀態管理

管理跨管線階段的狀態，支援：
  - 中間結果持久化（.npz 格式）
  - 階段跳過（若某階段已完成）
  - 風格重新應用（保留 SIMP 結果，重跑 StyleSkin）
"""
from __future__ import annotations
import uuid
from dataclasses import dataclass, field
from pathlib import Path
import numpy as np
from typing import Literal

from .config import RebornConfig
from .stages.voxel_massing import VoxelMassingResult
from .stages.topo_optimizer import TopologyResult
from .stages.style_skin import StyleResult
from .stages.nurbs_bridge import NurbsResult


StageState = Literal["init", "massing", "topology", "style", "nurbs", "complete"]


class RebornSession:
    """
    Reborn 管線工作階段。

    每次執行對應一個 UUID session_id。
    中間結果儲存於 sessions/{session_id}/ 下，
    允許在不重跑 SIMP 的情況下更換風格。
    """

    def __init__(
        self,
        config: RebornConfig,
        session_id: str | None = None,
    ):
        self.config = config
        self.session_id = session_id or str(uuid.uuid4())[:8]
        self._stage: StageState = "init"

        # 中間結果（記憶體快取）
        self._massing:  VoxelMassingResult | None = None
        self._topology: TopologyResult  | None = None
        self._style:    StyleResult     | None = None
        self._nurbs:    NurbsResult     | None = None

        # 工作目錄
        self._work_dir = Path(config.output_root) / self.session_id
        self._work_dir.mkdir(parents=True, exist_ok=True)

    # -----------------------------------------------------------------------
    # 屬性訪問
    # -----------------------------------------------------------------------

    @property
    def stage(self) -> StageState:
        return self._stage

    @property
    def massing(self) -> VoxelMassingResult | None:
        return self._massing

    @property
    def topology(self) -> TopologyResult | None:
        return self._topology

    @property
    def style(self) -> StyleResult | None:
        return self._style

    @property
    def nurbs(self) -> NurbsResult | None:
        return self._nurbs

    @property
    def work_dir(self) -> Path:
        return self._work_dir

    # -----------------------------------------------------------------------
    # 狀態更新
    # -----------------------------------------------------------------------

    def set_massing(self, result: VoxelMassingResult) -> None:
        self._massing = result
        self._stage = "massing"
        self._save_stage_marker("massing")

    def set_topology(self, result: TopologyResult) -> None:
        self._topology = result
        self._stage = "topology"
        # 持久化密度場（最重要的中間結果）
        np.save(str(self._work_dir / "density.npy"),
                result.density.astype(np.float32))
        np.save(str(self._work_dir / "stress.npy"),
                result.stress_voigt.astype(np.float32))
        np.savez(str(self._work_dir / "convergence.npz"),
                 compliance=np.array(result.compliance_history),
                 volfrac=np.array(result.volfrac_history))
        self._save_stage_marker("topology")

    def set_style(self, result: StyleResult) -> None:
        self._style = result
        self._stage = "style"
        np.save(str(self._work_dir / f"sdf_{result.style_name}.npy"),
                result.sdf.astype(np.float32))
        self._save_stage_marker("style")

    def set_nurbs(self, result: NurbsResult) -> None:
        self._nurbs = result
        self._stage = "complete" if result.export_success else "nurbs"
        self._save_stage_marker("complete" if result.export_success else "nurbs_failed")

    # -----------------------------------------------------------------------
    # 持久化工具
    # -----------------------------------------------------------------------

    def _save_stage_marker(self, stage: str) -> None:
        """儲存階段標記文件（用於判斷已完成的階段）"""
        marker = self._work_dir / f".stage_{stage}"
        marker.write_text(stage)

    def has_topology_cache(self) -> bool:
        """密度場快取是否存在"""
        return (self._work_dir / "density.npy").exists()

    def load_topology_from_cache(self) -> bool:
        """
        從快取載入拓撲結果。
        用於重新風格化時跳過 SIMP 重新計算。
        回傳 True 若成功載入。
        """
        density_path = self._work_dir / "density.npy"
        stress_path  = self._work_dir / "stress.npy"
        if not density_path.exists():
            return False
        try:
            density = np.load(str(density_path))
            stress  = np.load(str(stress_path)) if stress_path.exists() else \
                      np.zeros(density.shape + (6,), dtype=np.float32)
            disp    = np.zeros(density.shape + (3,), dtype=np.float32)
            phi     = np.zeros(density.shape, dtype=np.float32)

            conv_data = {}
            conv_path = self._work_dir / "convergence.npz"
            if conv_path.exists():
                conv_data = dict(np.load(str(conv_path)))

            self._topology = TopologyResult(
                density=density, stress_voigt=stress,
                displacement=disp, phi=phi,
                compliance_history=conv_data.get("compliance", np.array([])).tolist(),
                volfrac_history=conv_data.get("volfrac", np.array([])).tolist(),
                converged=True,
            )
            self._stage = "topology"
            return True
        except Exception as e:
            if self.config.verbose:
                print(f"[Session] 快取載入失敗：{e}")
            return False

    def get_summary(self) -> dict:
        """輸出工作階段摘要"""
        summary = {
            "session_id": self.session_id,
            "stage":      self._stage,
            "work_dir":   str(self._work_dir),
        }
        if self._topology:
            summary["topology"] = {
                "n_iter":    self._topology.n_iterations,
                "converged": self._topology.converged,
                "final_vf":  f"{self._topology.final_volfrac:.3f}",
                "compliance":f"{self._topology.final_compliance:.4e}",
            }
        if self._style:
            summary["style"] = {
                "mode": self._style.style_name,
                "sdf_range": [float(self._style.sdf.min()), float(self._style.sdf.max())],
            }
        if self._nurbs:
            summary["nurbs"] = {
                "success":    self._nurbs.export_success,
                "timing_ms":  self._nurbs.timing_ms,
                "step_path":  str(self._nurbs.step_path) if self._nurbs.step_path else None,
            }
        return summary
