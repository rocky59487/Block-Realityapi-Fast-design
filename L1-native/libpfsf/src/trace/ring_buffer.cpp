/**
 * @file ring_buffer.cpp
 * @brief v0.3d Phase 7 — structured trace ring buffer.
 *
 * Bounded MPMC drop-oldest ring backed by a static 4096-event array.
 * Enqueue / drain / clear all take the same mutex; trace is not a hot
 * path outside debug runs so simplicity beats lock-freedom here.
 *
 * Event layout (64 B) is the on-wire format — Java reads the same
 * bytes via a DirectByteBuffer drained through pfsf_drain_trace_dbb.
 *
 * @maps_to PFSFTrace.java — Java-side consumer.
 * @since v0.3d Phase 7
 */

#include "pfsf/pfsf_trace.h"

#include <atomic>
#include <cstdint>
#include <cstring>
#include <mutex>

namespace {

constexpr size_t RING_CAPACITY = static_cast<size_t>(PFSF_TRACE_RING_CAPACITY);
static_assert(sizeof(pfsf_trace_event) == 64,
              "pfsf_trace_event must be 64 bytes — on-wire layout frozen.");

struct ring_state {
    std::mutex                                mtx;
    pfsf_trace_event                          events[RING_CAPACITY];
    size_t                                    write_idx = 0;  /* next slot to fill */
    size_t                                    count     = 0;  /* live events */
};

ring_state& ring() {
    static ring_state r;
    return r;
}

std::atomic<int32_t>& level_ref() {
    static std::atomic<int32_t> lvl{PFSF_TRACE_INFO};
    return lvl;
}

inline void copy_msg(char* dst, const char* src) {
    if (src == nullptr) { dst[0] = '\0'; return; }
    constexpr size_t MAX = sizeof(((pfsf_trace_event*)0)->msg);
    size_t i = 0;
    for (; i < MAX - 1 && src[i] != '\0'; ++i) dst[i] = src[i];
    /* NUL-terminate + zero the tail so msg bytes are deterministic. */
    for (size_t z = i; z < MAX; ++z) dst[z] = '\0';
}

} /* namespace */

/* ═══════════════════════════════════════════════════════════════════
 *  Public API
 * ═══════════════════════════════════════════════════════════════════ */

extern "C" void pfsf_trace_emit(int16_t level,
                                  int64_t epoch,
                                  int32_t stage,
                                  int32_t island_id,
                                  int32_t voxel_index,
                                  int32_t errno_val,
                                  const char* msg) {
    const int32_t threshold = level_ref().load(std::memory_order_relaxed);
    if (threshold == PFSF_TRACE_OFF) return;
    if (static_cast<int32_t>(level) > threshold) return;

    auto& r = ring();
    std::lock_guard lk(r.mtx);

    pfsf_trace_event& slot = r.events[r.write_idx];
    slot.epoch       = epoch;
    slot.stage       = stage;
    slot.island_id   = island_id;
    slot.voxel_index = voxel_index;
    slot.errno_val   = errno_val;
    slot.level       = level;
    slot._pad        = 0;
    copy_msg(slot.msg, msg);

    r.write_idx = (r.write_idx + 1) % RING_CAPACITY;
    if (r.count < RING_CAPACITY) r.count += 1;
    /* else oldest silently overwritten — drop-oldest semantics. */
}

extern "C" int32_t pfsf_drain_trace_dbb(void* out_addr,
                                          int64_t out_bytes,
                                          int32_t capacity) {
    if (capacity < 0) return PFSF_ERROR_INVALID_ARG;
    if (capacity > 0 && out_addr == nullptr) return PFSF_ERROR_INVALID_ARG;

    auto& r = ring();
    std::lock_guard lk(r.mtx);

    int32_t to_copy = static_cast<int32_t>(r.count);
    if (to_copy > capacity) to_copy = capacity;

    /* Bound by caller buffer byte size. */
    const int64_t need = static_cast<int64_t>(to_copy)
                       * static_cast<int64_t>(sizeof(pfsf_trace_event));
    if (out_addr != nullptr && need > out_bytes) {
        to_copy = static_cast<int32_t>(out_bytes / sizeof(pfsf_trace_event));
    }

    /* Read oldest-first: start at (write_idx - count) mod capacity. */
    size_t read_idx = (r.write_idx + RING_CAPACITY - r.count) % RING_CAPACITY;

    auto* out = static_cast<pfsf_trace_event*>(out_addr);
    for (int32_t i = 0; i < to_copy; ++i) {
        if (out != nullptr) out[i] = r.events[read_idx];
        read_idx = (read_idx + 1) % RING_CAPACITY;
    }
    r.count -= to_copy;
    return to_copy;
}

extern "C" int32_t pfsf_drain_trace(pfsf_engine /*e*/,
                                      pfsf_trace_event* out,
                                      int32_t capacity) {
    return pfsf_drain_trace_dbb(out,
                                static_cast<int64_t>(capacity)
                                  * static_cast<int64_t>(sizeof(pfsf_trace_event)),
                                capacity);
}

extern "C" void pfsf_set_trace_level_global(pfsf_trace_level level) {
    level_ref().store(static_cast<int32_t>(level), std::memory_order_relaxed);
}

extern "C" int32_t pfsf_get_trace_level_global(void) {
    return level_ref().load(std::memory_order_relaxed);
}

extern "C" void pfsf_set_trace_level(pfsf_engine /*e*/, pfsf_trace_level level) {
    pfsf_set_trace_level_global(level);
}

extern "C" int32_t pfsf_trace_size(void) {
    auto& r = ring();
    std::lock_guard lk(r.mtx);
    return static_cast<int32_t>(r.count);
}

extern "C" void pfsf_trace_clear(void) {
    auto& r = ring();
    std::lock_guard lk(r.mtx);
    r.count     = 0;
    r.write_idx = 0;
}

/* ═══════════════════════════════════════════════════════════════════
 *  Internal: async-signal-safe peek for the crash handler.
 *
 *  Reads count + write_idx without holding the mutex. Tearing is
 *  acceptable here — the only alternative inside a SIGSEGV handler
 *  would be to risk deadlock on a mutex held by the faulting thread.
 *  The dumped snapshot is best-effort by design.
 * ═══════════════════════════════════════════════════════════════════ */

extern "C" int32_t pfsf_internal_trace_peek_unsafe(pfsf_trace_event* out, int32_t cap) {
    if (out == nullptr || cap <= 0) return 0;
    auto& r = ring();
    /* Relaxed reads; size_t is word-sized on every platform we ship to,
     * so individual loads are atomic enough for a snapshot. */
    size_t live = r.count;
    size_t widx = r.write_idx;
    if (live > RING_CAPACITY) live = RING_CAPACITY;
    int32_t n = (cap < static_cast<int32_t>(live)) ? cap : static_cast<int32_t>(live);
    /* Walk newest-first window: start = (write_idx - n) mod capacity. */
    size_t start = (widx + RING_CAPACITY - static_cast<size_t>(n)) % RING_CAPACITY;
    for (int32_t i = 0; i < n; ++i) {
        out[i] = r.events[(start + static_cast<size_t>(i)) % RING_CAPACITY];
    }
    return n;
}
