# ─────────────────────────────────────────────────────────────
#  pack_spirv.cmake — translate a .spv file into a constexpr
#  uint32_t[] header. Invoked per-shader by shaders/CMakeLists.txt.
#
#  Usage (via `cmake -P`):
#    cmake -P pack_spirv.cmake -- <spv> <hdr> <symbol> <canonical-name>
# ─────────────────────────────────────────────────────────────

# CMake passes -P's args as CMAKE_ARGV0..N. Skip the first three
# (cmake, -P, script path, --).
set(i 0)
while(i LESS ${CMAKE_ARGC})
    if(CMAKE_ARGV${i} STREQUAL "--")
        math(EXPR start "${i} + 1")
        break()
    endif()
    math(EXPR i "${i} + 1")
endwhile()

math(EXPR a0 "${start} + 0")
math(EXPR a1 "${start} + 1")
math(EXPR a2 "${start} + 2")
math(EXPR a3 "${start} + 3")
set(SPV_PATH    "${CMAKE_ARGV${a0}}")
set(HDR_PATH    "${CMAKE_ARGV${a1}}")
set(SYM         "${CMAKE_ARGV${a2}}")
set(CANONICAL   "${CMAKE_ARGV${a3}}")

file(READ "${SPV_PATH}" HEX HEX)
string(LENGTH "${HEX}" n_chars)
math(EXPR n_bytes "${n_chars} / 2")
math(EXPR n_words "${n_bytes} / 4")

# Emit words in little-endian order (SPIR-V spec is LE).
set(BODY "// AUTO-GENERATED — do not edit.\n#pragma once\n#include <cstdint>\n\n")
string(APPEND BODY "// shader: ${CANONICAL}\n")
string(APPEND BODY "constexpr std::uint32_t ${SYM}_spv[] = {\n")

set(j 0)
set(word_index 0)
while(word_index LESS n_words)
    math(EXPR off "${word_index} * 8")
    string(SUBSTRING "${HEX}" ${off} 8 w)
    string(SUBSTRING "${w}" 0 2 b0)
    string(SUBSTRING "${w}" 2 2 b1)
    string(SUBSTRING "${w}" 4 2 b2)
    string(SUBSTRING "${w}" 6 2 b3)
    string(APPEND BODY "    0x${b3}${b2}${b1}${b0}u,\n")
    math(EXPR word_index "${word_index} + 1")
endwhile()

string(APPEND BODY "};\n")
string(APPEND BODY "constexpr std::uint32_t ${SYM}_spv_word_count = ${n_words}u;\n")

file(WRITE "${HDR_PATH}" "${BODY}")
