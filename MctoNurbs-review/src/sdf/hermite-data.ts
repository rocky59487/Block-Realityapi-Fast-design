import type { Vec3, HermiteEdge } from '../types.js';
import type { SDFGrid } from './sdf-grid.js';

/**
 * Direction vectors for the 3 edges emanating from a grid cell corner.
 * Edge 0: +X direction
 * Edge 1: +Y direction
 * Edge 2: +Z direction
 */
export const EDGE_DIRS: readonly [number, number, number][] = [
  [1, 0, 0],
  [0, 1, 0],
  [0, 0, 1],
];

/**
 * For a cell at (ix, iy, iz), compute Hermite data for each of its 3
 * positive-direction edges that exhibit a sign change.
 *
 * Returns an array of up to 3 HermiteEdge entries (one per sign-changing edge),
 * or null for edges with no sign change.
 */
export function computeCellHermiteData(
  grid: SDFGrid,
  ix: number,
  iy: number,
  iz: number,
): (HermiteEdge | null)[] {
  const result: (HermiteEdge | null)[] = [null, null, null];
  const v0 = grid.get(ix, iy, iz);

  for (let edgeIdx = 0; edgeIdx < 3; edgeIdx++) {
    const [dx, dy, dz] = EDGE_DIRS[edgeIdx];
    const nx = ix + dx;
    const ny = iy + dy;
    const nz = iz + dz;

    if (!grid.inBounds(nx, ny, nz)) continue;

    const v1 = grid.get(nx, ny, nz);

    // Check for sign change (one negative, one positive)
    if ((v0 < 0) === (v1 < 0)) continue;

    // Linear interpolation to find zero-crossing
    const t = v0 / (v0 - v1);
    const [wx0, wy0, wz0] = grid.toWorld(ix, iy, iz);
    const point: Vec3 = {
      x: wx0 + t * dx * grid.cellSize,
      y: wy0 + t * dy * grid.cellSize,
      z: wz0 + t * dz * grid.cellSize,
    };

    // Compute normal via central differences of the SDF at the intersection
    const normal = computeGradient(grid, point);

    result[edgeIdx] = { point, normal };
  }

  return result;
}

/**
 * Compute the gradient of the SDF at an arbitrary world position
 * using trilinear interpolation of central differences.
 *
 * Instead of computing the gradient at a single nearest grid point (which
 * produces only axis-aligned normals for voxel geometry), we:
 * 1. Compute the gradient via central differences at all 8 corners of
 *    the containing grid cell
 * 2. Trilinearly interpolate based on the fractional position within the cell
 *
 * This produces smooth, direction-accurate normals even at feature edges
 * and corners, because the interpolation captures gradient variation across
 * the cell — critical for QEF to correctly place vertices at sharp features.
 */
function computeGradient(grid: SDFGrid, pos: Vec3): Vec3 {
  const [gx, gy, gz] = grid.toGrid(pos.x, pos.y, pos.z);

  // Grid cell containing this point
  const ix = Math.floor(gx);
  const iy = Math.floor(gy);
  const iz = Math.floor(gz);

  // Fractional position within the cell [0, 1)
  const fx = gx - ix;
  const fy = gy - iy;
  const fz = gz - iz;

  const h = grid.cellSize;
  let dx = 0, dy = 0, dz = 0;

  // Trilinearly interpolate gradients computed at the 8 cell corners
  for (let cz = 0; cz <= 1; cz++) {
    for (let cy = 0; cy <= 1; cy++) {
      for (let cx = 0; cx <= 1; cx++) {
        // Trilinear weight for this corner
        const w = (cx ? fx : 1 - fx) * (cy ? fy : 1 - fy) * (cz ? fz : 1 - fz);
        if (w < 1e-12) continue;

        // Clamp to valid range for central differences (need ±1 neighbors)
        const gix = Math.min(Math.max(ix + cx, 1), grid.sizeX - 2);
        const giy = Math.min(Math.max(iy + cy, 1), grid.sizeY - 2);
        const giz = Math.min(Math.max(iz + cz, 1), grid.sizeZ - 2);

        // Central differences at this corner
        dx += w * (grid.get(gix + 1, giy, giz) - grid.get(gix - 1, giy, giz));
        dy += w * (grid.get(gix, giy + 1, giz) - grid.get(gix, giy - 1, giz));
        dz += w * (grid.get(gix, giy, giz + 1) - grid.get(gix, giy, giz - 1));
      }
    }
  }

  // Scale by 1/(2h) for proper gradient magnitude
  dx /= (2 * h);
  dy /= (2 * h);
  dz /= (2 * h);

  // Normalize
  const len = Math.sqrt(dx * dx + dy * dy + dz * dz);
  if (len < 1e-10) {
    return { x: 0, y: 1, z: 0 }; // fallback normal
  }

  return { x: dx / len, y: dy / len, z: dz / len };
}
