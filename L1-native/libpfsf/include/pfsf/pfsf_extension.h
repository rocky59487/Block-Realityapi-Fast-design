/**
 * @file pfsf_extension.h
 * @brief SPI extension seam — augmentation DBBs + per-tick hooks.
 *
 * Rationale: each SPI manager (`IThermalManager`, `ICableManager`, …)
 * contributes a per-voxel DBB slot. The native runtime sums them into
 * source / conductivity at the appropriate hook point.  External mods
 * need *no* native knowledge — they only have to write floats into a
 * DirectByteBuffer.
 *
 * Phase 5 lights up the storage and hook-table layer. Plan buffer
 * (Phase 6) is what actually fires hooks and consumes slots during a
 * tick; until then, register/clear/query round-trip cleanly so Java
 * hosts (PFSFAugmentationHost) can be integrated ahead of time.
 *
 * Activation probe: {@code pfsf_has_feature("compute.v5")}
 * or {@code pfsf_has_feature("extension.v1")}.
 */
#ifndef PFSF_EXTENSION_H
#define PFSF_EXTENSION_H

#include "pfsf_types.h"

#ifdef __cplusplus
extern "C" {
#endif

/* ═══════════════════════════════════════════════════════════════
 *  Augmentation kinds — one entry per SPI manager
 * ═══════════════════════════════════════════════════════════════ */
typedef enum {
    PFSF_AUG_THERMAL_FIELD    = 1, /* IThermalManager           */
    PFSF_AUG_TENSION_OVERRIDE = 2, /* ICableManager             */
    PFSF_AUG_FLUID_PRESSURE   = 3, /* IFluidManager (existing)  */
    PFSF_AUG_EM_FIELD         = 4, /* IElectromagneticManager   */
    PFSF_AUG_FUSION_MASK      = 5, /* IFusionDetector           */
    PFSF_AUG_WIND_FIELD_3D    = 6, /* IWindManager              */
    PFSF_AUG_MATERIAL_OVR     = 7, /* IMaterialRegistry per-vox */
    PFSF_AUG_CURING_FIELD     = 8, /* ICuringManager (existing) */
    PFSF_AUG_LOADPATH_HINT    = 9, /* ILoadPathManager          */
} pfsf_augmentation_kind;

typedef struct {
    int32_t                struct_bytes;  /* sizeof(pfsf_aug_slot) — ABI extensibility */
    pfsf_augmentation_kind kind;
    void*                  dbb_addr;      /* 256-byte aligned, Java-owned */
    int64_t                dbb_bytes;
    int32_t                stride_bytes;
    int32_t                version;       /* bump to trigger native re-read */
} pfsf_aug_slot;

PFSF_API pfsf_result pfsf_register_augmentation(pfsf_engine e,
                                                  int32_t island_id,
                                                  const pfsf_aug_slot* slot);

PFSF_API void pfsf_clear_augmentation(pfsf_engine e,
                                        int32_t island_id,
                                        pfsf_augmentation_kind kind);

/* ═══════════════════════════════════════════════════════════════
 *  Per-tick hook points (stage boundaries)
 * ═══════════════════════════════════════════════════════════════ */
typedef enum {
    PFSF_HOOK_PRE_SOURCE  = 0,
    PFSF_HOOK_POST_SOURCE = 1,
    PFSF_HOOK_PRE_SOLVE   = 2,
    PFSF_HOOK_POST_SOLVE  = 3,
    PFSF_HOOK_PRE_SCAN    = 4,
    PFSF_HOOK_POST_SCAN   = 5,
} pfsf_hook_point;

#define PFSF_HOOK_POINT_COUNT 6

typedef void (*pfsf_hook_fn)(int32_t island_id, int64_t epoch, void* user_data);

PFSF_API void pfsf_set_hook(pfsf_engine e,
                             pfsf_hook_point pt,
                             pfsf_hook_fn fn,
                             void* user_data);

/* ═══════════════════════════════════════════════════════════════
 *  Phase 5 — compute-library-local augmentation + hook store
 *
 *  These entries do not require a pfsf_engine handle — they are
 *  backed by a process-wide registry inside libpfsf_compute so the
 *  Java host can round-trip slots ahead of Phase 6 plan-buffer
 *  integration. They share the same struct/enum types as the
 *  engine-flavoured symbols above.
 * ═══════════════════════════════════════════════════════════════ */

/**
 * Register (or overwrite) a per-voxel augmentation slot for an island.
 *
 * @param island_id island identifier
 * @param slot      caller-owned slot; copied into the registry
 * @return {@code PFSF_OK} or {@code PFSF_ERROR_INVALID_ARG}.
 */
PFSF_API pfsf_result pfsf_aug_register(int32_t island_id,
                                         const pfsf_aug_slot* slot);

/** Clear a single kind for an island — no-op when missing. */
PFSF_API void pfsf_aug_clear(int32_t island_id,
                              pfsf_augmentation_kind kind);

/** Clear every slot registered against an island. */
PFSF_API void pfsf_aug_clear_island(int32_t island_id);

/**
 * Fetch the registered slot for an (island, kind) pair.
 *
 * @param out slot copy target
 * @return 1 when found, 0 when missing.
 */
PFSF_API int32_t pfsf_aug_query(int32_t island_id,
                                  pfsf_augmentation_kind kind,
                                  pfsf_aug_slot* out);

/** @return number of slots currently registered to the island. */
PFSF_API int32_t pfsf_aug_island_count(int32_t island_id);

/* Hook table — mirrors pfsf_set_hook but keyed by island. */

PFSF_API void pfsf_hook_set(int32_t island_id,
                             pfsf_hook_point pt,
                             pfsf_hook_fn fn,
                             void* user_data);

/**
 * Fire the registered callback (if any) for (island, point).
 *
 * Phase 6's plan-buffer calls this at each stage boundary; Phase 5
 * exposes it publicly so unit tests can round-trip.
 */
PFSF_API int32_t pfsf_hook_fire(int32_t island_id,
                                  pfsf_hook_point pt,
                                  int64_t epoch);

PFSF_API void pfsf_hook_clear(int32_t island_id,
                               pfsf_hook_point pt);

PFSF_API void pfsf_hook_clear_island(int32_t island_id);

#ifdef __cplusplus
}
#endif

#endif /* PFSF_EXTENSION_H */
