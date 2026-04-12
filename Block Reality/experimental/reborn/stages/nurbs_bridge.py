"""
nurbs_bridge.py — 第四階段：SDF → NURBS/STEP 輸出橋

透過 JSON-RPC 2.0 呼叫 NurbsExporter Java sidecar，
輸出 STEP CAD 文件供 Rhino/AutoCAD 使用。

核心創新（論文貢獻 4）：
  首個從 Minecraft 體素 → 結構最佳化幾何 → 專業 STEP/NURBS 的端到端管線，
  無需人工干預，CPU 下執行時間 < 60 秒。

連線協定：
  - NurbsExporter.java sidecar 在 localhost:{port} 監聽 JSON-RPC 2.0 請求
  - 方法：dualContouring(sdf: float[][], smoothing: float, resolution: int)
  - 若 sidecar 不可用，輸出原始 SDF 為 .npy 文件（降級模式）

參考：
  docs/L1-fastdesign/L2-sidecar-export/L3-nurbs-bridge.md — 協定說明
  NurbsExporter.java — Java 側實作
"""
from __future__ import annotations
import json
import socket
import struct
import time
from dataclasses import dataclass
from pathlib import Path
import numpy as np
from numpy.typing import NDArray

from ..config import RebornConfig
from ..utils.density_to_sdf import density_to_sdf_smooth
from .style_skin import StyleResult


@dataclass
class NurbsResult:
    """第四階段輸出"""
    step_path:     Path | None    # STEP 文件路徑（若 sidecar 可用）
    sdf_npy_path:  Path | None    # SDF numpy 文件路徑（降級輸出）
    export_success: bool
    timing_ms:     float          # 輸出耗時（ms）
    step_file_size: int = 0       # STEP 文件大小（bytes），0 = 不可用
    message:       str = ""


class NurbsBridge:
    """
    第四階段：NURBS/STEP 輸出橋。

    連線到 NurbsExporter Java sidecar（JSON-RPC 2.0），
    執行 SDF → Dual Contouring → NURBS → STEP 轉換。
    """

    def __init__(self, config: RebornConfig | None = None):
        self.config = config or RebornConfig()
        nc = self.config.nurbs
        self._port    = nc.sidecar_port
        self._timeout = nc.sidecar_timeout
        self._smoothing   = nc.smoothing
        self._resolution  = nc.resolution
        self._iso         = nc.iso_threshold
        self._output_dir  = Path(nc.output_dir or self.config.output_root)

    def export(
        self,
        style_result: StyleResult,
        session_id: str = "default",
    ) -> NurbsResult:
        """
        執行 SDF → STEP 輸出。

        流程：
          1. 嘗試 JSON-RPC 連線 NurbsExporter sidecar
          2. 若可用：SDF → dualContouring → STEP
          3. 若不可用：儲存 SDF 為 .npy（降級）

        Args:
            style_result: 第三階段輸出
            session_id:   工作階段 ID（用於命名輸出文件）

        Returns:
            NurbsResult
        """
        t0 = time.time()
        out_dir = self._output_dir / session_id
        out_dir.mkdir(parents=True, exist_ok=True)

        # 儲存 SDF（無論如何都保存，方便後續分析）
        sdf_path = out_dir / "styled.sdf.npy"
        np.save(str(sdf_path), style_result.sdf)

        # 嘗試 sidecar 連線
        if self._check_sidecar():
            try:
                step_path = out_dir / f"reborn_{session_id}.step"
                success = self._call_dual_contouring(
                    style_result.sdf, step_path
                )
                timing_ms = (time.time() - t0) * 1000

                if success:
                    if self.config.verbose:
                        print(f"[NurbsBridge] STEP 輸出成功：{step_path}")
                    return NurbsResult(
                        step_path=step_path,
                        sdf_npy_path=sdf_path,
                        export_success=True,
                        timing_ms=timing_ms,
                        step_file_size=step_path.stat().st_size if step_path.exists() else 0,
                        message="STEP 輸出完成",
                    )
            except Exception as e:
                if self.config.verbose:
                    print(f"[NurbsBridge] sidecar 呼叫失敗：{e}，退回降級模式")

        # 降級：輸出 SDF + 密度場
        density_path = out_dir / "density.npy"
        np.save(str(density_path), style_result.density_styled)
        timing_ms = (time.time() - t0) * 1000

        if self.config.verbose:
            print(f"[NurbsBridge] sidecar 不可用，輸出 SDF/density .npy：{out_dir}")

        return NurbsResult(
            step_path=None,
            sdf_npy_path=sdf_path,
            export_success=False,
            timing_ms=timing_ms,
            message=f"sidecar 不可用，SDF 已儲存至 {sdf_path}",
        )

    def export_from_density(
        self,
        density: NDArray,
        session_id: str = "default",
    ) -> NurbsResult:
        """
        直接從密度場輸出（跳過 StyleSkin）。
        用於 exp_001/002 等只做拓撲最佳化的實驗。
        """
        sdf = density_to_sdf_smooth(density, iso=self._iso, smooth_sigma=0.8)
        from .style_skin import StyleResult
        dummy_result = StyleResult(
            sdf=sdf, density_styled=density,
            style_name="none", style_latent=None,
        )
        return self.export(dummy_result, session_id)

    # -----------------------------------------------------------------------
    # JSON-RPC 通信
    # -----------------------------------------------------------------------

    def _check_sidecar(self) -> bool:
        """快速檢查 sidecar 是否在線"""
        try:
            with socket.create_connection(("localhost", self._port), timeout=1.0):
                return True
        except (ConnectionRefusedError, OSError, TimeoutError):
            return False

    def _call_dual_contouring(
        self,
        sdf: NDArray,
        output_path: Path,
    ) -> bool:
        """
        呼叫 NurbsExporter.java sidecar 的 dualContouring 方法。

        JSON-RPC 2.0 請求格式（與 NurbsExporter.java 一致）：
        {
          "jsonrpc": "2.0",
          "method": "dualContouring",
          "params": {
            "sdf": [[...flattened float array...]],
            "shape": [Lx, Ly, Lz],
            "smoothing": 0.6,
            "resolution": 2,
            "outputPath": "/path/to/output.step"
          },
          "id": 1
        }
        """
        Lx, Ly, Lz = sdf.shape
        payload = {
            "jsonrpc": "2.0",
            "method":  "dualContouring",
            "params": {
                "sdf":        sdf.ravel().tolist(),
                "shape":      [Lx, Ly, Lz],
                "smoothing":  self._smoothing,
                "resolution": self._resolution,
                "outputPath": str(output_path.resolve()),
            },
            "id": 1,
        }

        request_bytes = json.dumps(payload).encode("utf-8")
        # 長度前綴協定（4 bytes big-endian）
        header = struct.pack(">I", len(request_bytes))

        with socket.create_connection(("localhost", self._port),
                                       timeout=self._timeout) as sock:
            sock.sendall(header + request_bytes)

            # 讀取回應長度
            resp_header = self._recv_exact(sock, 4)
            resp_len = struct.unpack(">I", resp_header)[0]
            resp_bytes = self._recv_exact(sock, resp_len)

        resp = json.loads(resp_bytes.decode("utf-8"))
        if "error" in resp:
            raise RuntimeError(f"sidecar 回傳錯誤：{resp['error']}")

        result = resp.get("result", {})
        return result.get("success", False)

    @staticmethod
    def _recv_exact(sock: socket.socket, n: int) -> bytes:
        """確保讀取精確 n 個字節"""
        data = b""
        while len(data) < n:
            chunk = sock.recv(n - len(data))
            if not chunk:
                raise ConnectionError("socket 提前關閉")
            data += chunk
        return data

    # -----------------------------------------------------------------------
    # 輔助工具
    # -----------------------------------------------------------------------

    def get_sidecar_status(self) -> dict:
        """回傳 sidecar 連線狀態資訊"""
        available = self._check_sidecar()
        return {
            "available": available,
            "port":      self._port,
            "timeout_s": self._timeout,
            "message":   "sidecar 在線" if available else
                         f"sidecar 離線（請確認 NurbsExporter 已啟動於 port {self._port}）",
        }
