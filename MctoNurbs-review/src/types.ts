/** A single Minecraft block with position and material (internal format) */
export interface BlockData {
  x: number;
  y: number;
  z: number;
  material: string;
}

/** Conversion request (internal) */
export interface ConvertRequest {
  blocks: BlockData[];
  options: ConvertOptions;
}

export interface ConvertOptions {
  /** Smoothing factor: 0.0 = Greedy Mesh (sharp), 0.01~1.0 = SDF+DC (smooth) */
  smoothing: number;
  /** Output file path for the .step file */
  outputPath: string;
  /** SDF sub-voxel resolution multiplier (1~4, default 1) */
  resolution?: number;
}

/** 3D vector */
export interface Vec3 {
  x: number;
  y: number;
  z: number;
}

/** Axis-aligned bounding box */
export interface AABB {
  minX: number;
  minY: number;
  minZ: number;
  maxX: number;
  maxY: number;
  maxZ: number;
}

/** Hermite edge data: intersection point + surface normal */
export interface HermiteEdge {
  point: Vec3;
  normal: Vec3;
}

/** Triangle mesh output */
export interface Mesh {
  vertices: Float64Array;  // [x0,y0,z0, x1,y1,z1, ...]
  indices: Uint32Array;    // [i0,i1,i2, i3,i4,i5, ...] (triangles)
  normals: Float64Array;   // per-vertex normals [nx0,ny0,nz0, ...]
}

/** Named mesh with material info */
export interface MaterialMesh {
  material: string;
  mesh: Mesh;
}

/** IPC status message (legacy single-shot mode) */
export interface StatusMessage {
  status: 'processing' | 'complete' | 'error';
  stage?: string;
  progress?: number;
  message?: string;
  outputPath?: string;
}

// ─── Java Mod Integration (block-reality-api) ───

/**
 * BlueprintBlock from the Java mod's Blueprint.BlueprintBlock class.
 * Field names match exactly what Java sends via JSON-RPC.
 */
export interface BlueprintBlock {
  relX: number;
  relY: number;
  relZ: number;
  blockState: string;     // e.g. "minecraft:stone[waterlogged=false]"
  rMaterialId: string;    // e.g. "concrete" | "rebar" | "steel" | "timber"

  // Physics metadata (passthrough — used for future structural analysis)
  rcomp?: number;         // Compressive strength (MPa)
  rtens?: number;         // Tensile strength (MPa)
  stressLevel?: number;   // Current stress level 0.0~1.0
  isAnchored?: boolean;   // Whether this is an anchor block
}

/**
 * Parameters for the "dualContouring" JSON-RPC method.
 * Called from Java via: bridge.call("dualContouring", params, timeout)
 */
export interface DualContouringParams {
  blocks: BlueprintBlock[];
  options: {
    smoothing: number;     // 0.0 = Greedy Mesh, 0.01~1.0 = SDF+DC
    outputPath: string;
    resolution?: number;   // 1~4, SDF sub-voxel multiplier
  };
}

/**
 * Result returned to Java via JSON-RPC response.result.
 * Java parses outputPath and blockCount for logging.
 */
export interface ConvertResult {
  success: boolean;
  outputPath: string;
  blockCount: number;
  materialBreakdown: Record<string, { blockCount: number; faceCount: number }>;
}

/**
 * Convert BlueprintBlock[] (from Java mod) to our internal BlockData[].
 * Maps relative coords to absolute coords and extracts material ID.
 */
export function blueprintToBlocks(blueprintBlocks: BlueprintBlock[]): BlockData[] {
  return blueprintBlocks.map(b => ({
    x: b.relX,
    y: b.relY,
    z: b.relZ,
    material: b.rMaterialId || b.blockState || 'unknown',
  }));
}

// ─── IFC 4.x Structural Export ───────────────────────────────────────────────

/**
 * Parameters for the "ifc4Export" JSON-RPC method.
 * Called from Java via: bridge.call("ifc4Export", params, timeout)
 */
export interface Ifc4ExportParams {
  blocks: BlueprintBlock[];
  options: {
    /** Output file path for the .ifc file */
    outputPath: string;
    /** Project name embedded in IFC header */
    projectName?: string;
    /** Author organization name */
    authorOrg?: string;
    /**
     * Include per-block geometry (IFCEXTRUDEDAREASOLID).
     * Default true. Set false for metadata-only export (faster, smaller file).
     */
    includeGeometry?: boolean;
  };
}

/**
 * Result returned to Java for the "ifc4Export" RPC method.
 */
export interface Ifc4ExportResult {
  success: boolean;
  outputPath: string;
  blockCount: number;
  elementCount: number;
  materialCount: number;
  columnCount: number;
  beamCount: number;
  wallCount: number;
  slabCount: number;
  /** Highest stress level across all elements (0.0–1.0) */
  maxStressLevel: number;
  /** Highest utilization ratio across all elements (%) */
  maxUtilization: number;
}
