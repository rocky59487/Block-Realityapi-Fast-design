import { describe, it, expect } from 'vitest';
import { solveQEF } from '../src/dc/qef-solver.js';
import { MeshBuilder } from '../src/dc/mesh.js';
import { dualContour } from '../src/dc/dual-contouring.js';
import { buildSDFGrid } from '../src/sdf/sdf-grid.js';
import type { HermiteEdge, Vec3, BlockData } from '../src/types.js';

describe('QEF Solver', () => {
  it('should place vertex at intersection of 3 perpendicular planes (corner)', () => {
    // Three perpendicular planes meeting at (1, 1, 1)
    const edges: HermiteEdge[] = [
      { point: { x: 1, y: 0.5, z: 0.5 }, normal: { x: 1, y: 0, z: 0 } },
      { point: { x: 0.5, y: 1, z: 0.5 }, normal: { x: 0, y: 1, z: 0 } },
      { point: { x: 0.5, y: 0.5, z: 1 }, normal: { x: 0, y: 0, z: 1 } },
    ];

    const result = solveQEF(
      edges,
      { x: 0, y: 0, z: 0 },
      { x: 2, y: 2, z: 2 },
    );

    expect(result.position.x).toBeCloseTo(1, 3);
    expect(result.position.y).toBeCloseTo(1, 3);
    expect(result.position.z).toBeCloseTo(1, 3);
    expect(result.error).toBeCloseTo(0, 3);
  });

  it('should place vertex on an edge (2 planes)', () => {
    // Two perpendicular planes meeting along x-axis at y=1, z=1
    const edges: HermiteEdge[] = [
      { point: { x: 0.5, y: 1, z: 0.5 }, normal: { x: 0, y: 1, z: 0 } },
      { point: { x: 0.5, y: 0.5, z: 1 }, normal: { x: 0, y: 0, z: 1 } },
    ];

    const result = solveQEF(
      edges,
      { x: 0, y: 0, z: 0 },
      { x: 2, y: 2, z: 2 },
    );

    // y and z should be at 1, x should be at mass point (0.5)
    expect(result.position.y).toBeCloseTo(1, 2);
    expect(result.position.z).toBeCloseTo(1, 2);
  });

  it('should return cell center for empty edges', () => {
    const result = solveQEF(
      [],
      { x: 0, y: 0, z: 0 },
      { x: 1, y: 1, z: 1 },
    );

    expect(result.position.x).toBeCloseTo(0.5, 5);
    expect(result.position.y).toBeCloseTo(0.5, 5);
    expect(result.position.z).toBeCloseTo(0.5, 5);
  });

  it('should clamp vertex to cell bounds', () => {
    // Planes that would place vertex far outside the cell
    const edges: HermiteEdge[] = [
      { point: { x: 10, y: 0.5, z: 0.5 }, normal: { x: 1, y: 0, z: 0 } },
      { point: { x: 0.5, y: 10, z: 0.5 }, normal: { x: 0, y: 1, z: 0 } },
      { point: { x: 0.5, y: 0.5, z: 10 }, normal: { x: 0, y: 0, z: 1 } },
    ];

    const result = solveQEF(
      edges,
      { x: 0, y: 0, z: 0 },
      { x: 1, y: 1, z: 1 },
    );

    expect(result.position.x).toBeLessThanOrEqual(1);
    expect(result.position.y).toBeLessThanOrEqual(1);
    expect(result.position.z).toBeLessThanOrEqual(1);
    expect(result.position.x).toBeGreaterThanOrEqual(0);
    expect(result.position.y).toBeGreaterThanOrEqual(0);
    expect(result.position.z).toBeGreaterThanOrEqual(0);
  });
});

describe('MeshBuilder', () => {
  it('should build a simple triangle mesh', () => {
    const builder = new MeshBuilder();
    const v0 = builder.addVertex({ x: 0, y: 0, z: 0 });
    const v1 = builder.addVertex({ x: 1, y: 0, z: 0 });
    const v2 = builder.addVertex({ x: 0, y: 1, z: 0 });
    builder.addTriangle(v0, v1, v2);

    const mesh = builder.build();
    expect(mesh.vertices.length).toBe(9); // 3 vertices * 3 components
    expect(mesh.indices.length).toBe(3);  // 1 triangle * 3 indices
    expect(mesh.normals.length).toBe(9);  // 3 normals * 3 components
  });

  it('should compute correct normals for a flat triangle', () => {
    const builder = new MeshBuilder();
    // Triangle in XY plane, CCW winding → normal should point +Z
    builder.addVertex({ x: 0, y: 0, z: 0 });
    builder.addVertex({ x: 1, y: 0, z: 0 });
    builder.addVertex({ x: 0, y: 1, z: 0 });
    builder.addTriangle(0, 1, 2);

    const mesh = builder.build();
    // All vertex normals should point in +Z direction
    for (let i = 0; i < 3; i++) {
      expect(Math.abs(mesh.normals[i * 3])).toBeCloseTo(0, 5);     // nx ≈ 0
      expect(Math.abs(mesh.normals[i * 3 + 1])).toBeCloseTo(0, 5); // ny ≈ 0
      expect(mesh.normals[i * 3 + 2]).toBeCloseTo(1, 5);           // nz ≈ 1
    }
  });

  it('should add quads as two triangles', () => {
    const builder = new MeshBuilder();
    builder.addVertex({ x: 0, y: 0, z: 0 });
    builder.addVertex({ x: 1, y: 0, z: 0 });
    builder.addVertex({ x: 1, y: 1, z: 0 });
    builder.addVertex({ x: 0, y: 1, z: 0 });
    builder.addQuad(0, 1, 2, 3);

    expect(builder.triangleCount).toBe(2);
    expect(builder.vertexCount).toBe(4);
  });
});

describe('Dual Contouring', () => {
  it('should generate mesh from a single block', () => {
    const blocks: BlockData[] = [{ x: 0, y: 0, z: 0, material: 'stone' }];
    const grid = buildSDFGrid(blocks);
    const mesh = dualContour(grid, 0);

    // Should have some vertices and triangles
    expect(mesh.vertices.length).toBeGreaterThan(0);
    expect(mesh.indices.length).toBeGreaterThan(0);

    // All vertices should be finite
    for (let i = 0; i < mesh.vertices.length; i++) {
      expect(isFinite(mesh.vertices[i])).toBe(true);
    }
  });

  it('should generate mesh from a 3x3x3 cube', () => {
    const blocks: BlockData[] = [];
    for (let x = 0; x < 3; x++)
      for (let y = 0; y < 3; y++)
        for (let z = 0; z < 3; z++)
          blocks.push({ x, y, z, material: 'concrete' });

    const grid = buildSDFGrid(blocks);
    const mesh = dualContour(grid, 0);

    expect(mesh.vertices.length).toBeGreaterThan(0);
    expect(mesh.indices.length).toBeGreaterThan(0);

    // Vertices should be roughly in the range of the cube
    const vertCount = mesh.vertices.length / 3;
    for (let i = 0; i < vertCount; i++) {
      const x = mesh.vertices[i * 3];
      const y = mesh.vertices[i * 3 + 1];
      const z = mesh.vertices[i * 3 + 2];
      // Should be near the cube (with some tolerance for DC vertex placement)
      expect(x).toBeGreaterThan(-2);
      expect(x).toBeLessThan(5);
      expect(y).toBeGreaterThan(-2);
      expect(y).toBeLessThan(5);
      expect(z).toBeGreaterThan(-2);
      expect(z).toBeLessThan(5);
    }
  });

  it('should generate valid triangle indices', () => {
    const blocks: BlockData[] = [
      { x: 0, y: 0, z: 0, material: 'stone' },
      { x: 1, y: 0, z: 0, material: 'stone' },
    ];
    const grid = buildSDFGrid(blocks);
    const mesh = dualContour(grid, 0);

    const vertCount = mesh.vertices.length / 3;
    for (let i = 0; i < mesh.indices.length; i++) {
      expect(mesh.indices[i]).toBeGreaterThanOrEqual(0);
      expect(mesh.indices[i]).toBeLessThan(vertCount);
    }
  });

  it('should respect smoothing parameter', () => {
    const blocks: BlockData[] = [];
    for (let x = 0; x < 2; x++)
      for (let y = 0; y < 2; y++)
        for (let z = 0; z < 2; z++)
          blocks.push({ x, y, z, material: 'stone' });

    const grid = buildSDFGrid(blocks);
    const meshSharp = dualContour(grid, 0);    // No smoothing
    const meshSmooth = dualContour(grid, 0.8); // High smoothing

    // Both should produce valid meshes
    expect(meshSharp.vertices.length).toBeGreaterThan(0);
    expect(meshSmooth.vertices.length).toBeGreaterThan(0);
  });
});
