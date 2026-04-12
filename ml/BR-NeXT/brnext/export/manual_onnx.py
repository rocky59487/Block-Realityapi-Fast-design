"""Manual ONNX builder for SSGO — zero dependency on jax2onnx.

Builds an ONNX graph node-by-node from the Flax parameter pytree.
Uses standard ONNX ops: MatMul, Add, DFT, IDFT, Roll, Squeeze, Unsqueeze,
Slice, Pad, Einsum, Sigmoid, Gelu(via Erf), Concat, Mul.
"""
from __future__ import annotations

import math
from pathlib import Path

import numpy as np
try:
    from onnx import helper, TensorProto, numpy_helper
    _HAS_ONNX = True
except ImportError:
    _HAS_ONNX = False
    helper = TensorProto = numpy_helper = None


def _flatten_params(params, prefix=""):
    if isinstance(params, dict):
        for k, v in params.items():
            yield from _flatten_params(v, f"{prefix}{k}/")
    else:
        yield (prefix.rstrip("/"), params)


def _dft_matrix(L: int):
    n = np.arange(L)
    k = np.arange(L)
    F = np.exp(-2j * np.pi * k[:, None] * n[None, :] / L)
    return F.real.astype(np.float32), F.imag.astype(np.float32)


def _rfft_matrix(L: int):
    n = np.arange(L)
    k = np.arange(L // 2 + 1)
    F = np.exp(-2j * np.pi * k[:, None] * n[None, :] / L)
    return F.real.astype(np.float32), F.imag.astype(np.float32)


def _idft_matrix(L: int):
    n = np.arange(L)
    k = np.arange(L)
    F = np.exp(2j * np.pi * k[:, None] * n[None, :] / L) / L
    return F.real.astype(np.float32), F.imag.astype(np.float32)


def _irfft_matrix(L: int):
    n = np.arange(L)
    k = np.arange(L // 2 + 1)
    M_r = np.zeros((L // 2 + 1, L), dtype=np.float32)
    M_i = np.zeros((L // 2 + 1, L), dtype=np.float32)
    M_r[0, :] = 1.0 / L
    for kk in range(1, L // 2):
        M_r[kk, :] = 2.0 * np.cos(2 * np.pi * kk * n / L) / L
        M_i[kk, :] = 2.0 * np.sin(2 * np.pi * kk * n / L) / L
    if L % 2 == 0:
        M_r[-1, :] = np.cos(np.pi * n) / L
    return M_r, M_i


class _ParamWalker:
    def __init__(self, params):
        self.flat = dict(_flatten_params(params))

    def get(self, *parts):
        key = "/".join(parts)
        if key not in self.flat:
            raise KeyError(f"Missing param: {key}. Available: {list(self.flat.keys())[:20]}")
        return self.flat.pop(key)

    def assert_empty(self):
        if self.flat:
            raise RuntimeError(f"Unused params remain: {list(self.flat.keys())}")


class _OnnxBuilder:
    def __init__(self, opset=17):
        self.nodes = []
        self.initializers = []
        self.inputs = []
        self.outputs = []
        self.opset = opset
        self._counter = 0
        self._shapes = {}  # name -> tuple for internal reference

    def _next(self, prefix: str) -> str:
        self._counter += 1
        return f"{prefix}_{self._counter}"

    def add_input(self, name: str, shape: list, dtype=None):
        if dtype is None:
            dtype = TensorProto.FLOAT
        self.inputs.append(helper.make_tensor_value_info(name, dtype, shape))
        self._shapes[name] = tuple(shape)

    def add_output(self, name: str, shape: list, dtype=None):
        if dtype is None:
            dtype = TensorProto.FLOAT
        self.outputs.append(helper.make_tensor_value_info(name, dtype, shape))

    def add_const(self, name: str, arr: np.ndarray):
        self.initializers.append(numpy_helper.from_array(arr, name=name))

    def add_node(self, op_type, inputs, outputs, **kwargs):
        self.nodes.append(helper.make_node(op_type, inputs, outputs, **kwargs))

    # ── Basic ops ──
    def add_dense(self, x, w, b, out):
        mm = self._next("mm")
        self.add_node("MatMul", [x, w], [mm])
        self.add_node("Add", [mm, b], [out])

    def add_gelu(self, x, out):
        # GELU(x) = 0.5 * x * (1 + Erf(x / sqrt(2)))
        c_sqrt2 = self._next("c_sqrt2")
        c_half = self._next("c_half")
        c_one = self._next("c_one")
        self.add_const(c_sqrt2, np.array(math.sqrt(2), dtype=np.float32))
        self.add_const(c_half, np.array(0.5, dtype=np.float32))
        self.add_const(c_one, np.array(1.0, dtype=np.float32))
        div = self._next("div")
        self.add_node("Div", [x, c_sqrt2], [div])
        erf = self._next("erf")
        self.add_node("Erf", [div], [erf])
        add = self._next("add")
        self.add_node("Add", [c_one, erf], [add])
        mul1 = self._next("mul")
        self.add_node("Mul", [x, add], [mul1])
        self.add_node("Mul", [c_half, mul1], [out])

    def add_sigmoid(self, x, out):
        self.add_node("Sigmoid", [x], [out])

    def add_relu(self, x, out):
        self.add_node("Relu", [x], [out])

    # ── Spectral ops ──
    def _add_cplx_einsum(self, x_r, x_i, w_r, w_i, out_r, out_i, equation):
        """Complex einsum: (x_r + j x_i) @ (w_r + j w_i)."""
        t1 = self._next("ce_r1")
        t2 = self._next("ce_r2")
        t3 = self._next("ce_i1")
        t4 = self._next("ce_i2")
        wr_name = self._next("ce_wr")
        wi_name = self._next("ce_wi")
        self.add_const(wr_name, w_r)
        self.add_const(wi_name, w_i)
        self.add_node("Einsum", [x_r, wr_name], [t1], equation=equation)
        self.add_node("Einsum", [x_i, wi_name], [t2], equation=equation)
        self.add_node("Sub", [t1, t2], [out_r])
        self.add_node("Einsum", [x_r, wi_name], [t3], equation=equation)
        self.add_node("Einsum", [x_i, wr_name], [t4], equation=equation)
        self.add_node("Add", [t3, t4], [out_i])

    def add_rfftn(self, x, out, axes=(1, 2, 3), s=None):
        """3D real FFT using precomputed DFT matrices (ORT-friendly)."""
        B, L1, L2, L3, C = self._shapes[x]
        # Imaginary part is zero
        c_zero = self._next("c_zero")
        self.add_const(c_zero, np.zeros((B, L1, L2, L3, C), dtype=np.float32))
        x_i = c_zero

        # Precompute matrices
        F1_r, F1_i = _dft_matrix(L1)
        F2_r, F2_i = _dft_matrix(L2)
        R3_r, R3_i = _rfft_matrix(L3)

        X1_r = self._next("X1_r")
        X1_i = self._next("X1_i")
        self._add_cplx_einsum(x, x_i, F1_r, F1_i, X1_r, X1_i, "b n x y c, n k -> b k x y c")
        sh1 = (B, L1, L2, L3, C)
        self._shapes[X1_r] = sh1
        self._shapes[X1_i] = sh1

        X2_r = self._next("X2_r")
        X2_i = self._next("X2_i")
        self._add_cplx_einsum(X1_r, X1_i, F2_r, F2_i, X2_r, X2_i, "b x n y c, n k -> b x k y c")
        sh2 = (B, L1, L2, L3, C)
        self._shapes[X2_r] = sh2
        self._shapes[X2_i] = sh2

        X3_r = self._next("X3_r")
        X3_i = self._next("X3_i")
        self._add_cplx_einsum(X2_r, X2_i, R3_r, R3_i, X3_r, X3_i, "b x y n c, k n -> b x y k c")
        sh3 = (B, L1, L2, L3 // 2 + 1, C)
        self._shapes[X3_r] = sh3
        self._shapes[X3_i] = sh3

        self.add_concat_complex(X3_r, X3_i, out, axis=-1)

    def add_irfftn(self, x, out, s, axes=(1, 2, 3)):
        """Inverse 3D real FFT using precomputed IDFT matrices (ORT-friendly)."""
        B, L1, L2, M3, C, _ = self._shapes[x]
        L3 = s[2]

        # Split real/imag and squeeze last dim
        X_r = self._next("X_r")
        X_i = self._next("X_i")
        self.add_gather(x, X_r, [0], axis=-1)
        self.add_gather(x, X_i, [1], axis=-1)
        sh_sq = (B, L1, L2, M3, C, 1)
        self._shapes[X_r] = sh_sq
        self._shapes[X_i] = sh_sq
        sq_r = self._next("sq_r")
        sq_i = self._next("sq_i")
        ax_sq = self._next("ax_sq")
        self.add_const(ax_sq, np.array([-1], dtype=np.int64))
        self.add_node("Squeeze", [X_r, ax_sq], [sq_r])
        self.add_node("Squeeze", [X_i, ax_sq], [sq_i])
        sh_g = (B, L1, L2, M3, C)
        self._shapes[sq_r] = sh_g
        self._shapes[sq_i] = sh_g

        # Precompute matrices
        M3_r, M3_i = _irfft_matrix(L3)
        F2_inv_r, F2_inv_i = _idft_matrix(L2)
        F1_inv_r, F1_inv_i = _idft_matrix(L1)

        x3_r = self._next("x3_r")
        x3_i = self._next("x3_i")
        self._add_cplx_einsum(sq_r, sq_i, M3_r, M3_i, x3_r, x3_i, "b x y k c, k n -> b x y n c")
        sh3 = (B, L1, L2, L3, C)
        self._shapes[x3_r] = sh3
        self._shapes[x3_i] = sh3

        x2_r = self._next("x2_r")
        x2_i = self._next("x2_i")
        self._add_cplx_einsum(x3_r, x3_i, F2_inv_r, F2_inv_i, x2_r, x2_i, "b x k y c, k n -> b x n y c")
        self._shapes[x2_r] = sh3
        self._shapes[x2_i] = sh3

        x1_r = self._next("x1_r")
        x1_i = self._next("x1_i")
        self._add_cplx_einsum(x2_r, x2_i, F1_inv_r, F1_inv_i, x1_r, x1_i, "b k x y c, k n -> b n x y c")
        self._shapes[x1_r] = sh3
        self._shapes[x1_i] = sh3

        self.add_node("Identity", [x1_r], [out])
        self._shapes[out] = sh3

    def add_gather(self, x, out, indices, axis=-1):
        """Gather elements along axis. More shape-inference friendly than Slice in some ORT versions."""
        idx_n = self._next("gather_idx")
        self.add_const(idx_n, np.array(indices, dtype=np.int64))
        self.add_node("Gather", [x, idx_n], [out], axis=axis)
        sh = list(self._shapes[x])
        sh[axis] = len(indices) if isinstance(indices, (list, tuple, np.ndarray)) else 1
        self._shapes[out] = tuple(sh)

    def add_pad_complex(self, x, out, pads):
        """Pad a complex tensor [..., 2]. pads: [before_0, after_0, ...]."""
        pads_n = self._next("pads")
        self.add_const(pads_n, np.array(pads, dtype=np.int64))
        self.add_node("Pad", [x, pads_n], [out], mode="constant")
        sh = list(self._shapes[x])
        # pads length = 2*rank
        for i in range(len(sh)):
            sh[i] += pads[2 * i] + pads[2 * i + 1]
        self._shapes[out] = tuple(sh)

    def add_einsum_complex(self, x_r, x_i, w_r, w_i, out_r, out_i, equation):
        """Complex einsum: out = x @ W."""
        # real part
        t1 = self._next("eins_r1")
        t2 = self._next("eins_r2")
        self.add_node("Einsum", [x_r, w_r], [t1], equation=equation)
        self.add_node("Einsum", [x_i, w_i], [t2], equation=equation)
        self.add_node("Sub", [t1, t2], [out_r])
        # imag part
        t3 = self._next("eins_i1")
        t4 = self._next("eins_i2")
        self.add_node("Einsum", [x_r, w_i], [t3], equation=equation)
        self.add_node("Einsum", [x_i, w_r], [t4], equation=equation)
        self.add_node("Add", [t3, t4], [out_i])

    def add_concat_complex(self, r, i, out, axis=-1):
        uns_r = self._next("uns_r")
        uns_i = self._next("uns_i")
        ax = self._next("ax_uns")
        self.add_const(ax, np.array([axis], dtype=np.int64))
        self.add_node("Unsqueeze", [r, ax], [uns_r])
        self.add_node("Unsqueeze", [i, ax], [uns_i])
        self._shapes[uns_r] = self._shapes[r] + (1,)
        self._shapes[uns_i] = self._shapes[i] + (1,)
        self.add_node("Concat", [uns_r, uns_i], [out], axis=axis)
        sh = list(self._shapes[uns_r])
        sh[axis] = 2
        self._shapes[out] = tuple(sh)

    def add_roll(self, x, out, shifts, axes):
        # ONNX Roll may not be registered in all checker versions;
        # implement circular roll via Split+Concat for maximum compatibility.
        tmp = x
        for shift, ax in zip(shifts, axes):
            if shift == 0:
                continue
            L = self._shapes[tmp][ax]
            s = shift % L
            if s == 0:
                continue
            if s > 0:
                # Split tmp into [L-s, s] along ax, then concat [second, first]
                split_name = self._next("split")
                p1 = self._next("roll_p1")
                p2 = self._next("roll_p2")
                self.add_const(split_name, np.array([L - s, s], dtype=np.int64))
                self.add_node("Split", [tmp, split_name], [p1, p2], axis=ax)
                sh = list(self._shapes[tmp])
                sh[ax] = L - s
                self._shapes[p1] = tuple(sh)
                sh[ax] = s
                self._shapes[p2] = tuple(sh)
                cat = self._next("roll_cat")
                self.add_node("Concat", [p2, p1], [cat], axis=ax)
                self._shapes[cat] = self._shapes[tmp]
                tmp = cat
            else:
                # s < 0, e.g., shift=-1 => equivalent to shift=L-1
                s = -s
                split_name = self._next("split")
                p1 = self._next("roll_p1")
                p2 = self._next("roll_p2")
                self.add_const(split_name, np.array([s, L - s], dtype=np.int64))
                self.add_node("Split", [tmp, split_name], [p1, p2], axis=ax)
                sh = list(self._shapes[tmp])
                sh[ax] = s
                self._shapes[p1] = tuple(sh)
                sh[ax] = L - s
                self._shapes[p2] = tuple(sh)
                cat = self._next("roll_cat")
                self.add_node("Concat", [p2, p1], [cat], axis=ax)
                self._shapes[cat] = self._shapes[tmp]
                tmp = cat
        self.add_node("Identity", [tmp], [out])
        self._shapes[out] = self._shapes[tmp]

    def build(self, model_name="brnext_ssgo"):
        from onnx import ModelProto
        # Inject explicit value_info from tracked shapes to help ORT when
        # built-in shape inference fails (e.g. DFT, Einsum on some versions).
        value_info = []
        for name, shape in self._shapes.items():
            # Skip inputs/outputs already declared
            if any(vi.name == name for vi in self.inputs + self.outputs):
                continue
            # Convert shape to TensorShapeProto
            tsp = helper.make_tensor_type_proto(elem_type=TensorProto.FLOAT, shape=list(shape))
            value_info.append(helper.make_value_info(name, tsp))

        graph = helper.make_graph(
            self.nodes,
            model_name,
            self.inputs,
            self.outputs,
            self.initializers,
            value_info=value_info,
        )
        model = helper.make_model(graph, opset_imports=[helper.make_opsetid("", self.opset)])
        model.ir_version = 8
        return model


def export_ssgo_manual(params, output_path: str | Path, grid_size: int = 12,
                       n_global_layers: int = 3, n_focal_layers: int = 2,
                       n_backbone_layers: int = 2, hidden: int = 48,
                       moe_hidden: int = 32):
    """Build ONNX from SSGO params manually."""
    if not _HAS_ONNX:
        raise ImportError("ONNX export requires 'onnx' package. Install: pip install onnx")
    output_path = Path(output_path)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    walker = _ParamWalker(params)
    b = _OnnxBuilder(opset=17)

    L = grid_size
    B = 1
    input_name = "input"
    b.add_input(input_name, [B, L, L, L, 6])
    h = b._next

    # Helper to track shape manually
    cur = input_name
    cur_shape = (B, L, L, L, 6)

    def _dense(path_prefix, in_ch, out_ch):
        nonlocal cur, cur_shape
        w = walker.get(path_prefix, "kernel")
        b_vec = walker.get(path_prefix, "bias")
        w_name = h("w")
        b_name = h("b")
        out_name = h("y")
        b.add_const(w_name, np.asarray(w).astype(np.float32))
        b.add_const(b_name, np.asarray(b_vec).astype(np.float32))
        b.add_dense(cur, w_name, b_name, out_name)
        cur_shape = (*cur_shape[:-1], out_ch)
        b._shapes[out_name] = cur_shape
        cur = out_name

    def _gelu():
        nonlocal cur
        out_name = h("gelu")
        b.add_gelu(cur, out_name)
        b._shapes[out_name] = cur_shape
        cur = out_name

    def _fno_block(prefix, channels, modes):
        nonlocal cur, cur_shape
        # Spectral path
        wr = walker.get(prefix, "WeightedSpectralConv3D_0", "weights_r")
        wi = walker.get(prefix, "WeightedSpectralConv3D_0", "weights_i")
        mode_w = walker.get(prefix, "WeightedSpectralConv3D_0", "mode_w")
        mx = min(modes, L)
        my = min(modes, L)
        mz = min(modes, L // 2 + 1)

        # FFT
        ft = h("ft")
        b.add_rfftn(cur, ft, axes=(1, 2, 3), s=(L, L, L))
        # shape ft: (B, L, L, L//2+1, channels, 2)
        ft_shape = (B, L, L, L // 2 + 1, channels, 2)
        b._shapes[ft] = ft_shape

        # Split real/imag from ft directly using Gather (avoid Slice issues)
        sq_r = h("ft_r")
        sq_i = h("ft_i")
        b.add_gather(ft, sq_r, [0], axis=-1)
        b._shapes[sq_r] = (B, L, L, L // 2 + 1, channels, 1)
        sr_sq = h("sr_sq")
        ax_sq = h("ax_sq")
        b.add_const(ax_sq, np.array([-1], dtype=np.int64))
        b.add_node("Squeeze", [sq_r, ax_sq], [sr_sq])
        b._shapes[sr_sq] = (B, L, L, L // 2 + 1, channels)

        b.add_gather(ft, sq_i, [1], axis=-1)
        b._shapes[sq_i] = (B, L, L, L // 2 + 1, channels, 1)
        si_sq = h("si_sq")
        ax_sq2 = h("ax_sq2")
        b.add_const(ax_sq2, np.array([-1], dtype=np.int64))
        b.add_node("Squeeze", [sq_i, ax_sq2], [si_sq])
        b._shapes[si_sq] = (B, L, L, L // 2 + 1, channels)

        # Apply mode_w to weights and pad to full freq grid in numpy
        mw = np.asarray(mode_w).astype(np.float32)  # [mx, my, mz]
        wr_arr = np.asarray(wr).astype(np.float32)  # [C_in, C_out, mx, my, mz]
        wi_arr = np.asarray(wi).astype(np.float32)
        mw_reshape = mw[None, None, ...]
        wr_mw = wr_arr * (1.0 / (1.0 + np.exp(-mw_reshape)))
        wi_mw = wi_arr * (1.0 / (1.0 + np.exp(-mw_reshape)))
        # Pad weights to [C_in, C_out, L, L, L//2+1] based on actual weight size
        wr_shape = wr_arr.shape[2:]
        pad_w = [(0, 0), (0, 0), (0, L - wr_shape[0]), (0, L - wr_shape[1]), (0, (L // 2 + 1) - wr_shape[2])]
        wr_pad = np.pad(wr_mw, pad_w)
        wi_pad = np.pad(wi_mw, pad_w)

        wr_name = h("wr")
        wi_name = h("wi")
        b.add_const(wr_name, wr_pad)
        b.add_const(wi_name, wi_pad)

        # Einsum on full freq grid
        eins_r = h("eins_r")
        eins_i = h("eins_i")
        b.add_einsum_complex(sr_sq, si_sq, wr_name, wi_name, eins_r, eins_i, "bxyzi,ioxyz->bxyzo")
        b._shapes[eins_r] = (B, L, L, L // 2 + 1, channels)
        b._shapes[eins_i] = (B, L, L, L // 2 + 1, channels)

        # Concat back to complex
        padded = h("ft_pad")
        b.add_concat_complex(eins_r, eins_i, padded, axis=-1)
        b._shapes[padded] = (B, L, L, L // 2 + 1, channels, 2)

        # IDFT
        ifft = h("ifft")
        b.add_irfftn(padded, ifft, s=(L, L, L), axes=(1, 2, 3))
        b._shapes[ifft] = (B, L, L, L, channels)

        # Local bypass dense
        bypass = h("bypass")
        w_b = walker.get(prefix, "Dense_0", "kernel")
        b_b = walker.get(prefix, "Dense_0", "bias")
        w_b_name = h("w_b")
        b_b_name = h("b_b")
        b.add_const(w_b_name, np.asarray(w_b).astype(np.float32))
        b.add_const(b_b_name, np.asarray(b_b).astype(np.float32))
        b.add_dense(cur, w_b_name, b_b_name, bypass)
        b._shapes[bypass] = (B, L, L, L, channels)

        # Add + GELU
        add_name = h("add")
        b.add_node("Add", [ifft, bypass], [add_name])
        b._shapes[add_name] = (B, L, L, L, channels)
        cur = add_name
        cur_shape = (B, L, L, L, channels)
        _gelu()

    def _voxel_gat(prefix, hidden):
        nonlocal cur, cur_shape
        # occupancy from original input
        occ_name = "occ_extracted"
        if "occ_extracted" not in b._shapes:
            # Gather channel 0 from input
            b.add_gather(input_name, occ_name, [0], axis=-1)
            b._shapes[occ_name] = (B, L, L, L, 1)

        messages = []
        masks = []
        dense_idx = 0
        for dx in (-1, 0, 1):
            for dy in (-1, 0, 1):
                for dz in (-1, 0, 1):
                    if dx == 0 and dy == 0 and dz == 0:
                        continue
                    x_nb = h("x_nb")
                    b.add_roll(cur, x_nb, [dx, dy, dz], [1, 2, 3])
                    occ_nb = h("occ_nb")
                    b.add_roll(occ_name, occ_nb, [dx, dy, dz], [1, 2, 3])
                    concat = h("gat_concat")
                    b.add_node("Concat", [cur, x_nb], [concat], axis=-1)
                    b._shapes[concat] = (B, L, L, L, hidden * 2)

                    msg = h("gat_msg")
                    w1 = walker.get(prefix, f"Dense_{dense_idx}", "kernel")
                    b1 = walker.get(prefix, f"Dense_{dense_idx}", "bias")
                    dense_idx += 1
                    w1n = h("w1")
                    b1n = h("b1")
                    b.add_const(w1n, np.asarray(w1).astype(np.float32))
                    b.add_const(b1n, np.asarray(b1).astype(np.float32))
                    b.add_dense(concat, w1n, b1n, msg)
                    b._shapes[msg] = (B, L, L, L, hidden)

                    msg_relu = h("msg_relu")
                    b.add_relu(msg, msg_relu)
                    b._shapes[msg_relu] = (B, L, L, L, hidden)

                    # mask
                    masked_msg = h("masked_msg")
                    b.add_node("Mul", [msg_relu, occ_nb], [masked_msg])
                    b._shapes[masked_msg] = (B, L, L, L, hidden)
                    messages.append(masked_msg)
                    masks.append(occ_nb)

        # Sum and normalize
        sum_msg = h("sum_msg")
        b.add_node("Sum", messages, [sum_msg])
        b._shapes[sum_msg] = (B, L, L, L, hidden)

        sum_mask = h("sum_mask")
        b.add_node("Sum", masks, [sum_mask])
        b._shapes[sum_mask] = (B, L, L, L, 1)

        denom = h("denom")
        c_eps = h("eps")
        b.add_const(c_eps, np.array(1e-8, dtype=np.float32))
        b.add_node("Add", [sum_mask, c_eps], [denom])
        b._shapes[denom] = (B, L, L, L, 1)

        agg = h("agg")
        b.add_node("Div", [sum_msg, denom], [agg])
        b._shapes[agg] = (B, L, L, L, hidden)

        # Update
        upd_concat = h("upd_concat")
        b.add_node("Concat", [cur, agg], [upd_concat], axis=-1)
        b._shapes[upd_concat] = (B, L, L, L, hidden * 2)

        upd = h("upd")
        w2 = walker.get(prefix, f"Dense_{dense_idx}", "kernel")
        b2 = walker.get(prefix, f"Dense_{dense_idx}", "bias")
        w2n = h("w2")
        b2n = h("b2")
        b.add_const(w2n, np.asarray(w2).astype(np.float32))
        b.add_const(b2n, np.asarray(b2).astype(np.float32))
        b.add_dense(upd_concat, w2n, b2n, upd)
        b._shapes[upd] = (B, L, L, L, hidden)

        masked_upd = h("masked_upd")
        b.add_node("Mul", [upd, occ_name], [masked_upd])
        b._shapes[masked_upd] = (B, L, L, L, hidden)

        cur = masked_upd
        cur_shape = (B, L, L, L, hidden)

    def _moe_head(prefix, hidden, moe_hidden, n_routed=8, n_shared=2):
        nonlocal cur, cur_shape
        # Router
        r1 = h("r1")
        moe_prefix = f"{prefix}/MoESpectralHead_0" if prefix else "MoESpectralHead_0"
        w_r1 = walker.get(moe_prefix, "Dense_0", "kernel")
        b_r1 = walker.get(moe_prefix, "Dense_0", "bias")
        w_r1_n = h("w_r1")
        b_r1_n = h("b_r1")
        b.add_const(w_r1_n, np.asarray(w_r1).astype(np.float32))
        b.add_const(b_r1_n, np.asarray(b_r1).astype(np.float32))
        b.add_dense(cur, w_r1_n, b_r1_n, r1)
        b._shapes[r1] = (B, L, L, L, 16)

        r_relu = h("r_relu")
        b.add_relu(r1, r_relu)
        b._shapes[r_relu] = (B, L, L, L, 16)

        logits = h("logits")
        w_r2 = walker.get(moe_prefix, "Dense_1", "kernel")
        b_r2 = walker.get(moe_prefix, "Dense_1", "bias")
        w_r2_n = h("w_r2")
        b_r2_n = h("b_r2")
        b.add_const(w_r2_n, np.asarray(w_r2).astype(np.float32))
        b.add_const(b_r2_n, np.asarray(b_r2).astype(np.float32))
        b.add_dense(r_relu, w_r2_n, b_r2_n, logits)
        b._shapes[logits] = (B, L, L, L, n_routed)

        # Softmax
        sm = h("sm")
        b.add_node("Softmax", [logits], [sm], axis=-1)
        b._shapes[sm] = (B, L, L, L, n_routed)

        # Threshold mask (> 1/n_routed)
        thr = h("thr")
        b.add_const(thr, np.array(1.0 / n_routed, dtype=np.float32))
        cmp = h("cmp")
        b.add_node("Greater", [sm, thr], [cmp])
        # Cast to float
        mask_f = h("mask_f")
        b.add_node("Cast", [cmp], [mask_f], to=TensorProto.FLOAT)
        masked_gate = h("masked_gate")
        b.add_node("Mul", [sm, mask_f], [masked_gate])
        b._shapes[masked_gate] = (B, L, L, L, n_routed)

        # Normalize
        sum_gate = h("sum_gate")
        axes_red = h("axes_red")
        b.add_const(axes_red, np.array([-1], dtype=np.int64))
        b.add_node("ReduceSum", [masked_gate, axes_red], [sum_gate], keepdims=1)
        b._shapes[sum_gate] = (B, L, L, L, 1)

        norm_gate = h("norm_gate")
        c_eps2 = h("eps2")
        b.add_const(c_eps2, np.array(1e-8, dtype=np.float32))
        b.add_node("Add", [sum_gate, c_eps2], [norm_gate])
        b._shapes[norm_gate] = (B, L, L, L, 1)

        gate = h("gate")
        b.add_node("Div", [masked_gate, norm_gate], [gate])
        b._shapes[gate] = (B, L, L, L, n_routed)

        # Compute all routed experts
        routed_outs = []
        for i in range(n_routed):
            e = h(f"expert_{i}")
            _expert_dense(moe_prefix, 2 + 2 * i, cur, e, hidden, moe_hidden)
            routed_outs.append(e)

        # Stack and einsum with gate
        stack_name = h("stack_routed")
        b.add_node("Concat", routed_outs, [stack_name], axis=-1)
        b._shapes[stack_name] = (B, L, L, L, hidden * n_routed)

        # Reshape stack to [B,L,L,L,hidden,n_routed]
        rs_shape = h("rs_shape")
        b.add_const(rs_shape, np.array([B, L, L, L, hidden, n_routed], dtype=np.int64))
        stack_rs = h("stack_rs")
        b.add_node("Reshape", [stack_name, rs_shape], [stack_rs])
        b._shapes[stack_rs] = (B, L, L, L, hidden, n_routed)

        # Einsum "...ho,...o->...h"
        routed_agg = h("routed_agg")
        b.add_node("Einsum", [stack_rs, gate], [routed_agg], equation="...ho,...o->...h")
        b._shapes[routed_agg] = (B, L, L, L, hidden)

        # Shared experts (sum)
        shared_sum = h("shared_sum")
        shared_outs = []
        for i in range(n_shared):
            e = h(f"shared_{i}")
            _expert_dense(moe_prefix, 2 + 2 * n_routed + 2 * i, cur, e, hidden, moe_hidden)
            shared_outs.append(e)
        b.add_node("Sum", shared_outs, [shared_sum])
        b._shapes[shared_sum] = (B, L, L, L, hidden)

        out_name = h("moe_out")
        b.add_node("Add", [routed_agg, shared_sum], [out_name])
        b._shapes[out_name] = (B, L, L, L, hidden)
        cur = out_name
        cur_shape = (B, L, L, L, hidden)

    def _expert_dense(prefix, dense_idx, x_name, out_name, in_ch, out_ch):
        w1 = walker.get(prefix, f"Dense_{dense_idx}", "kernel")
        b1 = walker.get(prefix, f"Dense_{dense_idx}", "bias")
        w1n = h("w_e1")
        b1n = h("b_e1")
        b.add_const(w1n, np.asarray(w1).astype(np.float32))
        b.add_const(b1n, np.asarray(b1).astype(np.float32))
        tmp = h("e_tmp")
        b.add_dense(x_name, w1n, b1n, tmp)
        b._shapes[tmp] = (B, L, L, L, out_ch)
        tmp_g = h("e_gelu")
        b.add_gelu(tmp, tmp_g)
        b._shapes[tmp_g] = (B, L, L, L, out_ch)
        w2 = walker.get(prefix, f"Dense_{dense_idx + 1}", "kernel")
        b2 = walker.get(prefix, f"Dense_{dense_idx + 1}", "bias")
        w2n = h("w_e2")
        b2n = h("b_e2")
        b.add_const(w2n, np.asarray(w2).astype(np.float32))
        b.add_const(b2n, np.asarray(b2).astype(np.float32))
        b.add_dense(tmp_g, w2n, b2n, out_name)
        b._shapes[out_name] = (B, L, L, L, in_ch)

    def _task_head(dense1, dense2, out_ch):
        nonlocal cur, cur_shape
        head_w = max(hidden, 32)
        _dense(dense1, cur_shape[-1], head_w)
        _gelu()
        _dense(dense2, cur_shape[-1], out_ch)

    # =========================== Build Graph ===========================
    # Global branch lifting
    global_in = cur
    _dense("Dense_0", 6, hidden)

    # FNOBlocks global
    for gi in range(n_global_layers):
        _fno_block(f"FNOBlock_{gi}", hidden, 6)
    global_out = cur

    # Focal branch lifting
    cur = global_in  # back to input
    cur_shape = (B, L, L, L, 6)
    _dense("Dense_1", 6, hidden)

    # Voxel GAT layers
    for fi in range(n_focal_layers):
        _voxel_gat(f"SparseVoxelGraphConv_{fi}", hidden)
    focal_out = cur

    # Gated Fusion
    concat_gf = h("gf_concat")
    b.add_node("Concat", [global_out, focal_out], [concat_gf], axis=-1)
    b._shapes[concat_gf] = (B, L, L, L, hidden * 2)
    cur = concat_gf
    cur_shape = (B, L, L, L, hidden * 2)

    _dense("Dense_2", hidden * 2, 1)
    gate_sig = h("gate_sig")
    b.add_sigmoid(cur, gate_sig)

    # fused = gate * global + (1-gate) * focal
    c_one_gf = h("c_one_gf")
    b.add_const(c_one_gf, np.array(1.0, dtype=np.float32))
    one_minus_gate = h("one_minus_gate")
    b.add_node("Sub", [c_one_gf, gate_sig], [one_minus_gate])
    b._shapes[one_minus_gate] = (B, L, L, L, 1)

    gated_g = h("gated_g")
    b.add_node("Mul", [gate_sig, global_out], [gated_g])
    b._shapes[gated_g] = (B, L, L, L, hidden)

    gated_f = h("gated_f")
    b.add_node("Mul", [one_minus_gate, focal_out], [gated_f])
    b._shapes[gated_f] = (B, L, L, L, hidden)

    fused = h("fused")
    b.add_node("Add", [gated_g, gated_f], [fused])
    b._shapes[fused] = (B, L, L, L, hidden)
    cur = fused
    cur_shape = (B, L, L, L, hidden)

    # Backbone
    backbone_start = n_global_layers
    for bi in range(n_backbone_layers):
        _fno_block(f"FNOBlock_{backbone_start + bi}", hidden, 6)

    # MoE Head
    _moe_head("", hidden, moe_hidden)

    # Task heads
    moe_out = cur

    # Stress (6ch)
    cur = moe_out
    cur_shape = (B, L, L, L, hidden)
    _task_head("Dense_3", "Dense_4", 6)
    stress_out = cur

    # Disp (3ch)
    cur = moe_out
    cur_shape = (B, L, L, L, hidden)
    _task_head("Dense_5", "Dense_6", 3)
    disp_out = cur

    # Phi (1ch)
    cur = moe_out
    cur_shape = (B, L, L, L, hidden)
    _task_head("Dense_7", "Dense_8", 1)
    phi_out = cur

    # Concatenate outputs
    concat_out = h("concat_out")
    b.add_node("Concat", [stress_out, disp_out, phi_out], [concat_out], axis=-1)
    b._shapes[concat_out] = (B, L, L, L, 10)

    # Mask by occupancy (channel 0 of input)
    occ_name = "occ_extracted"
    if "occ_extracted" not in b._shapes:
        starts_o = h("starts_o")
        ends_o = h("ends_o")
        ax_o = h("ax_o")
        steps_o = h("steps_o")
        b.add_const(starts_o, np.array([0], dtype=np.int64))
        b.add_const(ends_o, np.array([1], dtype=np.int64))
        b.add_const(ax_o, np.array([-1], dtype=np.int64))
        b.add_const(steps_o, np.array([1], dtype=np.int64))
        b.add_node("Slice", [input_name, starts_o, ends_o, ax_o, steps_o], [occ_name])
        b._shapes[occ_name] = (B, L, L, L, 1)

    final = h("output")
    b.add_node("Mul", [concat_out, occ_name], [final])
    b._shapes[final] = (B, L, L, L, 10)

    b.add_output(final, [B, L, L, L, 10])

    walker.assert_empty()

    model = b.build()
    from onnx import save_model
    save_model(model, str(output_path))
    return Path(output_path)

    # Validate
    from brnext.export.contract_adapter import validate_surrogate_contract
    validate_surrogate_contract(str(output_path))
