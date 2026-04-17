/**
 * @file callback_bus.cpp
 * @brief Deferred callback queue drained at tick boundaries.
 */
#include "br_core/callback_bus.h"

namespace br_core {

void CallbackBus::post(CallbackEvent ev) {
    std::lock_guard<std::mutex> lk(mutex_);
    events_.push_back(ev);
}

void CallbackBus::drain(std::vector<CallbackEvent>& out) {
    std::lock_guard<std::mutex> lk(mutex_);
    out.insert(out.end(), events_.begin(), events_.end());
    events_.clear();
}

std::size_t CallbackBus::pending() const {
    std::lock_guard<std::mutex> lk(mutex_);
    return events_.size();
}

} // namespace br_core
