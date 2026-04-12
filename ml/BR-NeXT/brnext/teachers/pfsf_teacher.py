"""PFSF teacher — self-contained sparse Laplacian solver.

Mirrors brml.pipeline.auto_train.solve_pfsf_phi but copied here
so BR-NeXT remains portable.
"""
from __future__ import annotations

import numpy as np


def solve_pfsf_phi(occupancy, E_field, density_field) -> np.ndarray:
    """Solve PFSF Laplacian via scipy sparse direct solver.

    Returns phi in PFSF-native scale (invariant to sigmaMax).
    """
    try:
        from scipy.sparse import csr_matrix
        from scipy.sparse.linalg import spsolve
    except ImportError:
        raise RuntimeError("scipy is required for PFSF teacher")

    occ = occupancy
    Lx, Ly, Lz = occ.shape
    conductivity = np.where(occ, E_field, 0.0).astype(np.float64)
    source = np.where(occ, density_field * 9.81, 0.0).astype(np.float64)

    sigma_max = float(conductivity.max())
    if sigma_max < 1.0:
        return np.zeros((Lx, Ly, Lz), dtype=np.float32)

    cond_norm = conductivity / sigma_max
    src_norm = source / sigma_max

    vtype = np.zeros((Lx, Ly, Lz), dtype=np.uint8)
    vtype[occ] = 1
    vtype[:, 0, :] = np.where(occ[:, 0, :], 2, 0)

    # Only keep voxels connected to the bottom face (grounded component)
    from scipy.ndimage import label
    labeled, nfeat = label(occ, structure=np.array([[[0,0,0],[0,1,0],[0,0,0]],
                                                     [[0,1,0],[1,1,1],[0,1,0]],
                                                     [[0,0,0],[0,1,0],[0,0,0]]]))
    grounded = np.zeros((Lx, Ly, Lz), dtype=bool)
    for i in range(1, nfeat + 1):
        mask = (labeled == i)
        if mask[:, 0, :].any():
            grounded |= mask
    vtype[~grounded] = 0

    is_interior = (vtype == 1)
    N_int = int(is_interior.sum())
    if N_int == 0:
        return np.zeros((Lx, Ly, Lz), dtype=np.float32)

    xs, ys, zs = np.where(is_interior)
    glob_to_row = np.full((Lx, Ly, Lz), -1, dtype=np.int32)
    glob_to_row[is_interior] = np.arange(N_int, dtype=np.int32)

    row_list, col_list, val_list = [], [], []
    diag = np.full(N_int, 1e-12, dtype=np.float64)
    b = src_norm[is_interior].copy()

    for dx, dy, dz in [(-1, 0, 0), (1, 0, 0), (0, -1, 0),
                       (0, 1, 0), (0, 0, -1), (0, 0, 1)]:
        nxs = xs + dx
        nys = ys + dy
        nzs = zs + dz

        in_bounds = ((nxs >= 0) & (nxs < Lx) &
                     (nys >= 0) & (nys < Ly) &
                     (nzs >= 0) & (nzs < Lz))
        nxc = np.clip(nxs, 0, Lx - 1)
        nyc = np.clip(nys, 0, Ly - 1)
        nzc = np.clip(nzs, 0, Lz - 1)

        vtype_nb = vtype[nxc, nyc, nzc]
        c_ij = np.minimum(cond_norm[xs, ys, zs], cond_norm[nxc, nyc, nzc])

        is_solid = in_bounds & (vtype_nb > 0)
        diag += np.where(is_solid, c_ij, 0.0)

        is_int_nb = in_bounds & (vtype_nb == 1)
        if is_int_nb.any():
            ri = np.where(is_int_nb)[0]
            ci = glob_to_row[nxc[is_int_nb], nyc[is_int_nb], nzc[is_int_nb]]
            row_list.append(ri)
            col_list.append(ci)
            val_list.append(-c_ij[is_int_nb])

    # Guard against isolated interior voxels (no solid neighbors)
    isolated = diag < 1e-10
    if isolated.any():
        diag = np.where(isolated, 1.0, diag)

    diag_idx = np.arange(N_int, dtype=np.int32)
    all_rows = np.concatenate([diag_idx] + row_list)
    all_cols = np.concatenate([diag_idx] + col_list)
    all_vals = np.concatenate([diag] + val_list)

    A = csr_matrix((all_vals, (all_rows, all_cols)), shape=(N_int, N_int))
    phi_int = spsolve(A, b)

    phi = np.zeros((Lx, Ly, Lz), dtype=np.float64)
    phi[is_interior] = phi_int
    phi = np.maximum(phi, 0.0)
    return phi.astype(np.float32)
