# Block Reality Multi-Agent Roles

This document defines the specialist agents for the Block Reality project.
Each agent is designed to own a specific layer or domain.

## Available Roles

| Role | Domain | Primary Directories |
|------|--------|---------------------|
| `architect` | Cross-stack architecture, SPI, docs sync | `docs/`, `Block Reality/*/src/main/java/com/blockreality/api/spi/` |
| `java-mod` | Minecraft Forge mod (Java) | `Block Reality/api/`, `Block Reality/fastdesign/` |
| `ml-pipeline` | Python ML training & ONNX | `brml/` |
| `cpp-gpu` | C++ Vulkan / PFSF / NRD | `libpfsf/`, `Block Reality/api/src/main/native/` |
| `doc-sync` | Documentation maintenance | `docs/`, `CLAUDE.md`, `AGENTS.md` |

## Role Details

### `architect`

- Reviews module boundaries (`api` vs `fastdesign` dependency direction).
- Ensures SPI contracts remain stable.
- Points out which documents must be updated for any structural change.
- Validates that new features do not violate the single-direction dependency rule.

### `java-mod`

- Deep expertise in Minecraft Forge 1.20.1, Java 17, Official Mappings.
- Knows the PFSF GPU pipeline, material system, collapse manager, blueprint I/O, and network packets.
- Enforces `@OnlyIn(Dist.CLIENT)` on client-only classes.
- Keeps network packet references free of direct client-class imports (uses FQN or lazy resolution).
- Runs `./gradlew build` and `./gradlew test` to validate changes.

### `ml-pipeline`

- Deep expertise in JAX/Flax, Optax, scipy, ONNX Runtime.
- Works in `brml/` (FNO3D, CollapsePredictor, NodeRecommender, FEM ground-truth).
- Follows `ruff` formatting (line length 100) and target `py310`.
- Validates forward-pass shapes and ONNX contracts after changes.
- Runs `pytest brml/tests/`.

### `cpp-gpu`

- Deep expertise in C++17/20, Vulkan Compute, CMake.
- Maintains 26-connectivity stencil consistency across PFSF shaders.
- Ensures `hField` write rules (only smoother writes, phase_field_evolve reads).
- Validates `sigmaMax` normalization in all threshold buffers.
- Builds `libpfsf/` and the NRD JNI bridge (`api/src/main/native/`).

### `doc-sync`

- Maintains `AGENTS.md`, `CLAUDE.md`, and the `docs/L1-*/L2-*/L3-*` hierarchy.
- When code changes affect documented behavior, updates the corresponding L3 and indexes.
- Ensures architecture rules (dependency direction, client/server split, sigmaMax rules) are accurately reflected in docs.
