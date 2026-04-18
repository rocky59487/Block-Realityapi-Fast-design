/**
 * @file pfsf_trace.h
 * @brief Structured trace ring buffer — survives the JNI boundary.
 *
 * Rationale: when something misbehaves inside a solver opcode, Java
 * sees only the JNI return code and no island context. The trace ring
 * captures per-opcode {epoch, stage, island, voxel, errno, msg}
 * 64-byte records so a Java-side log consumer can correlate native
 * anomalies with world coordinates + tick epoch.
 *
 * Activation probe: {@code pfsf_has_feature("compute.v7")} or
 *                   {@code pfsf_has_feature("trace.ring")}.
 *
 * @note Phase 7 ships the ring + Java drain path. v0.3e M5 layers an
 *       async-signal-safe crash dump on top — see {@link
 *       pfsf_install_crash_handler}. The handler chains to whatever
 *       signal handler the JVM had installed, so {@code hs_err_pid.log}
 *       still produces; we only add a sibling
 *       {@code pfsf-crash-<pid>.trace} that records the most recent
 *       trace events for cross-tick correlation.
 *
 * @since v0.3d Phase 7 (ring) / v0.3e M5 (crash dump)
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

/**
 * On-wire record layout. sizeof == 64 (8+4+4+4+4+2+2+36).
 * Layout is frozen for ABI v1; future additions swap the _pad slot
 * or extend at the end of the msg block behind a feature flag.
 */
typedef struct {
    int64_t epoch;
    int32_t stage;       /* pfsf_hook_point ∪ { -1 for non-stage events } */
    int32_t island_id;
    int32_t voxel_index; /* -1 if not voxel-specific */
    int32_t errno_val;   /* pfsf_result or implementation-defined */
    int16_t level;       /* pfsf_trace_level */
    int16_t _pad;
    char    msg[36];     /* UTF-8, NUL-terminated, truncated on overflow */
} pfsf_trace_event;

/** Ring size in events. Baked at 4096 — 256 KB steady-state footprint. */
#define PFSF_TRACE_RING_CAPACITY 4096

/**
 * Emit one event into the process-wide ring. Drops the event silently
 * when {@code level} < current global threshold. Thread-safe.
 *
 * {@code msg} is copied into a fixed 36-byte slot and truncated /
 * NUL-terminated unconditionally. Passing {@code NULL} stores an
 * empty string.
 */
PFSF_API void pfsf_trace_emit(int16_t level,
                                int64_t epoch,
                                int32_t stage,
                                int32_t island_id,
                                int32_t voxel_index,
                                int32_t errno_val,
                                const char* msg);

/**
 * Drain up to @p capacity events into a caller-supplied 64-byte-packed
 * output buffer. Events are removed from the ring on drain; oldest
 * first. Passing a NULL address with capacity 0 flushes the ring.
 *
 * @return number of events written, or a negative pfsf_result code.
 */
PFSF_API int32_t pfsf_drain_trace_dbb(void* out_addr,
                                        int64_t out_bytes,
                                        int32_t capacity);

/** Back-compat wrapper: same as {@link pfsf_drain_trace_dbb} with the
 *  engine handle ignored (trace storage is library-global). */
PFSF_API int32_t pfsf_drain_trace(pfsf_engine e,
                                    pfsf_trace_event* out,
                                    int32_t capacity);

/** Set the global emission threshold. Events below the threshold are
 *  dropped at emit-time without touching the ring. */
PFSF_API void pfsf_set_trace_level_global(pfsf_trace_level level);

/** @return the current global threshold. */
PFSF_API int32_t pfsf_get_trace_level_global(void);

/** Back-compat wrapper over the global setter. */
PFSF_API void pfsf_set_trace_level(pfsf_engine e, pfsf_trace_level level);

/** @return total number of events currently held in the ring. */
PFSF_API int32_t pfsf_trace_size(void);

/** Drop every queued event without reading them. */
PFSF_API void pfsf_trace_clear(void);

/* ─── v0.3e M5: async-signal-safe crash dump ─────────────────────────
 *
 * On SIGSEGV / SIGABRT / SIGFPE / SIGBUS the installed handler:
 *   1. Snapshots up to {@link PFSF_CRASH_MAX_EVENTS} most-recent trace
 *      events without taking the trace ring's mutex (best-effort).
 *   2. Writes {@code pfsf-crash-<pid>.trace} to the current working
 *      directory using only async-signal-safe primitives
 *      ({@code open}/{@code write}/{@code close}/{@code _exit}).
 *   3. Chains to the previously-installed handler so the JVM's
 *      {@code hs_err_pid.log} and AddressSanitizer reports keep working.
 *
 * Set the environment variable {@code BR_PFSF_NO_SIGNAL=1} to suppress
 * installation entirely — useful when running under a debugger or
 * inside a sanitizer that owns the signal handlers itself.
 *
 * Activation probe: {@code pfsf_has_feature("crash.handler")}.
 *
 * On Windows the install/uninstall calls are no-ops and
 * {@link pfsf_dump_now_for_test} writes the same file format
 * synchronously so unit tests can run cross-platform.
 */

/** Maximum trace events captured per crash dump. Files are bounded at
 *  64 + (PFSF_CRASH_MAX_EVENTS * sizeof(pfsf_trace_event)) bytes. */
#define PFSF_CRASH_MAX_EVENTS 100

/** Install signal handlers for SIGSEGV/SIGABRT/SIGFPE/SIGBUS.
 *  Idempotent. Honours {@code BR_PFSF_NO_SIGNAL=1}. Returns
 *  {@link PFSF_OK} if installed, {@link PFSF_OK} (no error) if skipped
 *  via env-var, or a negative {@link pfsf_result} on failure. */
PFSF_API pfsf_result pfsf_install_crash_handler(void);

/** Restore the prior signal handlers. Idempotent. */
PFSF_API void pfsf_uninstall_crash_handler(void);

/** Synchronously write a crash-dump file at {@code path} containing
 *  the same header + events the live handler would emit, with the
 *  supplied {@code signo} and {@code fault_addr}. Intended for unit
 *  tests — does not actually raise a signal and does not chain to the
 *  prior handler. Returns the number of events written, or a negative
 *  {@link pfsf_result}. */
PFSF_API int32_t pfsf_dump_now_for_test(const char* path,
                                          int32_t      signo,
                                          uintptr_t    fault_addr);

#ifdef __cplusplus
}
#endif

#endif /* PFSF_TRACE_H */
