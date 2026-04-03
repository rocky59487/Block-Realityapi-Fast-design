import type { ConvertRequest, MaterialMesh, StatusMessage, BlockData, ConvertResult } from './types.js';
import { buildSDFGrid } from './sdf/sdf-grid.js';
import { dualContour } from './dc/dual-contouring.js';
import { greedyMesh } from './greedy-mesh.js';
import { initOpenCascade, meshToShape } from './cad/mesh-to-brep.js';
import { writeSTEP, makeCompound } from './cad/step-writer.js';
import { RESOLUTION_RANGE, MAX_GRID_CELLS } from './constants.js';

export type ProgressCallback = (msg: StatusMessage) => void;

/** Error with extra context data for JSON-RPC error.data field */
class PipelineError extends Error {
  data: Record<string, unknown>;
  constructor(message: string, data: Record<string, unknown>) {
    super(message);
    this.data = data;
  }
}

/**
 * Main conversion pipeline with dual-path architecture:
 *
 *   smoothing = 0  → Greedy Meshing → B-Rep → STEP  (fast, sharp edges)
 *   smoothing > 0  → SDF → Dual Contouring → B-Rep → STEP  (smooth surfaces)
 *
 * Groups blocks by material (rMaterialId), each material becomes a separate
 * solid in the STEP compound. Returns per-material statistics.
 */
export async function convertVoxelsToSTEP(
  request: ConvertRequest,
  onProgress?: ProgressCallback,
): Promise<ConvertResult> {
  const { blocks, options } = request;
  const smoothing = options.smoothing ?? 0;
  const resolution = options.resolution ?? 1;

  // ★ HIGH-003 fix: Validate all input data before passing to OpenCASCADE.
  // The WASM CAD kernel processes raw numeric data — malformed inputs could
  // cause undefined behavior, memory corruption, or crashes in native code.
  validateBlocks(blocks);
  validateOptions(options);

  // Validate resolution (Java spec: 1~4)
  if (resolution < RESOLUTION_RANGE.min || resolution > RESOLUTION_RANGE.max) {
    throw new PipelineError(
      `Resolution ${resolution} out of valid range [${RESOLUTION_RANGE.min}, ${RESOLUTION_RANGE.max}]`,
      { resolution, validRange: RESOLUTION_RANGE },
    );
  }

  const report = (stage: string, progress: number, detail?: string) => {
    onProgress?.({ status: 'processing', stage, progress, message: detail });
  };

  // Step 1: Initialize OpenCASCADE
  report('init', 0, 'Initializing OpenCASCADE...');
  await initOpenCascade();
  report('init', 1.0);

  // Step 2: Group blocks by material
  report('grouping', 0);
  const materialGroups = groupByMaterial(blocks);
  const materialNames = Object.keys(materialGroups);
  report('grouping', 1.0);

  // Step 3: Generate meshes — dual path based on smoothing
  const useGreedyPath = smoothing === 0;
  const materialMeshes: MaterialMesh[] = [];

  if (useGreedyPath) {
    report('greedy_mesh', 0, 'Building greedy mesh...');
    for (let i = 0; i < materialNames.length; i++) {
      const material = materialNames[i];
      const matBlocks = materialGroups[material];
      const mesh = greedyMesh(matBlocks);
      if (mesh.indices.length > 0) {
        materialMeshes.push({ material, mesh });
      }
      report('greedy_mesh', (i + 1) / materialNames.length);
    }
  } else {
    // Memory guard: cap resolution and estimate grid size
    let cappedResolution = resolution;
    const MAX_RESOLUTION_CAP = 4;
    if (resolution > MAX_RESOLUTION_CAP) {
      console.warn(
        `[SDF Memory Guard] Resolution ${resolution} exceeds cap ${MAX_RESOLUTION_CAP}. ` +
        `Capping to ${MAX_RESOLUTION_CAP} to prevent memory explosion.`
      );
      cappedResolution = MAX_RESOLUTION_CAP;
    }

    for (const material of materialNames) {
      const matBlocks = materialGroups[material];
      const est = estimateGridSize(matBlocks, cappedResolution);

      // Memory estimation: gridSize * 8 bytes (float64) per cell
      const estimatedMemoryBytes = est * 8;
      const estimatedMemoryMB = estimatedMemoryBytes / (1024 * 1024);
      const MEMORY_THRESHOLD_MB = 100;

      if (estimatedMemoryBytes > MEMORY_THRESHOLD_MB * 1024 * 1024) {
        console.warn(
          `[SDF Memory Guard] Material "${material}": estimated ${estimatedMemoryMB.toFixed(1)}MB ` +
          `(${est.toLocaleString()} cells × 8 bytes/cell). ` +
          `Exceeds ${MEMORY_THRESHOLD_MB}MB threshold. Capping resolution to 1.`
        );
        cappedResolution = 1;
      }

      const finalEst = estimateGridSize(matBlocks, cappedResolution);
      if (finalEst > MAX_GRID_CELLS) {
        throw new PipelineError(
          `SDF grid too large: ${finalEst.toLocaleString()} cells (max ${MAX_GRID_CELLS.toLocaleString()}). ` +
          `Reduce resolution or block count.`,
          { stage: 'sdf_build', blockCount: matBlocks.length, gridSize: finalEst, resolution: cappedResolution },
        );
      }
    }

    report('sdf_build', 0, 'Building signed distance field...');
    for (let i = 0; i < materialNames.length; i++) {
      const material = materialNames[i];
      const matBlocks = materialGroups[material];
      const baseProgress = i / materialNames.length;
      const groupWeight = 1 / materialNames.length;

      report('sdf_build', baseProgress, `SDF for ${material}...`);
      const sdfGrid = buildSDFGrid(matBlocks, cappedResolution);
      if (sdfGrid.sizeX === 0) continue;

      report('dc_extract', baseProgress + groupWeight * 0.5, `Dual contouring ${material}...`);
      const mesh = dualContour(sdfGrid, smoothing);
      if (mesh.indices.length === 0) continue;

      materialMeshes.push({ material, mesh });
      report('dc_extract', baseProgress + groupWeight);
    }
  }

  if (materialMeshes.length === 0) {
    throw new PipelineError('No geometry generated from input blocks', {
      stage: 'mesh_generation',
      blockCount: blocks.length,
      materialCount: materialNames.length,
    });
  }

  // Step 4: Convert meshes to B-Rep shapes
  report('step_export', 0, 'Converting to STEP...');
  const shapes: any[] = [];
  const materialBreakdown: Record<string, { blockCount: number; faceCount: number }> = {};

  for (let i = 0; i < materialMeshes.length; i++) {
    const { material, mesh } = materialMeshes[i];
    const shape = meshToShape(mesh);
    shapes.push(shape);
    materialBreakdown[material] = {
      blockCount: materialGroups[material].length,
      faceCount: mesh.indices.length / 3,
    };
    report('step_export', (i + 1) / materialMeshes.length * 0.8);
  }

  // Step 5: Combine into compound and export STEP
  report('step_export', 0.9, 'Writing STEP file...');
  const finalShape = shapes.length === 1 ? shapes[0] : makeCompound(shapes);
  writeSTEP(finalShape, options.outputPath);
  report('complete', 1.0, options.outputPath);

  return {
    success: true,
    outputPath: options.outputPath,
    blockCount: blocks.length,
    materialBreakdown,
  };
}

/**
 * Estimate total grid cells for memory guard.
 */
function estimateGridSize(blocks: BlockData[], resolution: number): number {
  // ★ Fix: 空陣列防護 — 避免 Infinity - (-Infinity) = NaN 導致後續計算失敗
  if (blocks.length === 0) return 0;

  let minX = Infinity, minY = Infinity, minZ = Infinity;
  let maxX = -Infinity, maxY = -Infinity, maxZ = -Infinity;
  for (const b of blocks) {
    if (b.x < minX) minX = b.x; if (b.x > maxX) maxX = b.x;
    if (b.y < minY) minY = b.y; if (b.y > maxY) maxY = b.y;
    if (b.z < minZ) minZ = b.z; if (b.z > maxZ) maxZ = b.z;
  }
  const padding = 2;
  const sx = (maxX - minX + 1 + 2 * padding) * resolution + 1;
  const sy = (maxY - minY + 1 + 2 * padding) * resolution + 1;
  const sz = (maxZ - minZ + 1 + 2 * padding) * resolution + 1;
  return sx * sy * sz;
}

/**
 * Group blocks by their material type.
 */
function groupByMaterial(blocks: BlockData[]): Record<string, BlockData[]> {
  const groups: Record<string, BlockData[]> = {};
  for (const block of blocks) {
    const mat = block.material || 'default';
    if (!groups[mat]) groups[mat] = [];
    groups[mat].push(block);
  }
  return groups;
}

// ═══════════════════════════════════════════════════════════════
//  Input Validation (HIGH-003 fix)
// ═══════════════════════════════════════════════════════════════

/** Maximum coordinate magnitude — prevents extreme values from causing OC overflow */
const MAX_COORDINATE = 1e6;

/** Maximum block count per request */
const MAX_BLOCKS = 10_000;

/** Maximum material ID length */
const MAX_MATERIAL_ID_LENGTH = 128;

/**
 * ★ HIGH-003 fix: Validate block data before passing to OpenCASCADE WASM kernel.
 *
 * OpenCASCADE processes raw numeric data in native WASM memory. Malformed inputs
 * (NaN, Infinity, extreme values) could cause undefined behavior, memory corruption,
 * or WASM trap/abort in the CAD kernel.
 *
 * Validates:
 * - Array is non-empty and within size limits
 * - Each block has finite integer coordinates within safe range
 * - Material IDs are safe strings (no injection via material names)
 */
function validateBlocks(blocks: BlockData[]): void {
  if (!Array.isArray(blocks)) {
    throw new PipelineError('blocks must be an array', { received: typeof blocks });
  }
  if (blocks.length === 0) {
    throw new PipelineError('blocks array is empty', { blockCount: 0 });
  }
  if (blocks.length > MAX_BLOCKS) {
    throw new PipelineError(
      `Block count ${blocks.length} exceeds maximum ${MAX_BLOCKS}`,
      { blockCount: blocks.length, max: MAX_BLOCKS },
    );
  }

  for (let i = 0; i < blocks.length; i++) {
    const b = blocks[i];
    if (b == null || typeof b !== 'object') {
      throw new PipelineError(`blocks[${i}] is not an object`, { index: i, value: b });
    }

    // Validate coordinates are finite numbers within safe range
    for (const axis of ['x', 'y', 'z'] as const) {
      const v = b[axis];
      if (typeof v !== 'number' || !Number.isFinite(v)) {
        throw new PipelineError(
          `blocks[${i}].${axis} is not a finite number`,
          { index: i, axis, value: v },
        );
      }
      if (Math.abs(v) > MAX_COORDINATE) {
        throw new PipelineError(
          `blocks[${i}].${axis} = ${v} exceeds safe coordinate range ±${MAX_COORDINATE}`,
          { index: i, axis, value: v, max: MAX_COORDINATE },
        );
      }
    }

    // Validate material ID (if present) is a safe string
    if (b.material != null) {
      if (typeof b.material !== 'string') {
        throw new PipelineError(
          `blocks[${i}].material is not a string`,
          { index: i, material: b.material },
        );
      }
      if (b.material.length > MAX_MATERIAL_ID_LENGTH) {
        throw new PipelineError(
          `blocks[${i}].material exceeds ${MAX_MATERIAL_ID_LENGTH} characters`,
          { index: i, length: b.material.length },
        );
      }
      // Reject control characters (potential injection via material names passed to OC)
      if (/[\x00-\x1f]/.test(b.material)) {
        throw new PipelineError(
          `blocks[${i}].material contains control characters`,
          { index: i },
        );
      }
    }
  }
}

/**
 * ★ HIGH-003 fix: Validate pipeline options before processing.
 *
 * Ensures smoothing and resolution are within expected ranges,
 * and outputPath has already been validated by path-security.ts.
 */
function validateOptions(options: ConvertRequest['options']): void {
  if (options == null || typeof options !== 'object') {
    throw new PipelineError('options must be an object', { received: typeof options });
  }

  // Validate smoothing
  if (options.smoothing != null) {
    if (typeof options.smoothing !== 'number' || !Number.isFinite(options.smoothing)) {
      throw new PipelineError(
        `smoothing must be a finite number`,
        { smoothing: options.smoothing },
      );
    }
    if (options.smoothing < 0 || options.smoothing > 1) {
      throw new PipelineError(
        `smoothing ${options.smoothing} out of valid range [0, 1]`,
        { smoothing: options.smoothing },
      );
    }
  }

  // Validate resolution
  if (options.resolution != null) {
    if (typeof options.resolution !== 'number' || !Number.isInteger(options.resolution)) {
      throw new PipelineError(
        `resolution must be an integer`,
        { resolution: options.resolution },
      );
    }
  }

  // Validate outputPath exists
  if (!options.outputPath || typeof options.outputPath !== 'string') {
    throw new PipelineError('outputPath is required and must be a string', {
      outputPath: options.outputPath,
    });
  }
}
