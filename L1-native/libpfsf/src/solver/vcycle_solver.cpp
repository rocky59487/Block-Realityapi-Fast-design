#include "vcycle_solver.h"
#include "core/vulkan_context.h"
#include "br_core/compute_pipeline.h"

#include <cstdio>
#include <vector>

namespace pfsf {

namespace {
std::vector<br_core::PipelineLayoutBinding> restrictBindings() {
    return {
        { 0, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER },  // phi_fine
        { 1, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER },  // rho_fine
        { 2, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER },  // sigma_fine
        { 3, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER },  // vtype_fine
        { 4, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER },  // phi_coarse
        { 5, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER },  // rho_coarse
    };
}

std::vector<br_core::PipelineLayoutBinding> prolongBindings() {
    return {
        { 0, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER },  // phi_fine
        { 1, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER },  // correction_coarse
    };
}

std::vector<br_core::PipelineLayoutBinding> coarseRBGSBindings() {
    // Mirrors jacobi_smooth.comp.glsl set=0 bindings 0..5.
    return {
        { 0, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER },  // phi (in-place)
        { 1, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER },  // phi_prev
        { 2, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER },  // source
        { 3, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER },  // conductivity (SoA x6)
        { 4, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER },  // type
        { 5, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER },  // hField (optional; bound
                                                   // to phi when phase-field
                                                   // feature is off)
    };
}
} // namespace

VCycleSolver::VCycleSolver(VulkanContext& vk) : vk_(vk) {}
VCycleSolver::~VCycleSolver() { destroyPipeline(); }

bool VCycleSolver::createPipeline() {
    if (isReady()) return true;

    restrict_ = br_core::build_compute_pipeline(vk_.device(), VK_NULL_HANDLE, 
            "compute/pfsf/mg_restrict.comp", restrictBindings(),
            { 0, sizeof(MGPushConstants) });

    prolong_  = br_core::build_compute_pipeline(vk_.device(), VK_NULL_HANDLE, 
            "compute/pfsf/mg_prolong.comp", prolongBindings(),
            { 0, sizeof(MGPushConstants) });

    // Coarse-grid smoother uses jacobi_smooth.comp ??26-connectivity with
    // shared-memory tiling (CLAUDE.md marks this as the multigrid coarse-
    // level smoother; the fine grid uses rbgs_smooth.comp).
    coarse_rbgs_ = br_core::build_compute_pipeline(vk_.device(), VK_NULL_HANDLE, 
            "compute/pfsf/jacobi_smooth.comp", coarseRBGSBindings(),
            { 0, sizeof(CoarseRBGSPushConstants) });

    if (!isReady()) {
        std::fprintf(stderr, "[libpfsf] V-cycle createPipeline: blobs missing "
                             "(restrict=%p prolong=%p coarse_rbgs=%p)\n",
                     (void*)restrict_.pipeline, (void*)prolong_.pipeline,
                     (void*)coarse_rbgs_.pipeline);
        destroyPipeline();
        return false;
    }
    return true;
}

void VCycleSolver::destroyPipeline() {
    br_core::destroy_compute_pipeline(vk_.device(), restrict_);
    br_core::destroy_compute_pipeline(vk_.device(), prolong_);
    br_core::destroy_compute_pipeline(vk_.device(), coarse_rbgs_);
}

} // namespace pfsf
