# Block Reality: Citations and References Report
# Block Reality：參考文獻與學術引用報告

This document outlines the academic literature, algorithms, and technical whitepapers that form the mathematical and theoretical foundation of the Block Reality structural physics engine and rendering pipeline.

本文檔列出了構成 Block Reality 結構物理引擎與渲染管線之數學與理論基礎的學術文獻、演算法與技術白皮書。

---

## 1. Physics Engine & Potential Field Structure Failure (PFSF)
### 物理引擎與勢場結構失效

The core of Block Reality is the PFSF engine, which translates structural mechanics into sparse potential field diffusion problems evaluated via compute shaders.

- **Ambati, M., Gerasimov, T., & De Lorenzis, L. (2015)**
  *A review on phase-field models of brittle fracture and a new fast hybrid formulation.* Computational Mechanics, 55(2), 383-405.
  > **Application:** Provides the basis for the phase-field fracture mechanics integrated into PFSF v2.1, enabling mathematically robust crack propagation, structural yielding, and collapse detection.

- **Briggs, W. L., Henson, V. E., & McCormick, S. F. (2000)**
  *A Multigrid Tutorial (2nd Ed.).* SIAM.
  > **Application:** Foundational concepts for the **W-Cycle Multigrid** approach implemented in the GPU solver to vastly improve convergence rates over legacy Jacobi iterations for sparse block matrices.

- **Hackbusch, W. (1985)**
  *Multi-Grid Methods and Applications.* Springer.
  > **Application:** Explains the **Red-Black Gauss-Seidel (RBGS) 8-color in-place smoothing** solver, which replaces standard iterations to allow safe, lock-free parallel execution on GPU compute shaders.

## 2. Rendering Pipeline & Global Illumination
### 渲染管線與全局光照

Block Reality's renderer bypasses traditional rasterization when necessary, using Signed Distance Fields (SDF) and advanced culling.

- **Hart, J.C. (1996)**
  *Sphere Tracing: A Geometric Method for the Antialiased Ray Tracing of Implicit Surfaces.* The Visual Computer, 12(10), 527-545.
  > **Application:** The theoretical backbone for the `BRSDFRayMarcher`. Sphere tracing is used alongside the 3D SDF texture to compute Global Illumination (GI) and Ambient Occlusion (AO).

- **Rong, G., & Tan, T.-S. (2006)**
  *Jump Flooding in GPU with Applications to Voronoi Diagram and Distance Transform.* Proceedings of the 2006 Symposium on Interactive 3D Graphics and Games.
  > **Application:** The Jump Flooding Algorithm (JFA) is implemented in compute shaders to construct and dynamically update the 256³ R16F 3D SDF volume in real-time as blocks are placed or destroyed.

- **Karis, B., et al. (Epic Games, 2021)**
  *Nanite: A Deep Dive.* SIGGRAPH 2021 Advances in Real-Time Rendering in Games.
  > **Application:** Influenced the `BRMeshletEngine` and `SparseVoxelDAG` approaches for aggressive LODding and geometry clustering.

## 3. Surface Reconstruction & Voxel Export
### 表面重建與體素匯出

The `MctoNurbs` TypeScript sidecar relies on robust algorithms to convert discrete block data into continuous CAD-friendly B-Reps.

- **Ju, T., Losasso, F., Schaefer, S., & Warren, J. (2002)**
  *Dual Contouring of Hermite Data.* ACM Transactions on Graphics (SIGGRAPH 2002), 21(3), 339-346.
  > **Application:** Used in the `MctoNurbs` pipeline to reconstruct smooth surface meshes from voxel data prior to STEP conversion.

- **Garland, M., & Heckbert, P. S. (1997)**
  *Surface Simplification Using Quadric Error Metrics.* Proceedings of the 24th Annual Conference on Computer Graphics and Interactive Techniques.
  > **Application:** The **QEF (Quadratic Error Function) Solver** utilized in `src/dc/qef-solver.ts` employs Jacobi rotation iterations to calculate eigenvectors and accurately place mesh vertices at sharp features, bypassing mathematical instability in degenerate cases.

## 4. Fluid Dynamics & Particle Systems
### 流體動力學與粒子系統

- **Monaghan, J.J. (1992)**
  *Smoothed Particle Hydrodynamics.* Annual Review of Astronomy and Astrophysics, 30, 543-574.
  > **Application:** Defines the cubic spline kernels used in Block Reality's `api/sph/` SPH stress engine for fluid-like stress dissipation and particle effects.

- **Teschner, M., Heidelberger, B., Müller, M., Pomeranets, D., & Gross, M. (2003)**
  *Optimized Spatial Hashing for Collision Detection of Deformable Objects.* VMV 2003.
  > **Application:** Implemented in the particle system for highly efficient $O(1)$ neighbor searching across fluid particles via a spatial hash grid.
