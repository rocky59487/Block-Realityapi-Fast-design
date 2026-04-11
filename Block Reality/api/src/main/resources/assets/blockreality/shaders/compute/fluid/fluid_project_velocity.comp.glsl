#version 450
// fluid_project_velocity.comp.glsl
// Subtracts pressure gradient from velocity field to ensure ∇·u = 0.
// u -= ∇p / dt   (pressure stored as Pa, velocity in m/s)

layout(local_size_x = 8, local_size_y = 8, local_size_z = 8) in;

layout(push_constant) uniform PC {
    uint  Lx, Ly, Lz;
    float h;    // sub-cell size (m)
    float rho;  // reference density (kg/m³), typically 1000 for water
} pc;

layout(set=0, binding=0) buffer   VxBuf      { float vx[]; };
layout(set=0, binding=1) buffer   VyBuf      { float vy[]; };
layout(set=0, binding=2) buffer   VzBuf      { float vz[]; };
layout(set=0, binding=3) readonly buffer PressureBuf { float p[]; };
layout(set=0, binding=4) readonly buffer TypeBuf     { uint  fluidType[]; };

const uint SOLID_WALL = 4;

uint flat(uint x, uint y, uint z) {
    return x + y * pc.Lx + z * pc.Lx * pc.Ly;
}

void main() {
    uvec3 g = gl_GlobalInvocationID;
    if (g.x >= pc.Lx || g.y >= pc.Ly || g.z >= pc.Lz) return;
    uint idx = flat(g.x, g.y, g.z);
    if (fluidType[idx] == SOLID_WALL) return;

    float pxp = (g.x+1 < pc.Lx) ? p[flat(g.x+1, g.y, g.z)] : p[idx];
    float pxm = (g.x   > 0     ) ? p[flat(g.x-1, g.y, g.z)] : p[idx];
    float pyp = (g.y+1 < pc.Ly) ? p[flat(g.x, g.y+1, g.z)] : p[idx];
    float pym = (g.y   > 0     ) ? p[flat(g.x, g.y-1, g.z)] : p[idx];
    float pzp = (g.z+1 < pc.Lz) ? p[flat(g.x, g.y, g.z+1)] : p[idx];
    float pzm = (g.z   > 0     ) ? p[flat(g.x, g.y, g.z-1)] : p[idx];

    float inv2h = 1.0 / (2.0 * pc.h * pc.rho);
    vx[idx] -= (pxp - pxm) * inv2h;
    vy[idx] -= (pyp - pym) * inv2h;
    vz[idx] -= (pzp - pzm) * inv2h;
}
