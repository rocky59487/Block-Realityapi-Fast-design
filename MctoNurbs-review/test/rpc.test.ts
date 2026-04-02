import { describe, it, expect } from 'vitest';
import { RpcServer } from '../src/rpc-server.js';
import { blueprintToBlocks } from '../src/types.js';
import type { BlueprintBlock } from '../src/types.js';

describe('RpcServer', () => {
  it('should route to registered method handlers', async () => {
    const server = new RpcServer();
    let called = false;
    server.method('ping', () => {
      called = true;
      return { pong: true };
    });

    // Capture stdout
    const responses: string[] = [];
    const origWrite = process.stdout.write.bind(process.stdout);
    process.stdout.write = ((data: string) => {
      responses.push(data);
      return true;
    }) as typeof process.stdout.write;

    server.processLine('{"jsonrpc":"2.0","method":"ping","params":{},"id":1}');

    // Wait for async processing
    await new Promise(r => setTimeout(r, 50));

    process.stdout.write = origWrite;

    expect(called).toBe(true);
    expect(responses.length).toBeGreaterThan(0);

    const resp = JSON.parse(responses[0]);
    expect(resp.jsonrpc).toBe('2.0');
    expect(resp.id).toBe(1);
    expect(resp.result.pong).toBe(true);
  });

  it('should return METHOD_NOT_FOUND for unknown methods', async () => {
    const server = new RpcServer();

    const responses: string[] = [];
    const origWrite = process.stdout.write.bind(process.stdout);
    process.stdout.write = ((data: string) => {
      responses.push(data);
      return true;
    }) as typeof process.stdout.write;

    server.processLine('{"jsonrpc":"2.0","method":"nonexistent","params":{},"id":2}');
    await new Promise(r => setTimeout(r, 50));

    process.stdout.write = origWrite;

    const resp = JSON.parse(responses[0]);
    expect(resp.error).toBeDefined();
    expect(resp.error.code).toBe(-32601);
    expect(resp.id).toBe(2);
  });

  it('should return PARSE_ERROR for invalid JSON', async () => {
    const server = new RpcServer();

    const responses: string[] = [];
    const origWrite = process.stdout.write.bind(process.stdout);
    process.stdout.write = ((data: string) => {
      responses.push(data);
      return true;
    }) as typeof process.stdout.write;

    server.processLine('not valid json {{{');
    await new Promise(r => setTimeout(r, 50));

    process.stdout.write = origWrite;

    const resp = JSON.parse(responses[0]);
    expect(resp.error.code).toBe(-32700);
  });

  it('should return INTERNAL_ERROR when handler throws', async () => {
    const server = new RpcServer();
    server.method('fail', () => { throw new Error('test error'); });

    const responses: string[] = [];
    const origWrite = process.stdout.write.bind(process.stdout);
    process.stdout.write = ((data: string) => {
      responses.push(data);
      return true;
    }) as typeof process.stdout.write;

    server.processLine('{"jsonrpc":"2.0","method":"fail","params":{},"id":3}');
    await new Promise(r => setTimeout(r, 50));

    process.stdout.write = origWrite;

    const resp = JSON.parse(responses[0]);
    expect(resp.error.code).toBe(-32603);
    expect(resp.error.message).toBe('test error');
    expect(resp.id).toBe(3);
  });

  it('should support async handlers', async () => {
    const server = new RpcServer();
    server.method('asyncOp', async () => {
      await new Promise(r => setTimeout(r, 10));
      return { done: true };
    });

    const responses: string[] = [];
    const origWrite = process.stdout.write.bind(process.stdout);
    process.stdout.write = ((data: string) => {
      responses.push(data);
      return true;
    }) as typeof process.stdout.write;

    server.processLine('{"jsonrpc":"2.0","method":"asyncOp","params":{},"id":4}');
    await new Promise(r => setTimeout(r, 100));

    process.stdout.write = origWrite;

    const resp = JSON.parse(responses[0]);
    expect(resp.result.done).toBe(true);
    expect(resp.id).toBe(4);
  });
});

describe('BlueprintBlock adapter', () => {
  it('should convert BlueprintBlock[] to BlockData[]', () => {
    const blueprintBlocks: BlueprintBlock[] = [
      {
        relX: 0, relY: 0, relZ: 0,
        blockState: 'minecraft:stone',
        rMaterialId: 'concrete',
        stressLevel: 0.5,
      },
      {
        relX: 1, relY: 2, relZ: 3,
        blockState: 'minecraft:glass',
        rMaterialId: 'glass',
      },
    ];

    const blocks = blueprintToBlocks(blueprintBlocks);

    expect(blocks).toHaveLength(2);
    expect(blocks[0]).toEqual({ x: 0, y: 0, z: 0, material: 'concrete' });
    expect(blocks[1]).toEqual({ x: 1, y: 2, z: 3, material: 'glass' });
  });

  it('should fall back to blockState when rMaterialId is empty', () => {
    const blueprintBlocks: BlueprintBlock[] = [
      {
        relX: 5, relY: 10, relZ: 15,
        blockState: 'minecraft:oak_planks',
        rMaterialId: '',
      },
    ];

    const blocks = blueprintToBlocks(blueprintBlocks);
    expect(blocks[0].material).toBe('minecraft:oak_planks');
  });

  it('should handle blocks with physics metadata (passthrough)', () => {
    const blueprintBlocks: BlueprintBlock[] = [
      {
        relX: 0, relY: 0, relZ: 0,
        blockState: 'minecraft:iron_block',
        rMaterialId: 'structural_steel',
        rcomp: 250.0,
        rtens: 400.0,
        stressLevel: 0.3,
        isAnchored: true,
      },
    ];

    const blocks = blueprintToBlocks(blueprintBlocks);
    expect(blocks[0]).toEqual({
      x: 0, y: 0, z: 0,
      material: 'structural_steel',
    });
  });
});

describe('JSON-RPC dualContouring params format', () => {
  it('should match the Java SidecarBridge call format', () => {
    // This is exactly what the Java mod sends:
    // bridge.call("dualContouring", params, 10000)
    const javaParams = {
      blocks: [
        { relX: 0, relY: 0, relZ: 0, blockState: 'minecraft:stone', rMaterialId: 'concrete' },
        { relX: 1, relY: 0, relZ: 0, blockState: 'minecraft:stone', rMaterialId: 'concrete' },
        { relX: 0, relY: 1, relZ: 0, blockState: 'minecraft:glass', rMaterialId: 'glass' },
      ],
      options: {
        smoothing: 0,
        outputPath: 'exports/test-output.step',
      },
    };

    // Verify the adapter produces valid BlockData
    const blocks = blueprintToBlocks(javaParams.blocks);
    expect(blocks).toHaveLength(3);
    expect(blocks[0].x).toBe(0);
    expect(blocks[0].material).toBe('concrete');
    expect(blocks[2].material).toBe('glass');
  });
});
