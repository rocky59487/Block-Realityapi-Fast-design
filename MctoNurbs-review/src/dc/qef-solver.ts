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
 * Jacobi eigenvalue decomposition for a 3x3 symmetric matrix.
 * Iteratively applies Givens rotations to diagonalize the matrix.
 *
 * Returns eigenvalues and eigenvectors (as column vectors stored row-major:
 * eigenvectors[row][column] where column = eigenvector index).
 */
function jacobiEigen3x3(
  m: number[][],
): { eigenvalues: [number, number, number]; eigenvectors: number[][] } {
  // Working copy of the matrix
  const a = [
    [m[0][0], m[0][1], m[0][2]],
    [m[1][0], m[1][1], m[1][2]],
    [m[2][0], m[2][1], m[2][2]],
  ];

  // Eigenvector matrix (starts as identity)
  const v = [
    [1, 0, 0],
    [0, 1, 0],
    [0, 0, 1],
  ];

  const maxIter = 50;
  for (let iter = 0; iter < maxIter; iter++) {
    // Find largest off-diagonal element
    let maxVal = 0;
    let p = 0, q = 1;
    for (let i = 0; i < 3; i++) {
      for (let j = i + 1; j < 3; j++) {
        const val = Math.abs(a[i][j]);
        if (val > maxVal) {
          maxVal = val;
          p = i;
          q = j;
        }
      }
    }

    // Convergence check
    if (maxVal < 1e-12) break;

    // Compute Givens rotation angle
    const diff = a[p][p] - a[q][q];
    let t: number;
    if (Math.abs(diff) < 1e-12) {
      t = 1; // 45 degree rotation
    } else {
      const tau = diff / (2 * a[p][q]);
      t = Math.sign(tau) / (Math.abs(tau) + Math.sqrt(1 + tau * tau));
    }

    const c = 1 / Math.sqrt(1 + t * t);
    const s = t * c;

    // Apply rotation to matrix: A' = GᵀAG
    const app = a[p][p];
    const aqq = a[q][q];
    const apq = a[p][q];

    a[p][p] = app + t * apq;  // Simplified: c²·app + 2cs·apq + s²·aqq
    a[q][q] = aqq - t * apq;  // Simplified: s²·app - 2cs·apq + c²·aqq
    a[p][q] = 0;
    a[q][p] = 0;

    // Update off-diagonal elements
    for (let r = 0; r < 3; r++) {
      if (r === p || r === q) continue;
      const arp = a[r][p];
      const arq = a[r][q];
      a[r][p] = c * arp + s * arq;
      a[p][r] = a[r][p];
      a[r][q] = -s * arp + c * arq;
      a[q][r] = a[r][q];
    }

    // Update eigenvectors
    for (let r = 0; r < 3; r++) {
      const vrp = v[r][p];
      const vrq = v[r][q];
      v[r][p] = c * vrp + s * vrq;
      v[r][q] = -s * vrp + c * vrq;
    }
  }

  // Sort eigenvalues by descending absolute value.
  // Without sorting, the SVD truncation threshold (based on max eigenvalue)
  // behaves unpredictably because Jacobi iteration doesn't guarantee order.
  const unsorted: [number, number][] = [[0, a[0][0]], [1, a[1][1]], [2, a[2][2]]];
  unsorted.sort((a, b) => Math.abs(b[1]) - Math.abs(a[1]));
  const order = unsorted.map(e => e[0]);

  return {
    eigenvalues: [unsorted[0][1], unsorted[1][1], unsorted[2][1]] as [number, number, number],
    eigenvectors: v.map(row => [row[order[0]], row[order[1]], row[order[2]]]),
  };
}
