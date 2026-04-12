"""
Load Block Reality NodeGraph JSON files for recommendation model training.

NodeGraph JSON format (v1.1):
  { version, name, nodes: [{id, type, category, posX, posY, inputs: {...}}],
    wires: [{fromNode, fromPort, toNode, toPort}] }
"""
from __future__ import annotations

import json
from dataclasses import dataclass, field
from pathlib import Path

import numpy as np

# Known node categories and their IDs
CATEGORIES = {"material": 0, "physics": 1, "render": 2, "tool": 3, "export": 4}

# Known port types and their IDs (mirrors PortType.java)
PORT_TYPES = {
    "FLOAT": 0, "INT": 1, "BOOL": 2,
    "VEC2": 3, "VEC3": 4, "VEC4": 5,
    "COLOR": 6, "TEXTURE": 7,
    "MATERIAL": 8, "BLOCK": 9, "SHAPE": 10,
    "ENUM": 11, "CURVE": 12, "STRUCT": 13,
}


@dataclass
class NodeFeature:
    """Feature vector for a single node."""
    node_id: str
    type_name: str
    category_id: int
    position: tuple[float, float]
    enabled: bool
    input_count: int
    output_count: int


@dataclass
class GraphSample:
    """Single node graph converted to training features."""
    name: str

    # Node features: [num_nodes, feat_dim]
    node_features: np.ndarray  # float32

    # Adjacency (wire connections): [2, num_edges]
    edge_index: np.ndarray  # int32

    # Node type labels (for prediction target)
    node_types: list[str]

    # Type vocabulary index
    node_type_ids: np.ndarray  # int32

    # Global graph features
    num_nodes: int
    num_edges: int


class NodeGraphLoader:
    """Load NodeGraph JSON files and convert to graph training samples."""

    def __init__(self, graph_dir: str | Path):
        self.graph_dir = Path(graph_dir)
        self.type_vocab: dict[str, int] = {}
        self._next_type_id = 0

    def list_files(self) -> list[Path]:
        return sorted(self.graph_dir.glob("*.json"))

    def load(self, path: Path) -> GraphSample | None:
        """Load a single node graph JSON."""
        try:
            with open(path, "r", encoding="utf-8") as f:
                data = json.load(f)
        except (json.JSONDecodeError, OSError):
            return None

        if data.get("version") not in ("1.0", "1.1"):
            return None

        nodes = data.get("nodes", [])
        wires = data.get("wires", [])
        if not nodes:
            return None

        # Build node ID → index mapping
        id_to_idx: dict[str, int] = {}
        for i, node in enumerate(nodes):
            id_to_idx[node["id"]] = i

        # Extract node features
        node_feats = []
        node_types = []
        node_type_ids = []

        for node in nodes:
            type_name = node.get("type", "unknown")
            cat_name = node.get("category", "tool")
            cat_id = CATEGORIES.get(cat_name, len(CATEGORIES))

            # Register type in vocabulary
            if type_name not in self.type_vocab:
                self.type_vocab[type_name] = self._next_type_id
                self._next_type_id += 1
            type_id = self.type_vocab[type_name]

            inputs = node.get("inputs", {})
            n_inputs = len(inputs)

            feat = [
                float(type_id),
                float(cat_id),
                node.get("posX", 0.0),
                node.get("posY", 0.0),
                1.0 if node.get("enabled", True) else 0.0,
                float(n_inputs),
                0.0,  # output count (computed from wires)
            ]
            node_feats.append(feat)
            node_types.append(type_name)
            node_type_ids.append(type_id)

        # Build edge index from wires
        src_list, dst_list = [], []
        for wire in wires:
            from_id = wire.get("fromNode")
            to_id = wire.get("toNode")
            if from_id in id_to_idx and to_id in id_to_idx:
                src_list.append(id_to_idx[from_id])
                dst_list.append(id_to_idx[to_id])

        # Update output counts from edges
        for src in src_list:
            node_feats[src][6] += 1.0

        node_features = np.array(node_feats, dtype=np.float32)
        edge_index = np.array([src_list, dst_list], dtype=np.int32) if src_list else \
            np.zeros((2, 0), dtype=np.int32)

        return GraphSample(
            name=data.get("name", path.stem),
            node_features=node_features,
            edge_index=edge_index,
            node_types=node_types,
            node_type_ids=np.array(node_type_ids, dtype=np.int32),
            num_nodes=len(nodes),
            num_edges=len(src_list),
        )

    def build_vocabulary(self, paths: list[Path]) -> dict[str, int]:
        """Pre-scan all files to build node type vocabulary."""
        for path in paths:
            try:
                with open(path, "r", encoding="utf-8") as f:
                    data = json.load(f)
                for node in data.get("nodes", []):
                    type_name = node.get("type", "unknown")
                    if type_name not in self.type_vocab:
                        self.type_vocab[type_name] = self._next_type_id
                        self._next_type_id += 1
            except (json.JSONDecodeError, OSError):
                continue
        return self.type_vocab
