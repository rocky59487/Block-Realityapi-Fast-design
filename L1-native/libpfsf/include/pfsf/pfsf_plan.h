/**
 * @file pfsf_plan.h
 * @brief v0.3d Phase 6 — tick plan buffer opcode dispatcher.
 *
 * A tick plan is a single DirectByteBuffer that Java assembles once per
 * tick and hands to the native side via one JNI call. Inside, the plan
 * is a sequence of length-prefixed opcode records; the C++ dispatcher
 * walks the records in order and dispatches each one to a stateless
 * handler that reads args + touches the Phase 5 registry (augmentation
 * slots / hook table). The design erases the per-primitive JNI
 * boundary cost that v0.3c still paid on every tick.
 *
 * Binary layout (all little-endian):
 *
 *   [0]   uint32_t  magic        = PFSF_PLAN_MAGIC
 *   [4]   uint16_t  version      = 1
 *   [6]   uint16_t  flags        = 0
 *   [8]   int32_t   island_id
 *   [12]  int32_t   opcode_count
 *   [16]  <opcode records...>
 *
 * Each opcode record:
 *
 *   [0]   uint16_t  opcode       (one of {@link pfsf_plan_opcode})
 *   [2]   uint16_t  arg_bytes    (length of the arg payload 0..65535)
 *   [4]   uint8_t   args[arg_bytes]
 *
 * Activation probe: {@code pfsf_has_feature("compute.v6")} or
 *                   {@code pfsf_has_feature("plan.v1")}.
 *
 * @since v0.3d Phase 6
 */
#ifndef PFSF_PLAN_H
#define PFSF_PLAN_H

#include "pfsf_types.h"

#ifdef __cplusplus
extern "C" {
#endif

/** Magic number — byte stream "P","F","S","F" read as little-endian u32. */
#define PFSF_PLAN_MAGIC  0x46534650u

/** Current plan binary format version. */
#define PFSF_PLAN_VERSION 1u

/** Header size (fixed). */
#define PFSF_PLAN_HEADER_BYTES 16

/** Opcode record header size (opcode + arg_bytes prefix). */
#define PFSF_PLAN_OP_HEADER_BYTES 4

/**
 * Opcode set for plan.v1. IDs are stable — future versions add new
 * values and bump {@link PFSF_PLAN_VERSION} only when the binary
 * framing changes, not when new opcodes land.
 */
typedef enum {
    /** No-op — useful as a timing / alignment filler. */
    PFSF_OP_NO_OP              = 0,

    /**
     * Test-only: increments an atomic global counter by the int32 arg.
     * Used by GoldenParityTest to verify the dispatcher walks every
     * opcode in order. Not part of the production tick path.
     * args: int32_t delta
     */
    PFSF_OP_INCR_COUNTER       = 1,

    /**
     * Clear one augmentation slot for the plan's island.
     * args: int32_t kind (pfsf_augmentation_kind)
     */
    PFSF_OP_CLEAR_AUG          = 2,

    /**
     * Clear every augmentation slot registered to the plan's island.
     * args: none
     */
    PFSF_OP_CLEAR_AUG_ISLAND   = 3,

    /**
     * Fire the registered hook callback for (plan.island, point).
     * Silent no-op when no hook is registered.
     * args: int32_t point, int64_t epoch
     */
    PFSF_OP_FIRE_HOOK          = 4,

    /* ─── v0.3e M2 — Phase 1-4 compute primitives (ABI v1.1, additive) ───
     *
     * Buffer addressing: every {@code *_addr} field is a raw pointer (a
     * uint64 encoded as int64) obtained from {@code GetDirectBufferAddress}
     * or the LWJGL {@code MemoryUtil.memAddress} helper. The plan record's
     * {@code arg_bytes} prefix provides forward-compat — future ABIs may
     * append fields; older dispatchers read only the leading bytes they
     * know about and ignore the tail. A zero address is treated as "not
     * supplied" where the underlying primitive supports it (e.g.
     * {@code hydration_addr}). */

    /**
     * Run {@code pfsf_normalize_soa6} on caller-owned buffers.
     * args (LE): int64 source_addr, int64 rcomp_addr, int64 rtens_addr,
     *            int64 cond_addr, int64 hydration_addr, int64 out_sigma_addr,
     *            int32 n, int32 _pad
     */
    PFSF_OP_NORMALIZE_SOA6     = 5,

    /**
     * Run {@code pfsf_apply_wind_bias} on a caller-owned conductivity array.
     * args: int64 cond_addr, int32 n, int32 _pad,
     *       float wind_x, float wind_y, float wind_z, float upwind_factor
     */
    PFSF_OP_APPLY_WIND_BIAS    = 6,

    /**
     * Run {@code pfsf_compute_conductivity} into a caller-owned SoA-6 array.
     * args: int64 cond_addr, int64 rcomp_addr, int64 rtens_addr,
     *       int64 type_addr, int32 lx, int32 ly, int32 lz, int32 _pad,
     *       float wind_x, float wind_y, float wind_z, float upwind_factor
     */
    PFSF_OP_COMPUTE_CONDUCTIVITY = 7,

    /**
     * Run {@code pfsf_compute_arm_map}; writes int32×N arm into out_arm_addr.
     * args: int64 members_addr, int64 anchors_addr, int64 out_arm_addr,
     *       int32 lx, int32 ly, int32 lz, int32 _pad
     */
    PFSF_OP_ARM_MAP            = 8,

    /**
     * Run {@code pfsf_compute_arch_factor_map}; writes float×N arch factor.
     * args: int64 members_addr, int64 anchors_addr, int64 out_arch_addr,
     *       int32 lx, int32 ly, int32 lz, int32 _pad
     */
    PFSF_OP_ARCH_FACTOR        = 9,

    /**
     * Run {@code pfsf_inject_phantom_edges} over a conductivity array.
     * args: int64 members_addr, int64 cond_addr, int64 rcomp_addr,
     *       int64 out_injected_addr,
     *       int32 lx, int32 ly, int32 lz, int32 _pad,
     *       float edge_penalty, float corner_penalty
     */
    PFSF_OP_PHANTOM_EDGES      = 10,

    /**
     * Run {@code pfsf_downsample_2to1} into caller-owned coarse arrays.
     * args: int64 fine_addr, int64 fine_type_addr,
     *       int64 coarse_addr, int64 coarse_type_addr,
     *       int32 lxf, int32 lyf, int32 lzf, int32 _pad
     */
    PFSF_OP_DOWNSAMPLE_2TO1    = 11,

    /**
     * Run {@code pfsf_tiled_layout_build} into a caller-owned tiled array.
     * args: int64 linear_addr, int64 out_addr,
     *       int32 lx, int32 ly, int32 lz, int32 tile
     */
    PFSF_OP_TILED_LAYOUT       = 12,

    /**
     * Run {@code pfsf_chebyshev_omega} and write the float to {@code out_addr}.
     * args: int64 out_addr, int32 iter, int32 _pad, float rho_spec
     */
    PFSF_OP_CHEBYSHEV          = 13,

    /**
     * Run {@code pfsf_check_divergence}; mutates the {@code state_addr}
     * buffer in place and writes an int32 {@link pfsf_divergence_kind} to
     * {@code out_kind_addr}.
     * args: int64 state_addr, int64 macro_residuals_addr, int64 out_kind_addr,
     *       float max_phi_now, int32 macro_count,
     *       float divergence_ratio, float damping_settle_threshold
     */
    PFSF_OP_CHECK_DIVERGENCE   = 14,

    /**
     * Run {@code pfsf_extract_island_features}; writes 12 floats to out_addr.
     * args: int64 residuals_addr, int64 out12_addr,
     *       int32 lx, int32 ly, int32 lz, int32 chebyshev_iter,
     *       int32 oscillation_count, int32 damping_active,
     *       int32 stable_tick_count, int32 lod_level,
     *       int32 lod_dormant, int32 pcg_allocated,
     *       int32 macro_count, int32 _pad,
     *       float rho_spec_override, float prev_max_macro_residual
     */
    PFSF_OP_EXTRACT_FEATURES   = 15,

    /**
     * Run {@code pfsf_wind_pressure_source}; writes a single float to out_addr.
     * args: int64 out_addr, float wind_speed, float density_kg_m3,
     *       int32 exposed (non-zero for true)
     */
    PFSF_OP_WIND_PRESSURE      = 16,

    /**
     * Run {@code pfsf_timoshenko_moment_factor}; writes a single float.
     * args: int64 out_addr, float b, float h,
     *       int32 arm, float youngs_gpa, float nu
     */
    PFSF_OP_TIMOSHENKO         = 17,

    /* Reserve 18..255 for future phase opcodes. Callers MUST NOT assume
     * unknown opcodes are ignored — the dispatcher errors out at the
     * first unrecognised ID so version mismatches are caught loudly. */
} pfsf_plan_opcode;

/**
 * Out-parameter struct for plan execution. Caller zero-initialises;
 * dispatcher populates on return.
 */
typedef struct {
    int32_t struct_bytes;       /* sizeof(pfsf_plan_result) */
    int32_t executed_count;     /* opcodes successfully processed */
    int32_t failed_index;       /* index of first failing opcode, -1 when clean */
    int32_t error_code;         /* pfsf_result; PFSF_OK when clean */
    int32_t hook_fire_count;    /* how many hooks actually fired (stat) */
} pfsf_plan_result;

/**
 * Execute a plan buffer.
 *
 * @param plan       address of the plan buffer (Java DBB base)
 * @param plan_bytes total bytes available at {@code plan}
 * @param out        caller-owned result struct; may be NULL
 * @return PFSF_OK when every opcode executed, else the failure code
 *         (also written into {@code out->error_code} when non-NULL).
 *
 * Bounds-check discipline: every record header and every arg read is
 * clamped to {@code plan_bytes}. Malformed input returns
 * PFSF_ERROR_INVALID_ARG without touching registry state beyond what
 * the opcodes already processed successfully.
 */
PFSF_API pfsf_result pfsf_plan_execute(const void* plan,
                                         int64_t plan_bytes,
                                         pfsf_plan_result* out);

/* ─── Test-only helpers (available under compute.v6) ───────────────── */

/**
 * Read the global INCR_COUNTER accumulator and reset it atomically.
 * Used exclusively by GoldenParityTest to verify ordering / arity.
 */
PFSF_API int64_t pfsf_plan_test_counter_read_reset(void);

/**
 * Register a test-only counting hook on (island_id, point). When the
 * dispatcher fires PFSF_OP_FIRE_HOOK with a matching (island, point),
 * an internal counter advances; {@link pfsf_plan_test_hook_count}
 * reads it. This keeps hook parity testable without exposing arbitrary
 * C function pointer registration to Java.
 */
PFSF_API void pfsf_plan_test_hook_install(int32_t island_id, int32_t point);

/** @return fire count for a test hook installed via
 *          {@link pfsf_plan_test_hook_install}, resetting it. */
PFSF_API int64_t pfsf_plan_test_hook_count_read_reset(int32_t island_id,
                                                        int32_t point);

#ifdef __cplusplus
}
#endif

#endif /* PFSF_PLAN_H */
