find_program(GLSLANG_VALIDATOR glslangValidator)
if(NOT GLSLANG_VALIDATOR)
    return()
endif()

set(SHADER_SRC_DIR "${CMAKE_SOURCE_DIR}/../Block Reality/api/src/main/resources/assets/blockreality/shaders")
set(SHADER_OUT_DIR "${CMAKE_BINARY_DIR}/generated/spirv")
file(MAKE_DIRECTORY "${SHADER_OUT_DIR}")

file(GLOB_RECURSE SHADER_GLSLS RELATIVE "${SHADER_SRC_DIR}" "${SHADER_SRC_DIR}/*.comp.glsl")

set(FILTERED_SHADERS "")
foreach(rel IN LISTS SHADER_GLSLS)
    if(rel MATCHES "^rt/" OR rel MATCHES "^compute/fluid/" OR rel MATCHES "^lod/" OR rel MATCHES "relax_temporal" OR rel MATCHES "fsr_upscale" OR rel MATCHES "omm_classify")
        continue()
    endif()
    list(APPEND FILTERED_SHADERS "${rel}")
endforeach()

set(REGISTRY_ENTRIES "")
set(GENERATED_HEADERS "")

foreach(rel IN LISTS FILTERED_SHADERS)
    get_filename_component(stem "${rel}" NAME_WE)
    get_filename_component(dir "${rel}" DIRECTORY)
    string(REPLACE "/" "_" flat "${dir}_${stem}")
    string(REGEX REPLACE "^_+" "" flat "${flat}")
    string(REGEX REPLACE "[^a-zA-Z0-9]" "_" flat "${flat}")
    string(REGEX REPLACE "\\.glsl$" "" canonical "${rel}")

    set(src_path "${SHADER_SRC_DIR}/${rel}")
    set(spv_path "${SHADER_OUT_DIR}/${flat}.spv")
    set(hdr_path "${SHADER_OUT_DIR}/${flat}.h")

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
set(REG_BODY "#include \"br_core/spirv_registry.h\"\n#include \"br_core/br_core.h\"\n")

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
