/**
 * Numerical constants used across the pipeline.
 * Centralized here to avoid magic numbers and ensure consistency.
 */
export const EPSILON = {
  /** Threshold for considering a vector zero-length (normalization guard) */
  VECTOR_ZERO: 1e-10,
  /** Minimum trilinear interpolation weight worth computing */
  TRILINEAR_WEIGHT: 1e-12,
  /** SVD singular value truncation (relative to max eigenvalue) */
  SVD_RELATIVE: 1e-6,
  /** SVD absolute minimum threshold (handles all-zero eigenvalues) */
  SVD_ABSOLUTE: 1e-12,
  /** Minimum edge length for non-degenerate triangles */
  DEGENERATE_EDGE: 1e-7,
  /** Cosine threshold for coplanar face merging (~1.8°) */
  COPLANAR_COS: 0.999,
  /** Coplanar distance threshold (same plane check) */
  COPLANAR_DIST: 1e-4,
  /** OpenCASCADE sewing tolerance */
  SEWING_TOLERANCE: 1e-6,
} as const;

/** Valid range for the resolution parameter (Java spec: 1~4) */
export const RESOLUTION_RANGE = { min: 1, max: 4 } as const;

/** Max grid cells to prevent OOM (resolution³ × gridSize < this) */
export const MAX_GRID_CELLS = 4_000_000;

/** Max block count per RPC call (Java spec: 65536) */
export const MAX_BLOCK_COUNT = 65536;

/** Valid range for block coordinates (limited by numeric key packing) */
export const COORD_RANGE = { min: -16384, max: 16383 } as const;
