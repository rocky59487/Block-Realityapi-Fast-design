/**
 * @file fixture_loader.h
 * @brief v0.4 M3a — schema-v1 PFSF fixture reader for pfsf_cli.
 *
 * <p>The JSON schema lives in
 * `Block Reality/api/src/test/resources/pfsf-fixtures/README.md`. This
 * header defines the in-memory shape produced by a successful parse;
 * fields that the schema lists as optional are exposed as vectors whose
 * `.empty()` means "not specified" (the driver then falls back to
 * synthesised defaults).</p>
 *
 * <p>Binary fields — {@code materials.voxels}, {@code fluid_pressure},
 * {@code curing}, {@code expected_stress} — are base64-encoded in JSON
 * and decoded here. They're stored as flat host arrays sized
 * {@code lx * ly * lz}.</p>
 *
 * <p>The parser intentionally only handles the subset of JSON it needs.
 * Comments, trailing commas and scientific-notation numbers in
 * non-numeric positions are rejected. This keeps the reader auditable
 * (≈ 400 LoC) without dragging in a third-party JSON library whose
 * features (SAX, schema, Unicode escapes) pfsf_cli doesn't exercise.</p>
 */
#ifndef PFSF_CLI_FIXTURE_LOADER_H_
#define PFSF_CLI_FIXTURE_LOADER_H_

#include <array>
#include <cstdint>
#include <string>
#include <vector>

namespace pfsf_cli {

struct FixtureMaterialEntry {
    int32_t      id = 0;
    std::string  name;
    float        rcomp = 0.0f;     /* MPa — will be normalised by driver */
    float        rtens = 0.0f;     /* MPa */
    float        density = 2400.0f;  /* kg/m^3 */
    float        youngs_gpa = 30.0f;
    float        poisson = 0.2f;
    float        gc = 100.0f;
    bool         is_anchor = false;
};

struct FixtureFailure {
    std::array<int32_t, 3> pos  = {0, 0, 0};
    std::string            type;
    int32_t                tick = 0;
};

struct Fixture {
    int32_t      schema_version = 1;
    std::string  fixture_id;
    std::string  description;
    std::string  recorded_at;
    std::string  git_sha;

    int32_t      lx = 0;
    int32_t      ly = 0;
    int32_t      lz = 0;

    std::vector<std::array<int32_t, 3>>   anchors;

    std::vector<int32_t>                  material_voxels; /* N = lx*ly*lz */
    std::vector<FixtureMaterialEntry>     material_registry;

    std::vector<float>                    fluid_pressure;  /* optional */
    std::vector<float>                    curing;          /* optional */
    std::array<float, 3>                  wind = {0.0f, 0.0f, 0.0f};

    int32_t                               ticks = 1;
    std::vector<float>                    expected_stress; /* optional */
    std::vector<FixtureFailure>           expected_failures;

    /* Tolerances block — defaults match historical GoldenParityTest. */
    float  tol_stress_abs    = 1e-5f;
    int32_t tol_failure_tick = 5;
};

struct LoadResult {
    bool         ok = false;
    std::string  error;    /* human-readable; empty when ok=true */
    Fixture      fixture;
};

/** Read a schema-v1 fixture from the given filesystem path. Slurps the
 *  whole file; a broken JSON or dimension mismatch returns
 *  `{ok=false, error=...}`. */
LoadResult load_fixture(const std::string& path);

/** Parse a fixture from an already-in-memory JSON string. Exposed for
 *  unit testing; the loader implementation calls through. */
LoadResult parse_fixture(const std::string& json);

/** @return the material entry whose id matches `id`, or the first entry
 *  (treated as a default) when the id is not found. The registry is
 *  always non-empty after a successful load, so this never returns a
 *  dangling pointer. */
const FixtureMaterialEntry& lookup_material(const Fixture& fx, int32_t id);

} /* namespace pfsf_cli */

#endif /* PFSF_CLI_FIXTURE_LOADER_H_ */
