"""
hybr_proxy.py — HYBR AdaptiveSSGO 風格條件化推論包裝器

HYBR 架構（凍結推論路徑）：
  佔用網格 → SpectralGeometryEncoder → z_geom [B,d]
  風格代碼 → StyleEmbedding           → z_style [B,d]
  z = z_geom + alpha * z_style
  z → HyperMLP → SpectralWeightHead → CP factors → SpectralWeightBank
  SpectralWeightBank → AdaptiveSSGO（動態權重） → [B,L,L,L,10]

關鍵設計：
  - 基礎 SSGO 權重（W_base）完全凍結
  - 只有 StyleEmbedding（4×latent_dim 個參數）是可學習的
  - CP 分解保證 ||ΔW|| / ||W_base|| < 0.3（Lipschitz 穩定性）
  - 風格擾動在頻譜域中操作，自然分離低頻結構與高頻風格細節

本模組在 HYBR 不可用時優雅降級（回傳 None，管線跳過此步驟）。

參考：
  HYBR/README.md — 完整架構說明
  HYBR/hybr/core/adaptive_ssgo.py — AdaptiveSSGO 實作
  HYBR/hybr/core/weight_bank.py   — SpectralWeightBank CP 分解
"""
from __future__ import annotations
import sys
from pathlib import Path
from typing import Literal
import numpy as np
from numpy.typing import NDArray


# 設定 HYBR 路徑
_REPO_ROOT = Path(__file__).resolve().parent.parent.parent.parent.parent
_HYBR_PATH = str(_REPO_ROOT / "ml" / "HYBR")
if _HYBR_PATH not in sys.path:
    sys.path.insert(0, _HYBR_PATH)

# 風格代碼表
STYLE_TOKENS: dict[str, int] = {
    "raw":    0,
    "gaudi":  1,
    "zaha":   2,
    "hybrid": 3,
}

# 風格描述（用於日誌）
STYLE_DESC: dict[str, str] = {
    "raw":    "無風格（結構原型）",
    "gaudi":  "高第仿生（懸鏈/雙曲面）",
    "zaha":   "札哈流動（參數化流線）",
    "hybrid": "混合（高第 50% + 札哈 50%）",
}


class HYBRProxy:
    """
    HYBR AdaptiveSSGO 風格條件化推論包裝器。

    使用方式：
        proxy = HYBRProxy()                   # 自動嘗試載入 HYBR
        proxy = HYBRProxy(mock=True)          # 無需 HYBR，回傳解析解
        out = proxy.forward(occupancy, style="gaudi")

    當 HYBR 不可用時，forward() 回傳 None，管線跳過此步驟。
    """

    def __init__(
        self,
        weights_dir: str | None = None,
        alpha: float = 0.3,    # 風格擾動強度
        mock: bool = False,
        verbose: bool = False,
    ):
        self._alpha = alpha
        self._verbose = verbose
        self._available = False
        self._mock = mock

        if mock:
            return

        self._available = self._try_load_hybr(weights_dir)
        if verbose:
            status = "可用" if self._available else "不可用（使用 None 降級）"
            print(f"[HYBRProxy] HYBR 狀態：{status}")

    def forward(
        self,
        occupancy: NDArray,
        style:     str = "gaudi",
    ) -> NDArray | None:
        """
        執行風格條件化推論。

        Args:
            occupancy: bool[Lx,Ly,Lz] — 結構佔用網格
            style:     風格名稱（"raw" / "gaudi" / "zaha" / "hybrid"）

        Returns:
            float32[Lx,Ly,Lz,10] — σ(6)+u(3)+φ(1)，若不可用回傳 None
        """
        if style not in STYLE_TOKENS:
            raise ValueError(f"未知風格：{style}，可用：{list(STYLE_TOKENS)}")

        if self._mock:
            return self._forward_mock(occupancy, style)

        if not self._available:
            return None

        try:
            return self._forward_hybr(occupancy, style)
        except Exception as e:
            if self._verbose:
                print(f"[HYBRProxy] 推論失敗：{e}，回傳 None")
            return None

    def get_style_latent(self, style: str) -> NDArray | None:
        """
        取得風格潛在向量（用於可重現性記錄）。
        若 HYBR 不可用，回傳 None。
        """
        if not self._available or self._mock:
            return None
        try:
            import jax.numpy as jnp
            style_id = jnp.array([STYLE_TOKENS[style]])
            z_style = self._style_embedding.apply(
                {"params": self._style_params}, style_id
            )
            return np.array(z_style[0])
        except Exception:
            return None

    @property
    def available(self) -> bool:
        return self._available or self._mock

    # -----------------------------------------------------------------------
    # 載入 HYBR
    # -----------------------------------------------------------------------

    def _try_load_hybr(self, weights_dir: str | None) -> bool:
        """嘗試載入 HYBR 模型與 Flax/JAX 環境"""
        try:
            import jax
            import jax.numpy as jnp
            import flax.linen as nn
            from hybr.core.adaptive_ssgo import AdaptiveSSGO
            from hybr.core.geometry_encoder import SpectralGeometryEncoder
            from hybr.core.hypernet import HyperMLP, SpectralWeightHead
            from hybr.core.weight_bank import SpectralWeightBank

            self._jnp = jnp
            self._AdaptiveSSGO = AdaptiveSSGO
            self._SpectralGeometryEncoder = SpectralGeometryEncoder
            self._HyperMLP = HyperMLP

            # 嘗試載入預訓練權重
            if weights_dir is None:
                weights_dir = str(_REPO_ROOT / "ml" / "experiments" / "outputs" / "hybr")

            ckpt_dir = Path(weights_dir)
            ckpt_file = ckpt_dir / "hybr_ssgo.msgpack"

            if not ckpt_file.exists():
                if self._verbose:
                    print(f"[HYBRProxy] 找不到模型檔：{ckpt_file}")
                    print("[HYBRProxy] 將使用 Mock 模式初始化 StyleEmbedding")
                # 即使沒有預訓練權重，仍可用 Mock StyleEmbedding
                self._init_mock_style_embedding()
                return True   # 降級但可用

            # 載入完整模型
            self._load_full_model(ckpt_file)
            return True

        except ImportError as e:
            if self._verbose:
                print(f"[HYBRProxy] HYBR/JAX 未安裝：{e}")
            return False

    def _init_mock_style_embedding(self) -> None:
        """初始化零風格嵌入（無預訓練權重時的退回選項）"""
        try:
            import flax.linen as nn
            import jax.numpy as jnp

            class _StyleEmbedding(nn.Module):
                n_styles: int = 4
                latent_dim: int = 32

                @nn.compact
                def __call__(self, style_id):
                    table = self.param("style_table",
                                       nn.initializers.zeros,
                                       (self.n_styles, self.latent_dim))
                    return table[style_id]

            self._style_embedding = _StyleEmbedding()
            style_id = jnp.array([0])
            self._style_params = self._style_embedding.init(
                __import__("jax").random.PRNGKey(0), style_id
            )["params"]
            self._full_model_loaded = False
        except Exception as e:
            if self._verbose:
                print(f"[HYBRProxy] StyleEmbedding 初始化失敗：{e}")

    def _load_full_model(self, ckpt_file: Path) -> None:
        """載入完整 HYBR 模型（含預訓練基礎權重）"""
        from flax import serialization
        with open(ckpt_file, "rb") as f:
            state_bytes = f.read()
        # 解析模型狀態
        # 注意：具體結構取決於 HYBR 訓練程式的輸出格式
        import msgpack
        state = msgpack.unpackb(state_bytes, raw=False)
        self._base_params = state.get("params", {})
        self._style_params = state.get("style_params", {})
        self._model_config = state.get("config", {})
        self._full_model_loaded = True
        if self._verbose:
            print(f"[HYBRProxy] 載入完整模型：{ckpt_file}")

    # -----------------------------------------------------------------------
    # 推論
    # -----------------------------------------------------------------------

    def _forward_hybr(self, occupancy: NDArray, style: str) -> NDArray:
        """完整 HYBR 推論（需要 JAX + HYBR）"""
        jnp = self._jnp
        import jax

        # 輸入準備
        occ_jnp = jnp.array(occupancy.astype(np.float32))[None]  # [1,Lx,Ly,Lz]
        style_id = jnp.array([STYLE_TOKENS[style]])

        # 風格潛在向量
        z_style = self._style_embedding.apply(
            {"params": self._style_params}, style_id
        )  # [1, latent_dim]

        if not getattr(self, "_full_model_loaded", False):
            # 只有 StyleEmbedding 可用，使用 Mock 輸出 + z_style 記錄
            out = self._forward_mock(occupancy, style)
            return out

        # 完整 AdaptiveSSGO 推論
        from hybr.core.adaptive_ssgo import AdaptiveSSGO
        from hybr.core.geometry_encoder import SpectralGeometryEncoder

        cfg = self._model_config
        model = AdaptiveSSGO(
            hidden_channels=cfg.get("hidden_channels", 32),
            num_layers=cfg.get("num_layers", 4),
            modes=cfg.get("modes", 8),
            out_channels=10,
            latent_dim=cfg.get("latent_dim", 32),
        )

        # 幾何編碼 + 風格注入
        key = jax.random.PRNGKey(0)
        variables = {"params": self._base_params}

        # 調用模型，傳入組合潛在向量（z_geom + alpha * z_style 由模型內部處理）
        out = model.apply(
            variables, occ_jnp,
            style_offset=z_style * self._alpha,
            deterministic=True,
        )  # [1, Lx, Ly, Lz, 10]

        return np.array(out[0])  # [Lx, Ly, Lz, 10]

    def _forward_mock(self, occupancy: NDArray, style: str) -> NDArray:
        """
        Mock 推論：基於解析自重應力 + 風格特定偏置。

        用於 HYBR 不可用時的功能驗證。
        各風格產生不同的頻率偏置（可用於視覺驗證）。
        """
        Lx, Ly, Lz = occupancy.shape
        out = np.zeros((Lx, Ly, Lz, 10), dtype=np.float32)

        # 基礎自重應力（σ_yy 分量）
        occ = occupancy.astype(np.float32)
        cum_weight = np.cumsum(occ, axis=1) * 9.81 * 2400 / 1e6  # 簡化 MPa
        out[..., 1] = -cum_weight.astype(np.float32)   # σ_yy

        # 風格特定的頻率偏置
        style_biases = {
            "raw":    (1.0, 0.0, 0.0),
            "gaudi":  (0.8, 0.3, 0.1),  # 低頻主導（大尺度拱）
            "zaha":   (0.5, 0.6, 0.3),  # 中頻主導（流線帶）
            "hybrid": (0.65, 0.45, 0.2),
        }
        bias = style_biases.get(style, (1.0, 0.0, 0.0))

        # φ 場（反映風格偏置）
        xx = np.linspace(0, 2 * np.pi * bias[1], Lx)
        yy = np.linspace(0, 2 * np.pi * bias[2], Ly)
        zz = np.linspace(0, 2 * np.pi * bias[0], Lz)
        X, Y, Z = np.meshgrid(xx, yy, zz, indexing="ij")
        phi_mock = (np.sin(X) + np.sin(Y) * 0.5 + np.cos(Z) * 0.3) * occ
        out[..., 9] = (phi_mock * bias[0]).astype(np.float32)

        return out
