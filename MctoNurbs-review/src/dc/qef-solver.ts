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
  const position = solveSVD3x3(ata, atb, massX, massY, massZ);

  // Clamp to cell bounds
  position.x = Math.max(cellMin.x, Math.min(cellMax.x, position.x));
  position.y = Math.max(cellMin.y, Math.min(cellMax.y, position.y));
  position.z = Math.max(cellMin.z, Math.min(cellMax.z, position.z));

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
function jacobiEigen3x3(
  m: number[][],
): { eigenvalues: [number, number, number]; eigenvectors: number[][] } {
  const a00 = m[0][0], a01 = m[0][1], a02 = m[0][2];
  const a11 = m[1][1], a12 = m[1][2];
  const a22 = m[2][2];

  // Characteristic polynomial: -λ³ + p·λ + q = 0  (after shifting by trace/3)
  const trace = a00 + a11 + a22;
  const mean = trace / 3;

  // Shift matrix: B = A - mean·I
  const b00 = a00 - mean, b11 = a11 - mean, b22 = a22 - mean;

  // p = (1/6) × ( (a00-a11)² + (a00-a22)² + (a11-a22)² + 6(a01² + a02² + a12²) )
  const q0 = (a00 - a11), q1 = (a00 - a22), q2 = (a11 - a22);
  const p = (q0 * q0 + q1 * q1 + q2 * q2 + 6 * (a01 * a01 + a02 * a02 + a12 * a12)) / 6;

  // det(B) = b00(b11·b22 - a12²) - a01(a01·b22 - a12·a02) + a02(a01·a12 - b11·a02)
  const detB = b00 * (b11 * b22 - a12 * a12)
             - a01 * (a01 * b22 - a12 * a02)
             + a02 * (a01 * a12 - b11 * a02);

  // r = det(B) / 2
  const halfDet = detB / 2;

  // Handle degenerate case: p ≈ 0 means A ≈ mean·I (all eigenvalues equal)
  if (p < 1e-20) {
    return {
      eigenvalues: [mean, mean, mean],
      eigenvectors: [[1, 0, 0], [0, 1, 0], [0, 0, 1]],
    };
  }

  // phi = (1/3) × acos( r / (p^(3/2)) ), clamped to [-1, 1] for numerical safety
  const pSqrt = Math.sqrt(p);
  const pCubed = p * pSqrt; // p^(3/2)
  let phi = halfDet / pCubed;
  phi = Math.max(-1, Math.min(1, phi)); // clamp for acos
  phi = Math.acos(phi) / 3;

  // Eigenvalues from Cardano's formula (sorted: λ₀ ≥ λ₁ ≥ λ₂)
  const sqrt2p = 2 * Math.sqrt(p);
  const eig0 = mean + sqrt2p * Math.cos(phi);
  const eig2 = mean + sqrt2p * Math.cos(phi + (2 * Math.PI / 3));
  const eig1 = 3 * mean - eig0 - eig2; // trace = sum of eigenvalues

  const eigenvalues: [number, number, number] = [eig0, eig1, eig2];

  // Compute eigenvectors via cross products of rows of (A - λI)
  const eigenvectors: number[][] = [[0, 0, 0], [0, 0, 0], [0, 0, 0]];

  for (let i = 0; i < 3; i++) {
    const lam = eigenvalues[i];
    // Rows of (A - λI)
    const r0 = [a00 - lam, a01, a02];
    const r1 = [a01, a11 - lam, a12];
    const r2 = [a02, a12, a22 - lam];

    // Cross product of two rows gives the eigenvector direction
    // Try r0 × r1 first, fall back to r0 × r2 or r1 × r2 if degenerate
    let vx: number, vy: number, vz: number;

    vx = r0[1] * r1[2] - r0[2] * r1[1];
    vy = r0[2] * r1[0] - r0[0] * r1[2];
    vz = r0[0] * r1[1] - r0[1] * r1[0];
    let len = Math.sqrt(vx * vx + vy * vy + vz * vz);

    if (len < 1e-10) {
      // Try r0 × r2
      vx = r0[1] * r2[2] - r0[2] * r2[1];
      vy = r0[2] * r2[0] - r0[0] * r2[2];
      vz = r0[0] * r2[1] - r0[1] * r2[0];
      len = Math.sqrt(vx * vx + vy * vy + vz * vz);
    }

    if (len < 1e-10) {
      // Try r1 × r2
      vx = r1[1] * r2[2] - r1[2] * r2[1];
      vy = r1[2] * r2[0] - r1[0] * r2[2];
      vz = r1[0] * r2[1] - r1[1] * r2[0];
      len = Math.sqrt(vx * vx + vy * vy + vz * vz);
    }

    if (len < 1e-10) {
      // Degenerate: use canonical basis vector
      eigenvectors[0][i] = i === 0 ? 1 : 0;
      eigenvectors[1][i] = i === 1 ? 1 : 0;
      eigenvectors[2][i] = i === 2 ? 1 : 0;
    } else {
      eigenvectors[0][i] = vx / len;
      eigenvectors[1][i] = vy / len;
      eigenvectors[2][i] = vz / len;
    }
  }

  // Sort by descending absolute eigenvalue (preserves existing SVD truncation behavior)
  const indices = [0, 1, 2];
  indices.sort((a, b) => Math.abs(eigenvalues[b]) - Math.abs(eigenvalues[a]));

  return {
    eigenvalues: [eigenvalues[indices[0]], eigenvalues[indices[1]], eigenvalues[indices[2]]],
    eigenvectors: eigenvectors.map(row => [row[indices[0]], row[indices[1]], row[indices[2]]]),
  };
}
