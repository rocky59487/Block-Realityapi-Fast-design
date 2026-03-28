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
  PARSE_ERROR: -32700,
  INVALID_REQUEST: -32600,
  METHOD_NOT_FOUND: -32601,
  INVALID_PARAMS: -32602,
  INTERNAL_ERROR: -32603,
} as const;

export type RpcHandler = (params: Record<string, unknown>) => unknown | Promise<unknown>;

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

  /** Send a JSON-RPC response to stdout */
  private respond(id: number | null, result?: unknown, error?: { code: number; message: string; data?: unknown }): void {
    const resp: JsonRpcResponse = { jsonrpc: '2.0', id };
    if (error) {
      resp.error = error;
    } else {
      resp.result = result ?? {};
    }
    process.stdout.write(JSON.stringify(resp) + '\n');
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
      this.handleLine(trimmed);
    });

    this.rl.on('close', () => {
      this.running = false;
    });
  }

  /** Process a single line externally (for re-injecting buffered input) */
  processLine(line: string): void {
    this.handleLine(line);
  }

  /** Process a single line of input */
  private async handleLine(line: string): Promise<void> {
    let req: JsonRpcRequest;
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

    // Handle shutdown — delay exit to let pending async responses flush
    if (req.method === 'shutdown') {
      this.respond(req.id, { shutdown: true });
      this.stop();
      setTimeout(() => process.exit(0), 100);
      return;
    }

    // Find handler
    const handler = this.handlers.get(req.method);
    if (!handler) {
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
