import type { Mesh, Vec3, HermiteEdge } from '../types.js';
import type { SDFGrid } from '../sdf/sdf-grid.js';
import { EDGE_DIRS, computeCellHermiteData } from '../sdf/hermite-data.js';
import { solveQEF } from './qef-solver.js';
import { MeshBuilder } from './mesh.js';

/**
 * Dual Contouring algorithm for extracting an isosurface mesh from an SDF grid.
 *
 * The algorithm:
 * 1. For each cell, detect sign changes on its 3 positive-direction edges
 * 2. For cells with sign changes, compute Hermite data and solve QEF
 *    to place an optimal vertex inside the cell
 * 3. For each sign-changing edge, connect the vertices of the 4 cells
 *    sharing that edge to form a quad
 * 4. Triangulate quads into the final mesh
 *
 * DC preserves sharp features (corners, edges) because the QEF minimizer
 * naturally places vertices at feature intersections when the normals
 * from different planes are not parallel.
 */
export function dualContour(
  grid: SDFGrid,
  smoothing: number = 0,
): Mesh {
  const { sizeX, sizeY, sizeZ, cellSize } = grid;
  const builder = new MeshBuilder();

  // Pre-compute and cache ALL Hermite edge data in a single pass.
  // Without caching, collectCellEdges recomputes the same edges 2-4 times
  // (each edge is shared by up to 4 cells). Cache key = flat grid index.
  const hermiteCache = new Map<number, (HermiteEdge | null)[]>();
  for (let iz = 0; iz < sizeZ; iz++) {
    for (let iy = 0; iy < sizeY; iy++) {
      for (let ix = 0; ix < sizeX; ix++) {
        const data = computeCellHermiteData(grid, ix, iy, iz);
        if (data[0] !== null || data[1] !== null || data[2] !== null) {
          hermiteCache.set(grid.index(ix, iy, iz), data);
        }
      }
    }
  }

  // Map from cell index to vertex index in the mesh
  const cellVertexMap = new Map<number, number>();

  // Phase 1: Place vertices in cells that have sign-changing edges
  for (let iz = 0; iz < sizeZ - 1; iz++) {
    for (let iy = 0; iy < sizeY - 1; iy++) {
      for (let ix = 0; ix < sizeX - 1; ix++) {
        // Collect edges from cache instead of recomputing
        const allEdges = collectCellEdgesFromCache(grid, ix, iy, iz, hermiteCache);

        if (allEdges.length === 0) continue;

        // Cell bounds in world space
        // Grid points are offset by half a cell (see sdf-grid.ts), so the
        // DC cell is centered on the grid point, extending ±halfCell in each axis.
        const [wx, wy, wz] = grid.toWorld(ix, iy, iz);
        const halfCS = cellSize / 2;
        const cellMin: Vec3 = { x: wx - halfCS, y: wy - halfCS, z: wz - halfCS };
        const cellMax: Vec3 = {
          x: wx + halfCS,
          y: wy + halfCS,
          z: wz + halfCS,
        };

        // Solve QEF to find optimal vertex position
        const { position } = solveQEF(allEdges, cellMin, cellMax, smoothing);

        const cellIdx = ix + sizeX * (iy + sizeY * iz);
        const vertIdx = builder.addVertex(position);
        cellVertexMap.set(cellIdx, vertIdx);
      }
    }
  }

  // Phase 2: Generate quads for each sign-changing edge
  // Each internal edge is shared by 4 cells. We connect their vertices.
  for (let iz = 0; iz < sizeZ - 1; iz++) {
    for (let iy = 0; iy < sizeY - 1; iy++) {
      for (let ix = 0; ix < sizeX - 1; ix++) {
        const v0 = grid.get(ix, iy, iz);

        // Check edge in +X direction
        if (ix + 1 < sizeX) {
          const v1 = grid.get(ix + 1, iy, iz);
          if ((v0 < 0) !== (v1 < 0) && iy > 0 && iz > 0) {
            emitQuadX(grid, builder, cellVertexMap, ix, iy, iz, v0 < 0);
          }
        }

        // Check edge in +Y direction
        if (iy + 1 < sizeY) {
          const v1 = grid.get(ix, iy + 1, iz);
          if ((v0 < 0) !== (v1 < 0) && ix > 0 && iz > 0) {
            emitQuadY(grid, builder, cellVertexMap, ix, iy, iz, v0 < 0);
          }
        }

        // Check edge in +Z direction
        if (iz + 1 < sizeZ) {
          const v1 = grid.get(ix, iy, iz + 1);
          if ((v0 < 0) !== (v1 < 0) && ix > 0 && iy > 0) {
            emitQuadZ(grid, builder, cellVertexMap, ix, iy, iz, v0 < 0);
          }
        }
      }
    }
  }

  return builder.build();
}

/**
 * Collect all Hermite edge intersections for a cell from the pre-computed cache.
 * Each cell has 12 edges (4 per axis). Edges are deduplicated by their numeric key.
 */
function collectCellEdgesFromCache(
  grid: SDFGrid,
  ix: number,
  iy: number,
  iz: number,
  cache: Map<number, (HermiteEdge | null)[]>,
): HermiteEdge[] {
  const edges: HermiteEdge[] = [];

  // 12 edges of the cell: 4 per axis direction
  const edgeStarts: [number, number, number, number][] = [
    [ix, iy, iz, 0], [ix, iy + 1, iz, 0], [ix, iy, iz + 1, 0], [ix, iy + 1, iz + 1, 0],
    [ix, iy, iz, 1], [ix + 1, iy, iz, 1], [ix, iy, iz + 1, 1], [ix + 1, iy, iz + 1, 1],
    [ix, iy, iz, 2], [ix + 1, iy, iz, 2], [ix, iy + 1, iz, 2], [ix + 1, iy + 1, iz, 2],
  ];

  const seen = new Set<number>();
  for (const [ex, ey, ez, edgeDir] of edgeStarts) {
    if (!grid.inBounds(ex, ey, ez)) continue;
    const [dx, dy, dz] = EDGE_DIRS[edgeDir];
    if (!grid.inBounds(ex + dx, ey + dy, ez + dz)) continue;

    const edgeKey = ex + grid.sizeX * (ey + grid.sizeY * (ez + grid.sizeZ * edgeDir));
    if (seen.has(edgeKey)) continue;
    seen.add(edgeKey);

    // Read from cache instead of recomputing
    const cached = cache.get(grid.index(ex, ey, ez));
    if (cached && cached[edgeDir]) {
      edges.push(cached[edgeDir]!);
    }
  }

  return edges;
}

/**
 * Emit a quad for an X-direction edge at grid position (ix, iy, iz).
 * The 4 cells sharing this edge are:
 *   (ix, iy-1, iz-1), (ix, iy, iz-1), (ix, iy-1, iz), (ix, iy, iz)
 */
function emitQuadX(
  grid: SDFGrid,
  builder: MeshBuilder,
  cellVertexMap: Map<number, number>,
  ix: number,
  iy: number,
  iz: number,
  flipWinding: boolean,
): void {
  const { sizeX, sizeY } = grid;
  const cells = [
    ix + sizeX * ((iy - 1) + sizeY * (iz - 1)),
    ix + sizeX * (iy + sizeY * (iz - 1)),
    ix + sizeX * (iy + sizeY * iz),
    ix + sizeX * ((iy - 1) + sizeY * iz),
  ];

  const verts = cells.map(c => cellVertexMap.get(c));
  if (verts.some(v => v === undefined)) return;

  if (flipWinding) {
    builder.addQuad(verts[0]!, verts[3]!, verts[2]!, verts[1]!);
  } else {
    builder.addQuad(verts[0]!, verts[1]!, verts[2]!, verts[3]!);
  }
}

/**
 * Emit a quad for a Y-direction edge at grid position (ix, iy, iz).
 * The 4 cells sharing this edge are:
 *   (ix-1, iy, iz-1), (ix, iy, iz-1), (ix, iy, iz), (ix-1, iy, iz)
 */
function emitQuadY(
  grid: SDFGrid,
  builder: MeshBuilder,
  cellVertexMap: Map<number, number>,
  ix: number,
  iy: number,
  iz: number,
  flipWinding: boolean,
): void {
  const { sizeX, sizeY } = grid;
  const cells = [
    (ix - 1) + sizeX * (iy + sizeY * (iz - 1)),
    ix + sizeX * (iy + sizeY * (iz - 1)),
    ix + sizeX * (iy + sizeY * iz),
    (ix - 1) + sizeX * (iy + sizeY * iz),
  ];

  const verts = cells.map(c => cellVertexMap.get(c));
  if (verts.some(v => v === undefined)) return;

  if (flipWinding) {
    builder.addQuad(verts[0]!, verts[3]!, verts[2]!, verts[1]!);
  } else {
    builder.addQuad(verts[0]!, verts[1]!, verts[2]!, verts[3]!);
  }
}

/**
 * Emit a quad for a Z-direction edge at grid position (ix, iy, iz).
 * The 4 cells sharing this edge are:
 *   (ix-1, iy-1, iz), (ix, iy-1, iz), (ix, iy, iz), (ix-1, iy, iz)
 */
function emitQuadZ(
  grid: SDFGrid,
  builder: MeshBuilder,
  cellVertexMap: Map<number, number>,
  ix: number,
  iy: number,
  iz: number,
  flipWinding: boolean,
): void {
  const { sizeX, sizeY } = grid;
  const cells = [
    (ix - 1) + sizeX * ((iy - 1) + sizeY * iz),
    ix + sizeX * ((iy - 1) + sizeY * iz),
    ix + sizeX * (iy + sizeY * iz),
    (ix - 1) + sizeX * (iy + sizeY * iz),
  ];

  const verts = cells.map(c => cellVertexMap.get(c));
  if (verts.some(v => v === undefined)) return;

  if (flipWinding) {
    builder.addQuad(verts[0]!, verts[3]!, verts[2]!, verts[1]!);
  } else {
    builder.addQuad(verts[0]!, verts[1]!, verts[2]!, verts[3]!);
  }
}
