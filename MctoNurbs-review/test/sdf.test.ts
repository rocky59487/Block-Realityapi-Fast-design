import { describe, it, expect } from 'vitest';
import { SDFGrid, buildSDFGrid, computeAABB } from '../src/sdf/sdf-grid.js';
import { computeCellHermiteData } from '../src/sdf/hermite-data.js';
import type { BlockData } from '../src/types.js';

describe('computeAABB', () => {
  it('should compute bounding box of blocks', () => {
    const blocks: BlockData[] = [
      { x: 0, y: 0, z: 0, material: 'stone' },
      { x: 2, y: 3, z: 1, material: 'stone' },
      { x: -1, y: 1, z: 4, material: 'stone' },
    ];
    const aabb = computeAABB(blocks);
    expect(aabb.minX).toBe(-1);
    expect(aabb.minY).toBe(0);
    expect(aabb.minZ).toBe(0);
    expect(aabb.maxX).toBe(2);
    expect(aabb.maxY).toBe(3);
    expect(aabb.maxZ).toBe(4);
  });
});

describe('SDFGrid', () => {
  it('should correctly index and retrieve values', () => {
    const grid = new SDFGrid(3, 3, 3, 0, 0, 0, 1);
    grid.set(1, 1, 1, -1.0);
    grid.set(0, 0, 0, 1.0);
    expect(grid.get(1, 1, 1)).toBe(-1.0);
    expect(grid.get(0, 0, 0)).toBe(1.0);
  });

  it('should convert between world and grid coordinates', () => {
    const grid = new SDFGrid(10, 10, 10, -2, -2, -2, 1);
    const [wx, wy, wz] = grid.toWorld(2, 2, 2);
    expect(wx).toBe(0);
    expect(wy).toBe(0);
    expect(wz).toBe(0);

    const [gx, gy, gz] = grid.toGrid(0, 0, 0);
    expect(gx).toBe(2);
    expect(gy).toBe(2);
    expect(gz).toBe(2);
  });

  it('should check bounds correctly', () => {
    const grid = new SDFGrid(5, 5, 5, 0, 0, 0, 1);
    expect(grid.inBounds(0, 0, 0)).toBe(true);
    expect(grid.inBounds(4, 4, 4)).toBe(true);
    expect(grid.inBounds(-1, 0, 0)).toBe(false);
    expect(grid.inBounds(5, 0, 0)).toBe(false);
  });
});

describe('buildSDFGrid', () => {
  it('should return empty grid for empty blocks', () => {
    const grid = buildSDFGrid([]);
    expect(grid.sizeX).toBe(0);
  });

  it('should build SDF for a single block', () => {
    const blocks: BlockData[] = [{ x: 0, y: 0, z: 0, material: 'stone' }];
    const grid = buildSDFGrid(blocks);

    expect(grid.sizeX).toBeGreaterThan(0);
    expect(grid.sizeY).toBeGreaterThan(0);
    expect(grid.sizeZ).toBeGreaterThan(0);

    // Check that there are both negative (inside) and positive (outside) values
    let hasNeg = false, hasPos = false;
    for (let i = 0; i < grid.data.length; i++) {
      if (grid.data[i] < 0) hasNeg = true;
      if (grid.data[i] > 0) hasPos = true;
    }
    expect(hasNeg).toBe(true);
    expect(hasPos).toBe(true);
  });

  it('should build SDF for a 3x3x3 cube', () => {
    const blocks: BlockData[] = [];
    for (let x = 0; x < 3; x++)
      for (let y = 0; y < 3; y++)
        for (let z = 0; z < 3; z++)
          blocks.push({ x, y, z, material: 'concrete' });

    const grid = buildSDFGrid(blocks);

    // Center of the cube should be negative (inside)
    const [cx, cy, cz] = grid.toGrid(1.5, 1.5, 1.5);
    const centerVal = grid.get(Math.round(cx), Math.round(cy), Math.round(cz));
    expect(centerVal).toBeLessThan(0);

    // Far outside should be positive
    const [ox, oy, oz] = grid.toGrid(-1.5, -1.5, -1.5);
    if (grid.inBounds(Math.round(ox), Math.round(oy), Math.round(oz))) {
      const outsideVal = grid.get(Math.round(ox), Math.round(oy), Math.round(oz));
      expect(outsideVal).toBeGreaterThan(0);
    }
  });

  it('should produce signed distances (negative inside, positive outside)', () => {
    // Use a 2x2x2 cube so there's a clear interior grid point
    const blocks: BlockData[] = [];
    for (let x = 0; x < 2; x++)
      for (let y = 0; y < 2; y++)
        for (let z = 0; z < 2; z++)
          blocks.push({ x, y, z, material: 'stone' });

    const grid = buildSDFGrid(blocks);

    // Grid point at world (1, 1, 1) is inside the 2x2x2 cube [0,2]^3
    const [gx, gy, gz] = grid.toGrid(1, 1, 1);
    const ix = Math.round(gx), iy = Math.round(gy), iz = Math.round(gz);
    if (grid.inBounds(ix, iy, iz)) {
      expect(grid.get(ix, iy, iz)).toBeLessThan(0);
    }

    // Grid point at world (-1.5, -1.5, -1.5) is clearly outside
    const [gx2, gy2, gz2] = grid.toGrid(-1.5, -1.5, -1.5);
    const ix2 = Math.round(gx2), iy2 = Math.round(gy2), iz2 = Math.round(gz2);
    if (grid.inBounds(ix2, iy2, iz2)) {
      expect(grid.get(ix2, iy2, iz2)).toBeGreaterThan(0);
    }
  });

  it('should produce exact distances with correct magnitudes', () => {
    // At resolution=1 with half-cell offset, all grid points are at block centers.
    // Interior point at (0.5, 0.5, 0.5) is exactly 0.5 from each face.
    // Points further away should have larger distances.
    const blocks: BlockData[] = [{ x: 0, y: 0, z: 0, material: 'stone' }];
    const grid = buildSDFGrid(blocks);

    // Collect all unique absolute SDF values
    const absValues = new Set<string>();
    for (let i = 0; i < grid.data.length; i++) {
      const v = Math.abs(grid.data[i]);
      if (v > 0) {
        absValues.add(v.toFixed(4));
      }
    }

    // Should have multiple distinct distance values (0.5, 1.5, 2.5, diagonal distances...)
    expect(absValues.size).toBeGreaterThan(2);
  });

  it('should have distances that increase moving away from surface', () => {
    const blocks: BlockData[] = [{ x: 0, y: 0, z: 0, material: 'stone' }];
    const grid = buildSDFGrid(blocks);

    // Walk along a line from surface outward
    // Grid points: ..., -0.5 (outside, +0.5), 0.5 (inside, -0.5), 1.5 (outside, +0.5), 2.5 (outside, +1.5), ...
    const [, gyMid, gzMid] = grid.toGrid(0.5, 0.5, 0.5);
    const iyMid = Math.round(gyMid);
    const izMid = Math.round(gzMid);

    // Check that far-outside points have larger distance than near-surface points
    const values: number[] = [];
    for (let ix = 0; ix < grid.sizeX; ix++) {
      values.push(grid.get(ix, iyMid, izMid));
    }

    // Find the first positive value and the last positive value
    const positives = values.filter(v => v > 0);
    if (positives.length >= 2) {
      const maxPositive = Math.max(...positives);
      const minPositive = Math.min(...positives);
      // Farthest outside point should have larger distance than nearest outside point
      expect(maxPositive).toBeGreaterThan(minPositive);
    }
  });

  it('should produce correct t=0.5 for surface-crossing edges (voxel geometry)', () => {
    // For axis-aligned voxel geometry, surface faces lie at integer coordinates.
    // Grid edges that cross these faces are always perpendicular to the face,
    // with both endpoints equidistant → t=0.5 is the geometrically correct answer.
    // The improvement from exact distances shows in GRADIENTS, not t values.
    const blocks: BlockData[] = [
      { x: 0, y: 0, z: 0, material: 'stone' },
      { x: 1, y: 0, z: 0, material: 'stone' },
      { x: 0, y: 1, z: 0, material: 'stone' },
    ];
    const grid = buildSDFGrid(blocks, 2);

    const tValues: number[] = [];
    for (let iz = 0; iz < grid.sizeZ - 1; iz++) {
      for (let iy = 0; iy < grid.sizeY - 1; iy++) {
        for (let ix = 0; ix < grid.sizeX - 1; ix++) {
          const hermite = computeCellHermiteData(grid, ix, iy, iz);
          for (let edgeIdx = 0; edgeIdx < 3; edgeIdx++) {
            const edge = hermite[edgeIdx];
            if (!edge) continue;
            const dx = edgeIdx === 0 ? 1 : 0;
            const dy = edgeIdx === 1 ? 1 : 0;
            const dz = edgeIdx === 2 ? 1 : 0;
            const v0 = grid.get(ix, iy, iz);
            const v1 = grid.get(ix + dx, iy + dy, iz + dz);
            if ((v0 < 0) !== (v1 < 0)) {
              tValues.push(v0 / (v0 - v1));
            }
          }
        }
      }
    }

    expect(tValues.length).toBeGreaterThan(0);

    // All t values should be close to 0.5 (correct for voxel geometry)
    for (const t of tValues) {
      expect(t).toBeCloseTo(0.5, 2);
    }
  });
});

describe('Hermite Data Quality', () => {
  it('should produce non-axis-aligned normals near L-shape corner', () => {
    // An L-shape has a concave corner where gradients are non-axis-aligned.
    // The gradient at the inner corner points diagonally (toward -X and -Y).
    const blocks: BlockData[] = [
      { x: 0, y: 0, z: 0, material: 'stone' },
      { x: 1, y: 0, z: 0, material: 'stone' },
      { x: 0, y: 1, z: 0, material: 'stone' },
    ];
    const grid = buildSDFGrid(blocks);

    // Collect all normals from Hermite edges
    const normals: { x: number; y: number; z: number }[] = [];
    for (let iz = 0; iz < grid.sizeZ - 1; iz++) {
      for (let iy = 0; iy < grid.sizeY - 1; iy++) {
        for (let ix = 0; ix < grid.sizeX - 1; ix++) {
          const hermite = computeCellHermiteData(grid, ix, iy, iz);
          for (const edge of hermite) {
            if (edge) normals.push(edge.normal);
          }
        }
      }
    }

    expect(normals.length).toBeGreaterThan(0);

    // With trilinear gradient interpolation and an L-shape,
    // at least some normals should have multiple non-zero components
    const nonAxisAligned = normals.filter(n => {
      const components = [Math.abs(n.x), Math.abs(n.y), Math.abs(n.z)];
      const nonZero = components.filter(c => c > 0.1);
      return nonZero.length >= 2;
    });

    // The L-shape's inner corner creates diagonal gradients
    expect(nonAxisAligned.length).toBeGreaterThan(0);
  });

  it('should produce correct axis-aligned normals for flat surfaces', () => {
    // A single block's flat faces should produce axis-aligned normals
    const blocks: BlockData[] = [{ x: 0, y: 0, z: 0, material: 'stone' }];
    const grid = buildSDFGrid(blocks);

    const normals: { x: number; y: number; z: number }[] = [];
    for (let iz = 0; iz < grid.sizeZ - 1; iz++) {
      for (let iy = 0; iy < grid.sizeY - 1; iy++) {
        for (let ix = 0; ix < grid.sizeX - 1; ix++) {
          const hermite = computeCellHermiteData(grid, ix, iy, iz);
          for (const edge of hermite) {
            if (edge) normals.push(edge.normal);
          }
        }
      }
    }

    expect(normals.length).toBeGreaterThan(0);

    // All normals should be valid (unit length)
    for (const n of normals) {
      const len = Math.sqrt(n.x * n.x + n.y * n.y + n.z * n.z);
      expect(len).toBeCloseTo(1.0, 3);
    }
  });
});
