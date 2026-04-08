/**
 * @file constants.h
 * @brief Internal constants — 1:1 port from Java PFSFConstants.java.
 */
#pragma once

#include <cstdint>

namespace pfsf {

// ── Physics ──
constexpr double GRAVITY      = 9.81;
constexpr double BLOCK_VOLUME = 1.0;
constexpr double BLOCK_AREA   = 1.0;

// ── Failure thresholds ──
constexpr float  PHI_ORPHAN_THRESHOLD  = 1e6f;
constexpr int    MAX_FAILURE_PER_TICK  = 2000;
constexpr int    MAX_CASCADE_RADIUS    = 64;

// ── Scheduling ──
constexpr int    MG_INTERVAL     = 4;
constexpr int    WARMUP_STEPS    = 8;
constexpr float  DIVERGENCE_RATIO = 1.5f;
constexpr float  SAFETY_MARGIN   = 0.95f;
constexpr int    SCAN_INTERVAL   = 8;

// ── GPU workgroup sizes ──
constexpr int    WG_X    = 8;
constexpr int    WG_Y    = 8;
constexpr int    WG_Z    = 4;
constexpr int    WG_SCAN = 256;
constexpr int    WG_RBGS = 256;

// ── Iteration steps ──
constexpr int    STEPS_MINOR    = 4;
constexpr int    STEPS_MAJOR    = 16;
constexpr int    STEPS_COLLAPSE = 32;

// ── Voxel type markers ──
constexpr uint8_t VOXEL_AIR    = 0;
constexpr uint8_t VOXEL_SOLID  = 1;
constexpr uint8_t VOXEL_ANCHOR = 2;

// ── Failure flags ──
constexpr uint8_t FAIL_OK         = 0;
constexpr uint8_t FAIL_CANTILEVER = 1;
constexpr uint8_t FAIL_CRUSHING   = 2;
constexpr uint8_t FAIL_NO_SUPPORT = 3;
constexpr uint8_t FAIL_TENSION    = 4;

// ── Damping & stability ──
constexpr float  DAMPING_FACTOR           = 0.995f;
constexpr float  MAX_OMEGA                = 1.98f;
constexpr float  OMEGA_DENOM_EPSILON      = 0.01f;
constexpr float  DAMPING_SETTLE_THRESHOLD = 0.01f;

// ── Wind pressure (Eurocode 1) ──
constexpr float  WIND_BASE_PRESSURE      = 0.5f * 1.225f * 1.2f * 1e-6f;
constexpr float  WIND_CONDUCTIVITY_DECAY = 0.05f;
constexpr float  WIND_UPWIND_FACTOR      = 0.30f;

// ── Shear penalty (26-connectivity) ──
constexpr float  SHEAR_EDGE_PENALTY   = 0.35f;
constexpr float  SHEAR_CORNER_PENALTY = 0.15f;

// ── Timoshenko ──
constexpr float  DEFAULT_POISSON_RATIO      = 0.2f;
constexpr double STRESS_SYNC_BROADCAST_RADIUS = 64.0;
constexpr int    STRESS_SYNC_INTERVAL       = 10;

// ── 6-direction indices (conductivity SoA) ──
constexpr int DIR_NEG_X = 0;
constexpr int DIR_POS_X = 1;
constexpr int DIR_NEG_Y = 2;
constexpr int DIR_POS_Y = 3;
constexpr int DIR_NEG_Z = 4;
constexpr int DIR_POS_Z = 5;

// ── Phase-field fracture (Ambati 2015 hybrid) ──
constexpr float  PHASE_FIELD_L0                   = 1.5f;
constexpr float  G_C_CONCRETE                     = 100.0f;    // J/m²
constexpr float  G_C_STEEL                        = 50000.0f;  // J/m²
constexpr float  G_C_WOOD                         = 300.0f;    // J/m²
constexpr float  PHASE_FIELD_RELAX                = 0.3f;
constexpr float  PHASE_FIELD_FRACTURE_THRESHOLD   = 0.95f;
constexpr int    PHASE_FIELD_P_CONCRETE           = 2;
constexpr int    PHASE_FIELD_P_STEEL              = 4;

// ── Memory layout ──
constexpr int    RBGS_COLORS       = 8;
constexpr int    MORTON_BLOCK_SIZE = 8;

} // namespace pfsf
