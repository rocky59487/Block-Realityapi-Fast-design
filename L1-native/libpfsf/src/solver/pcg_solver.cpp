/**
 * @file pcg_solver.cpp
 * @brief PCG — pipelines only (dispatch sequencing lives in the dispatcher).
 */
#include "pcg_solver.h"
#include "core/vulkan_context.h"
#include "br_core/compute_pipeline.h"

#include <cstdio>
#include <vector>

namespace pfsf {

namespace {
// Binding tables mirror each GLSL shader byte-for-byte.
std::vector<br_core::DescriptorBinding> matvecBindings() {
    return {
        { 0, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER },  // inputVec (p)
        { 1, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER },  // outputVec (Ap)
        { 2, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER },  // sigma (cond 6N SoA)
        { 3, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER },  // vtype
    };
}

std::vector<br_core::DescriptorBinding> updateBindings() {
    return {
        { 0, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER },  // phi
        { 1, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER },  // r
        { 2, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER },  // p
        { 3, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER },  // Ap
        { 4, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER },  // source
        { 5, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER },  // vtype
        { 6, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER },  // partialSums
        { 7, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER },  // reductionBuf
        { 8, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER },  // sigma
    };
}

std::vector<br_core::DescriptorBinding> directionBindings() {
    return {
        { 0, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER },  // r
        { 1, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER },  // p
        { 2, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER },  // vtype
        { 3, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER },  // reductionBuf
        { 4, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER },  // sigma
    };
}

std::vector<br_core::DescriptorBinding> dotBindings() {
    return {
        { 0, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER },  // vecA
        { 1, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER },  // vecB
        { 2, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER },  // partials
    };
}
} // namespace

PCGSolver::PCGSolver(VulkanContext& vk) : vk_(vk) {}

PCGSolver::~PCGSolver() { destroyPipelines(); }

bool PCGSolver::createPipelines() {
    if (isReady()) return true;

    matvec_ = br_core::build_compute_pipeline(
            "compute/pfsf/pcg_matvec.comp", matvecBindings(),
            { 0, sizeof(PCGMatvecPushConstants) });

    update_ = br_core::build_compute_pipeline(
            "compute/pfsf/pcg_update.comp", updateBindings(),
            { 0, sizeof(PCGUpdatePushConstants) });

    direction_ = br_core::build_compute_pipeline(
            "compute/pfsf/pcg_direction.comp", directionBindings(),
            { 0, sizeof(PCGDirectionPushConstants) });

    dot_ = br_core::build_compute_pipeline(
            "compute/pfsf/pcg_dot.comp", dotBindings(),
            { 0, sizeof(PCGDotPushConstants) });

    if (!isReady()) {
        std::fprintf(stderr, "[libpfsf] PCG createPipelines: one or more blobs missing "
                             "(matvec=%p update=%p direction=%p dot=%p)\n",
                     (void*)matvec_.pipeline,  (void*)update_.pipeline,
                     (void*)direction_.pipeline, (void*)dot_.pipeline);
        destroyPipelines();
        return false;
    }
    return true;
}

void PCGSolver::destroyPipelines() {
    br_core::destroy_compute_pipeline(matvec_);
    br_core::destroy_compute_pipeline(update_);
    br_core::destroy_compute_pipeline(direction_);
    br_core::destroy_compute_pipeline(dot_);
}

} // namespace pfsf
