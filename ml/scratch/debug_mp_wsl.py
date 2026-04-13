import multiprocessing as mp
import numpy as np
import time

def worker_fn(i):
    print(f"Worker {i} starting...")
    import scipy.ndimage
    from brnext.teachers.pfsf_teacher import solve_pfsf_phi
    from brnext.pipeline.structure_gen import generate_structure
    rng = np.random.default_rng(i)
    struct = generate_structure(12, rng, "bridge")
    phi = solve_pfsf_phi(struct.occupancy, struct.E_field, struct.density_field)
    print(f"Worker {i} finished. Phi max: {np.max(phi)}")
    return i

def test_mp():
    print("Testing multiprocessing in WSL...")
    ctx = mp.get_context("spawn")
    with ctx.Pool(2) as pool:
        results = pool.map(worker_fn, [1, 2, 3, 4])
    print(f"Results: {results}")

if __name__ == "__main__":
    test_mp()
