#include "dispatcher.h"

#include "jacobi_solver.h"
#include "vcycle_solver.h"
#include "phase_field.h"
#include "failure_scan.h"
#include "pcg_solver.h"

#include "core/constants.h"
#include "core/island_buffer.h"
#include "core/vulkan_context.h"

#include <algorithm>
#include <cstdio>

namespace pfsf {

namespace {

void computeBarrier(VkCommandBuffer cmd) {
    VkMemoryBarrier mb{};
    mb.sType         = VK_STRUCTURE_TYPE_MEMORY_BARRIER;
    mb.srcAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
    mb.dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
    vkCmdPipelineBarrier(cmd,
        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
        0, 1, &mb, 0, nullptr, 0, nullptr);
}

/** Chebyshev damping — mirrors the Java warm-up / relaxation curve.
 *  Java: damping=0.0 until chebyshev_iter >= WARMUP_STEPS, then DAMPING_FACTOR. */
float chebyshevDamping(int iter) {
    return iter >= WARMUP_STEPS ? DAMPING_FACTOR : 0.0f;
}

/** Shortest-side heuristic matching Java PFSFIslandBuffer.getLmax():
 *  V-Cycle is only productive when the shortest island dim > 4. */
bool vcycleProductive(const IslandBuffer& buf) {
    return std::min({buf.lx, buf.ly, buf.lz}) > 4;
}

} // namespace

Dispatcher::Dispatcher(VulkanContext& vk,
                       JacobiSolver&      rbgs,
                       VCycleSolver&      vcycle,
                       PhaseFieldSolver&  phaseField,
                       FailureScan&       failure,
                       PCGSolver&         pcg)
    : vk_(vk),
      rbgs_(rbgs),
      vcycle_(vcycle),
      phaseField_(phaseField),
      failure_(failure),
      pcg_(pcg) {}

bool Dispatcher::supportsPCG(const IslandBuffer& /*buf*/) const {
    // IslandBuffer does not yet expose r/z/p/Ap/partialSums. Until it does
    // the native path stays on the pure RBGS+V-Cycle route, which is the
    // same fallback Java takes when BRConfig.isPFSFPCGEnabled() is false.
    return false;
}

int Dispatcher::recordSolveSteps(VkCommandBuffer cmd, IslandBuffer& buf,
                                  int steps, VkDescriptorPool pool) {
    if (cmd == VK_NULL_HANDLE || pool == VK_NULL_HANDLE) return 0;
    if (steps <= 0 || buf.N() <= 0) return 0;
    if (!rbgs_.isReady()) return 0;

    // PCG branch — tracked for M2c-follow-up, currently disabled.
    if (supportsPCG(buf)) {
        // Residual-driven adaptive switching lives here (see Java
        // PFSFDispatcher.recordSolveSteps, MIN_RBGS=2 / MIN_PCG=1 /
        // RESIDUAL_STALL_RATIO=0.95f). Keep the shape so the follow-up
        // port drops straight in without another structural pass.
    }

    // Pure RBGS + V-Cycle fallback — identical cadence to the Java path.
    const bool mgAvailable = vcycle_.isReady() && vcycleProductive(buf);
    int recorded = 0;

    for (int k = 0; k < steps; ++k) {
        if (k > 0 && (k % MG_INTERVAL) == 0 && mgAvailable) {
            // V-cycle recording requires coarse-grid IslandBuffer mirrors
            // (phi_coarse, rho_coarse, sigma_coarse, type_coarse). Those
            // don't exist in IslandBuffer yet — when they land, swap this
            // branch for a real restrict → coarse RBGS → prolong sequence.
            rbgs_.recordStep(cmd, buf, pool, chebyshevDamping(buf.chebyshev_iter));
        } else {
            rbgs_.recordStep(cmd, buf, pool, chebyshevDamping(buf.chebyshev_iter));
            buf.chebyshev_iter++;
        }
        computeBarrier(cmd);
        ++recorded;
    }
    return recorded;
}

void Dispatcher::recordPhaseFieldEvolve(VkCommandBuffer cmd, IslandBuffer& buf,
                                         VkDescriptorPool pool) {
    if (!phaseField_.isReady()) return;
    // Clamp l0 ≥ 2 (topological stability — Ambati 2015 §3.2).
    const float l0 = std::max(PHASE_FIELD_L0, 2.0f);
    phaseField_.recordEvolve(cmd, buf, pool,
                             l0, G_C_CONCRETE, PHASE_FIELD_RELAX,
                             /*spectralSplit=*/true);
    computeBarrier(cmd);
}

void Dispatcher::recordFailureDetection(VkCommandBuffer cmd, IslandBuffer& buf,
                                         VkDescriptorPool pool) {
    if (!failure_.isReady()) return;
    failure_.recordStep(cmd, buf, pool, PHI_ORPHAN_THRESHOLD);
    computeBarrier(cmd);
    // Compact readback + phi max reduction are tracked separately — they
    // need their own shaders + staging readback path (M2c follow-up).
}

} // namespace pfsf
