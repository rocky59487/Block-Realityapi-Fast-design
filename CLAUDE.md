# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Block Reality — a Minecraft Forge 1.20.1 structural physics simulation engine. Two Gradle subprojects (`api`, `fastdesign`) plus a TypeScript sidecar (`MctoNurbs-review`). The user's primary language is Traditional Chinese.

## Build & Run Commands

All Gradle commands run from `Block Reality/`:

```bash
cd "Block Reality"

# Build
./gradlew build                      # Full build (both modules)
./gradlew mergedJar                  # Merged mpd.jar → project root (ready for mods/)
./gradlew :api:jar                   # API module only
./gradlew :fastdesign:jar            # Fast Design module only

# Run Minecraft
./gradlew :api:runClient             # API-only client
./gradlew :fastdesign:runClient      # Fast Design + API client
./gradlew :api:runServer             # Dedicated server

# Deploy to PrismLauncher dev instance
./gradlew :api:copyToDevInstance
./gradlew :fastdesign:copyToDevInstance

# Tests (JUnit 5)
./gradlew test                       # All Java tests
./gradlew :api:test                  # API tests only
./gradlew :api:test --tests "com.blockreality.api.physics.ForceEquilibriumSolverTest"  # Single test class
```

TypeScript sidecar commands from `MctoNurbs-review/`:

```bash
cd MctoNurbs-review
npm install                          # Install dependencies
npm run build                        # Compile TS → dist/sidecar.js
npm test                             # Run vitest
npm run test:watch                   # Watch mode
npm start                            # Start RPC server
```

The sidecar is auto-built during `fastdesign:processResources` — no manual step needed for dev runs.

## Architecture

```
api/  (com.blockreality.api)           ← Foundation layer, standalone mod
  physics/       UnionFind connectivity, ForceEquilibriumSolver (SOR), BeamStressEngine (Euler buckling)
  material/      BlockTypeRegistry, DefaultMaterial (concrete/steel/timber/brick/glass/bedrock),
                 CustomMaterial.Builder, DynamicMaterial (RC fusion 97/3)
  blueprint/     Blueprint ↔ NBT serialization, BlueprintIO file I/O
  collapse/      CollapseManager — triggers destruction when physics fails
  chisel/        10×10×10 voxel sub-block shape system
  sph/           SPH particle effects for collapse visuals
  sidecar/       SidecarBridge — stdio IPC to TypeScript process
  client/render/ GreedyMesher, AnimationEngine, RenderPipeline, ClientStressCache

fastdesign/  (com.blockreality.fastdesign)  ← Extension layer, depends on :api
  client/        3D hologram preview, HUD overlay, GUI screens
  construction/  Rebar placement system
  sidecar/       NURBS/STEP export pipeline (delegates to MctoNurbs sidecar)

MctoNurbs-review/                    ← TypeScript sidecar (Node.js)
  src/pipeline.ts    NURBS export pipeline
  src/rpc-server.ts  Stdio RPC server for Java ↔ TS communication
  src/greedy-mesh.ts Mesh optimization
  Uses opencascade.js for CAD kernel operations
```

**Dependency direction**: `fastdesign` → `api` (never the reverse). The sidecar communicates with Java via stdio RPC through `SidecarBridge`.

## Key Conventions

- **Java 17** toolchain, **Gradle 8.8** wrapper, daemon disabled, 3GB heap (`-Xmx3G`)
- **Forge 1.20.1** (47.2.0) with **Official Mappings**
- All Java source uses **UTF-8** encoding
- Tests use **JUnit 5** (Jupiter) — `useJUnitPlatform()` in build.gradle
- Physics values use real engineering units (MPa for strength, GPa for Young's modulus, kg/m³ for density)
- Access Transformer config at `api/src/main/resources/META-INF/accesstransformer.cfg`
- Mod metadata in `api/src/main/resources/META-INF/mods.toml`; merged version in `Block Reality/merged-resources/`
