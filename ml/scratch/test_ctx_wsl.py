import multiprocessing as mp
import numpy as np
from brnext.pipeline.async_data_loader import AsyncBuffer, pfsf_worker
from brnext.pipeline.structure_gen import generate_structure

def gen_fn():
    rng = np.random.default_rng(42)
    for i in range(5):
        yield generate_structure(12, rng, "bridge")

def test_context(ctx_name):
    print(f"\n--- Testing context: {ctx_name} ---")
    ctx = mp.get_context(ctx_name)
    with AsyncBuffer(gen_fn(), pfsf_worker, n_workers=2, chunksize=1) as buf:
        # Override pool context for test
        buf._pool.terminate()
        buf._pool.join()
        buf._pool = ctx.Pool(2)
        buf._iterator = buf._pool.imap_unordered(buf.worker_fn, itertools_islice(gen_fn(), 5), chunksize=1)
        
        print("Polling...")
        buf.poll(max_size=5, timeout=5.0)
        print(f"Buffer size: {len(buf)}")
        return len(buf) > 0

import itertools
def itertools_islice(gen, n):
    return itertools.islice(gen, n)

if __name__ == "__main__":
    # Test simple imap with the worker
    ctx = mp.get_context("fork")
    print("Testing 'fork' imap...")
    with ctx.Pool(2) as pool:
        results = list(pool.imap(pfsf_worker, gen_fn()))
    print(f"Results: {len(results)} samples")
    if len(results) > 0 and results[0] is not None:
        print("SUCCESS with fork")
    else:
        print("FAILED with fork")
        
    ctx_spawn = mp.get_context("spawn")
    print("\nTesting 'spawn' imap...")
    with ctx_spawn.Pool(2) as pool:
        try:
            results_s = list(pool.imap(pfsf_worker, gen_fn()))
            print(f"Results: {len(results_s)} samples")
        except Exception as e:
            print(f"Spawn failed: {e}")
