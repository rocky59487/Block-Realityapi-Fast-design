# ────────────────────────────────────────────────────────────────
#  br_shaders — SPIR-V blob registry packaged as a static library.
# ────────────────────────────────────────────────────────────────
#
#  Sources: Block Reality/api/src/main/resources/assets/blockreality/
#           shaders/**/*.{comp,vert,frag,rgen,rchit,rahit,rmiss}.glsl
#
#  Two build paths:
#
#    1. glslangValidator is on PATH  (normal dev + CI build)
#       → compile each GLSL to SPIR-V, pack as constexpr uint32[],
#         register via SpirvRegistry.
#
#    2. glslang is missing AND -DPFSF_ALLOW_PREBUILT_SPV=ON
#       → read pre-compiled L1-native/shaders/prebuilt/<path>.spv
#         files and pack those instead. CI ships prebuilt binaries
#         so devs without the Vulkan SDK can still build libpfsf.
#         `.github/workflows/shaders-drift.yml` guards them against
#         drift on every PR.
#
#  If neither path is available we FATAL_ERROR. Silently skipping the
#  target (as the pre-v0.3e.1 code did) left `br_shaders` undefined,
#  so `build_compute_pipeline()` returned null at runtime and the
#  solver aborted on first tick — we'd rather refuse to link than
#  ship a broken library.
#

# Some distros ship the reference compiler as `glslang` rather than
# `glslangValidator` (e.g. Vulkan SDK >= 1.3.296 renamed the binary).
# Accept either name so CI runners and user workstations don't diverge.
find_program(GLSLANG_VALIDATOR NAMES glslangValidator glslang)

option(PFSF_ALLOW_PREBUILT_SPV "Fall back to L1-native/shaders/prebuilt/*.spv when glslang is missing" OFF)

set(SHADER_SRC_DIR      "${CMAKE_SOURCE_DIR}/../Block Reality/api/src/main/resources/assets/blockreality/shaders")
set(SHADER_PREBUILT_DIR "${CMAKE_CURRENT_SOURCE_DIR}/prebuilt")
set(SHADER_OUT_DIR      "${CMAKE_BINARY_DIR}/generated/spirv")
file(MAKE_DIRECTORY "${SHADER_OUT_DIR}")

set(_have_glslang OFF)
set(_use_prebuilt OFF)
if(GLSLANG_VALIDATOR)
    set(_have_glslang ON)
elseif(PFSF_ALLOW_PREBUILT_SPV AND EXISTS "${SHADER_PREBUILT_DIR}")
    set(_use_prebuilt ON)
    message(STATUS "br_shaders: glslang not found; using prebuilt SPIR-V at ${SHADER_PREBUILT_DIR}")
else()
    message(FATAL_ERROR
        "br_shaders: glslang/glslangValidator not found on PATH and "
        "PFSF_ALLOW_PREBUILT_SPV is OFF. Install the Vulkan SDK, or pass "
        "-DPFSF_ALLOW_PREBUILT_SPV=ON with prebuilt blobs committed at "
        "L1-native/shaders/prebuilt/.")
endif()

# Supported shader extensions for v0.3c. CONFIGURE_DEPENDS asks CMake to
# re-run this glob on every build so that adding or removing a shader
# triggers reconfiguration — without it, newly-added shaders would be
# silently omitted from br_shaders until someone manually re-ran cmake.
file(GLOB_RECURSE SHADER_GLSLS
    CONFIGURE_DEPENDS
    RELATIVE "${SHADER_SRC_DIR}"
    "${SHADER_SRC_DIR}/*.comp.glsl"
    "${SHADER_SRC_DIR}/*.vert.glsl"
    "${SHADER_SRC_DIR}/*.frag.glsl"
    "${SHADER_SRC_DIR}/*.rgen.glsl"
    "${SHADER_SRC_DIR}/*.rchit.glsl"
    "${SHADER_SRC_DIR}/*.rahit.glsl"
    "${SHADER_SRC_DIR}/*.rmiss.glsl"
)

set(FILTERED_SHADERS "")
foreach(rel IN LISTS SHADER_GLSLS)
    # Capy: Blacklist shaders known to have syntax errors or use
    # unsupported extensions in the older CI glslangValidator.
    if(rel MATCHES "^rt/" OR rel MATCHES "^compute/fluid/" OR rel MATCHES "relax_temporal")
        continue()
    endif()
    list(APPEND FILTERED_SHADERS "${rel}")
endforeach()

set(REGISTRY_ENTRIES "")
set(GENERATED_HEADERS "")

foreach(rel IN LISTS FILTERED_SHADERS)
    # Generate a safe C++ identifier from the relative path.
    # E.g. "compute/pfsf/rbgs_smooth.comp.glsl" -> "compute_pfsf_rbgs_smooth_comp"
    string(REGEX REPLACE "\\.glsl$" "" base "${rel}")
    string(REGEX REPLACE "[^a-zA-Z0-9]" "_" flat "${base}")
    string(REGEX REPLACE "^_+" "" flat "${flat}")

    # The canonical name for registry lookup (matches Java assets path)
    string(REGEX REPLACE "\\.glsl$" "" canonical "${rel}")

    set(src_path "${SHADER_SRC_DIR}/${rel}")
    set(spv_path "${SHADER_OUT_DIR}/${flat}.spv")
    set(hdr_path "${SHADER_OUT_DIR}/${flat}.h")

    if(_have_glslang)
        # Native glslangValidator in CI doesn't support -O (too old).
        add_custom_command(
            OUTPUT "${spv_path}"
            COMMAND "${GLSLANG_VALIDATOR}" --target-env vulkan1.2 -V -o "${spv_path}" "${src_path}"
            DEPENDS "${src_path}"
            VERBATIM
        )
    else()
        # Prebuilt path: the committed .spv already sits next to the GLSL
        # in the prebuilt tree, keyed by the same relative path.
        set(prebuilt_spv "${SHADER_PREBUILT_DIR}/${base}.spv")
        if(NOT EXISTS "${prebuilt_spv}")
            message(FATAL_ERROR
                "br_shaders: PFSF_ALLOW_PREBUILT_SPV=ON but missing prebuilt "
                "blob for ${rel} at ${prebuilt_spv}. Run "
                "scripts/emit_prebuilt_spirv.sh to refresh committed blobs.")
        endif()
        add_custom_command(
            OUTPUT "${spv_path}"
            COMMAND "${CMAKE_COMMAND}" -E copy_if_different "${prebuilt_spv}" "${spv_path}"
            DEPENDS "${prebuilt_spv}"
            VERBATIM
        )
    endif()

    add_custom_command(
        OUTPUT "${hdr_path}"
        COMMAND "${CMAKE_COMMAND}" -P "${CMAKE_CURRENT_SOURCE_DIR}/pack_spirv.cmake" -- "${spv_path}" "${hdr_path}" "${flat}" "${canonical}"
        DEPENDS "${spv_path}" "${CMAKE_CURRENT_SOURCE_DIR}/pack_spirv.cmake"
        VERBATIM
    )

    list(APPEND GENERATED_HEADERS "${hdr_path}")
    list(APPEND REGISTRY_ENTRIES "${flat}|${canonical}")
endforeach()

set(REGISTRY_TU "${SHADER_OUT_DIR}/_spirv_registry_autogen.cpp")
set(REG_BODY "#include \"br_core/spirv_registry.h\"\n")

foreach(entry IN LISTS REGISTRY_ENTRIES)
    string(REPLACE "|" ";" parts "${entry}")
    list(GET parts 0 flat)
    string(APPEND REG_BODY "#include \"${flat}.h\"\n")
endforeach()

string(APPEND REG_BODY "\nnamespace {\nstruct _SpirvAutoRegister {\n  _SpirvAutoRegister() {\n")
foreach(entry IN LISTS REGISTRY_ENTRIES)
    string(REPLACE "|" ";" parts "${entry}")
    list(GET parts 0 flat)
    list(GET parts 1 rel)
    string(APPEND REG_BODY "    br_core::SpirvRegistry::add_deferred_blob(\"${rel}\", ${flat}_spv, ${flat}_spv_word_count);\n")
endforeach()
string(APPEND REG_BODY "  }\n};\nstatic _SpirvAutoRegister _spirv_auto_register{};\n}\n")

file(WRITE "${REGISTRY_TU}" "${REG_BODY}")

add_library(br_shaders STATIC ${GENERATED_HEADERS} "${REGISTRY_TU}")
target_include_directories(br_shaders PRIVATE "${SHADER_OUT_DIR}")
target_link_libraries(br_shaders PUBLIC br_core)
set_target_properties(br_shaders PROPERTIES LINKER_LANGUAGE CXX POSITION_INDEPENDENT_CODE ON)
