import type { BlockData, AABB } from '../types.js';

/**
 * 3D Signed Distance Field grid.
 * Stores scalar values in a flat Float32Array with 3D indexing.
 * Convention: negative = inside solid, positive = outside (air), zero = surface.
 */
export class SDFGrid {
  readonly data: Float32Array;
  readonly sizeX: number;
  readonly sizeY: number;
  readonly sizeZ: number;
  readonly originX: number;
  readonly originY: number;
  readonly originZ: number;
  readonly cellSize: number;

  constructor(
    sizeX: number,
    sizeY: number,
    sizeZ: number,
    originX: number,
    originY: number,
    originZ: number,
    cellSize: number,
  ) {
    this.sizeX = sizeX;
    this.sizeY = sizeY;
    this.sizeZ = sizeZ;
    this.originX = originX;
    this.originY = originY;
    this.originZ = originZ;
    this.cellSize = cellSize;
    this.data = new Float32Array(sizeX * sizeY * sizeZ);
  }

  /** Convert 3D index to flat array index */
  index(ix: number, iy: number, iz: number): number {
    return ix + this.sizeX * (iy + this.sizeY * iz);
  }

  /** Get SDF value at grid coordinates */
  get(ix: number, iy: number, iz: number): number {
    return this.data[this.index(ix, iy, iz)];
  }

  /** Set SDF value at grid coordinates */
  set(ix: number, iy: number, iz: number, value: number): void {
    this.data[this.index(ix, iy, iz)] = value;
  }

  /** Check if grid indices are in bounds */
  inBounds(ix: number, iy: number, iz: number): boolean {
    return ix >= 0 && ix < this.sizeX &&
           iy >= 0 && iy < this.sizeY &&
           iz >= 0 && iz < this.sizeZ;
  }

  /** Convert grid coordinates to world coordinates */
  toWorld(ix: number, iy: number, iz: number): [number, number, number] {
    return [
      this.originX + ix * this.cellSize,
      this.originY + iy * this.cellSize,
      this.originZ + iz * this.cellSize,
    ];
  }

  /** Convert world coordinates to grid coordinates (not rounded) */
  toGrid(wx: number, wy: number, wz: number): [number, number, number] {
    return [
      (wx - this.originX) / this.cellSize,
      (wy - this.originY) / this.cellSize,
      (wz - this.originZ) / this.cellSize,
    ];
  }
}

/**
 * Compute axis-aligned bounding box of a set of blocks.
 */
export function computeAABB(blocks: BlockData[]): AABB {
  let minX = Infinity, minY = Infinity, minZ = Infinity;
  let maxX = -Infinity, maxY = -Infinity, maxZ = -Infinity;

  for (const b of blocks) {
    if (b.x < minX) minX = b.x;
    if (b.y < minY) minY = b.y;
    if (b.z < minZ) minZ = b.z;
    if (b.x > maxX) maxX = b.x;
    if (b.y > maxY) maxY = b.y;
    if (b.z > maxZ) maxZ = b.z;
  }

  return { minX, minY, minZ, maxX, maxY, maxZ };
}

/**
 * Pack 3D block coordinates into a single numeric key for fast Set lookups.
 * Uses bit packing: 15 bits per axis, supporting coords in [-16384, +16383].
 * This avoids string allocation overhead that kills GC performance in hot loops.
 */
function blockKey(x: number, y: number, z: number): number {
  return ((x + 16384) * 32768 + (y + 16384)) * 32768 + (z + 16384);
}

/**
 * Build an SDF grid from discrete voxel block data.
 *
 * Each block occupies a 1x1x1 cube in world space: [x, x+1] × [y, y+1] × [z, z+1].
 * Padding of 2 cells is added around the bounding box so DC can extract the outer surface.
 *
 * CRITICAL: Grid points are offset by half a cell so they sample at block centers,
 * NOT at block corners. This ensures no grid point lies exactly on a voxel surface
 * face, which would produce distance=0 and break sign-change detection.
 *
 * For each grid point, the SDF value is computed as:
 *   sign * exact_euclidean_distance_to_nearest_surface_face
 *
 * where "surface face" = the boundary between an occupied block and an air cell.
 * This produces true signed distances with smooth gradients, enabling:
 *   - Accurate edge intersection via linear interpolation (not stuck at midpoints)
 *   - Correct normals via gradient computation (not just axis-aligned)
 *   - Proper QEF feature preservation at corners and edges
 */
export function buildSDFGrid(
  blocks: BlockData[],
  resolution: number = 1,
): SDFGrid {
  if (blocks.length === 0) {
    return new SDFGrid(0, 0, 0, 0, 0, 0, 1);
  }

  const aabb = computeAABB(blocks);
  const padding = 2;
  const cellSize = 1.0 / resolution;
  const halfCell = 0.5 * cellSize;

  // Grid origin offset by half a cell so samples are at block centers.
  // For resolution=1: samples at x.5, y.5, z.5 positions (never on block boundaries).
  const gridMinX = aabb.minX - padding + halfCell;
  const gridMinY = aabb.minY - padding + halfCell;
  const gridMinZ = aabb.minZ - padding + halfCell;
  const gridMaxX = aabb.maxX + 1 + padding - halfCell;
  const gridMaxY = aabb.maxY + 1 + padding - halfCell;
  const gridMaxZ = aabb.maxZ + 1 + padding - halfCell;

  const sizeX = Math.ceil((gridMaxX - gridMinX) / cellSize) + 1;
  const sizeY = Math.ceil((gridMaxY - gridMinY) / cellSize) + 1;
  const sizeZ = Math.ceil((gridMaxZ - gridMinZ) / cellSize) + 1;

  const grid = new SDFGrid(sizeX, sizeY, sizeZ, gridMinX, gridMinY, gridMinZ, cellSize);

  // Build a fast lookup set for occupied block positions.
  // Use numeric keys instead of strings to avoid GC pressure from billions of
  // string allocations in hot loops. Safe for coords in [-16384, +16383].
  const occupied = new Set<number>();
  for (const b of blocks) {
    occupied.add(blockKey(b.x, b.y, b.z));
  }

  // Phase 1: Sign classification — O(n) where n = grid size
  const signs = new Int8Array(sizeX * sizeY * sizeZ);
  for (let iz = 0; iz < sizeZ; iz++) {
    for (let iy = 0; iy < sizeY; iy++) {
      for (let ix = 0; ix < sizeX; ix++) {
        const [wx, wy, wz] = grid.toWorld(ix, iy, iz);
        const inside = occupied.has(blockKey(Math.floor(wx), Math.floor(wy), Math.floor(wz)));
        signs[grid.index(ix, iy, iz)] = inside ? -1 : 1;
      }
    }
  }

  // Phase 2: Inverted-loop distance computation — O(surfaceFaces × nearbyPoints)
  //
  // Instead of "for each grid point, search ±3 blocks" (O(gridSize × 2058)),
  // we do "for each surface face, update nearby grid points".
  // For N surface blocks: ~6N faces × ~216 grid points each ≈ 1296N updates.
  // This is 100× faster for typical inputs (10K blocks → 13M vs 1.3B lookups).
  const INF = 1e20;
  const distSq = new Float32Array(sizeX * sizeY * sizeZ);
  distSq.fill(INF);

  // Search radius in grid cells (covers padding + diagonal)
  const searchRadius = padding + 1;

  for (const b of blocks) {
    // Check each of 6 faces of this block for exposed surfaces
    const dirs: [number, number, number, number, number][] = [
      [1, 0, 0, b.x + 1, 0],  // +X face at x = bx+1
      [-1, 0, 0, b.x, 0],     // -X face at x = bx
      [0, 1, 0, b.y + 1, 1],  // +Y face at y = by+1
      [0, -1, 0, b.y, 1],     // -Y face at y = by
      [0, 0, 1, b.z + 1, 2],  // +Z face at z = bz+1
      [0, 0, -1, b.z, 2],     // -Z face at z = bz
    ];

    for (const [dx, dy, dz, coord, axis] of dirs) {
      const adjKey = blockKey(b.x + dx, b.y + dy, b.z + dz);
      if (occupied.has(adjKey)) continue; // Internal face, skip

      // This is an exposed surface face. Update all nearby grid points.
      // Face spans: axis=0 → [by, by+1]×[bz, bz+1]; axis=1 → [bx, bx+1]×[bz, bz+1]; axis=2 → [bx, bx+1]×[by, by+1]
      const u0 = axis === 0 ? b.y : b.x;
      const v0 = axis === 2 ? b.y : b.z;
      const u1 = u0 + 1;
      const v1 = v0 + 1;

      // Grid index range for nearby points (clamped to grid bounds)
      const [gMinX, gMinY, gMinZ] = grid.toGrid(
        (axis === 0 ? coord : u0) - searchRadius,
        (axis === 1 ? coord : (axis === 0 ? u0 : v0)) - searchRadius,
        (axis === 2 ? coord : v0) - searchRadius,
      );
      const [gMaxX, gMaxY, gMaxZ] = grid.toGrid(
        (axis === 0 ? coord : u1) + searchRadius,
        (axis === 1 ? coord : (axis === 0 ? u1 : v1)) + searchRadius,
        (axis === 2 ? coord : v1) + searchRadius,
      );

      const ixMin = Math.max(0, Math.floor(gMinX));
      const iyMin = Math.max(0, Math.floor(gMinY));
      const izMin = Math.max(0, Math.floor(gMinZ));
      const ixMax = Math.min(sizeX - 1, Math.ceil(gMaxX));
      const iyMax = Math.min(sizeY - 1, Math.ceil(gMaxY));
      const izMax = Math.min(sizeZ - 1, Math.ceil(gMaxZ));

      for (let giz = izMin; giz <= izMax; giz++) {
        for (let giy = iyMin; giy <= iyMax; giy++) {
          for (let gix = ixMin; gix <= ixMax; gix++) {
            const [wx, wy, wz] = grid.toWorld(gix, giy, giz);
            const d2 = distSqToAxisAlignedFace(wx, wy, wz, axis, coord, u0, v0, u1, v1);
            const idx = grid.index(gix, giy, giz);
            if (d2 < distSq[idx]) distSq[idx] = d2;
          }
        }
      }
    }
  }

  // Phase 3: Write final SDF = sign × distance
  for (let i = 0; i < grid.data.length; i++) {
    const d = distSq[i] < INF ? Math.sqrt(distSq[i]) : INF;
    grid.data[i] = signs[i] * d;
  }

  return grid;
}

/**
 * Compute squared distance from point (px, py, pz) to an axis-aligned rectangle.
 *
 * @param axis - 0=X, 1=Y, 2=Z: the axis perpendicular to the face
 * @param coord - the coordinate of the face along that axis
 * @param u0, v0 - minimum bounds of the face in the other two axes
 * @param u1, v1 - maximum bounds of the face in the other two axes
 */
function distSqToAxisAlignedFace(
  px: number,
  py: number,
  pz: number,
  axis: number,
  coord: number,
  u0: number,
  v0: number,
  u1: number,
  v1: number,
): number {
  let perpDist: number;
  let pu: number, pv: number;

  if (axis === 0) {
    // Face perpendicular to X at x=coord, spans [u0,u1] in Y, [v0,v1] in Z
    perpDist = px - coord;
    pu = py;
    pv = pz;
  } else if (axis === 1) {
    // Face perpendicular to Y at y=coord, spans [u0,u1] in X, [v0,v1] in Z
    perpDist = py - coord;
    pu = px;
    pv = pz;
  } else {
    // Face perpendicular to Z at z=coord, spans [u0,u1] in X, [v0,v1] in Y
    perpDist = pz - coord;
    pu = px;
    pv = py;
  }

  // Clamp to face rectangle
  const cu = Math.max(u0, Math.min(u1, pu));
  const cv = Math.max(v0, Math.min(v1, pv));

  const du = pu - cu;
  const dv = pv - cv;

  return perpDist * perpDist + du * du + dv * dv;
}

/* eslint-disable @typescript-eslint/no-unused-vars -- EDT kept for future high-res support */
function _edt3D(
  distSq: Float64Array,
  sizeX: number,
  sizeY: number,
  sizeZ: number,
): void {
  // Temporary buffer for 1D EDT
  const maxDim = Math.max(sizeX, sizeY, sizeZ);
  const f = new Float64Array(maxDim);
  const d = new Float64Array(maxDim);
  const v = new Int32Array(maxDim);
  const z = new Float64Array(maxDim + 1);

  // Pass 1: EDT along X axis
  for (let iz = 0; iz < sizeZ; iz++) {
    for (let iy = 0; iy < sizeY; iy++) {
      // Extract row
      const offset = iy * sizeX + iz * sizeX * sizeY;
      for (let ix = 0; ix < sizeX; ix++) {
        f[ix] = distSq[offset + ix];
      }
      // 1D EDT
      _edt1D(f, d, v, z, sizeX);
      // Write back
      for (let ix = 0; ix < sizeX; ix++) {
        distSq[offset + ix] = d[ix];
      }
    }
  }

  // Pass 2: EDT along Y axis
  for (let iz = 0; iz < sizeZ; iz++) {
    for (let ix = 0; ix < sizeX; ix++) {
      // Extract column
      for (let iy = 0; iy < sizeY; iy++) {
        f[iy] = distSq[ix + iy * sizeX + iz * sizeX * sizeY];
      }
      _edt1D(f, d, v, z, sizeY);
      for (let iy = 0; iy < sizeY; iy++) {
        distSq[ix + iy * sizeX + iz * sizeX * sizeY] = d[iy];
      }
    }
  }

  // Pass 3: EDT along Z axis
  for (let iy = 0; iy < sizeY; iy++) {
    for (let ix = 0; ix < sizeX; ix++) {
      // Extract depth column
      for (let iz = 0; iz < sizeZ; iz++) {
        f[iz] = distSq[ix + iy * sizeX + iz * sizeX * sizeY];
      }
      _edt1D(f, d, v, z, sizeZ);
      for (let iz = 0; iz < sizeZ; iz++) {
        distSq[ix + iy * sizeX + iz * sizeX * sizeY] = d[iz];
      }
    }
  }
}

/**
 * 1D Euclidean Distance Transform (Felzenszwalb-Huttenlocher).
 *
 * Computes the lower envelope of parabolas defined by f[i] + (x - i)².
 * For each output position q, d[q] = min_i(f[i] + (q - i)²).
 *
 * This gives exact squared Euclidean distances in O(n).
 *
 * @param f - Input: squared distance values at each position (modified as work buffer)
 * @param d - Output: minimum squared distance at each position
 * @param v - Work buffer: locations of parabolas in lower envelope
 * @param z - Work buffer: boundaries between parabolas
 * @param n - Length of the row
 */
function _edt1D(
  f: Float64Array,
  d: Float64Array,
  v: Int32Array,
  z: Float64Array,
  n: number,
): void {
  v[0] = 0;
  z[0] = -1e20;
  z[1] = 1e20;
  let k = 0;

  for (let q = 1; q < n; q++) {
    // Intersection of parabola at q with parabola at v[k]
    let s = ((f[q] + q * q) - (f[v[k]] + v[k] * v[k])) / (2 * q - 2 * v[k]);
    while (k > 0 && s <= z[k]) {
      k--;
      s = ((f[q] + q * q) - (f[v[k]] + v[k] * v[k])) / (2 * q - 2 * v[k]);
    }
    k++;
    v[k] = q;
    z[k] = s;
    z[k + 1] = 1e20;
  }

  k = 0;
  for (let q = 0; q < n; q++) {
    while (z[k + 1] < q) k++;
    d[q] = (q - v[k]) * (q - v[k]) + f[v[k]];
  }
}
