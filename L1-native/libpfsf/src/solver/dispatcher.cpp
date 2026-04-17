#include "dispatcher.h"

#include "jacobi_solver.h"
#include "vcycle_solver.h"
#include "phase_field.h"
#include "failure_scan.h"
#include "pcg_solver.h"
#include "sparse_scatter.h"

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
                       JacobiSolver&        rbgs,
                       VCycleSolver&        vcycle,
                       PhaseFieldSolver&    phaseField,
                       FailureScan&         failure,
                       PCGSolver&           pcg,
                       SparseScatterSolver& sparse)
    : vk_(vk),
      rbgs_(rbgs),
      vcycle_(vcycle),
      phaseField_(phaseField),
      failure_(failure),
      pcg_(pcg),
      sparse_(sparse) {}

bool Dispatcher::supportsPCG(const IslandBuffer& buf) const {
    // PCG tail activates once r/z/p/Ap/partialSums are allocated AND the
    // PCG pipelines are ready. The dispatcher itself owns the sequencing
    // (matvec → dot → update → dot → direction); recording that plumbing
    // is the next follow-up commit. Returning the full readiness check now
    // so the gate flips atomically when the sequencing lands.
    return pcg_.isReady() && buf.hasPCGBuffers();
}

int Dispatcher::recordSolveSteps(VkCommandBuffer cmd, IslandBuffer& buf,
                                  int steps, VkDescriptorPool pool) {
    if (cmd == VK_NULL_HANDLE || pool == VK_NULL_HANDLE) return 0;
    if (steps <= 0 || buf.N() <= 0) return 0;
    if (!rbgs_.isReady()) return 0;

    const bool mgAvailable = vcycle_.isReady() && vcycleProductive(buf);
    int recorded = 0;

    // ── Hybrid RBGS → PCG path ───────────────────────────────────────
    // Mirrors Java PFSFDispatcher's deterministic MIN_RBGS/MIN_PCG split.
    // Residual-driven adaptive switching (macro_residual readback → ratio
    // check) is gated behind its own follow-up because it requires a
    // GPU→host round-trip per tick; this fixed split captures the
    // performance win (low-frequency modes → PCG) without the sync cost.
    if (supportsPCG(buf) && steps >= PCG_MIN_RBGS + PCG_MIN_STEPS) {
        const int rbgsSteps = PCG_MIN_RBGS;
        const int pcgSteps  = steps - rbgsSteps;

        for (int k = 0; k < rbgsSteps; ++k) {
            rbgs_.recordStep(cmd, buf, pool, chebyshevDamping(buf.chebyshev_iter));
            buf.chebyshev_iter++;
            computeBarrier(cmd);
            ++recorded;
        }

        // RBGS → PCG handoff barrier (the matvec dispatch in
        // PCG step 1 reads phi written by the RBGS tail).
        computeBarrier(cmd);

        recordPCGInitialResidual(cmd, buf, pool);
        for (int k = 0; k < pcgSteps; ++k) {
            recordPCGStep(cmd, buf, pool);
            ++recorded;
        }
        return recorded;
    }

    // Pure RBGS + V-Cycle fallback — identical cadence to the Java path.
    // At MG_INTERVAL boundaries (k > 0 && k % MG_INTERVAL == 0) the
    // dispatcher runs a full V-Cycle sweep (pre-smooth + restrict +
    // coarse RBGS ×4 + prolong + post-smooth) instead of a single RBGS.
    // The V-Cycle lazily allocates multigrid buffers on first use; if
    // allocation fails we fall back to a plain RBGS for that iteration
    // so the fine-grid solve still makes progress.
    for (int k = 0; k < steps; ++k) {
        bool didVCycle = false;
        if (k > 0 && (k % MG_INTERVAL) == 0 && mgAvailable) {
            if (!buf.hasMultigridL1()) {
                buf.allocateMultigrid(vk_);
            }
            if (buf.hasMultigridL1()) {
                const int vcRecorded = recordVCycle(cmd, buf, pool);
                if (vcRecorded > 0) {
                    recorded += vcRecorded;
                    // V-Cycle advanced chebyshev_iter inside rbgs_.recordStep;
                    // no extra bookkeeping here.
                    didVCycle = true;
                }
            }
        }
        if (!didVCycle) {
            rbgs_.recordStep(cmd, buf, pool, chebyshevDamping(buf.chebyshev_iter));
            buf.chebyshev_iter++;
            computeBarrier(cmd);
            ++recorded;
        }
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

bool Dispatcher::recordSparseScatter(VkCommandBuffer cmd, IslandBuffer& buf,
                                      VkDescriptorPool pool, int updateCount) {
    if (!sparse_.isReady()) return false;
    if (cmd == VK_NULL_HANDLE || pool == VK_NULL_HANDLE) return false;
    if (updateCount <= 0) return false;
    if (!buf.hasSparseUpload()) return false;

    // Clamp to the island's upload-buffer capacity — mirrors Java's
    // Math.min(updates.size(), MAX_SPARSE_UPDATES_PER_TICK) guard.
    const std::uint32_t cap =
        static_cast<std::uint32_t>(buf.sparse_upload_capacity);
    const std::uint32_t count =
        static_cast<std::uint32_t>(updateCount) > cap ? cap
                                                      : static_cast<std::uint32_t>(updateCount);

    sparse_.recordScatter(cmd, buf, pool, count);
    // The scatter shader writes source/cond/type/maxPhi/rcomp/rtens — any
    // subsequent RBGS/failure_scan dispatch reads those, so we must gate
    // the command stream with a compute→compute barrier.
    computeBarrier(cmd);
    return true;
}

} // namespace pfsf
