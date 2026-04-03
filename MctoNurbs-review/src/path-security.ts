import * as path from 'node:path';

/**
 * Path Security — Prevents path traversal attacks on outputPath parameters.
 *
 * All export operations (dualContouring, ifc4Export) MUST validate
 * outputPath through this module before any filesystem operation.
 *
 * Defence layers:
 *   1. Null-byte rejection (bypasses path parsing on some OS)
 *   2. path.resolve() normalization (collapses .. segments)
 *   3. startsWith() containment check (ensures within allowed base)
 */

/**
 * Default export base directory.
 * Configurable via BR_EXPORT_DIR environment variable.
 */
export function getExportBaseDir(): string {
  return process.env['BR_EXPORT_DIR'] || path.resolve(process.cwd(), 'exports');
}

/**
 * Validate and resolve an outputPath, ensuring it stays within the allowed base directory.
 *
 * @param outputPath - User-provided output path (may be relative or absolute)
 * @param allowedBaseDir - Base directory that outputPath must be contained within
 * @returns Resolved absolute path guaranteed to be within allowedBaseDir
 * @throws Error if path traversal is detected or path contains unsafe characters
 */
export function validateOutputPath(outputPath: string, allowedBaseDir?: string): string {
  const baseDir = allowedBaseDir ?? getExportBaseDir();

  // Layer 1: Reject null bytes (can bypass path parsing on some OS)
  if (outputPath.includes('\0')) {
    throw Object.assign(
      new Error('Path contains null bytes — potential path traversal attack'),
      { code: 'PATH_TRAVERSAL_NULL_BYTE', data: {} },
    );
  }

  // Layer 2: Reject control characters (U+0001..U+001F except common whitespace)
  // eslint-disable-next-line no-control-regex
  if (/[\x01-\x08\x0B\x0C\x0E-\x1F]/.test(outputPath)) {
    throw Object.assign(
      new Error('Path contains control characters'),
      { code: 'PATH_TRAVERSAL_CONTROL_CHARS', data: {} },
    );
  }

  // Layer 3: Resolve to absolute, normalizing away all .. and . segments
  const resolved = path.resolve(outputPath);
  const normalizedBase = path.resolve(baseDir);

  // Layer 4: Containment check — resolved path must be within base dir
  // Use path.sep suffix to prevent prefix attacks (e.g. /exports-evil matching /exports)
  if (resolved !== normalizedBase && !resolved.startsWith(normalizedBase + path.sep)) {
    throw Object.assign(
      new Error(
        `Path traversal blocked: resolved path '${resolved}' ` +
        `is outside allowed directory '${normalizedBase}'`,
      ),
      { code: 'PATH_TRAVERSAL_ESCAPE', data: { resolved, allowedBase: normalizedBase } },
    );
  }

  return resolved;
}

/**
 * Validate outputPath and ensure the parent directory exists (creating it if needed,
 * but only within the allowed base directory).
 *
 * This replaces raw `fs.mkdirSync(dir, { recursive: true })` calls that could
 * create arbitrary directories via path traversal.
 */
export function validateAndEnsureDir(
  outputPath: string,
  fs: { existsSync: (p: string) => boolean; mkdirSync: (p: string, opts?: { recursive?: boolean }) => void },
  allowedBaseDir?: string,
): string {
  const validated = validateOutputPath(outputPath, allowedBaseDir);
  const dir = path.dirname(validated);

  if (!fs.existsSync(dir)) {
    fs.mkdirSync(dir, { recursive: true });
  }

  return validated;
}
