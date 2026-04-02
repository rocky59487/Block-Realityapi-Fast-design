import { describe, it, expect, beforeAll } from 'vitest';
import { existsSync, readFileSync, unlinkSync, mkdirSync } from 'node:fs';
import { resolve } from 'node:path';
import { initOpenCascade, meshToShape } from '../src/cad/mesh-to-brep.js';
import { writeSTEP, makeCompound, shapeToSTEPString } from '../src/cad/step-writer.js';
import { MeshBuilder } from '../src/dc/mesh.js';
import type { Mesh } from '../src/types.js';

/**
 * CAD tests require OpenCASCADE WASM initialization.
 * These tests are slower due to WASM startup (~2-5s).
 */

describe('OpenCASCADE Integration', () => {
  beforeAll(async () => {
    await initOpenCascade();
  }, 30000); // 30s timeout for WASM init

  it('should handle degenerate triangles gracefully (identical points)', () => {
    const builder = new MeshBuilder();
    builder.addVertex({ x: 0, y: 0, z: 0 });
    builder.addVertex({ x: 0, y: 0, z: 0 });
    builder.addVertex({ x: 0, y: 0, z: 0 });
    builder.addTriangle(0, 1, 2);

    const mesh = builder.build();
    expect(() => meshToShape(mesh)).not.toThrow();
  });

  it('should handle collinear degenerate triangles without throwing', () => {
    const builder = new MeshBuilder();
    builder.addVertex({ x: 0, y: 0, z: 0 });
    builder.addVertex({ x: 1, y: 0, z: 0 });
    builder.addVertex({ x: 2, y: 0, z: 0 });
    builder.addTriangle(0, 1, 2);

    const mesh = builder.build();
    expect(() => meshToShape(mesh)).not.toThrow();
  });

  it('should handle a mix of valid and degenerate triangles gracefully', () => {
    const builder = new MeshBuilder();
    // Valid triangle
    builder.addVertex({ x: 0, y: 0, z: 0 });
    builder.addVertex({ x: 1, y: 0, z: 0 });
    builder.addVertex({ x: 0, y: 1, z: 0 });
    builder.addTriangle(0, 1, 2);

    // Degenerate triangle (identical points)
    builder.addVertex({ x: 5, y: 5, z: 5 });
    builder.addTriangle(3, 3, 3);

    const mesh = builder.build();
    expect(() => meshToShape(mesh)).not.toThrow();
  });

  it('should initialize OpenCASCADE successfully', async () => {
    // If we get here, init succeeded
    expect(true).toBe(true);
  });

  it('should convert a simple triangle mesh to a shape', () => {
    const builder = new MeshBuilder();
    // Create a tetrahedron (simplest closed mesh)
    builder.addVertex({ x: 0, y: 0, z: 0 });
    builder.addVertex({ x: 1, y: 0, z: 0 });
    builder.addVertex({ x: 0.5, y: 1, z: 0 });
    builder.addVertex({ x: 0.5, y: 0.5, z: 1 });

    builder.addTriangle(0, 2, 1); // bottom
    builder.addTriangle(0, 1, 3); // front
    builder.addTriangle(1, 2, 3); // right
    builder.addTriangle(2, 0, 3); // left

    const mesh = builder.build();
    const shape = meshToShape(mesh);
    expect(shape).toBeDefined();
  });

  it('should convert a cube mesh to a shape', () => {
    const mesh = createCubeMesh();
    const shape = meshToShape(mesh);
    expect(shape).toBeDefined();
  });

  it('should export shape to STEP string', () => {
    const mesh = createCubeMesh();
    const shape = meshToShape(mesh);
    const stepString = shapeToSTEPString(shape);

    expect(stepString).toContain('ISO-10303-21');
    expect(stepString.length).toBeGreaterThan(100);
  });

  it('should produce true NURBS surfaces (B_SPLINE_SURFACE in STEP)', () => {
    const mesh = createCubeMesh();
    const shape = meshToShape(mesh);
    const stepString = shapeToSTEPString(shape);

    // After UnifySameDomain + NurbsConvert, the STEP file must contain
    // B_SPLINE_SURFACE entities — not just PLANE or triangulated faces.
    // This is what makes the output genuine NURBS, not faceted B-Rep.
    expect(stepString).toContain('B_SPLINE_SURFACE');
  });

  it('should create compound from multiple shapes', () => {
    const mesh1 = createCubeMesh();
    const mesh2 = createCubeMesh(); // offset would be better, but this tests the API

    const shape1 = meshToShape(mesh1);
    const shape2 = meshToShape(mesh2);
    const compound = makeCompound([shape1, shape2]);

    expect(compound).toBeDefined();

    const stepString = shapeToSTEPString(compound);
    expect(stepString).toContain('ISO-10303-21');
  });

  it('should handle makeCompound with empty array', () => {
    const compound = makeCompound([]);
    expect(compound).toBeDefined();
  });

  it('should write STEP file to disk', () => {
    const mesh = createCubeMesh();
    const shape = meshToShape(mesh);

    // Write to exports/ (security check requires paths within this directory)
    const exportsDir = resolve(process.cwd(), 'exports');
    mkdirSync(exportsDir, { recursive: true });
    const outPath = resolve(exportsDir, 'test-mctonurbs-output.step');
    writeSTEP(shape, outPath);

    // Verify file exists on the real filesystem
    expect(existsSync(outPath)).toBe(true);
    const content = readFileSync(outPath, 'utf-8');
    expect(content).toContain('ISO-10303-21');

    // Cleanup
    unlinkSync(outPath);
  });
});

/**
 * Helper: Create a simple cube mesh (axis-aligned, 1x1x1 at origin)
 */
function createCubeMesh(): Mesh {
  const builder = new MeshBuilder();

  // 8 vertices of a unit cube
  const v = [
    builder.addVertex({ x: 0, y: 0, z: 0 }), // 0
    builder.addVertex({ x: 1, y: 0, z: 0 }), // 1
    builder.addVertex({ x: 1, y: 1, z: 0 }), // 2
    builder.addVertex({ x: 0, y: 1, z: 0 }), // 3
    builder.addVertex({ x: 0, y: 0, z: 1 }), // 4
    builder.addVertex({ x: 1, y: 0, z: 1 }), // 5
    builder.addVertex({ x: 1, y: 1, z: 1 }), // 6
    builder.addVertex({ x: 0, y: 1, z: 1 }), // 7
  ];

  // 6 faces (2 triangles each, CCW winding when viewed from outside)
  builder.addQuad(v[0], v[3], v[2], v[1]); // front (-Z)
  builder.addQuad(v[4], v[5], v[6], v[7]); // back (+Z)
  builder.addQuad(v[0], v[1], v[5], v[4]); // bottom (-Y)
  builder.addQuad(v[2], v[3], v[7], v[6]); // top (+Y)
  builder.addQuad(v[0], v[4], v[7], v[3]); // left (-X)
  builder.addQuad(v[1], v[2], v[6], v[5]); // right (+X)

  return builder.build();
}
