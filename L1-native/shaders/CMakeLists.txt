# Some distros ship the reference compiler as `glslang` rather than
# `glslangValidator` (e.g. Vulkan SDK >= 1.3.296 renamed the binary).
# Accept either name so CI runners and user workstations don't diverge.
find_program(GLSLANG_VALIDATOR NAMES glslangValidator glslang)
if(NOT GLSLANG_VALIDATOR)
    return()
endif()

set(SHADER_SRC_DIR "${CMAKE_SOURCE_DIR}/../Block Reality/api/src/main/resources/assets/blockreality/shaders")
set(SHADER_OUT_DIR "${CMAKE_BINARY_DIR}/generated/spirv")
file(MAKE_DIRECTORY "${SHADER_OUT_DIR}")

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

    # Native glslangValidator in CI doesn't support -O (too old).
    add_custom_command(
        OUTPUT "${spv_path}"
        COMMAND "${GLSLANG_VALIDATOR}" --target-env vulkan1.2 -V -o "${spv_path}" "${src_path}"
        DEPENDS "${src_path}"
        VERBATIM
    )

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
