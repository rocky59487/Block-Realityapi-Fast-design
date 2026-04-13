import numpy as np
import sys
from brnext.teachers.fem_teacher import solve_fem
from brnext.pipeline.structure_gen import generate_structure

def test_fem():
    print("Testing FEM solver in WSL...")
    rng = np.random.default_rng(42)
    struct = generate_structure(12, rng, "bridge")
    print(f"Structure occupancy sum: {struct.occupancy.sum()}")
    
    try:
        fem = solve_fem(struct.occupancy, struct.anchors, struct.E_field, struct.nu_field, struct.density_field)
        print(f"FEM solved. Converged: {fem.converged}")
        if fem.displacement is not None:
             print(f"Max displacement: {np.max(np.abs(fem.displacement))}")
        return fem.converged
    except Exception as e:
        print(f"FEM Solver failed: {e}")
        import traceback
        traceback.print_exc()
        return False

if __name__ == "__main__":
    if test_fem():
        print("Test PASSED")
        sys.exit(0)
    else:
        print("Test FAILED")
        sys.exit(1)
