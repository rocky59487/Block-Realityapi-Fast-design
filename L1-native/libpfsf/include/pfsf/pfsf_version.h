/**
 * @file pfsf_version.h
 * @brief libpfsf ABI version & feature query surface (v0.3d Phase 0 — stub).
 *
 * Implementations land in Phase 7. This header exists now so downstream
 * callers can start wiring `pfsf_abi_version()` / `pfsf_has_feature()`
 * calls and receive a clean "unavailable" response until the real symbols
 * are compiled in.
 *
 * ABI layout (stable from v0.3d):
 *   pfsf_abi_version() → (MAJOR << 16) | (MINOR << 8) | PATCH
 *   Clients MUST bail out when (returned_major != expected_major).
 */
#ifndef PFSF_VERSION_H
#define PFSF_VERSION_H

#include "pfsf_types.h"

#ifdef __cplusplus
extern "C" {
#endif

/* Packed (MAJOR<<16)|(MINOR<<8)|PATCH. 0 == implementation not yet linked. */
PFSF_API uint32_t pfsf_abi_version(void);

/**
 * Feature probes — returns true when the named capability is compiled in.
 * Known names (v0.3d GA): "simd.avx2", "simd.avx512", "simd.neon",
 * "ml.features", "aug.thermal", "aug.cable", "aug.em", "aug.fusion",
 * "aug.wind3d", "aug.material_override", "aug.curing", "aug.loadpath",
 * "plan_buffer", "trace.ring".
 */
PFSF_API bool pfsf_has_feature(const char* name);

/**
 * Static build metadata — "<compiler> <flags> <git_sha> <utc_ts>".
 * Pointer is owned by libpfsf; do NOT free.
 */
PFSF_API const char* pfsf_build_info(void);

/**
 * The pinned external ABI contract version as declared in
 * pfsf_v1.abi.json (e.g. "1.2.0"). Unlike pfsf_abi_version() (internal
 * phase counter), this is the semver-stable number a host should
 * compare against `pfsf.abi.version` in the jar manifest to detect
 * a native/java mismatch at load time.
 *
 * Returns a statically-allocated string; do NOT free.
 *
 * Added in v0.4 M1c (contract version 1.2.0).
 *
 * @see pfsf_v1.abi.json {@code abi_version}
 * @maps_to NativePFSFBridge.java:nativeAbiContractVersion()
 */
PFSF_API const char* pfsf_abi_contract_version(void);

#ifdef __cplusplus
}
#endif

#endif /* PFSF_VERSION_H */
