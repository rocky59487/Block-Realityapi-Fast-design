import jax
import jax.numpy as jnp
from brml.models.fno_fluid import FNOFluid3D
import numpy as np
import time

def test_fno_minimal():
    print("Testing FNOFluid3D minimal execution in WSL...")
    model = FNOFluid3D()
    rng = jax.random.PRNGKey(42)
    
    # Try smaller grid for test
    grid_size = 32
    dummy = jnp.zeros((1, grid_size, grid_size, grid_size, 8))
    
    print("Initializing model...")
    params = model.init(rng, dummy)['params']
    
    @jax.jit
    def forward(p, x):
        return model.apply({'params': p}, x)
        
    print("Compiling (first run)...")
    t0 = time.time()
    res = forward(params, dummy)
    print(f"First run took {time.time() - t0:.2f}s")
    
    print("Second run (cached)...")
    t1 = time.time()
    res = forward(params, dummy)
    print(f"Second run took {time.time() - t1:.2f}s")
    
    if res.shape == (1, grid_size, grid_size, grid_size, 4):
        print("Test PASSED")
    else:
        print(f"Test FAILED: Shape mismatch {res.shape}")

if __name__ == "__main__":
    test_fno_minimal()
