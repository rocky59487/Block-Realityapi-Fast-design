#version 450
// fluid_pressure_solve.comp.glsl
// Jacobi iteration for Poisson pressure equation: ∇²p = ∇·u/dt
// Called repeatedly (iter times) via CPU-side loop.

layout(local_size_x = 8, local_size_y = 8, local_size_z = 8) in;

layout(push_constant) uniform PC {
    uint  Lx, Ly, Lz;
    float h;    // sub-cell size (m)
    float dt;   // time step (s)
} pc;

layout(set=0, binding=0) buffer    PressureBuf { float p[]; };   // read+write (in-place Jacobi)
layout(set=0, binding=1) readonly  buffer DivBuf   { float div[]; }; // ∇·u
layout(set=0, binding=2) readonly  buffer TypeBuf  { uint  fluidType[]; };

// fluid type IDs (must match FluidType.getId())
const uint AIR        = 0;
const uint SOLID_WALL = 4;

uint flat(uint x, uint y, uint z) {
    return x + y * pc.Lx + z * pc.Lx * pc.Ly;
}

void main() {
    uvec3 g = gl_GlobalInvocationID;
    if (g.x >= pc.Lx || g.y >= pc.Ly || g.z >= pc.Lz) return;

    uint idx = flat(g.x, g.y, g.z);
    if (fluidType[idx] == SOLID_WALL) return; // walls hold p=0

    float h2 = pc.h * pc.h;

    // 6-neighbour pressure sum (Neumann BC: ghost = self for walls/OOB)
    float sum = 0.0;
    sum += (g.x+1 < pc.Lx) ? p[flat(g.x+1, g.y, g.z)] : p[idx];
    sum += (g.x   > 0     ) ? p[flat(g.x-1, g.y, g.z)] : p[idx];
    sum += (g.y+1 < pc.Ly) ? p[flat(g.x, g.y+1, g.z)] : p[idx];
    sum += (g.y   > 0     ) ? p[flat(g.x, g.y-1, g.z)] : p[idx];
    sum += (g.z+1 < pc.Lz) ? p[flat(g.x, g.y, g.z+1)] : p[idx];
    sum += (g.z   > 0     ) ? p[flat(g.x, g.y, g.z-1)] : p[idx];

    p[idx] = (sum - h2 * div[idx]) / 6.0;
}
