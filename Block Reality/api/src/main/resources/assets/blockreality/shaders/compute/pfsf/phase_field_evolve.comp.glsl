#version 450

// ═══════════════════════════════════════════════════════════════
//  PFSF Phase-Field Fracture — Miehe 2010 Operator Split
//
//  v2 Phase C：連續損傷場 d ∈ [0,1] 取代二元斷裂。
//  每體素計算：
//    1. 彈性應變能密度 ψ_e = 0.5 × Σ(σ_d × Δφ²)
//    2. 歷史場更新 H = max(H_prev, ψ_e)（不可逆）
//    3. 損傷演化 d = H / (H + G_c / (2 × l_0))
//    4. 傳導率退化 σ_eff = σ × (1-d)²
//
//  Workgroup: 256 (1D flat dispatch)
// ═══════════════════════════════════════════════════════════════

layout(local_size_x = 256) in;

layout(push_constant) uniform PC {
    uint Lx, Ly, Lz;
    float l_0;      // 正則化長度（blocks，建議 1.5-2.0）
    float dt;       // 未使用（rate-independent），保留擴展
} pc;

layout(set = 0, binding = 0) readonly buffer Phi       { float phi[];     };
layout(set = 0, binding = 1) buffer Cond                { float sigma[];   };
layout(set = 0, binding = 2) buffer Damage              { float damage[];  };
layout(set = 0, binding = 3) buffer History             { float history[]; };
layout(set = 0, binding = 4) readonly buffer Type       { uint  vtype[];   };
layout(set = 0, binding = 5) readonly buffer Gc         { float gc[];      };

// Morton encode (inlined)
uint mortonExpandBits(uint v) {
    v &= 0x3FFu;
    v = (v | (v << 16u)) & 0x030000FFu;
    v = (v | (v <<  8u)) & 0x0300F00Fu;
    v = (v | (v <<  4u)) & 0x030C30C3u;
    v = (v | (v <<  2u)) & 0x09249249u;
    return v;
}

uint gIdx(uint x, uint y, uint z) {
    return mortonExpandBits(x) | (mortonExpandBits(y) << 1u) | (mortonExpandBits(z) << 2u);
}

void main() {
    uint gid = gl_GlobalInvocationID.x;

    // 從 flat index 恢復 3D 座標（用於邊界檢查和鄰居存取）
    // 注意：dispatch 是 1D flat，需要從 gid 映射回 (x,y,z)
    uint N_padded = pc.Lx * pc.Ly * pc.Lz; // 呼叫端傳入 padded N
    if (gid >= N_padded) return;

    // 使用 Morton 解碼取得座標
    uint mortonCompactBits(uint v) {
        v &= 0x09249249u;
        v = (v | (v >>  2u)) & 0x030C30C3u;
        v = (v | (v >>  4u)) & 0x0300F00Fu;
        v = (v | (v >>  8u)) & 0x030000FFu;
        v = (v | (v >> 16u)) & 0x3FFu;
        return v;
    }
    // 由於 1D dispatch，gid 不是 Morton code 而是線性索引
    // 計算 (x,y,z) 然後編碼為 Morton
    uint x = gid % pc.Lx;
    uint rem = gid / pc.Lx;
    uint y = rem % pc.Ly;
    uint z = rem / pc.Ly;

    if (x >= pc.Lx || y >= pc.Ly || z >= pc.Lz) return;

    uint i = gIdx(x, y, z);

    // 跳過空氣和錨點
    if (vtype[i] == 0u || vtype[i] == 2u) return;

    // ─── Step 1: 計算彈性應變能密度 ψ_e ───
    // ψ_e = 0.5 × Σ_d(σ_d × (φ_neighbor - φ_i)²)
    float phi_i = phi[i];
    float psi_e = 0.0;

    // 6 個面鄰居
    int dx[6] = int[6](-1, 1,  0, 0,  0, 0);
    int dy[6] = int[6]( 0, 0, -1, 1,  0, 0);
    int dz[6] = int[6]( 0, 0,  0, 0, -1, 1);

    uint N = pc.Lx * pc.Ly * pc.Lz;
    for (int d = 0; d < 6; d++) {
        int nx = int(x) + dx[d], ny = int(y) + dy[d], nz = int(z) + dz[d];
        if (nx < 0 || uint(nx) >= pc.Lx || ny < 0 || uint(ny) >= pc.Ly || nz < 0 || uint(nz) >= pc.Lz) continue;

        uint ni = gIdx(uint(nx), uint(ny), uint(nz));
        float s = sigma[d * N + i];
        float dphi = phi[ni] - phi_i;
        psi_e += 0.5 * s * dphi * dphi;
    }

    // ─── Step 2: 歷史場更新（不可逆裂紋） ───
    float H = max(history[i], psi_e);
    history[i] = H;

    // ─── Step 3: 損傷演化 (Miehe 2010 closed-form) ───
    float Gc_i = gc[i];
    float d_old = damage[i];
    float d_new;

    if (Gc_i > 0.0 && pc.l_0 > 0.0) {
        // d = H / (H + G_c / (2 * l_0))
        float denominator = H + Gc_i / (2.0 * pc.l_0);
        d_new = (denominator > 1e-12) ? H / denominator : 0.0;
    } else {
        d_new = d_old;
    }

    // 不可逆：損傷只增不減
    d_new = max(d_new, d_old);
    d_new = clamp(d_new, 0.0, 1.0);
    damage[i] = d_new;

    // ─── Step 4: 傳導率退化 σ_eff = σ × (1-d)² ───
    // 只在損傷發生變化時更新（避免無意義的全域寫入）
    if (d_new > d_old + 1e-6) {
        float degradation = (1.0 - d_new) * (1.0 - d_new);
        degradation = max(degradation, 1e-4); // 防止零 conductivity 導致 NaN
        float oldDeg = (1.0 - d_old) * (1.0 - d_old);
        float ratio = (oldDeg > 1e-12) ? degradation / oldDeg : degradation;

        for (int d = 0; d < 6; d++) {
            sigma[d * N + i] *= ratio;
        }
    }
}
