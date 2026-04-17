#include "vcycle_solver.h"
#include "core/vulkan_context.h"
#include "br_core/compute_pipeline.h"

#include <cstdio>
#include <vector>

namespace pfsf {

namespace {
std::vector<br_core::DescriptorBinding> restrictBindings() {
    return {
        { 0, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER },  // phi_fine
        { 1, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER },  // rho_fine
        { 2, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER },  // sigma_fine
        { 3, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER },  // vtype_fine
        { 4, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER },  // phi_coarse
        { 5, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER },  // rho_coarse
    };
}

std::vector<br_core::DescriptorBinding> prolongBindings() {
    return {
        { 0, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER },  // phi_fine
        { 1, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER },  // correction_coarse
    };
}
} // namespace

VCycleSolver::VCycleSolver(VulkanContext& vk) : vk_(vk) {}
VCycleSolver::~VCycleSolver() { destroyPipeline(); }

bool VCycleSolver::createPipeline() {
    if (isReady()) return true;

    restrict_ = br_core::build_compute_pipeline(
            "compute/pfsf/mg_restrict.comp", restrictBindings(),
            { 0, sizeof(MGPushConstants) });

    prolong_  = br_core::build_compute_pipeline(
            "compute/pfsf/mg_prolong.comp", prolongBindings(),
            { 0, sizeof(MGPushConstants) });

    if (!isReady()) {
        std::fprintf(stderr, "[libpfsf] V-cycle createPipeline: blobs missing "
                             "(restrict=%p prolong=%p)\n",
                     (void*)restrict_.pipeline, (void*)prolong_.pipeline);
        destroyPipeline();
        return false;
    }
    return true;
}

void VCycleSolver::destroyPipeline() {
    br_core::destroy_compute_pipeline(restrict_);
    br_core::destroy_compute_pipeline(prolong_);
}

} // namespace pfsf
