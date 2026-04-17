/**
 * @file pfsf_trace.h
 * @brief Structured trace ring buffer — survives JNI boundary and crashes.
 *        (v0.3d Phase 0 — stub; implementations land in Phase 7.)
 *
 * Rationale: when native crashes in a solver opcode, Java sees only
 * `SIGSEGV` and no island context. The trace ring captures per-opcode
 * {epoch, stage, island, voxel, errno, msg} 64-byte records so a
 * post-mortem dump (via a SIGSEGV/SIGABRT handler) can reconstruct the
 * last ~100 ticks of activity and map them back to world coordinates.
 */
#ifndef PFSF_TRACE_H
#define PFSF_TRACE_H

#include "pfsf_types.h"

#ifdef __cplusplus
extern "C" {
#endif

typedef enum {
    PFSF_TRACE_OFF     = 0,
    PFSF_TRACE_ERROR   = 1,
    PFSF_TRACE_WARN    = 2,
    PFSF_TRACE_INFO    = 3,
    PFSF_TRACE_VERBOSE = 4,
} pfsf_trace_level;

typedef struct {
    int64_t epoch;
    int32_t stage;       /* pfsf_hook_point */
    int32_t island_id;
    int32_t voxel_index; /* -1 if not voxel-specific */
    int32_t errno_val;
    int16_t level;       /* pfsf_trace_level */
    int16_t _pad;
    char    msg[36];     /* truncated, NUL-terminated */
} pfsf_trace_event;

/**
 * Drain up to @p capacity trace events into @p out (caller-owned).
 * Returns number of events written. Events are removed from the ring
 * on drain.
 */
PFSF_API int32_t pfsf_drain_trace(pfsf_engine e,
                                    pfsf_trace_event* out,
                                    int32_t capacity);

PFSF_API void pfsf_set_trace_level(pfsf_engine e, pfsf_trace_level level);

#ifdef __cplusplus
}
#endif

#endif /* PFSF_TRACE_H */
