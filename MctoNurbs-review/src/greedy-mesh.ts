import type { BlockData, Mesh } from './types.js';

/**
 * Greedy Meshing for voxel geometry (smoothing=0, sharp edges only).
 *
 * Instead of SDF → Dual Contouring → triangle mesh, this directly converts
 * voxel blocks into a minimal set of large axis-aligned rectangular faces.
 * This is 10-100× faster and produces cleaner CAD output (fewer faces).
 *
 * Algorithm (per axis, per slice):
 * 1. For each axis direction (±X, ��Y, ±Z), scan each 2D slice
 * 2. Build a bitmask of exposed faces in the slice
 * 3. Greedily merge adjacent faces with the same material into maximal rectangles
 * 4. Emit each rectangle as 2 triangles
 *
 * Reference: Mikola Lysenko, "Meshing in a Minecraft Game" (0fps.net)
 */
export function greedyMesh(blocks: BlockData[]): Mesh {
  if (blocks.length === 0) {
    return { vertices: new Float64Array(0), indices: new Uint32Array(0), normals: new Float64Array(0) };
  }

  // Build occupancy map: key = "x,y,z" → material
  const occupancy = new Map<number, string>();
  let minX = Infinity, minY = Infinity, minZ = Infinity;
  let maxX = -Infinity, maxY = -Infinity, maxZ = -Infinity;

  for (const b of blocks) {
    occupancy.set(packKey(b.x, b.y, b.z), b.material);
    if (b.x < minX) minX = b.x;
    if (b.y < minY) minY = b.y;
    if (b.z < minZ) minZ = b.z;
    if (b.x > maxX) maxX = b.x;
    if (b.y > maxY) maxY = b.y;
    if (b.z > maxZ) maxZ = b.z;
  }

  const sizeX = maxX - minX + 1;
  const sizeY = maxY - minY + 1;
  const sizeZ = maxZ - minZ + 1;

  const vertices: number[] = [];
  const indices: number[] = [];
  const normals: number[] = [];

  // For each of 6 face directions
  const directions: [number, number, number][] = [
    [1, 0, 0], [-1, 0, 0],  // ±X
    [0, 1, 0], [0, -1, 0],  // ±Y
    [0, 0, 1], [0, 0, -1],  // ±Z
  ];

  for (const [nx, ny, nz] of directions) {
    // Determine which axis is the "depth" axis and the two "slice" axes
    const axis = nx !== 0 ? 0 : ny !== 0 ? 1 : 2;
    const dir = nx + ny + nz; // +1 or -1
    const isPositive = dir > 0;

    // Slice dimensions
    const axes = [0, 1, 2].filter(a => a !== axis);
    const uAxis = axes[0]; // first tangent axis
    const vAxis = axes[1]; // second tangent axis

    const dimU = [sizeX, sizeY, sizeZ][uAxis];
    const dimV = [sizeX, sizeY, sizeZ][vAxis];
    const dimD = [sizeX, sizeY, sizeZ][axis];
    const minCoords = [minX, minY, minZ];

    // For each slice along the depth axis
    for (let d = 0; d < dimD; d++) {
      // Build the face mask: which cells in this slice have an exposed face in this direction
      const mask = new Array<string | null>(dimU * dimV).fill(null);

      for (let v = 0; v < dimV; v++) {
        for (let u = 0; u < dimU; u++) {
          // Build world coordinates for this cell
          const coords = [0, 0, 0];
          coords[axis] = d + minCoords[axis];
          coords[uAxis] = u + minCoords[uAxis];
          coords[vAxis] = v + minCoords[vAxis];

          const mat = occupancy.get(packKey(coords[0], coords[1], coords[2]));
          if (!mat) continue; // Air

          // Check adjacent cell in the face direction
          const adjCoords = [coords[0] + nx, coords[1] + ny, coords[2] + nz];
          const adjMat = occupancy.get(packKey(adjCoords[0], adjCoords[1], adjCoords[2]));
          if (adjMat) continue; // Internal face (both occupied)

          // Exposed face: record the material
          mask[u + v * dimU] = mat;
        }
      }

      // Greedy merge: find maximal rectangles of the same material
      const visited = new Uint8Array(dimU * dimV);

      for (let v = 0; v < dimV; v++) {
        for (let u = 0; u < dimU; u++) {
          const idx = u + v * dimU;
          if (visited[idx] || !mask[idx]) continue;

          const mat = mask[idx]!;

          // Expand width (u direction) as far as possible
          let w = 1;
          while (u + w < dimU && mask[u + w + v * dimU] === mat && !visited[u + w + v * dimU]) {
            w++;
          }

          // Expand height (v direction) as far as possible
          let h = 1;
          outer: while (v + h < dimV) {
            for (let du = 0; du < w; du++) {
              const ni = (u + du) + (v + h) * dimU;
              if (mask[ni] !== mat || visited[ni]) break outer;
            }
            h++;
          }

          // Mark visited
          for (let dv = 0; dv < h; dv++) {
            for (let du = 0; du < w; du++) {
              visited[(u + du) + (v + dv) * dimU] = 1;
            }
          }

          // Emit quad for this rectangle
          // Compute 4 corners in world space
          const p = [0, 0, 0];
          p[axis] = d + minCoords[axis] + (isPositive ? 1 : 0); // Face offset
          p[uAxis] = u + minCoords[uAxis];
          p[vAxis] = v + minCoords[vAxis];

          const du = [0, 0, 0];
          du[uAxis] = w;
          const dv = [0, 0, 0];
          dv[vAxis] = h;

          // 4 corners of the quad
          const c0 = [p[0], p[1], p[2]];
          const c1 = [p[0] + du[0], p[1] + du[1], p[2] + du[2]];
          const c2 = [p[0] + du[0] + dv[0], p[1] + du[1] + dv[1], p[2] + du[2] + dv[2]];
          const c3 = [p[0] + dv[0], p[1] + dv[1], p[2] + dv[2]];

          const vi = vertices.length / 3;

          // Add 4 vertices
          for (const c of [c0, c1, c2, c3]) {
            vertices.push(c[0], c[1], c[2]);
            normals.push(nx, ny, nz);
          }

          // Add 2 triangles (winding matches normal direction)
          if (isPositive) {
            indices.push(vi, vi + 1, vi + 2);
            indices.push(vi, vi + 2, vi + 3);
          } else {
            indices.push(vi, vi + 2, vi + 1);
            indices.push(vi, vi + 3, vi + 2);
          }
        }
      }
    }
  }

  return {
    vertices: new Float64Array(vertices),
    indices: new Uint32Array(indices),
    normals: new Float64Array(normals),
  };
}

/** Pack 3D coords into a single number for fast Map lookups */
function packKey(x: number, y: number, z: number): number {
  return ((x + 16384) * 32768 + (y + 16384)) * 32768 + (z + 16384);
}
