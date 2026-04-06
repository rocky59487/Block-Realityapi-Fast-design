import type { Vec3, HermiteEdge } from '../types.js';

/**
 * QEF (Quadratic Error Function) Solver for Dual Contouring.
 *
 * Given a set of planes (defined by point + normal pairs from Hermite data),
 * finds the point that minimizes the sum of squared distances to all planes:
 *
 *   minimize Σ (n_i · (x - p_i))²
 *
 * This is equivalent to solving the normal equations: AᵀA·x = AᵀB
 * where A = matrix of normals, B = vector of (n_i · p_i) values.
 *
 * Uses SVD-based pseudo-inverse for numerical stability, with singular
 * value truncation to handle degenerate cases (e.g., all planes parallel).
 */

/** 3x3 symmetric matrix stored as [a00, a01, a02, a11, a12, a22] */
type Sym3x3 = [number, number, number, number, number, number];

export interface QEFResult {
  position: Vec3;
  error: number;
}

/**
 * Solve the QEF for a set of Hermite edge intersections.
 * The result is clamped to the given cell bounds.
 */
export function solveQEF(
  hermiteEdges: HermiteEdge[],
  cellMin: Vec3,
  cellMax: Vec3,
  smoothing: number = 0,
): QEFResult {
  if (hermiteEdges.length === 0) {
    return {
      position: {
        x: (cellMin.x + cellMax.x) / 2,
        y: (cellMin.y + cellMax.y) / 2,
        z: (cellMin.z + cellMax.z) / 2,
      },
      error: 0,
    };
  }

  // Build AᵀA (3x3 symmetric) and AᵀB (3-vector)
  // AᵀA[i][j] = Σ n_i_k * n_j_k
  // AᵀB[i] = Σ n_i_k * (n_k · p_k)
  let ata: Sym3x3 = [0, 0, 0, 0, 0, 0];
  let atb: [number, number, number] = [0, 0, 0];

  // Mass point (centroid of intersection points) for regularization
  let massX = 0, massY = 0, massZ = 0;

  for (const edge of hermiteEdges) {
    const { normal: n, point: p } = edge;
    const d = n.x * p.x + n.y * p.y + n.z * p.z;

    // Accumulate AᵀA
    ata[0] += n.x * n.x; // a00
    ata[1] += n.x * n.y; // a01
    ata[2] += n.x * n.z; // a02
    ata[3] += n.y * n.y; // a11
    ata[4] += n.y * n.z; // a12
    ata[5] += n.z * n.z; // a22

    // Accumulate AᵀB
    atb[0] += n.x * d;
    atb[1] += n.y * d;
    atb[2] += n.z * d;

    massX += p.x;
    massY += p.y;
    massZ += p.z;
  }

  const count = hermiteEdges.length;
  massX /= count;
  massY /= count;
  massZ /= count;

  // Apply smoothing regularization toward mass point
  // This biases the solution toward the centroid, which prevents
  // extreme vertex positions in under-constrained cases
  if (smoothing > 0) {
    // Fixed regularization strength — NOT scaled by edge count.
    // Scaling by count makes cells with many edges oversmoothed.
    const reg = smoothing;
    ata[0] += reg; // a00
    ata[3] += reg; // a11
    ata[5] += reg; // a22

    atb[0] += reg * massX;
    atb[1] += reg * massY;
    atb[2] += reg * massZ;
  }

  // Solve via SVD-based pseudo-inverse
  let position = solveSVD3x3(ata, atb, massX, massY, massZ);

  // If SVD solution is outside cell bounds, prefer the mass point.
  // The mass point (centroid of edge intersections) is always geometrically valid
  // and on the surface, avoiding topology breaks from hard cell-corner clamping.
  const outsideBounds =
    position.x < cellMin.x || position.x > cellMax.x ||
    position.y < cellMin.y || position.y > cellMax.y ||
    position.z < cellMin.z || position.z > cellMax.z;
  if (outsideBounds) {
    position = { x: massX, y: massY, z: massZ };
  } else {
    // Safety clamp (should be no-op for in-bounds solutions)
    position.x = Math.max(cellMin.x, Math.min(cellMax.x, position.x));
    position.y = Math.max(cellMin.y, Math.min(cellMax.y, position.y));
    position.z = Math.max(cellMin.z, Math.min(cellMax.z, position.z));
  }

  // Compute error
  let error = 0;
  for (const edge of hermiteEdges) {
    const { normal: n, point: p } = edge;
    const d = n.x * (position.x - p.x) + n.y * (position.y - p.y) + n.z * (position.z - p.z);
    error += d * d;
  }

  return { position, error };
}

/**
 * Solve 3x3 symmetric system AᵀA·x = AᵀB using SVD.
 * Uses Jacobi eigenvalue iteration for the 3x3 symmetric case.
 *
 * SVD truncation threshold prevents instability from near-zero singular values.
 * When a singular value is too small, the corresponding component defaults
 * to the mass point, which preserves sharp features.
 */
function solveSVD3x3(
  ata: Sym3x3,
  atb: [number, number, number],
  massX: number,
  massY: number,
  massZ: number,
): Vec3 {
  // For a symmetric matrix, SVD = eigendecomposition: A = V·Σ·Vᵀ
  // pseudo-inverse: A⁺ = V·Σ⁺·Vᵀ

  // Extract full 3x3 matrix
  const m: number[][] = [
    [ata[0], ata[1], ata[2]],
    [ata[1], ata[3], ata[4]],
    [ata[2], ata[4], ata[5]],
  ];

  // Jacobi eigenvalue decomposition
  const { eigenvalues, eigenvectors } = jacobiEigen3x3(m);

  // Compute pseudo-inverse solution: x = V · Σ⁺ · Vᵀ · b
  // where Σ⁺ truncates near-zero eigenvalues
  // Relative + absolute threshold: prevents division by near-zero eigenvalues.
  // The absolute minimum (1e-12) handles the degenerate case where all eigenvalues are zero.
  const threshold = Math.max(
    1e-6 * Math.max(Math.abs(eigenvalues[0]), Math.abs(eigenvalues[1]), Math.abs(eigenvalues[2])),
    1e-12,
  );

  // Vᵀ · b
  const vtb: [number, number, number] = [0, 0, 0];
  for (let i = 0; i < 3; i++) {
    vtb[i] = eigenvectors[0][i] * atb[0] +
             eigenvectors[1][i] * atb[1] +
             eigenvectors[2][i] * atb[2];
  }

  // Vᵀ · massPoint (for truncated components)
  const vtm: [number, number, number] = [0, 0, 0];
  for (let i = 0; i < 3; i++) {
    vtm[i] = eigenvectors[0][i] * massX +
             eigenvectors[1][i] * massY +
             eigenvectors[2][i] * massZ;
  }

  // Σ⁺ · (Vᵀ · b), with truncation falling back to mass point
  const sinvb: [number, number, number] = [0, 0, 0];
  for (let i = 0; i < 3; i++) {
    if (Math.abs(eigenvalues[i]) > threshold) {
      sinvb[i] = vtb[i] / eigenvalues[i];
    } else {
      sinvb[i] = vtm[i]; // Fall back to mass point component
    }
  }

  // V · result
  const x = eigenvectors[0][0] * sinvb[0] + eigenvectors[0][1] * sinvb[1] + eigenvectors[0][2] * sinvb[2];
  const y = eigenvectors[1][0] * sinvb[0] + eigenvectors[1][1] * sinvb[1] + eigenvectors[1][2] * sinvb[2];
  const z = eigenvectors[2][0] * sinvb[0] + eigenvectors[2][1] * sinvb[1] + eigenvectors[2][2] * sinvb[2];

  return { x, y, z };
}

/**
 * ★ Audit fix (劉教授): Analytic eigenvalue decomposition for 3×3 symmetric matrices.
 *
 * Replaces the previous Jacobi iterative solver (50 iterations per cell,
 * 1.35 billion FLOPs for a 100³ grid) with a closed-form solution using
 * Cardano's formula for the cubic characteristic polynomial.
 *
 * For a 3×3 symmetric matrix A, the eigenvalues are roots of:
 *   det(A - λI) = -λ³ + tr(A)λ² - (A₁₁A₂₂ - A₁₂² + A₀₀A₂₂ - A₀₂² + A₀₀A₁₁ - A₀₁²)λ + det(A) = 0
 *
 * This is solved exactly using Cardano's depressed cubic formula,
 * which requires only ~30 FLOPs vs ~300 FLOPs per Jacobi iteration × 5-50 iterations.
 *
 * Eigenvectors are computed via cross-product of rows of (A - λI), which is
 * exact for distinct eigenvalues and handled gracefully for repeated eigenvalues.
 *
 * References:
 *   - Smith, O.K. (1961). "Eigenvalues of a symmetric 3×3 matrix". CACM 4(4).
 *   - Kopp, J. (2008). "Efficient numerical diagonalization of hermitian 3×3 matrices".
 *     Int. J. Mod. Phys. C 19(03), 523-548.
 */
/**
 * ★ P1-fix: Cardano 解析解取代 Jacobi 迭代。
 *
 * 3×3 對稱矩陣的特徵值透過特徵多項式 Cardano 公式精確求解（~30 FLOPs）。
 * 特徵向量透過 (A - λI) 的行向量叉積計算。
 *
 * References:
 *   - Smith, O.K. (1961). "Eigenvalues of a symmetric 3×3 matrix". CACM 4(4).
 *   - Kopp, J. (2008). Int. J. Mod. Phys. C 19(03), 523-548.
 */
function jacobiEigen3x3(
  m: number[][],
): { eigenvalues: [number, number, number]; eigenvectors: number[][] } {
  const a00 = m[0][0], a01 = m[0][1], a02 = m[0][2];
  const a11 = m[1][1], a12 = m[1][2];
  const a22 = m[2][2];

  // Characteristic polynomial: -λ³ + p·λ + q = 0 (depressed cubic after shift)
  const tr = a00 + a11 + a22; // trace
  const mean = tr / 3;

  // Shift matrix: B = A - mean·I
  const b00 = a00 - mean, b11 = a11 - mean, b22 = a22 - mean;

  // q = det(B) / 2,  p = (‖B‖²_F) / 6
  const p = (b00 * b00 + b11 * b11 + b22 * b22 + 2 * (a01 * a01 + a02 * a02 + a12 * a12)) / 6;

  const det = b00 * (b11 * b22 - a12 * a12)
            - a01 * (a01 * b22 - a12 * a02)
            + a02 * (a01 * a12 - b11 * a02);
  const halfDet = det / 2;

  // r = halfDet / p^(3/2) — clamped to [-1, 1] for numerical safety
  const pCubed = p * p * p;
  let r: number;
  if (pCubed <= 1e-30) {
    // Nearly identity or zero matrix — eigenvalues are all ~mean
    return {
      eigenvalues: [mean, mean, mean],
      eigenvectors: [[1, 0, 0], [0, 1, 0], [0, 0, 1]],
    };
  }
  r = Math.max(-1, Math.min(1, halfDet / Math.sqrt(pCubed)));

  // Cardano: three real roots via trigonometric solution
  const phi = Math.acos(r) / 3;
  const sqrtP2 = 2 * Math.sqrt(p);

  let eig0 = mean + sqrtP2 * Math.cos(phi);
  let eig1 = mean + sqrtP2 * Math.cos(phi - (2 * Math.PI / 3));
  let eig2 = mean + sqrtP2 * Math.cos(phi - (4 * Math.PI / 3));

  // Sort by descending absolute value
  const eigs = [eig0, eig1, eig2].sort((a, b) => Math.abs(b) - Math.abs(a));
  eig0 = eigs[0]; eig1 = eigs[1]; eig2 = eigs[2];

  // Compute eigenvectors via cross product of rows of (A - λI)
  function eigenvector(lam: number): [number, number, number] {
    // Rows of (A - λI)
    const r0: [number, number, number] = [a00 - lam, a01, a02];
    const r1: [number, number, number] = [a01, a11 - lam, a12];
    const r2: [number, number, number] = [a02, a12, a22 - lam];

    // Try cross product of pairs — pick the one with largest norm
    const crosses: [number, number, number][] = [
      cross3(r0, r1),
      cross3(r0, r2),
      cross3(r1, r2),
    ];

    let best = crosses[0];
    let bestNorm = norm3(best);
    for (let i = 1; i < 3; i++) {
      const n = norm3(crosses[i]);
      if (n > bestNorm) { best = crosses[i]; bestNorm = n; }
    }

    if (bestNorm < 1e-10) {
      // Rows of (A - λI) are nearly collinear or zero.
      // Find the row with the largest norm to identify the constraint direction.
      let maxRow: [number, number, number] = r0;
      let maxRowNorm = norm3(r0);
      for (const row of [r1, r2] as [number, number, number][]) {
        const rn = norm3(row);
        if (rn > maxRowNorm) { maxRow = row; maxRowNorm = rn; }
      }
      if (maxRowNorm < 1e-15) return [1, 0, 0]; // truly degenerate (triple eigenvalue)
      // Pick the standard basis vector most orthogonal to maxRow, then Gram-Schmidt
      const invN = 1 / maxRowNorm;
      const nx = maxRow[0] * invN, ny = maxRow[1] * invN, nz = maxRow[2] * invN;
      let bestBase: [number, number, number] = [1, 0, 0];
      let minAbsDot = Math.abs(nx);
      if (Math.abs(ny) < minAbsDot) { bestBase = [0, 1, 0]; minAbsDot = Math.abs(ny); }
      if (Math.abs(nz) < minAbsDot) { bestBase = [0, 0, 1]; }
      const dot = bestBase[0] * nx + bestBase[1] * ny + bestBase[2] * nz;
      const proj: [number, number, number] = [
        bestBase[0] - dot * nx,
        bestBase[1] - dot * ny,
        bestBase[2] - dot * nz,
      ];
      const pn = norm3(proj);
      if (pn < 1e-15) return bestBase;
      return [proj[0] / pn, proj[1] / pn, proj[2] / pn];
    }
    const inv = 1 / bestNorm;
    return [best[0] * inv, best[1] * inv, best[2] * inv];
  }

  const v0 = eigenvector(eig0);
  const v2 = eigenvector(eig2);
  // v1 = v2 × v0 for orthogonality — correctly handles repeated eigenvalue case (eig0 = eig1)
  const v1raw = cross3(v2, v0);
  const v1norm = norm3(v1raw);
  const v1: [number, number, number] = v1norm > 1e-15
    ? [v1raw[0] / v1norm, v1raw[1] / v1norm, v1raw[2] / v1norm]
    : eigenvector(eig1);

  return {
    eigenvalues: [eig0, eig1, eig2],
    eigenvectors: [
      [v0[0], v1[0], v2[0]],
      [v0[1], v1[1], v2[1]],
      [v0[2], v1[2], v2[2]],
    ],
  };
}

function cross3(a: [number, number, number], b: [number, number, number]): [number, number, number] {
  return [
    a[1] * b[2] - a[2] * b[1],
    a[2] * b[0] - a[0] * b[2],
    a[0] * b[1] - a[1] * b[0],
  ];
}

function norm3(v: [number, number, number]): number {
  return Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
}