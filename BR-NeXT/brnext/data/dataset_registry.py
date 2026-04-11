"""DuckDB-based metadata registry for cached training samples."""
from __future__ import annotations

from pathlib import Path
from typing import Any


class DatasetRegistry:
    """Lightweight metadata registry using DuckDB (zero external server)."""

    def __init__(self, db_path: str | Path):
        self.db_path = Path(db_path)
        self.db_path.parent.mkdir(parents=True, exist_ok=True)
        self._con = None
        self._init_db()

    def _connect(self):
        import duckdb
        if self._con is None:
            self._con = duckdb.connect(str(self.db_path))
        return self._con

    def _init_db(self):
        con = self._connect()
        con.execute(
            """
            CREATE TABLE IF NOT EXISTS samples (
                sample_id VARCHAR PRIMARY KEY,
                config_hash VARCHAR,
                grid_size INTEGER,
                style VARCHAR,
                n_solid INTEGER,
                irregularity FLOAT,
                fem_converged BOOLEAN,
                fem_max_vm FLOAT,
                pfsf_max_phi FLOAT,
                zarr_path VARCHAR,
                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
            )
            """
        )

    def register(
        self,
        sample_id: str,
        config_hash: str,
        grid_size: int,
        style: str,
        n_solid: int,
        irregularity: float,
        fem_converged: bool | None,
        fem_max_vm: float | None,
        pfsf_max_phi: float | None,
        zarr_path: str,
    ) -> None:
        con = self._connect()
        con.execute(
            """
            INSERT OR REPLACE INTO samples
            (sample_id, config_hash, grid_size, style, n_solid, irregularity,
             fem_converged, fem_max_vm, pfsf_max_phi, zarr_path)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            [
                sample_id,
                config_hash,
                grid_size,
                style,
                n_solid,
                irregularity,
                fem_converged,
                fem_max_vm,
                pfsf_max_phi,
                zarr_path,
            ],
        )

    def fetch_sample_ids(self, config_hash: str, grid_size: int) -> list[str]:
        con = self._connect()
        result = con.execute(
            "SELECT sample_id FROM samples WHERE config_hash = ? AND grid_size = ?",
            [config_hash, grid_size],
        ).fetchall()
        return [row[0] for row in result]

    def fetch_metadata(self, sample_id: str) -> dict[str, Any] | None:
        con = self._connect()
        row = con.execute(
            "SELECT * FROM samples WHERE sample_id = ?", [sample_id]
        ).fetchone()
        if row is None:
            return None
        cols = [desc[0] for desc in con.description]
        return dict(zip(cols, row))

    def count(self, config_hash: str, grid_size: int) -> int:
        con = self._connect()
        row = con.execute(
            "SELECT COUNT(*) FROM samples WHERE config_hash = ? AND grid_size = ?",
            [config_hash, grid_size],
        ).fetchone()
        return row[0] if row else 0

    def close(self):
        if self._con:
            self._con.close()
            self._con = None

    def __enter__(self):
        return self

    def __exit__(self, *args):
        self.close()
