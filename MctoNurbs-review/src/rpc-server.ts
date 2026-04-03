import * as readline from 'node:readline';

/**
 * JSON-RPC 2.0 protocol types matching the Java SidecarBridge.
 */
export interface JsonRpcRequest {
  jsonrpc: '2.0';
  method: string;
  params: Record<string, unknown>;
  id: number | null;
}

export interface JsonRpcResponse {
  jsonrpc: '2.0';
  result?: unknown;
  error?: { code: number; message: string; data?: unknown };
  id: number | null;
}

/** JSON-RPC 2.0 notification (no id, no response expected) */
export interface JsonRpcNotification {
  jsonrpc: '2.0';
  method: string;
  params: Record<string, unknown>;
}

/** Standard JSON-RPC 2.0 error codes */
export const RPC_ERRORS = {
  // Standard JSON-RPC 2.0 error codes (-32700 to -32600)
  PARSE_ERROR: -32700,
  INVALID_REQUEST: -32600,
  METHOD_NOT_FOUND: -32601,
  INVALID_PARAMS: -32602,
  INTERNAL_ERROR: -32603,

  // ★ Audit fix (API 設計師): Application-specific error codes (-32000 to -32099)
  // These provide machine-readable error classification for the Java client,
  // enabling structured error handling instead of parsing error message strings.

  /** SDF grid exceeds MAX_GRID_CELLS or memory threshold */
  GRID_TOO_LARGE: -32001,
  /** No geometry generated from input blocks */
  EMPTY_GEOMETRY: -32002,
  /** OpenCASCADE WASM kernel operation failed */
  CAD_KERNEL_ERROR: -32003,
  /** Block validation failed (invalid coordinates, materials, etc.) */
  INVALID_BLOCKS: -32004,
  /** Output path validation failed (path traversal, permissions, etc.) */
  INVALID_OUTPUT_PATH: -32005,
  /** Export timeout exceeded */
  EXPORT_TIMEOUT: -32006,
  /** Resolution or smoothing parameter out of range */
  INVALID_PIPELINE_OPTIONS: -32007,
  /** OpenCASCADE WASM module failed to initialize */
  OC_INIT_FAILED: -32008,
  /** IFC export specific errors */
  IFC_EXPORT_ERROR: -32009,
} as const;

export type RpcHandler = (params: Record<string, unknown>) => unknown | Promise<unknown>;

/**
 * ★ P9-fix (2025-04): JSON 解析深度限制。
 *
 * 惡意客戶端可傳送深度巢狀 JSON（如 {"a":{"a":{"a":...}}}×10000）造成
 * V8 解析堆疊溢位或 DoS。此函式在 JSON.parse 前掃描原始字串的最大括號深度。
 *
 * 實作：僅計算 `{` / `[` / `}` / `]`（跳過字串內容），O(n) 單次掃描。
 *
 * @param json  原始 JSON 字串
 * @param maxDepth  最大容許深度（預設 16）
 * @returns  超過限制時回傳 false，否則回傳 true
 */
function checkJsonDepth(json: string, maxDepth = MAX_JSON_DEPTH): boolean {
  let depth = 0;
  let inString = false;
  let escape = false;
  for (let i = 0; i < json.length; i++) {
    const ch = json[i];
    if (escape) { escape = false; continue; }
    if (ch === '\\' && inString) { escape = true; continue; }
    if (ch === '"') { inString = !inString; continue; }
    if (inString) continue;
    if (ch === '{' || ch === '[') {
      depth++;
      if (depth > maxDepth) return false;
    } else if (ch === '}' || ch === ']') {
      depth--;
    }
  }
  return true;
}

/** ★ P9-fix: 最大允許的 JSON 巢狀深度（防止 DoS 攻擊）。 */
const MAX_JSON_DEPTH = 16;

/**
 * JSON-RPC 2.0 server over stdio.
 *
 * Compatible with the Java SidecarBridge:
 *   - Reads one JSON-RPC request per line from stdin
 *   - Writes one JSON-RPC response per line to stdout
 *   - Stays alive until "shutdown" method is received
 *   - Supports async handlers (returns Promise)
 */
export class RpcServer {
  private handlers = new Map<string, RpcHandler>();
  private rl: readline.Interface | null = null;
  private running = false;

  /** Register a method handler */
  method(name: string, handler: RpcHandler): this {
    this.handlers.set(name, handler);
    return this;
  }

  /** Send a JSON-RPC response to stdout with backpressure handling */
  private respond(id: number | null, result?: unknown, error?: { code: number; message: string; data?: unknown }): void {
    const resp: JsonRpcResponse = { jsonrpc: '2.0', id };
    if (error) {
      resp.error = error;
    } else {
      resp.result = result ?? {};
    }
    const payload = JSON.stringify(resp) + '\n';
    // ★ FIX BACKPRESSURE: 檢查 payload 大小，超過 10MB 截斷結果防止 OOM
    const MAX_PAYLOAD_BYTES = 10 * 1024 * 1024; // 10 MB
    if (Buffer.byteLength(payload, 'utf8') > MAX_PAYLOAD_BYTES) {
      console.error(`[RPC] Response for id=${id} exceeds ${MAX_PAYLOAD_BYTES} bytes, truncating`);
      const truncResp: JsonRpcResponse = {
        jsonrpc: '2.0',
        id,
        error: { code: RPC_ERRORS.INTERNAL_ERROR, message: 'Response too large (>10MB), truncated' },
      };
      process.stdout.write(JSON.stringify(truncResp) + '\n');
      return;
    }
    // ★ FIX BACKPRESSURE: 檢查 stdout 是否可寫，處理背壓
    const canWrite = process.stdout.write(payload);
    if (!canWrite) {
      // stdout 緩衝區已滿，等待 drain 事件
      process.stdout.once('drain', () => {
        // 背壓已釋放，繼續正常運作
      });
    }
  }

  /**
   * Send a JSON-RPC notification (no id, no response expected).
   * Used for progress updates that the Java readLoop logs as [Sidecar Notification].
   */
  notify(method: string, params: Record<string, unknown>): void {
    const notification: JsonRpcNotification = { jsonrpc: '2.0', method, params };
    process.stdout.write(JSON.stringify(notification) + '\n');
  }

  /** Start listening for requests on stdin (creates new readline) */
  start(): void {
    if (this.running) return;
    const rl = readline.createInterface({ input: process.stdin });
    this.startWithRL(rl);
  }

  /** Start with an existing readline interface (for mode-detection reuse) */
  startWithRL(rl: readline.Interface): void {
    if (this.running) return;
    this.running = true;
    this.rl = rl;

    this.rl.on('line', (line: string) => {
      const trimmed = line.trim();
      if (!trimmed) return;
      // ★ Fix: 捕獲未處理的 promise rejection，防止靜默崩潰
      this.handleLine(trimmed).catch(err => {
        console.error('[RPC] Unhandled error in handleLine:', err);
      });
    });

    this.rl.on('close', () => {
      this.running = false;
    });
  }

  /** Process a single line externally (for re-injecting buffered input) */
  processLine(line: string): void {
    // ★ Fix: 捕獲未處理的 promise rejection
    this.handleLine(line).catch(err => {
      console.error('[RPC] Unhandled error in processLine:', err);
    });
  }

  /** Process a single line of input */
  private async handleLine(line: string): Promise<void> {
    let req: JsonRpcRequest;
    // ★ P9-fix: 解析前檢查 JSON 深度，防止深度巢狀 DoS
    if (!checkJsonDepth(line)) {
      this.respond(null, undefined, {
        code: RPC_ERRORS.INVALID_REQUEST,
        message: `JSON nesting depth exceeds limit (max ${MAX_JSON_DEPTH})`,
      });
      return;
    }
    try {
      req = JSON.parse(line);
    } catch {
      this.respond(null, undefined, { code: RPC_ERRORS.PARSE_ERROR, message: 'Invalid JSON' });
      return;
    }

    // Validate JSON-RPC structure
    if (!req.method || typeof req.method !== 'string') {
      this.respond(req.id ?? null, undefined, {
        code: RPC_ERRORS.INVALID_REQUEST,
        message: 'Missing or invalid "method" field',
      });
      return;
    }

    // Handle shutdown — flush stdout before exit to ensure Java receives ACK
    if (req.method === 'shutdown') {
      this.respond(req.id, { shutdown: true });
      this.stop();
      // ★ FIX SHUTDOWN: 等待 stdout 完全 flush 後再退出
      // 舊版使用固定 100ms，在高負載時不夠；改為監聽 stdout drain
      const exit = () => process.exit(0);
      if (process.stdout.writableLength === 0) {
        // 已經 flush 完成，給一小段時間讓 OS 管道傳送
        setTimeout(exit, 200);
      } else {
        // 等待 stdout 完全排空
        process.stdout.once('drain', () => setTimeout(exit, 100));
        // 安全超時：最多等 2 秒
        setTimeout(exit, 2000);
      }
      return;
    }

    // Find handler — only registered methods can be invoked (implicit whitelist)
    const handler = this.handlers.get(req.method);
    if (!handler) {
      console.error(`[RPC] Rejected unknown method '${req.method}' (id=${req.id}) — only registered methods are allowed`);
      this.respond(req.id, undefined, {
        code: RPC_ERRORS.METHOD_NOT_FOUND,
        message: `Method not found: ${req.method}`,
      });
      return;
    }

    // Execute handler
    try {
      const result = await handler(req.params ?? {});
      this.respond(req.id, result);
    } catch (e: unknown) {
      const msg = e instanceof Error ? e.message : String(e);
      // Include error context data if the error carries it
      const data = (e as any)?.data ?? undefined;
      this.respond(req.id, undefined, { code: RPC_ERRORS.INTERNAL_ERROR, message: msg, data });
    }
  }

  /** Stop the server */
  stop(): void {
    this.running = false;
    this.rl?.close();
    this.rl = null;
  }

  get isRunning(): boolean {
    return this.running;
  }
}
