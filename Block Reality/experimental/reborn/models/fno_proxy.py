"""
fno_proxy.py — FNO 應力代理模型 Python 包裝器

橋接 brml/ 的 PFSFSurrogate ONNX 模型與 Reborn Python 管線。
正規化常數與 OnnxPFSFRuntime.java 完全一致。

模式優先順序：
  1. ONNX Runtime（onnxruntime-gpu 或 onnxruntime-cpu）
  2. BR-NeXT FEM 求解器（FEMSolverV2，CPU，慢但無依賴）
  3. Mock 解析解（純 numpy，僅用於 exp_001/003 等不需要精確物理的實驗）

參考：
  BIFROST.md §Java Integration — 正規化常數文件
  OnnxPFSFRuntime.java — Java 側對應實作
"""
from __future__ import annotations
import sys
from pathlib import Path
from typing import Literal
import numpy as np
from numpy.typing import NDArray


# ---------------------------------------------------------------------------
# 路徑設定：brml 與 BR-NeXT 必須可 import
# ---------------------------------------------------------------------------
_REPO_ROOT = Path(__file__).resolve().parent.parent.parent.parent.parent
_BRML_PATH = str(_REPO_ROOT / "ml" / "brml")
_BRNEXT_PATH = str(_REPO_ROOT / "ml" / "BR-NeXT")

for _p in (_BRML_PATH, _BRNEXT_PATH):
    if _p not in sys.path:
        sys.path.insert(0, _p)


# ---------------------------------------------------------------------------
# 正規化常數（必須與 OnnxPFSFRuntime.java 一致）
# ---------------------------------------------------------------------------
E_SCALE   = 200e9     # Pa   — 楊氏模量
RHO_SCALE = 7850.0    # kg/m³ — 密度（鋼材基準）
RC_SCALE  = 250.0     # MPa  — 壓縮強度
RT_SCALE  = 500.0     # MPa  — 拉伸強度

# FNO 輸入通道排列（5 通道）
# 與 brml/export/onnx_contracts.py 的 PFSF_INPUT_SPEC 一致
INPUT_CHANNELS = ["occ", "E_norm", "nu", "rho_norm", "rcomp_norm"]

# FNO 輸出通道排列（10 通道）
# σ(6) = [σxx,σyy,σzz,σyz,σxz,σxy], u(3) = [ux,uy,uz], φ(1)
OUTPUT_CHANNELS = ["sxx", "syy", "szz", "syz", "sxz", "sxy",
                   "ux", "uy", "uz", "phi"]


class FNOProxy:
    """
    FNO 應力代理模型 Python 包裝器。

    使用方式：
        proxy = FNOProxy()                          # 自動尋找模型
        proxy = FNOProxy(onnx_path="path/to/model.onnx")
        proxy = FNOProxy(mode="mock")               # 純解析解，用於快速測試

        stress, disp, phi = proxy.predict(occ, E, nu, rho, rcomp)
    """

    def __init__(
        self,
        onnx_path: str | None = None,
        mode: Literal["auto", "onnx", "fem", "mock"] = "auto",
        verbose: bool = False,
    ):
        self._verbose = verbose
        self._mode = mode
        self._session = None
        self._fem_solver = None

        if mode == "mock":
            self._active_mode = "mock"
            return

        # 嘗試載入 ONNX
        if mode in ("auto", "onnx"):
            self._active_mode = self._try_load_onnx(onnx_path)
        else:
            self._active_mode = "fem"

        # 若 ONNX 不可用，退回 FEM
        if self._active_mode != "onnx":
            self._active_mode = self._try_load_fem()

        if verbose:
            print(f"[FNOProxy] 使用模式：{self._active_mode}")

    # -----------------------------------------------------------------------
    # 主要推論接口
    # -----------------------------------------------------------------------

    def predict(
        self,
        occupancy: NDArray,
        E_field:   NDArray,
        nu_field:  NDArray,
        density:   NDArray,
        rcomp:     NDArray,
    ) -> tuple[NDArray, NDArray, NDArray]:
        """
        執行應力場推論。

        Args:
            occupancy: bool  [Lx,Ly,Lz] — 固體佔用遮罩
            E_field:   float [Lx,Ly,Lz] — 楊氏模量 (Pa)
            nu_field:  float [Lx,Ly,Lz] — 泊松比
            density:   float [Lx,Ly,Lz] — 密度 (kg/m³)
            rcomp:     float [Lx,Ly,Lz] — 壓縮強度 (MPa)

        Returns:
            stress:       float32[Lx,Ly,Lz,6]  — Voigt 應力
            displacement: float32[Lx,Ly,Lz,3]  — 位移 (m)
            phi:          float32[Lx,Ly,Lz]    — 勢場（PFSF phi）
        """
        if self._active_mode == "onnx":
            return self._predict_onnx(occupancy, E_field, nu_field, density, rcomp)
        elif self._active_mode == "fem":
            return self._predict_fem(occupancy, E_field, nu_field, density, rcomp)
        else:
            return self._predict_mock(occupancy, E_field, nu_field, density, rcomp)

    def predict_from_grid(self, grid: NDArray) -> tuple[NDArray, NDArray, NDArray]:
        """
        從預打包的 [Lx,Ly,Lz,5] 輸入張量執行推論。
        通道排列：(occ, E_norm, nu, rho_norm, rcomp_norm)
        """
        occ   = grid[..., 0] > 0.5
        E     = grid[..., 1] * E_SCALE
        nu    = grid[..., 2]
        rho   = grid[..., 3] * RHO_SCALE
        rcomp = grid[..., 4] * RC_SCALE
        return self.predict(occ, E, nu, rho, rcomp)

    # -----------------------------------------------------------------------
    # ONNX 路徑
    # -----------------------------------------------------------------------

    def _try_load_onnx(self, onnx_path: str | None) -> str:
        """嘗試載入 ONNX 模型，成功回傳 'onnx'，失敗回傳 'failed'"""
        try:
            import onnxruntime as ort

            if onnx_path is None:
                onnx_path = self._auto_find_onnx()

            if onnx_path is None:
                return "failed"

            providers = ["CUDAExecutionProvider", "CPUExecutionProvider"]
            self._session = ort.InferenceSession(
                str(onnx_path), providers=providers
            )
            self._input_name  = self._session.get_inputs()[0].name
            self._output_name = self._session.get_outputs()[0].name
            if self._verbose:
                print(f"[FNOProxy] 載入 ONNX：{onnx_path}")
            return "onnx"
        except Exception as e:
            if self._verbose:
                print(f"[FNOProxy] ONNX 載入失敗：{e}")
            return "failed"

    def _auto_find_onnx(self) -> str | None:
        """自動尋找 brml/ 或 config/ 下的 ONNX 模型"""
        candidates = [
            _REPO_ROOT / "ml" / "brml" / "output" / "bifrost_surrogate.onnx",
            _REPO_ROOT / "Block Reality" / "config" / "blockreality" / "models" / "bifrost_surrogate.onnx",
            _REPO_ROOT / "ml" / "experiments" / "outputs" / "hybr" / "hybr_ssgo.onnx",
        ]
        for c in candidates:
            if c.exists():
                return str(c)
        return None

    def _predict_onnx(self, occ, E, nu, rho, rcomp) -> tuple[NDArray, NDArray, NDArray]:
        """ONNX Runtime 推論"""
        inp = self._pack_input(occ, E, nu, rho, rcomp)  # [1,Lx,Ly,Lz,5]
        out = self._session.run(
            [self._output_name], {self._input_name: inp}
        )[0][0]  # [Lx,Ly,Lz,10]
        return (
            out[..., :6].astype(np.float32),   # stress Voigt
            out[..., 6:9].astype(np.float32),  # displacement
            out[..., 9].astype(np.float32),    # phi
        )

    # -----------------------------------------------------------------------
    # FEM 退回路徑
    # -----------------------------------------------------------------------

    def _try_load_fem(self) -> str:
        """嘗試載入 BR-NeXT FEM 求解器"""
        try:
            from brnext.fem.fem_solver_v2 import FEMSolverV2
            self._fem_solver = FEMSolverV2()
            if self._verbose:
                print("[FNOProxy] 退回使用 FEM 求解器")
            return "fem"
        except ImportError:
            if self._verbose:
                print("[FNOProxy] FEM 不可用，退回 Mock 解析解")
            return "mock"

    def _predict_fem(self, occ, E, nu, rho, rcomp) -> tuple[NDArray, NDArray, NDArray]:
        """BR-NeXT FEM 求解器推論（慢，但無需 ONNX）"""
        anchors = np.zeros_like(occ)
        anchors[:, 0, :] = occ[:, 0, :]   # 預設底部為固定端
        result = self._fem_solver.solve(occ, anchors, E, nu, rho)
        phi = result.von_mises / (result.von_mises.max() + 1e-8)
        return (
            result.stress.astype(np.float32),
            result.displacement.astype(np.float32),
            phi.astype(np.float32),
        )

    # -----------------------------------------------------------------------
    # Mock 解析解（用於快速測試）
    # -----------------------------------------------------------------------

    def _predict_mock(self, occ, E, nu, rho, rcomp) -> tuple[NDArray, NDArray, NDArray]:
        """
        解析近似解：假設均勻應力場，用自重估算。

        僅用於不需要精確物理的實驗（exp_001 幾何驗證等）。
        公式：σ_yy ≈ ρ·g·h（靜水壓力近似）
        """
        Lx, Ly, Lz = occ.shape
        g = 9.81  # m/s²
        # 每層的累積自重（沿 Y 方向積分）
        cum_rho = np.cumsum(rho * occ.astype(float), axis=1)
        sigma_yy = cum_rho * g  # Pa（簡化線性分布）

        stress = np.zeros((Lx, Ly, Lz, 6), dtype=np.float32)
        stress[..., 1] = sigma_yy.astype(np.float32)  # σ_yy 分量

        # 位移（線性近似：u_y ∝ -∫σ_yy/E dy）
        disp = np.zeros((Lx, Ly, Lz, 3), dtype=np.float32)
        E_safe = np.where(E > 0, E, 1.0)
        disp[..., 1] = -(sigma_yy / E_safe).astype(np.float32)

        # Phi：歸一化自重應力
        vm_approx = np.abs(sigma_yy)
        phi = (vm_approx / (vm_approx.max() + 1e-8) * occ).astype(np.float32)

        return stress, disp, phi

    # -----------------------------------------------------------------------
    # 工具方法
    # -----------------------------------------------------------------------

    def _pack_input(self, occ, E, nu, rho, rcomp) -> NDArray:
        """打包 [1,Lx,Ly,Lz,5] 輸入張量"""
        return np.stack([
            occ.astype(np.float32),
            (E.astype(np.float32)    / E_SCALE),
            nu.astype(np.float32),
            (rho.astype(np.float32)  / RHO_SCALE),
            (rcomp.astype(np.float32) / RC_SCALE),
        ], axis=-1)[None]  # [1,Lx,Ly,Lz,5]

    @property
    def mode(self) -> str:
        """回傳當前推論模式"""
        return self._active_mode

    def warm_up(self, grid_size: int = 8) -> None:
        """
        使用小型虛擬輸入預熱 ONNX Session（第一次推論較慢）。
        在管線初始化時呼叫一次。
        """
        if self._active_mode != "onnx":
            return
        dummy_occ = np.ones((grid_size,) * 3, dtype=bool)
        dummy_E   = np.full((grid_size,) * 3, 30e9, dtype=np.float32)
        dummy_nu  = np.full((grid_size,) * 3, 0.2, dtype=np.float32)
        dummy_rho = np.full((grid_size,) * 3, 2400.0, dtype=np.float32)
        dummy_rc  = np.full((grid_size,) * 3, 30.0, dtype=np.float32)
        self.predict(dummy_occ, dummy_E, dummy_nu, dummy_rho, dummy_rc)
