import type { Mesh, Vec3 } from '../types.js';

/**
 * MeshBuilder accumulates vertices and triangles, then compacts
 * them into typed arrays for efficient processing.
 */
export class MeshBuilder {
  private vertices: Vec3[] = [];
  private triangles: [number, number, number][] = [];

  /** Add a vertex and return its index */
  addVertex(v: Vec3): number {
    const idx = this.vertices.length;
    this.vertices.push(v);
    return idx;
  }

  /** Add a triangle (3 vertex indices, counter-clockwise winding) */
  addTriangle(i0: number, i1: number, i2: number): void {
    this.triangles.push([i0, i1, i2]);
  }

  /** Add a quad as two triangles. Vertices should be in CCW order. */
  addQuad(i0: number, i1: number, i2: number, i3: number): void {
    this.addTriangle(i0, i1, i2);
    this.addTriangle(i0, i2, i3);
  }

  /** Build the final mesh with computed normals */
  build(): Mesh {
    const vertCount = this.vertices.length;
    const triCount = this.triangles.length;

    const vertices = new Float64Array(vertCount * 3);
    const indices = new Uint32Array(triCount * 3);
    const normals = new Float64Array(vertCount * 3);

    // Copy vertices
    for (let i = 0; i < vertCount; i++) {
      const v = this.vertices[i];
      vertices[i * 3] = v.x;
      vertices[i * 3 + 1] = v.y;
      vertices[i * 3 + 2] = v.z;
    }

    // Copy indices and accumulate face normals per vertex
    for (let t = 0; t < triCount; t++) {
      const [i0, i1, i2] = this.triangles[t];
      indices[t * 3] = i0;
      indices[t * 3 + 1] = i1;
      indices[t * 3 + 2] = i2;

      // Compute face normal
      const ax = vertices[i1 * 3] - vertices[i0 * 3];
      const ay = vertices[i1 * 3 + 1] - vertices[i0 * 3 + 1];
      const az = vertices[i1 * 3 + 2] - vertices[i0 * 3 + 2];
      const bx = vertices[i2 * 3] - vertices[i0 * 3];
      const by = vertices[i2 * 3 + 1] - vertices[i0 * 3 + 1];
      const bz = vertices[i2 * 3 + 2] - vertices[i0 * 3 + 2];

      const nx = ay * bz - az * by;
      const ny = az * bx - ax * bz;
      const nz = ax * by - ay * bx;

      // Accumulate to all 3 vertices (area-weighted by cross product magnitude)
      for (const idx of [i0, i1, i2]) {
        normals[idx * 3] += nx;
        normals[idx * 3 + 1] += ny;
        normals[idx * 3 + 2] += nz;
      }
    }

    // Normalize vertex normals
    for (let i = 0; i < vertCount; i++) {
      const nx = normals[i * 3];
      const ny = normals[i * 3 + 1];
      const nz = normals[i * 3 + 2];
      const len = Math.sqrt(nx * nx + ny * ny + nz * nz);
      if (len > 1e-10) {
        normals[i * 3] /= len;
        normals[i * 3 + 1] /= len;
        normals[i * 3 + 2] /= len;
      }
    }

    return { vertices, indices, normals };
  }

  get vertexCount(): number {
    return this.vertices.length;
  }

  get triangleCount(): number {
    return this.triangles.length;
  }
}
