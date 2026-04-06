#version 450
#extension GL_EXT_shader_explicit_arithmetic_types : enable

// ═══════════════════════════════════════════════════════════════
//  PFSF Phase-Field Evolution（v2.1 旗艦特性）
//
//  實作：Ambati 2015 增量線性混合相場公式
//  (Ambati, M., Gerasimov, T., & De Lorenzis, L., 2015,
//   "A review on phase-field models of brittle fracture and a
//    new fast hybrid formulation", Computational Mechanics)
//
//  離散方程（6-鄰域有限差分）：
//    H_i = max(H_i, ψ_e_i)          ← 已在 jacobi/rbgs 中完成
//    ∇²d ≈ Σ_{j∈N(i)} (d_j - d_i) / (l0²)
//    d_new = (H_i + l0² × ∇²d_i) / (H_i + G_c/(2l0))
//    d_new = clamp(d_new, 0, 1)
//    d_i ← mix(d_i, d_new, relax)   ← 鬆弛因子防過衝
//
//  G_c 固化時間效應：G_c_eff = G_c_base × hydration^1.5
//
//  Workgroup: 256 threads（1D flat）
// ═══════════════════════════════════════════════════════════════

layout(local_size_x = 256, local_size_y = 1, local_size_z = 1) in;

layout(push_constant) uniform PushConstants {
    uint  Lx, Ly, Lz;    // island grid dimensions
    float l0;             // 正則化長度尺度（blocks），建議 1.5~2.0
    float gcBase;         // 基礎臨界能量釋放率 G_c（J/m²），隨材料不同
    float relax;          // 鬆弛因子 ∈ (0,1]，建議 0.3（防過衝）
} pc;

// ─── Buffer bindings（匹配 PFSFPipelineFactory.phaseFieldDSLayout）───
layout(set = 0, binding = 0) readonly buffer Phi       { float phi[];       };  // 勢能場（唯讀）
layout(set = 0, binding = 1) buffer         HField     { float hField[];    };  // 歷史應變能場（讀寫）
layout(set = 0, binding = 2) buffer         DField     { float dField[];    };  // 損傷場（讀寫）
layout(set = 0, binding = 3) readonly buffer Cond      { float sigma[];     };  // 傳導率（6N SoA）
layout(set = 0, binding = 4) readonly buffer Type      { uint  vtype[];     };  // 體素類型
layout(set = 0, binding = 5) buffer         FailFlags  { uint  failFlags[]; };  // 斷裂標記（寫入）
layout(set = 0, binding = 6) readonly buffer Hydration { float hydration[]; };  // 水化度 [0,1]

uint gIdx(uint x, uint y, uint z) {
    return x + pc.Lx * (y + pc.Ly * z);
}

void main() {
    uint i = gl_GlobalInvocationID.x;
    uint N = pc.Lx * pc.Ly * pc.Lz;
    if (i >= N) return;

    // 跳過空氣與錨點
    if (vtype[i] == 0u || vtype[i] == 2u) return;

    // 已斷裂的體素不再演化（防止重複觸發）
    if (failFlags[i] != 0u) return;

    // ─── 還原 3D 座標 ───
    uint gx = i % pc.Lx;
    uint rem = i / pc.Lx;
    uint gy = rem % pc.Ly;
    uint gz = rem / pc.Ly;

    float phi_i = phi[i];
    float sumSigma = 0.0;
    uint N_cond = N;

    float laplacian_phi = 0.0;
    float laplacian_d   = 0.0;
    float d_i = dField[i];
    int valid_count = 0;

    int igx = int(gx), igy = int(gy), igz = int(gz);
    int dx[6] = int[6](-1, 1,  0, 0,  0, 0);
    int dy[6] = int[6]( 0, 0, -1, 1,  0, 0);
    int dz[6] = int[6]( 0, 0,  0, 0, -1, 1);

    for (int d = 0; d < 6; d++) {
        int nx = igx + dx[d];
        int ny = igy + dy[d];
        int nz = igz + dz[d];
        if (nx < 0 || nx >= int(pc.Lx) ||
            ny < 0 || ny >= int(pc.Ly) ||
            nz < 0 || nz >= int(pc.Lz)) continue;

        uint j = gIdx(uint(nx), uint(ny), uint(nz));
        if (vtype[j] == 0u) continue;  // 空氣鄰居不貢獻

        float s = sigma[d * N_cond + i];
        float phi_j = phi[j];
        float d_j   = dField[j];

        // phi Laplacian（用於補充 H_field 估計）
        if (!isnan(phi_j) && !isinf(phi_j)) {
            laplacian_phi += (phi_j - phi_i);
            sumSigma += s;
        }

        // d_field Laplacian（用於相場擴散方程）
        if (!isnan(d_j) && !isinf(d_j)) {
            laplacian_d += (d_j - d_i);
            valid_count++;
        }
    }

    // ─── H_field 補充更新（若 RBGS/Jacobi 尚未本 tick 更新此格）───
    // psi_e 近似：0.5 × σ_avg × |∇φ|²，其中 |∇φ|² ≈ -phi_i × laplacian_phi
    float sigma_avg = (sumSigma > 0.0) ? sumSigma / 6.0 : 0.001;
    float grad_phi_sq = max(0.0, -phi_i * laplacian_phi);
    float psi_e_new = 0.5 * sigma_avg * grad_phi_sq;
    hField[i] = max(hField[i], psi_e_new);

    float H_val = hField[i];
    if (H_val <= 0.0) return;  // 無應變能驅動，d 不演化

    // ─── Ambati 2015 混合相場公式（線性化，無需 Newton-Raphson）───
    //
    // 離散 PDE：(H + G_c/(2l0)) × d - l0² × ∇²d = H
    // → d_new = (H + l0² × ∇²d_old) / (H + G_c/(2l0))
    //
    // 其中：
    //   ∇²d = Σ(d_j - d_i) / l0²  （有限差分，l0² 為擴散係數）
    //   G_c_eff = G_c_base × hydration[i]^1.5（固化時間效應）
    //
    // 數學保證：分母 H + G_c/(2l0) > 0 恆成立 → 無條件數值穩定

    // 固化時間效應：G_c 隨水化度縮放
    float hDeg = clamp(hydration[i], 0.01, 1.0);  // 避免 hDeg=0 使 G_c=0
    float Gc_eff = pc.gcBase * pow(hDeg, 1.5);    // G_c(t) = G_c_final × H^1.5

    float l0sq = pc.l0 * pc.l0;
    // 離散 d Laplacian：∇²d ≈ Σ(d_j - d_i)（單位格間距 = 1 block）
    float l0sq_laplacian_d = l0sq * laplacian_d;

    float numerator   = H_val + l0sq_laplacian_d;
    float denominator = H_val + Gc_eff / (2.0 * pc.l0);
    denominator = max(denominator, 1e-8);  // 防除零

    float d_new = clamp(numerator / denominator, 0.0, 1.0);

    // 鬆弛更新（防過衝，pc.relax = 0.3）
    d_new = mix(d_i, d_new, pc.relax);
    d_new = clamp(d_new, 0.0, 1.0);

    // NaN 防護
    if (isnan(d_new) || isinf(d_new)) d_new = d_i;

    dField[i] = d_new;

    // ─── 斷裂觸發（PHASE_FIELD_FRACTURE_THRESHOLD = 0.95）───
    // d > 0.95 → 寫入 FAIL_CANTILEVER（最接近的現有斷裂模式），
    // 由 PFSFFailureApplicator 讀回後觸發 CollapseManager 連鎖崩塌。
    if (d_new > 0.95) {
        failFlags[i] = 1u;  // FAIL_CANTILEVER（懸臂/相場斷裂）
    }
}
