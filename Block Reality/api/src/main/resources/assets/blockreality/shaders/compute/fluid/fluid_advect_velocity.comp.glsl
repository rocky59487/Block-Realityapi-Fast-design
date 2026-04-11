#version 450
// fluid_advect_velocity.comp.glsl
// Semi-Lagrangian velocity advection (sub-cell, 0.1m grid)
// Reads vx/vy/vz, back-traces particle, trilinear-interpolates, writes new vx/vy/vz.

layout(local_size_x = 8, local_size_y = 8, local_size_z = 8) in;

layout(push_constant) uniform PC {
    uint Lx;   // sub-grid size X (= blocks*10)
    uint Ly;   // sub-grid size Y
    uint Lz;   // sub-grid size Z
    float dt;  // time step (s)
    float h;   // sub-cell size (m) = BLOCK_SIZE / 10
} pc;

layout(set=0, binding=0) buffer VxBuf   { float vx[]; };
layout(set=0, binding=1) buffer VyBuf   { float vy[]; };
layout(set=0, binding=2) buffer VzBuf   { float vz[]; };
layout(set=0, binding=3) buffer VxOldBuf { float vxOld[]; };
layout(set=0, binding=4) buffer VyOldBuf { float vyOld[]; };
layout(set=0, binding=5) buffer VzOldBuf { float vzOld[]; };

uint flat(uint x, uint y, uint z) {
    return x + y * pc.Lx + z * pc.Lx * pc.Ly;
}

// Trilinear sample from 'buf' at continuous position (px, py, pz)
float trilinear(readonly buffer float[] buf, float px, float py, float pz) {
    px = clamp(px, 0.0, float(pc.Lx) - 1.001);
    py = clamp(py, 0.0, float(pc.Ly) - 1.001);
    pz = clamp(pz, 0.0, float(pc.Lz) - 1.001);
    uint x0 = uint(px), y0 = uint(py), z0 = uint(pz);
    uint x1 = min(x0+1, pc.Lx-1), y1 = min(y0+1, pc.Ly-1), z1 = min(z0+1, pc.Lz-1);
    float fx = px - x0, fy = py - y0, fz = pz - z0;
    float c00 = mix(buf[flat(x0,y0,z0)], buf[flat(x1,y0,z0)], fx);
    float c01 = mix(buf[flat(x0,y0,z1)], buf[flat(x1,y0,z1)], fx);
    float c10 = mix(buf[flat(x0,y1,z0)], buf[flat(x1,y1,z0)], fx);
    float c11 = mix(buf[flat(x0,y1,z1)], buf[flat(x1,y1,z1)], fx);
    return mix(mix(c00, c01, fz), mix(c10, c11, fz), fy);
}

void main() {
    uvec3 g = gl_GlobalInvocationID;
    if (g.x >= pc.Lx || g.y >= pc.Ly || g.z >= pc.Lz) return;
    uint idx = flat(g.x, g.y, g.z);

    // Back-trace: position = g - dt/h * v_old
    float px = float(g.x) - pc.dt / pc.h * vxOld[idx];
    float py = float(g.y) - pc.dt / pc.h * vyOld[idx];
    float pz = float(g.z) - pc.dt / pc.h * vzOld[idx];

    vx[idx] = trilinear(vxOld, px, py, pz);
    vy[idx] = trilinear(vyOld, px, py, pz);
    vz[idx] = trilinear(vzOld, px, py, pz);
}
