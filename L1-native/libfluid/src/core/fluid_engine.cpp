/**
 * @file fluid_engine.cpp
 * @brief Placeholder translation unit — the real Jacobi-pressure +
 *        semi-Lagrangian advection + surface-tension kernels land as
 *        M3 progresses. Keeping the TU present in the CMake sources list
 *        lets the skeleton build + link out of the box so downstream
 *        Java glue can be exercised end-to-end ahead of the GPU port.
 */

namespace br_fluid {

// Intentionally empty — see include/fluid/fluid.h for the public API,
// src/fluid_api.cpp for the Phase-1 stubs, and plan §M3 for the solver
// architecture (Jacobi pressure, MacCormack advection, γ-tension, etc.).

} // namespace br_fluid
