#!/usr/bin/env bash
# emit_prebuilt_spirv.sh — compile every GLSL shader under
# Block Reality/api/src/main/resources/assets/blockreality/shaders/
# into a committed .spv blob under L1-native/shaders/prebuilt/.
#
# Used for:
#   (a) dev machines without the Vulkan SDK
#       (build with -DPFSF_ALLOW_PREBUILT_SPV=ON)
#   (b) CI drift detection
#       (.github/workflows/shaders-drift.yml runs this on every PR
#        and fails if any diff appears)
#
# Requires: glslangValidator (or glslang) on PATH. We pin the
# version on CI runners via workflow so the bytecode matches.

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SRC_DIR="${ROOT}/Block Reality/api/src/main/resources/assets/blockreality/shaders"
OUT_DIR="${ROOT}/L1-native/shaders/prebuilt"

if ! command -v glslangValidator >/dev/null 2>&1 \
        && ! command -v glslang >/dev/null 2>&1; then
    echo "error: need glslangValidator or glslang on PATH" >&2
    exit 2
fi
GLSLANG="$(command -v glslangValidator || command -v glslang)"

# Match CMakeLists.txt blacklist: skip rt/, compute/fluid/, relax_temporal.
skip_rel() {
    case "$1" in
        rt/*|compute/fluid/*|*relax_temporal*) return 0 ;;
        *) return 1 ;;
    esac
}

rm -rf "${OUT_DIR}"
mkdir -p "${OUT_DIR}"

count=0
cd "${SRC_DIR}"
while IFS= read -r -d '' rel; do
    rel="${rel#./}"
    if skip_rel "${rel}"; then
        continue
    fi
    base="${rel%.glsl}"
    dest="${OUT_DIR}/${base}.spv"
    mkdir -p "$(dirname "${dest}")"
    "${GLSLANG}" --target-env vulkan1.2 -V -o "${dest}" "${rel}"
    count=$((count + 1))
done < <(find . \( \
        -name '*.comp.glsl' \
     -o -name '*.vert.glsl' \
     -o -name '*.frag.glsl' \
     -o -name '*.rgen.glsl' \
     -o -name '*.rchit.glsl' \
     -o -name '*.rahit.glsl' \
     -o -name '*.rmiss.glsl' \
    \) -print0)

echo "compiled ${count} shader(s) → ${OUT_DIR}"
