"use strict";
var __createBinding = (this && this.__createBinding) || (Object.create ? (function(o, m, k, k2) {
    if (k2 === undefined) k2 = k;
    var desc = Object.getOwnPropertyDescriptor(m, k);
    if (!desc || ("get" in desc ? !m.__esModule : desc.writable || desc.configurable)) {
      desc = { enumerable: true, get: function() { return m[k]; } };
    }
    Object.defineProperty(o, k2, desc);
}) : (function(o, m, k, k2) {
    if (k2 === undefined) k2 = k;
    o[k2] = m[k];
}));
var __setModuleDefault = (this && this.__setModuleDefault) || (Object.create ? (function(o, v) {
    Object.defineProperty(o, "default", { enumerable: true, value: v });
}) : function(o, v) {
    o["default"] = v;
});
var __importStar = (this && this.__importStar) || (function () {
    var ownKeys = function(o) {
        ownKeys = Object.getOwnPropertyNames || function (o) {
            var ar = [];
            for (var k in o) if (Object.prototype.hasOwnProperty.call(o, k)) ar[ar.length] = k;
            return ar;
        };
        return ownKeys(o);
    };
    return function (mod) {
        if (mod && mod.__esModule) return mod;
        var result = {};
        if (mod != null) for (var k = ownKeys(mod), i = 0; i < k.length; i++) if (k[i] !== "default") __createBinding(result, mod, k[i]);
        __setModuleDefault(result, mod);
        return result;
    };
})();
Object.defineProperty(exports, "__esModule", { value: true });
// sidecar/src/sidecar.ts
const readline = __importStar(require("readline"));
const rl = readline.createInterface({ input: process.stdin });
function respond(id, result, error) {
    const resp = { jsonrpc: '2.0', id };
    if (error)
        resp.error = error;
    else
        resp.result = result ?? {};
    process.stdout.write(JSON.stringify(resp) + '\n');
}
// 方法路由表
const handlers = {
    ping: (_params) => ({ pong: true, ts: Date.now() }),
    // dualContouring: (params) => { ... },
    // nurbsFitting: (params)   => { ... },
};
rl.on('line', (line) => {
    let req;
    try {
        req = JSON.parse(line);
    }
    catch {
        return; // 忽略非 JSON 行
    }
    if (req.method === 'shutdown') {
        process.exit(0);
    }
    const handler = handlers[req.method];
    if (!handler) {
        respond(req.id, undefined, { code: -32601, message: `Method not found: ${req.method}` });
        return;
    }
    try {
        const result = handler(req.params ?? {});
        respond(req.id, result);
    }
    catch (e) {
        const msg = e instanceof Error ? e.message : String(e);
        respond(req.id, undefined, { code: -32603, message: msg });
    }
});
//# sourceMappingURL=sidecar.js.map