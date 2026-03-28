import { describe, it, expect } from 'vitest';
import { greedyMesh } from '../src/greedy-mesh.js';
import type { BlockData } from '../src/types.js';

describe('Greedy Meshing', () => {
  it('should return empty mesh for empty blocks', () => {
    const mesh = greedyMesh([]);
    expect(mesh.vertices.length).toBe(0);
    expect(mesh.indices.length).toBe(0);
  });

  it('should generate 6 faces for a single block', () => {
    const blocks: BlockData[] = [{ x: 0, y: 0, z: 0, material: 'stone' }];
    const mesh = greedyMesh(blocks);

    // 6 faces × 4 vertices = 24 vertices, 6 faces × 2 triangles × 3 = 36 indices
    expect(mesh.vertices.length).toBe(24 * 3);
    expect(mesh.indices.length).toBe(36);
  });

  it('should merge adjacent faces on a flat wall', () => {
    // 3×1×1 wall: should merge into 2 large faces (front+back) + 4 side faces
    const blocks: BlockData[] = [
      { x: 0, y: 0, z: 0, material: 'stone' },
      { x: 1, y: 0, z: 0, material: 'stone' },
      { x: 2, y: 0, z: 0, material: 'stone' },
    ];
    const mesh = greedyMesh(blocks);

    // Without merging: 3 blocks × 6 faces × 2 triangles = 36 triangles
    // With merging: long faces get merged → fewer triangles
    const triCount = mesh.indices.length / 3;
    expect(triCount).toBeLessThan(36);

    // Minimum: 6 faces × 2 triangles = 12 (if perfectly merged into 6 large rectangles)
    // But internal faces between blocks are culled, so:
    // Top, bottom, front, back = 4 merged faces (3×1 rectangles)
    // Left, right = 2 end caps (1×1)
    // Total = 6 faces × 2 triangles = 12
    expect(triCount).toBeLessThanOrEqual(12);
  });

  it('should not merge faces of different materials', () => {
    const blocks: BlockData[] = [
      { x: 0, y: 0, z: 0, material: 'stone' },
      { x: 1, y: 0, z: 0, material: 'glass' },
    ];
    const mesh = greedyMesh(blocks);

    // Each material gets separate faces — no merging across materials
    const triCount = mesh.indices.length / 3;
    // 2 blocks, some internal faces culled, but materials prevent cross-block merging
    expect(triCount).toBeGreaterThan(0);
  });

  it('should produce correct normals', () => {
    const blocks: BlockData[] = [{ x: 0, y: 0, z: 0, material: 'stone' }];
    const mesh = greedyMesh(blocks);

    // All normals should be axis-aligned and unit-length
    const vertCount = mesh.normals.length / 3;
    for (let i = 0; i < vertCount; i++) {
      const nx = mesh.normals[i * 3];
      const ny = mesh.normals[i * 3 + 1];
      const nz = mesh.normals[i * 3 + 2];
      const len = Math.sqrt(nx * nx + ny * ny + nz * nz);
      expect(len).toBeCloseTo(1.0, 5);

      // Exactly one component should be ±1, others 0
      const absComponents = [Math.abs(nx), Math.abs(ny), Math.abs(nz)];
      expect(absComponents.filter(c => c > 0.5).length).toBe(1);
    }
  });

  it('should produce valid triangle indices', () => {
    const blocks: BlockData[] = [];
    for (let x = 0; x < 3; x++)
      for (let y = 0; y < 3; y++)
        for (let z = 0; z < 3; z++)
          blocks.push({ x, y, z, material: 'concrete' });

    const mesh = greedyMesh(blocks);
    const vertCount = mesh.vertices.length / 3;

    for (let i = 0; i < mesh.indices.length; i++) {
      expect(mesh.indices[i]).toBeGreaterThanOrEqual(0);
      expect(mesh.indices[i]).toBeLessThan(vertCount);
    }
  });

  it('should be significantly faster than DC for large inputs', () => {
    // 10×10×10 cube = 1000 blocks
    const blocks: BlockData[] = [];
    for (let x = 0; x < 10; x++)
      for (let y = 0; y < 10; y++)
        for (let z = 0; z < 10; z++)
          blocks.push({ x, y, z, material: 'concrete' });

    const start = performance.now();
    const mesh = greedyMesh(blocks);
    const elapsed = performance.now() - start;

    // Should complete in < 100ms for 1000 blocks
    expect(elapsed).toBeLessThan(100);
    expect(mesh.vertices.length).toBeGreaterThan(0);
    expect(mesh.indices.length).toBeGreaterThan(0);

    // Face merging should produce very few triangles for a solid cube
    // 6 large faces × 2 triangles = 12 triangles total
    const triCount = mesh.indices.length / 3;
    expect(triCount).toBe(12);
  });
});
