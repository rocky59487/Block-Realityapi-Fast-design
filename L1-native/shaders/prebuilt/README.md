# Prebuilt SPIR-V Blobs

Fallback target for hosts without the Vulkan SDK. Used by:

- **dev machines**: pass `-DPFSF_ALLOW_PREBUILT_SPV=ON` at CMake time
  if `glslangValidator` is not on PATH.
- **CI**: `.github/workflows/shaders-drift.yml` regenerates these on
  every PR and fails if the committed blobs drift from the GLSL source.

## Refresh

```bash
# Install Vulkan SDK or `apt install glslang-tools`, then:
scripts/emit_prebuilt_spirv.sh
git add L1-native/shaders/prebuilt
```

SPIR-V is platform-independent, so a single committed copy serves
linux-x64, windows-x64, and macos-arm64. Runner glslang versions
are pinned on CI so blob bytes stay stable.

## Directory Layout

Mirrors the source tree under
`Block Reality/api/src/main/resources/assets/blockreality/shaders/`,
keyed by relative path with `.glsl` stripped:

```
<shader relpath>.spv
```

e.g. `compute/pfsf/rbgs_smooth.comp.glsl` → `compute/pfsf/rbgs_smooth.comp.spv`.

Shaders blacklisted in `L1-native/shaders/CMakeLists.txt` (`rt/*`,
`compute/fluid/*`, `*relax_temporal*`) are skipped here too.
