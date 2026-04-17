/**
 * @file pfsf_extension.h
 * @brief SPI extension seam — augmentation DBBs + per-tick hooks.
 *        (v0.3d Phase 0 — stub; implementations land in Phase 5.)
 *
 * Rationale: each SPI manager (`IThermalManager`, `ICableManager`, …)
 * contributes a per-voxel DBB slot. The native runtime sums them into
 * source / conductivity at the appropriate hook point.  External mods
 * need *no* native knowledge — they only have to write floats into a
 * DirectByteBuffer.
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

typedef void (*pfsf_hook_fn)(int32_t island_id, int64_t epoch, void* user_data);

PFSF_API void pfsf_set_hook(pfsf_engine e,
                             pfsf_hook_point pt,
                             pfsf_hook_fn fn,
                             void* user_data);

#ifdef __cplusplus
}
#endif

#endif /* PFSF_EXTENSION_H */
