import numpy as np
import sys
from brnext.teachers.pfsf_teacher import solve_pfsf_phi
from brnext.pipeline.structure_gen import generate_structure

def test_pfsf():
    print("Testing PFSF solver in WSL...")
    rng = np.random.default_rng(42)
    struct = generate_structure(12, rng, "bridge")
    print(f"Structure occupancy sum: {struct.occupancy.sum()}")
    
    try:
        phi = solve_pfsf_phi(struct.occupancy, struct.E_field, struct.density_field)
        print(f"Phi solved. Max value: {np.max(phi)}")
        print(f"Phi mean: {np.mean(phi)}")
        return True
    except Exception as e:
        print(f"PFSF Solver failed: {e}")
        import traceback
        traceback.print_exc()
        return False

if __name__ == "__main__":
    if test_pfsf():
        print("Test PASSED")
        sys.exit(0)
    else:
        print("Test FAILED")
        sys.exit(1)
