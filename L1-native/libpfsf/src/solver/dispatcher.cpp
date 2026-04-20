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

/** Longest-side heuristic matching Java PFSFIslandBuffer.getLmax()
 *  (`Math.max(Lx, Math.max(Ly, Lz))`): V-Cycle is productive when the
 *  longest island dim > 4. Using min here (Capy-ai R5, PR#187) forced
 *  large thin islands (walls/slabs/bridges) off the W-cycle path and
 *  broke Java parity on convergence rate. */
bool vcycleProductive(const IslandBuffer& buf) {
    return std::max({buf.lx, buf.ly, buf.lz}) > 4;
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
    // Capy-ai R4 (PR#187): honour the Java runtime toggle
    // (BRConfig.isPFSFPCGEnabled). When disabled, dispatcher stays on
    // pure RBGS + V-Cycle regardless of pipeline/buffer readiness.
    if (!pcg_enabled_) return false;
    // PR#187 capy-ai R22: the pass-2 pcg_dot reducer writes partials[slot]
    // from workgroup 0 only, which caps numPartials at kElPerWG and the
    // voxel count at kElPerWG^2 = 262144. Larger islands would require a
    // recursive pass-2 chain that is not yet implemented; fall back to
    // pure RBGS + V-Cycle instead of recording a reduction that would
    // race multiple workgroups on the same output slot. The Java reference
    // path accepts up to 1,000,000 voxels, so this gate actually fires in
    // practice for stadium-scale structures.
    //
    // Constant mirrored from dispatcher_pcg.cpp::kPCGMaxN. Declared here
    // rather than exported to keep the pcg-only knob local to the solver
    // layer; any change must be made in both files together.
    constexpr std::int64_t kPCGMaxN = 512LL * 512LL;
    if (buf.N() > kPCGMaxN) return false;
    // PCG tail activates once r/z/p/Ap/partialSums are allocated AND the
    // PCG pipelines are ready.
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
    // Residual-driven adaptive switching (M2o) — parity with
    // PFSFDispatcher.java's stall-ratio heuristic.
    //   prev = residual max two ticks ago
    //   curr = residual max one tick ago (readback in the prior tick)
    //   stalled = curr / prev > RESIDUAL_STALL_RATIO (0.95)
    // Stalled → RBGS has no marginal gain, jump straight into PCG.
    // Not stalled → let RBGS consume the budget minus MIN_PCG_STEPS.
    if (steps >= PCG_MIN_RBGS + PCG_MIN_STEPS) {
        constexpr float RESIDUAL_STALL_RATIO = 0.95f;
        const float prev  = buf.prev_max_macro_residual;
        const float curr  = buf.last_max_macro_residual;
        const float ratio = (prev > 1e-10f) ? (curr / prev) : 0.0f;
        const bool  stalled = ratio > RESIDUAL_STALL_RATIO;

        // Lazy-allocate PCG buffers on first stall — but only when PCG is
        // actually enabled (R4): allocating VRAM we'll never dispatch onto
        // is a silent budget drain.
        if (stalled && pcg_enabled_ && !buf.hasPCGBuffers() && pcg_.isReady()) {
            buf.allocatePCG(vk_);
        }

        if (supportsPCG(buf)) {
            constexpr int MIN_PCG_STEPS = PCG_MIN_STEPS;
            const int maxRbgs   = std::max(PCG_MIN_RBGS, steps - MIN_PCG_STEPS);
            const int rbgsSteps = stalled ? PCG_MIN_RBGS : maxRbgs;
            const int pcgSteps  = steps - rbgsSteps;

            // PR#187 capy-ai R10: the Java PFSFDispatcher keeps MG_INTERVAL
            // cadence even when PCG is enabled (coarse-grid correction is
            // applied inside the RBGS half before the PCG tail). Without
            // this, large islands lose the coarse correction and diverge
            // from the reference schedule once PCG buffers are allocated.
            for (int k = 0; k < rbgsSteps; ++k) {
                if (tryRecordVCycleAt(cmd, buf, pool, k, mgAvailable, recorded)) {
                    continue;
                }
                rbgs_.recordStep(cmd, buf, pool, chebyshevDamping(buf.chebyshev_iter));
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
    }

    // Pure RBGS + V-Cycle fallback — identical cadence to the Java path.
    // At MG_INTERVAL boundaries (k > 0 && k % MG_INTERVAL == 0) the
    // dispatcher runs a full V-Cycle sweep (pre-smooth + restrict +
    // coarse RBGS ×4 + prolong + post-smooth) instead of a single RBGS.
    // The V-Cycle lazily allocates multigrid buffers on first use; if
    // allocation fails we fall back to a plain RBGS for that iteration
    // so the fine-grid solve still makes progress.
    for (int k = 0; k < steps; ++k) {
        if (tryRecordVCycleAt(cmd, buf, pool, k, mgAvailable, recorded)) {
            continue;
        }
        rbgs_.recordStep(cmd, buf, pool, chebyshevDamping(buf.chebyshev_iter));
        computeBarrier(cmd);
        ++recorded;
    }
    return recorded;
}

bool Dispatcher::tryRecordVCycleAt(VkCommandBuffer cmd, IslandBuffer& buf,
                                    VkDescriptorPool pool, int k,
                                    bool mgAvailable, int& recorded) {
    if (!(k > 0 && (k % MG_INTERVAL) == 0 && mgAvailable)) return false;

    // Track upload success — a transient staging/cmd-buffer failure
    // would otherwise leave coarse buffers with stale or zero-filled
    // conductivity/type, silently producing an incorrect coarse solve
    // instead of falling back to fine-grid RBGS for this iteration.
    bool mgDataOk = buf.hasMultigridL1();
    if (!mgDataOk) {
        if (buf.allocateMultigrid(vk_)) {
            // Populate coarse conductivity/type from fine-grid data so
            // the first V-cycle doesn't run against zero-filled buffers.
            mgDataOk = buf.uploadMultigridData(vk_);
        }
    } else if (buf.mg_coarse_dirty) {
        // PR#187 capy-ai R9: the fine-grid cond/type were re-uploaded this
        // tick (or on a previous dirty tick whose MG refresh we missed).
        // Re-run uploadMultigridData so the V-cycle does not apply coarse
        // correction with stale material/anchor snapshots. uploadMultigridData
        // clears the flag on success; on transient failure we leave the
        // flag set so the next MG_INTERVAL hit retries, and skip the V-cycle
        // this iteration so we fall back to plain RBGS.
        if (!buf.uploadMultigridData(vk_)) {
            mgDataOk = false;
        }
    }
    if (mgDataOk && buf.hasMultigridL1()) {
        const int vcRecorded = recordVCycle(cmd, buf, pool);
        if (vcRecorded > 0) {
            recorded += vcRecorded;
            // V-Cycle advanced chebyshev_iter inside rbgs_.recordStep;
            // no extra bookkeeping here.
            return true;
        }
    }
    return false;
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

    // PR#187 capy-ai R32: recordScatter has several silent early-return
    // paths (pipeline null, buffer missing, descriptor-set alloc failure).
    // Previously we ignored its result and always returned true, so
    // notifySparseUpdates would skip the full-upload fallback and the
    // Java side's sparse-update queue — already drained — would lose the
    // edits. Propagate the bool so the engine falls back to a full
    // uploadFromHosts next tick (via markDirty in the caller).
    if (!sparse_.recordScatter(cmd, buf, pool, count)) return false;
    // The scatter shader writes source/cond/type/maxPhi/rcomp/rtens — any
    // subsequent RBGS/failure_scan dispatch reads those, so we must gate
    // the command stream with a compute→compute barrier.
    computeBarrier(cmd);
    return true;
}

} // namespace pfsf
