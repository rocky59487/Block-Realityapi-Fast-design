import { describe, it, expect } from 'vitest';
import * as path from 'node:path';
import { validateOutputPath, getExportBaseDir } from '../src/path-security.js';

describe('path-security', () => {
  const baseDir = path.resolve('/tmp/br-test-exports');

  // ─── Valid paths ───

  it('accepts a path within the base directory', () => {
    const result = validateOutputPath('/tmp/br-test-exports/model.step', baseDir);
    expect(result).toBe(path.resolve('/tmp/br-test-exports/model.step'));
  });

  it('accepts a nested path within the base directory', () => {
    const result = validateOutputPath('/tmp/br-test-exports/sub/dir/model.ifc', baseDir);
    expect(result).toBe(path.resolve('/tmp/br-test-exports/sub/dir/model.ifc'));
  });

  it('accepts a relative path that resolves inside base', () => {
    // This depends on cwd but validateOutputPath resolves relative to cwd
    // We test with an absolute path in baseDir to be deterministic
    const result = validateOutputPath(
      path.join(baseDir, 'output.step'),
      baseDir,
    );
    expect(result).toBe(path.resolve(baseDir, 'output.step'));
  });

  // ─── Path traversal attacks ───

  it('rejects path traversal with ../../../etc/passwd', () => {
    expect(() =>
      validateOutputPath('/tmp/br-test-exports/../../../etc/passwd', baseDir),
    ).toThrow('Path traversal blocked');
  });

  it('rejects path traversal targeting /etc/cron.d/backdoor', () => {
    expect(() =>
      validateOutputPath(
        '/tmp/br-test-exports/../../../etc/cron.d/backdoor',
        baseDir,
      ),
    ).toThrow('Path traversal blocked');
  });

  it('rejects path that resolves outside base via .. segments', () => {
    expect(() =>
      validateOutputPath('/tmp/br-test-exports/sub/../../outside.step', baseDir),
    ).toThrow('Path traversal blocked');
  });

  it('rejects absolute path outside base directory', () => {
    expect(() =>
      validateOutputPath('/etc/passwd', baseDir),
    ).toThrow('Path traversal blocked');
  });

  it('rejects path with .ifc extension but traversal (audit HIGH-001 PoC)', () => {
    // Exact attack vector from the audit report
    expect(() =>
      validateOutputPath(
        '../../../../../../../etc/cron.d/backdoor.ifc',
        baseDir,
      ),
    ).toThrow('Path traversal blocked');
  });

  // ─── Null byte attacks ───

  it('rejects null bytes in path', () => {
    expect(() =>
      validateOutputPath('/tmp/br-test-exports/model.step\0.txt', baseDir),
    ).toThrow('null bytes');
  });

  it('rejects null byte at start of path', () => {
    expect(() =>
      validateOutputPath('\0/tmp/br-test-exports/model.step', baseDir),
    ).toThrow('null bytes');
  });

  // ─── Control character attacks ───

  it('rejects control characters in path', () => {
    expect(() =>
      validateOutputPath('/tmp/br-test-exports/model\x01.step', baseDir),
    ).toThrow('control characters');
  });

  // ─── Prefix attacks ───

  it('rejects path that shares prefix but is outside base (e.g. /exports-evil)', () => {
    expect(() =>
      validateOutputPath('/tmp/br-test-exports-evil/model.step', baseDir),
    ).toThrow('Path traversal blocked');
  });

  // ─── getExportBaseDir ───

  it('returns default exports dir when BR_EXPORT_DIR is not set', () => {
    const original = process.env['BR_EXPORT_DIR'];
    delete process.env['BR_EXPORT_DIR'];
    try {
      const dir = getExportBaseDir();
      expect(dir).toBe(path.resolve(process.cwd(), 'exports'));
    } finally {
      if (original !== undefined) process.env['BR_EXPORT_DIR'] = original;
    }
  });

  it('returns BR_EXPORT_DIR when set', () => {
    const original = process.env['BR_EXPORT_DIR'];
    process.env['BR_EXPORT_DIR'] = '/custom/export/path';
    try {
      const dir = getExportBaseDir();
      expect(dir).toBe(path.resolve('/custom/export/path'));
    } finally {
      if (original !== undefined) {
        process.env['BR_EXPORT_DIR'] = original;
      } else {
        delete process.env['BR_EXPORT_DIR'];
      }
    }
  });
});
