/**
 * @file callback_bus.h
 * @brief Deferred callbacks from native code into Java, drained at
 *        tick boundaries so the hot solver loop never crosses JNI
 *        per voxel (plan §B.3).
 */
#ifndef BR_CORE_CALLBACK_BUS_H
#define BR_CORE_CALLBACK_BUS_H

#include <cstdint>
#include <mutex>
#include <vector>

namespace br_core {

enum class CallbackKind : std::uint32_t {
    AnchorInvalidate,   ///< Anchor set changed mid-tick for an island.
    IslandEvicted,      ///< VRAM-budget evicted this island.
    FailureBatch,       ///< A batch of failure events is ready.
};

struct CallbackEvent {
    CallbackKind  kind;
    std::int32_t  island_id;
    std::int64_t  payload;
};

class CallbackBus {
public:
    /** Enqueue an event. Thread-safe. */
    void post(CallbackEvent ev);

    /**
     * Drain all pending events into @p out. Thread-safe. Call at tick
     * boundary from the server thread; @p out is then handed up to Java.
     */
    void drain(std::vector<CallbackEvent>& out);

    std::size_t pending() const;

private:
    mutable std::mutex          mutex_;
    std::vector<CallbackEvent>  events_;
};

} // namespace br_core

#endif // BR_CORE_CALLBACK_BUS_H
