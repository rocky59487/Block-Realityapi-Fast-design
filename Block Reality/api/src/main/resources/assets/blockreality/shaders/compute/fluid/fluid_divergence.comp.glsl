#version 450
// fluid_divergence.comp.glsl
// Computes ∇·u at each sub-cell using central differences.
// Output is stored in divergenceBuf for use by pressure_solve.

layout(local_size_x = 8, local_size_y = 8, local_size_z = 8) in;

layout(push_constant) uniform PC {
    uint  Lx, Ly, Lz;
    float h;   // sub-cell size (m) = 0.1
} pc;

layout(set=0, binding=0) readonly  buffer VxBuf  { float vx[]; };
layout(set=0, binding=1) readonly  buffer VyBuf  { float vy[]; };
layout(set=0, binding=2) readonly  buffer VzBuf  { float vz[]; };
layout(set=0, binding=3) writeonly buffer DivBuf { float div[]; };

uint flat(uint x, uint y, uint z) {
    return x + y * pc.Lx + z * pc.Lx * pc.Ly;
}

void main() {
    uvec3 g = gl_GlobalInvocationID;
    if (g.x >= pc.Lx || g.y >= pc.Ly || g.z >= pc.Lz) return;

    uint xp = min(g.x+1, pc.Lx-1), xm = g.x > 0 ? g.x-1 : 0;
    uint yp = min(g.y+1, pc.Ly-1), ym = g.y > 0 ? g.y-1 : 0;
    uint zp = min(g.z+1, pc.Lz-1), zm = g.z > 0 ? g.z-1 : 0;

    float dvx = vx[flat(xp, g.y, g.z)] - vx[flat(xm, g.y, g.z)];
    float dvy = vy[flat(g.x, yp, g.z)] - vy[flat(g.x, ym, g.z)];
    float dvz = vz[flat(g.x, g.y, zp)] - vz[flat(g.x, g.y, zm)];

    div[flat(g.x, g.y, g.z)] = (dvx + dvy + dvz) / (2.0 * pc.h);
}
