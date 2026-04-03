import * as readline from 'node:readline';
import * as path from 'node:path';
import * as fs from 'node:fs';
import { convertVoxelsToSTEP } from './pipeline.js';
import { RpcServer } from './rpc-server.js';
import type { ConvertRequest, StatusMessage, DualContouringParams, ConvertResult, Ifc4ExportParams, Ifc4ExportResult } from './types.js';
import { blueprintToBlocks } from './types.js';
import { MAX_BLOCK_COUNT, RESOLUTION_RANGE } from './constants.js';
import { exportToIfc4 } from './ifc/ifc-structural-export.js';
import { validateAndEnsureDir } from './path-security.js';

/**
 * MctoNurbs Sidecar Entry Point — Dual Mode
 *
 * MODE 1: JSON-RPC 2.0 Server (for Java SidecarBridge)
 *   Spawned as: GAMEDIR/blockreality/sidecar/dist/sidecar.js
 *   Reads JSON-RPC requests from stdin, stays alive until "shutdown".
 *
 * MODE 2: Single-shot (CLI testing, backward compat)
 *   Reads a full ConvertRequest JSON from stdin until EOF, processes it, exits.
 *   Auto-detected: first stdin line does NOT contain "jsonrpc".
 */

function main(): void {
  const rl = readline.createInterface({ input: process.stdin });
  let firstLine = true;
  let singleShotBuffer: string[] = [];
  let rpcServer: RpcServer | null = null;

  rl.on('line', (line: string) => {
    const trimmed = line.trim();
    if (!trimmed) return;

    if (firstLine) {
      firstLine = false;

      if (trimmed.includes('"jsonrpc"')) {
        // JSON-RPC mode: start server and process this line
        rpcServer = createRpcServer();
        rpcServer.startWithRL(rl);
        rpcServer.processLine(trimmed);
      } else {
        // Single-shot mode: accumulate all lines
        singleShotBuffer.push(trimmed);
      }
      return;
    }

    if (rpcServer) {
      // Already in RPC mode — server handles via readline
      return;
    }

    // Single-shot: keep accumulating
    singleShotBuffer.push(trimmed);
  });

  rl.on('close', () => {
    if (!rpcServer && singleShotBuffer.length > 0) {
      // Single-shot mode: process accumulated input
      runSingleShot(singleShotBuffer.join('\n'));
    }
  });
}

// ─── JSON-RPC 2.0 Server Mode ───

function createRpcServer(): RpcServer {
  const server = new RpcServer();

  server.method('ping', () => ({ pong: true, ts: Date.now() }));

  server.method('dualContouring', async (params) => {
    return handleDualContouring(params, server);
  });

  server.method('ifc4Export', async (params) => {
    return handleIfc4Export(params, server);
  });

  return server;
}

async function handleDualContouring(
  params: Record<string, unknown>,
  server: RpcServer,
): Promise<ConvertResult> {
  const p = params as unknown as DualContouringParams;

  if (!p.blocks || !Array.isArray(p.blocks)) {
    throw Object.assign(new Error('params.blocks must be an array'), {
      data: { received: typeof p.blocks },
    });
  }
  if (p.blocks.length === 0) {
    throw Object.assign(new Error('params.blocks cannot be empty'), { data: {} });
  }
  if (p.blocks.length > MAX_BLOCK_COUNT) {
    throw Object.assign(
      new Error(`Block count ${p.blocks.length} exceeds maximum ${MAX_BLOCK_COUNT}`),
      { data: { blockCount: p.blocks.length, max: MAX_BLOCK_COUNT } },
    );
  }
  if (!p.options?.outputPath) {
    throw Object.assign(new Error('params.options.outputPath is required'), { data: {} });
  }

  const resolution = p.options.resolution ?? 1;
  if (resolution < RESOLUTION_RANGE.min || resolution > RESOLUTION_RANGE.max) {
    throw Object.assign(
      new Error(`Resolution ${resolution} out of range [${RESOLUTION_RANGE.min}, ${RESOLUTION_RANGE.max}]`),
      { data: { resolution, validRange: RESOLUTION_RANGE } },
    );
  }

  // ★ Security: validate outputPath stays within allowed export directory
  const validatedPath = validateAndEnsureDir(p.options.outputPath, fs);

  const blocks = blueprintToBlocks(p.blocks);
  const request: ConvertRequest = {
    blocks,
    options: {
      smoothing: p.options.smoothing ?? 0,
      outputPath: validatedPath,
      resolution,
    },
  };

  // Run pipeline with progress notifications
  const result = await convertVoxelsToSTEP(request, (msg) => {
    if (msg.stage) {
      server.notify('progress', {
        stage: msg.stage,
        percent: Math.round((msg.progress ?? 0) * 100),
        detail: msg.message,
      });
    }
  });

  return result;
}

async function handleIfc4Export(
  params: Record<string, unknown>,
  server: RpcServer,
): Promise<Ifc4ExportResult> {
  const p = params as unknown as Ifc4ExportParams;

  if (!p.blocks || !Array.isArray(p.blocks)) {
    throw Object.assign(new Error('params.blocks must be an array'), {
      data: { received: typeof p.blocks },
    });
  }
  if (p.blocks.length === 0) {
    throw Object.assign(new Error('params.blocks cannot be empty'), { data: {} });
  }
  if (p.blocks.length > MAX_BLOCK_COUNT) {
    throw Object.assign(
      new Error(`Block count ${p.blocks.length} exceeds maximum ${MAX_BLOCK_COUNT}`),
      { data: { blockCount: p.blocks.length, max: MAX_BLOCK_COUNT } },
    );
  }
  if (!p.options?.outputPath) {
    throw Object.assign(new Error('params.options.outputPath is required'), { data: {} });
  }

  // Validate outputPath has .ifc extension
  const outPath = p.options.outputPath;
  if (!outPath.toLowerCase().endsWith('.ifc')) {
    throw Object.assign(
      new Error('outputPath must have .ifc extension for IFC export'),
      { data: { outputPath: outPath } },
    );
  }

  // ★ Security: validate outputPath stays within allowed export directory
  const validatedOutPath = validateAndEnsureDir(outPath, fs);

  server.notify('progress', { stage: 'ifc_classify', percent: 10, detail: 'Classifying structural elements...' });

  const result = await exportToIfc4(p.blocks, {
    outputPath: validatedOutPath,
    projectName: p.options.projectName,
    authorOrg: p.options.authorOrg,
    includeGeometry: p.options.includeGeometry,
  });

  server.notify('progress', { stage: 'ifc_write', percent: 100, detail: `IFC file written: ${validatedOutPath}` });

  return result;
}

// ─── Single-Shot Mode (backward compat) ───

function sendMessage(msg: StatusMessage): void {
  process.stdout.write(JSON.stringify(msg) + '\n');
}

async function runSingleShot(input: string): Promise<void> {
  try {
    if (!input.trim()) {
      throw new Error('Empty input received on stdin');
    }

    const rawData = JSON.parse(input);
    const request = validateSingleShotRequest(rawData);

    sendMessage({
      status: 'processing',
      stage: 'starting',
      progress: 0,
      message: `Processing ${request.blocks.length} blocks`,
    });

    const result = await convertVoxelsToSTEP(request, sendMessage);

    sendMessage({
      status: 'complete',
      outputPath: result.outputPath,
      message: `STEP file written to ${result.outputPath}`,
    });
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    sendMessage({ status: 'error', message });
    process.exit(1);
  }
}

function validateSingleShotRequest(data: unknown): ConvertRequest {
  if (typeof data !== 'object' || data === null) {
    throw new Error('Invalid input: expected a JSON object');
  }

  const obj = data as Record<string, unknown>;

  if (!Array.isArray(obj.blocks) || obj.blocks.length === 0) {
    throw new Error('Invalid input: "blocks" must be a non-empty array');
  }

  for (let i = 0; i < obj.blocks.length; i++) {
    const block = obj.blocks[i] as Record<string, unknown>;
    if (typeof block.x !== 'number' || typeof block.y !== 'number' || typeof block.z !== 'number') {
      throw new Error(`Invalid block at index ${i}: x, y, z must be numbers`);
    }
    if (!Number.isFinite(block.x) || !Number.isFinite(block.y) || !Number.isFinite(block.z)) {
      throw new Error(`Invalid block at index ${i}: coordinates must be finite`);
    }
    if (typeof block.material !== 'string' || (block.material as string).length === 0) {
      throw new Error(`Invalid block at index ${i}: material must be a non-empty string`);
    }
  }

  if (typeof obj.options !== 'object' || obj.options === null) {
    throw new Error('Invalid input: "options" must be an object');
  }

  const options = obj.options as Record<string, unknown>;
  if (typeof options.outputPath !== 'string' || options.outputPath.length === 0) {
    throw new Error('Invalid input: options.outputPath must be a non-empty string');
  }

  return {
    blocks: obj.blocks as ConvertRequest['blocks'],
    options: {
      smoothing: (options.smoothing as number) ?? 0.5,
      outputPath: options.outputPath as string,
      resolution: (options.resolution as number) ?? 1,
    },
  };
}

// ★ Fix: 全域錯誤處理 — 防止未捕獲的 Promise rejection 導致靜默崩潰
process.on('unhandledRejection', (reason: unknown) => {
  console.error('[Sidecar] Unhandled promise rejection:', reason);
});
process.on('uncaughtException', (err: Error) => {
  console.error('[Sidecar] Uncaught exception:', err);
  process.exit(1);
});

main();
