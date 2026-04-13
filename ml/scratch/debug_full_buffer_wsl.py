import multiprocessing as mp
import numpy as np
from brnext.pipeline.async_data_loader import AsyncBuffer, pfsf_worker, CurriculumSampler
from brnext.pipeline.structure_gen import generate_structure

def test_full_buffer():
    print("Testing Full AsyncBuffer Setup in WSL...")
    styles = ["bridge", "cantilever", "arch", "spiral", "tree", "cave", "overhang", "tower"]
    rng = np.random.default_rng(42)
    grid_size = 12
    
    def gen_fn():
        for i in range(10):
            yield generate_structure(grid_size, rng, "bridge")
            
    print("Initializing AsyncBuffer...")
    with AsyncBuffer(gen_fn(), pfsf_worker, n_workers=2, chunksize=1) as buf:
        print("Prefetching...")
        buf.prefetch(min_buffer=5, timeout=10.0)
        print(f"Buffer size after 10s: {len(buf)}")
        if len(buf) > 0:
            print(f"Sample 0: {type(buf.buffer[0])}")
            return True
        else:
            print("Buffer is still empty!")
            return False

if __name__ == "__main__":
    if test_full_buffer():
        print("Full Buffer Test PASSED")
    else:
        print("Full Buffer Test FAILED")
