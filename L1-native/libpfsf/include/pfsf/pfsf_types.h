/**
 * @file pfsf_types.h
 * @brief Public type definitions for libpfsf.
 *
 * Portable C types — no Vulkan or C++ headers leaked.
 * Mirrors the Java PFSFConstants / PFSFIslandBuffer layout exactly.
 */
#ifndef PFSF_TYPES_H
#define PFSF_TYPES_H

#include <stdint.h>
#include <stdbool.h>

#ifdef __cplusplus
extern "C" {
#endif

/* ═══ Platform export ═══ */
#if defined(_WIN32)
#  if defined(PFSF_EXPORT)
#    define PFSF_API __declspec(dllexport)
#  else
#    define PFSF_API __declspec(dllimport)
#  endif
#else
#  define PFSF_API __attribute__((visibility("default")))
#endif

/* ═══ Opaque handle ═══ */
typedef struct pfsf_engine_t* pfsf_engine;

/* ═══ Result codes ═══ */
typedef enum {
    PFSF_OK                 =  0,
    PFSF_ERROR_VULKAN       = -1,   /* Vulkan init or dispatch failure */
    PFSF_ERROR_NO_DEVICE    = -2,   /* No compute-capable GPU found */
    PFSF_ERROR_OUT_OF_VRAM  = -3,   /* VRAM budget exceeded */
    PFSF_ERROR_INVALID_ARG  = -4,   /* NULL pointer or out-of-range */
    PFSF_ERROR_NOT_INIT     = -5,   /* Engine not yet initialized */
    PFSF_ERROR_ISLAND_FULL  = -6,   /* Island exceeds MAX_ISLAND_SIZE */
} pfsf_result;

/* ═══ Voxel type markers (matches GPU type[] buffer) ═══ */
typedef enum {
    PFSF_VOXEL_AIR    = 0,
    PFSF_VOXEL_SOLID  = 1,
    PFSF_VOXEL_ANCHOR = 2,
} pfsf_voxel_type;

/* ═══ Failure flags (matches GPU fail_flags[] buffer) ═══ */
typedef enum {
    PFSF_FAIL_OK          = 0,
    PFSF_FAIL_CANTILEVER  = 1,
    PFSF_FAIL_CRUSHING    = 2,
    PFSF_FAIL_NO_SUPPORT  = 3,
    PFSF_FAIL_TENSION     = 4,
} pfsf_failure_type;

/* ═══ 6-direction indices (conductivity SoA layout) ═══ */
typedef enum {
    PFSF_DIR_NEG_X = 0,
    PFSF_DIR_POS_X = 1,
    PFSF_DIR_NEG_Y = 2,
    PFSF_DIR_POS_Y = 3,
    PFSF_DIR_NEG_Z = 4,
    PFSF_DIR_POS_Z = 5,
} pfsf_direction;

/* ═══ 3D integer position ═══ */
typedef struct {
    int32_t x, y, z;
} pfsf_pos;

/* ═══ 3D float vector ═══ */
typedef struct {
    float x, y, z;
} pfsf_vec3;

/* ═══ Island grid descriptor ═══ */
typedef struct {
    int32_t  island_id;
    pfsf_pos origin;        /* AABB minimum corner (world coords) */
    int32_t  lx, ly, lz;   /* Grid dimensions */
} pfsf_island_desc;

/* ═══ Per-voxel material data (for block change notification) ═══ */
typedef struct {
    float    density;       /* kg/m³ */
    float    rcomp;         /* Compression strength (MPa) */
    float    rtens;         /* Tension strength (MPa) */
    float    youngs_gpa;    /* Young's modulus (GPa) */
    float    poisson;       /* Poisson's ratio */
    float    gc;            /* Critical energy release rate (J/m²) */
    bool     is_anchor;
} pfsf_material;

/* ═══ Sparse voxel update ═══ */
typedef struct {
    int32_t          flat_index;
    float            source;         /* Self-weight force */
    pfsf_voxel_type  type;
    float            max_phi;        /* Per-voxel failure threshold */
    float            rcomp;          /* Compression strength (MPa) */
    float            cond[6];        /* 6-direction conductivity */
} pfsf_voxel_update;

/* ═══ Failure event (readback from GPU) ═══ */
typedef struct {
    pfsf_pos           pos;
    pfsf_failure_type  type;
} pfsf_failure_event;

/* ═══ Engine configuration ═══ */
typedef struct {
    int32_t  max_island_size;       /* Default: 50000 */
    int32_t  tick_budget_ms;        /* Default: 8 */
    int64_t  vram_budget_bytes;     /* Default: 512 MB */
    bool     enable_phase_field;    /* Default: true */
    bool     enable_multigrid;      /* Default: true */
} pfsf_config;

/* ═══ Engine statistics ═══ */
typedef struct {
    int32_t  island_count;
    int64_t  total_voxels;
    int64_t  vram_used_bytes;
    int64_t  vram_budget_bytes;
    float    last_tick_ms;
} pfsf_stats;

/* ═══ Tick result (failures detected this tick) ═══ */
typedef struct {
    pfsf_failure_event* events;     /* Caller-owned array, filled by engine */
    int32_t             capacity;   /* Size of events array */
    int32_t             count;      /* Number of failures written (out) */
} pfsf_tick_result;

/* ═══ Callback: material lookup ═══ */
typedef pfsf_material (*pfsf_material_fn)(pfsf_pos pos, void* user_data);

/* ═══ Callback: anchor lookup ═══ */
typedef bool (*pfsf_anchor_fn)(pfsf_pos pos, void* user_data);

/* ═══ Callback: fill ratio lookup ═══ */
typedef float (*pfsf_fill_ratio_fn)(pfsf_pos pos, void* user_data);

/* ═══ Callback: curing/hydration lookup ═══ */
typedef float (*pfsf_curing_fn)(pfsf_pos pos, void* user_data);

#ifdef __cplusplus
}
#endif

#endif /* PFSF_TYPES_H */
