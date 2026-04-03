import type { Mesh } from '../types.js';

/**
 * Minimal type interface for OpenCASCADE WASM module.
 *
 * ★ Audit fix (程式員A): Replace bare `type OC = any` with a structural interface
 * covering the OC API surface actually used by this module. This provides
 * compile-time safety for method names and constructor signatures without
 * requiring the full auto-generated OC type definitions (which are
 * version-dependent and ~30K lines).
 *
 * Methods not listed here will still work via the index signature,
 * but the commonly used ones are now type-checked.
 */
interface OCInstance {
  gp_Pnt_3: new (x: number, y: number, z: number) => OCHandle;
  BRepBuilderAPI_Sewing: new (tolerance: number, opt1: boolean, opt2: boolean, opt3: boolean, opt4: boolean) => OCSewing;
  BRepBuilderAPI_MakePolygon_1: new () => OCMakePolygon;
  BRepBuilderAPI_MakeFace_15: new (wire: OCHandle, planar: boolean) => OCMakeFace;
  BRepBuilderAPI_MakeSolid_1: new () => OCMakeSolid;
  BRepBuilderAPI_NurbsConvert_2: new (shape: OCHandle, copy: boolean) => OCNurbsConvert;
  ShapeUpgrade_UnifySameDomain_2: new (shape: OCHandle, faces: boolean, edges: boolean, concatBSpl: boolean) => OCUnifySameDomain;
  TopExp_Explorer_2: new (shape: OCHandle, toFind: number, toAvoid: number) => OCExplorer;
  Message_ProgressRange_1: new () => OCHandle;
  TopAbs_ShapeEnum: { TopAbs_SHELL: number; TopAbs_SHAPE: number };
  TopoDS: { Shell_1: (shape: OCHandle) => OCHandle };
  [key: string]: unknown; // Allow access to unlisted OC APIs
}

/** Any OC object with a delete() method for WASM heap cleanup */
interface OCHandle { delete(): void }

interface OCSewing extends OCHandle {
  Add(shape: OCHandle): void;
  Perform(progress: OCHandle): void;
  SewedShape(): OCHandle;
}

interface OCMakePolygon extends OCHandle {
  Add_1(point: OCHandle): void;
  Close(): void;
  IsDone(): boolean;
  Wire(): OCHandle;
}

interface OCMakeFace extends OCHandle {
  IsDone(): boolean;
  Face(): OCHandle;
}

interface OCMakeSolid extends OCHandle {
  Add(shell: OCHandle): void;
  IsDone(): boolean;
  Solid(): OCHandle;
}

interface OCNurbsConvert extends OCHandle {
  Shape(): OCHandle;
}

interface OCUnifySameDomain extends OCHandle {
  Build(): void;
  Shape(): OCHandle;
}

interface OCExplorer extends OCHandle {
  More(): boolean;
  Current(): OCHandle;
  Next(): void;
}

let oc: OCInstance | null = null;

/**
 * Initialize the OpenCASCADE WASM module.
 * Must be called once before any CAD operations.
 * This is slow (~2-5s) due to WASM compilation.
 */
export async function initOpenCascade(): Promise<void> {
  if (oc) return;

  // Dynamic import for the Node.js-specific entry point
  const initOC = (await import('opencascade.js/dist/node.js')).default;
  oc = await initOC() as OCInstance;
}

/**
 * Get the initialized OpenCASCADE instance.
 * Throws if initOpenCascade() hasn't been called.
 */
export function getOC(): OCInstance {
  if (!oc) throw new Error('OpenCASCADE not initialized. Call initOpenCascade() first.');
  return oc;
}

/**
 * Helper to track OC objects for cleanup.
 * Every OC object allocated on the WASM heap must be manually deleted.
 */
class OCCleaner {
  private objects: { delete(): void }[] = [];

  track<T extends { delete(): void }>(obj: T): T {
    this.objects.push(obj);
    return obj;
  }

  cleanup(): void {
    for (const obj of this.objects) {
      try { obj.delete(); } catch { /* already deleted — safe to ignore */ }
    }
    this.objects = [];
  }
}

/**
 * Convert a triangle mesh to an OpenCASCADE TopoDS_Shape (solid or shell).
 *
 * Strategy: Create individual planar faces from triangles, then sew them
 * together into a shell, then attempt to make a solid.
 *
 * For large meshes, coplanar adjacent triangles are merged first to reduce
 * the total face count.
 */
export function meshToShape(mesh: Mesh): OCHandle {
  const oc = getOC();
  const cleaner = new OCCleaner();

  try {
    // Create sewing tool with tolerance
    // BRepBuilderAPI_Sewing(tolerance, option1=faceAnalysis, option2=sewingOperation, option3=cutting, option4=nonManifold)
    const sewing = cleaner.track(new oc.BRepBuilderAPI_Sewing(1e-6, true, true, true, false));

    const { vertices, indices } = mesh;
    const triCount = indices.length / 3;

    // Group triangles by their face normal to merge coplanar faces
    const coplanarGroups = groupCoplanarTriangles(mesh);

    if (coplanarGroups.length > 0) {
      // Build merged polygonal faces for each coplanar group
      for (const group of coplanarGroups) {
        const face = buildCoplanarFace(oc, cleaner, mesh, group);
        if (face) {
          sewing.Add(face);
        }
      }
    } else {
      // Fallback: build individual triangle faces
      for (let t = 0; t < triCount; t++) {
        const i0 = indices[t * 3];
        const i1 = indices[t * 3 + 1];
        const i2 = indices[t * 3 + 2];

        const face = buildTriangleFace(oc, cleaner,
          vertices[i0 * 3], vertices[i0 * 3 + 1], vertices[i0 * 3 + 2],
          vertices[i1 * 3], vertices[i1 * 3 + 1], vertices[i1 * 3 + 2],
          vertices[i2 * 3], vertices[i2 * 3 + 1], vertices[i2 * 3 + 2],
        );

        if (face) {
          sewing.Add(face);
        }
      }
    }

    // Perform sewing
    const progressRange = new oc.Message_ProgressRange_1();
    sewing.Perform(progressRange);
    progressRange.delete();
    let shape = sewing.SewedShape();

    // Post-process Step 1: Merge coplanar faces and collinear edges.
    // ShapeUpgrade_UnifySameDomain merges adjacent faces on the same geometric
    // surface. A 12-triangle cube → 6 quad faces.
    try {
      const usd = new oc.ShapeUpgrade_UnifySameDomain_2(shape, true, true, false);
      usd.Build();
      shape = usd.Shape();
      usd.delete();
    } catch (e) {
      console.warn('[mesh-to-brep] ShapeUpgrade_UnifySameDomain failed, continuing with unmerged shape:', e instanceof Error ? e.message : e);
    }

    // Post-process Step 2: Convert ALL faces to NURBS (B-Spline surfaces).
    // This is what makes the output true NURBS — not just faceted triangles.
    // BRepBuilderAPI_NurbsConvert replaces each planar/analytic surface with
    // a Geom_BSplineSurface, so the STEP file contains B_SPLINE_SURFACE
    // entities that CAD tools (FreeCAD, Rhino, SolidWorks) can use for
    // history-based operations (filleting, chamfering, extrusion, etc.).
    try {
      const nurbsConverter = new oc.BRepBuilderAPI_NurbsConvert_2(shape, false);
      shape = nurbsConverter.Shape();
      nurbsConverter.delete();
    } catch (e) {
      console.warn('[mesh-to-brep] NurbsConvert failed, falling back to unified shape:', e instanceof Error ? e.message : e);
    }

    // Try to create a solid from the shell
    try {
      const solidMaker = new oc.BRepBuilderAPI_MakeSolid_1();
      const explorer = new oc.TopExp_Explorer_2(
        shape,
        oc.TopAbs_ShapeEnum.TopAbs_SHELL,
        oc.TopAbs_ShapeEnum.TopAbs_SHAPE,
      );

      let hasShell = false;
      while (explorer.More()) {
        const shell = oc.TopoDS.Shell_1(explorer.Current());
        solidMaker.Add(shell);
        hasShell = true;
        explorer.Next();
      }
      explorer.delete();

      if (hasShell && solidMaker.IsDone()) {
        const solid = solidMaker.Solid();
        solidMaker.delete();
        return solid;
      }
      solidMaker.delete();
    } catch (e) {
      console.warn('[mesh-to-brep] Solid creation failed, returning sewn shell:', e instanceof Error ? e.message : e);
    }

    return shape;
  } finally {
    cleaner.cleanup();
  }
}

/**
 * Build a planar triangular face from 3 vertices.
 */
function buildTriangleFace(
  oc: OCInstance,
  cleaner: OCCleaner,
  x0: number, y0: number, z0: number,
  x1: number, y1: number, z1: number,
  x2: number, y2: number, z2: number,
): OCHandle | null {
  try {
    const p0 = cleaner.track(new oc.gp_Pnt_3(x0, y0, z0));
    const p1 = cleaner.track(new oc.gp_Pnt_3(x1, y1, z1));
    const p2 = cleaner.track(new oc.gp_Pnt_3(x2, y2, z2));

    // Check for degenerate triangle
    const d01 = Math.sqrt((x1 - x0) ** 2 + (y1 - y0) ** 2 + (z1 - z0) ** 2);
    const d12 = Math.sqrt((x2 - x1) ** 2 + (y2 - y1) ** 2 + (z2 - z1) ** 2);
    const d20 = Math.sqrt((x0 - x2) ** 2 + (y0 - y2) ** 2 + (z0 - z2) ** 2);
    if (d01 < 1e-7 || d12 < 1e-7 || d20 < 1e-7) return null;

    // Create polygon wire
    const polygon = cleaner.track(new oc.BRepBuilderAPI_MakePolygon_1());
    polygon.Add_1(p0);
    polygon.Add_1(p1);
    polygon.Add_1(p2);
    polygon.Close();

    if (!polygon.IsDone()) return null;

    const wire = polygon.Wire();

    // Create planar face from wire
    const faceMaker = cleaner.track(new oc.BRepBuilderAPI_MakeFace_15(wire, true));
    if (!faceMaker.IsDone()) return null;

    return faceMaker.Face();
  } catch (e) {
    console.warn('[mesh-to-brep] buildTriangleFace failed:', e instanceof Error ? e.message : e);
    return null;
  }
}

/**
 * Group triangles that share the same plane (coplanar) for merging.
 * Two triangles are coplanar if their normals are parallel and they
 * share at least one edge.
 *
 * Returns groups of triangle indices.
 */
function groupCoplanarTriangles(mesh: Mesh): number[][] {
  const { vertices, indices } = mesh;
  const triCount = indices.length / 3;

  if (triCount === 0) return [];

  // Compute face normals
  const normals: [number, number, number][] = [];
  for (let t = 0; t < triCount; t++) {
    const i0 = indices[t * 3], i1 = indices[t * 3 + 1], i2 = indices[t * 3 + 2];
    const ax = vertices[i1 * 3] - vertices[i0 * 3];
    const ay = vertices[i1 * 3 + 1] - vertices[i0 * 3 + 1];
    const az = vertices[i1 * 3 + 2] - vertices[i0 * 3 + 2];
    const bx = vertices[i2 * 3] - vertices[i0 * 3];
    const by = vertices[i2 * 3 + 1] - vertices[i0 * 3 + 1];
    const bz = vertices[i2 * 3 + 2] - vertices[i0 * 3 + 2];
    const nx = ay * bz - az * by;
    const ny = az * bx - ax * bz;
    const nz = ax * by - ay * bx;
    const len = Math.sqrt(nx * nx + ny * ny + nz * nz);
    if (len > 1e-10) {
      normals.push([nx / len, ny / len, nz / len]);
    } else {
      normals.push([0, 0, 0]);
    }
  }

  // Build edge-to-triangle adjacency
  const edgeMap = new Map<string, number[]>();
  for (let t = 0; t < triCount; t++) {
    const i0 = indices[t * 3], i1 = indices[t * 3 + 1], i2 = indices[t * 3 + 2];
    const edges = [[i0, i1], [i1, i2], [i2, i0]];
    for (const [a, b] of edges) {
      const key = a < b ? `${a}-${b}` : `${b}-${a}`;
      if (!edgeMap.has(key)) edgeMap.set(key, []);
      edgeMap.get(key)!.push(t);
    }
  }

  // Union-Find for grouping coplanar triangles
  const parent = new Int32Array(triCount);
  for (let i = 0; i < triCount; i++) parent[i] = i;

  function find(x: number): number {
    while (parent[x] !== x) {
      parent[x] = parent[parent[x]];
      x = parent[x];
    }
    return x;
  }

  function union(a: number, b: number): void {
    const ra = find(a), rb = find(b);
    if (ra !== rb) parent[ra] = rb;
  }

  // Merge adjacent coplanar triangles
  const coplanarThreshold = 0.999; // cos(~2.5 degrees)
  for (const tris of edgeMap.values()) {
    if (tris.length !== 2) continue;
    const [t0, t1] = tris;
    const [nx0, ny0, nz0] = normals[t0];
    const [nx1, ny1, nz1] = normals[t1];
    // Don't use Math.abs — opposite-facing normals (dot ≈ -1) must NOT be
    // merged, as they represent opposite sides of a thin surface.
    const dot = nx0 * nx1 + ny0 * ny1 + nz0 * nz1;
    if (dot > coplanarThreshold) {
      // Also check that they lie on the same plane (same distance from origin)
      const i0 = indices[t0 * 3];
      const i1 = indices[t1 * 3];
      const d0 = nx0 * vertices[i0 * 3] + ny0 * vertices[i0 * 3 + 1] + nz0 * vertices[i0 * 3 + 2];
      const d1 = nx0 * vertices[i1 * 3] + ny0 * vertices[i1 * 3 + 1] + nz0 * vertices[i1 * 3 + 2];
      if (Math.abs(d0 - d1) < 1e-4) {
        union(t0, t1);
      }
    }
  }

  // Collect groups
  const groups = new Map<number, number[]>();
  for (let t = 0; t < triCount; t++) {
    const root = find(t);
    if (!groups.has(root)) groups.set(root, []);
    groups.get(root)!.push(t);
  }

  return Array.from(groups.values());
}

/**
 * Build a single merged face from a group of coplanar triangles.
 * Extracts the boundary edges and creates a polygonal wire.
 */
function buildCoplanarFace(
  oc: OCInstance,
  cleaner: OCCleaner,
  mesh: Mesh,
  triangleIndices: number[],
): OCHandle | null {
  const { vertices, indices } = mesh;

  if (triangleIndices.length === 1) {
    // Single triangle - just build it directly
    const t = triangleIndices[0];
    const i0 = indices[t * 3], i1 = indices[t * 3 + 1], i2 = indices[t * 3 + 2];
    return buildTriangleFace(oc, cleaner,
      vertices[i0 * 3], vertices[i0 * 3 + 1], vertices[i0 * 3 + 2],
      vertices[i1 * 3], vertices[i1 * 3 + 1], vertices[i1 * 3 + 2],
      vertices[i2 * 3], vertices[i2 * 3 + 1], vertices[i2 * 3 + 2],
    );
  }

  try {
    // Find boundary edges (edges that appear only once)
    const edgeCount = new Map<string, { a: number; b: number; count: number }>();
    for (const t of triangleIndices) {
      const i0 = indices[t * 3], i1 = indices[t * 3 + 1], i2 = indices[t * 3 + 2];
      const edges: [number, number][] = [[i0, i1], [i1, i2], [i2, i0]];
      for (const [a, b] of edges) {
        const key = a < b ? `${a}-${b}` : `${b}-${a}`;
        if (!edgeCount.has(key)) {
          edgeCount.set(key, { a, b, count: 0 });
        }
        edgeCount.get(key)!.count++;
      }
    }

    // Boundary edges appear exactly once
    const boundaryEdges: [number, number][] = [];
    for (const { a, b, count } of edgeCount.values()) {
      if (count === 1) {
        boundaryEdges.push([a, b]);
      }
    }

    if (boundaryEdges.length < 3) return null;

    // Order boundary edges into a loop
    const loop = orderBoundaryLoop(boundaryEdges);
    if (!loop || loop.length < 3) return null;

    // Create polygon wire from ordered boundary vertices
    const polygon = cleaner.track(new oc.BRepBuilderAPI_MakePolygon_1());
    for (const vi of loop) {
      const pt = cleaner.track(new oc.gp_Pnt_3(
        vertices[vi * 3],
        vertices[vi * 3 + 1],
        vertices[vi * 3 + 2],
      ));
      polygon.Add_1(pt);
    }
    polygon.Close();

    if (!polygon.IsDone()) return null;

    const wire = polygon.Wire();
    const faceMaker = cleaner.track(new oc.BRepBuilderAPI_MakeFace_15(wire, true));
    if (!faceMaker.IsDone()) return null;

    return faceMaker.Face();
  } catch (e) {
    console.warn('[mesh-to-brep] buildCoplanarFace failed:', e instanceof Error ? e.message : e);
    return null;
  }
}

/**
 * Order a set of boundary edges into a closed loop of vertex indices.
 */
function orderBoundaryLoop(edges: [number, number][]): number[] | null {
  if (edges.length === 0) return null;

  // Build adjacency
  const adj = new Map<number, number[]>();
  for (const [a, b] of edges) {
    if (!adj.has(a)) adj.set(a, []);
    if (!adj.has(b)) adj.set(b, []);
    adj.get(a)!.push(b);
    adj.get(b)!.push(a);
  }

  // Walk the loop
  const loop: number[] = [];
  const visited = new Set<number>();
  let current = edges[0][0];
  loop.push(current);
  visited.add(current);

  for (let i = 0; i < edges.length; i++) {
    const neighbors = adj.get(current);
    if (!neighbors) break;
    const next = neighbors.find(n => !visited.has(n));
    if (next === undefined) break;
    loop.push(next);
    visited.add(next);
    current = next;
  }

  return loop.length >= 3 ? loop : null;
}
