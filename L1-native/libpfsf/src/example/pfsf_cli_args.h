/**
 * @file pfsf_cli_args.h
 * @brief v0.4 M3a — flag parser for pfsf_cli.
 *
 * Hand-rolled argv walker — pfsf_cli is the only consumer and its flag
 * surface is small (6 options). Pulling in a dependency like cxxopts for
 * this one caller would dwarf the parser it replaces, and the CI build
 * matrix already has to vend several native deps.
 */
#ifndef PFSF_CLI_ARGS_H_
#define PFSF_CLI_ARGS_H_

#include <cstdint>
#include <string>

namespace pfsf_cli {

enum class Backend {
    /** Default — old smoke test path, kept for `pfsf_cli` with no flags so
     *  existing CI jobs keep working unchanged. */
    SMOKE,
    /** Pure-CPU replay — drives `libpfsf_compute` host primitives without
     *  Vulkan. Good for numeric correctness on GPU-less runners. */
    CPU,
    /** Full stack — GPU compute pipelines through `pfsf_tick`. */
    VK,
};

struct Args {
    Backend      backend       = Backend::SMOKE;
    std::string  fixture_path;
    int32_t      ticks         = 1;
    std::string  dump_stress;    /* binary float32 SoA dump (optional) */
    std::string  dump_trace;     /* newline-delimited JSON trace dump (optional) */
    int32_t      seed          = 0;
    bool         want_help     = false;
    /** When non-empty, parse failed; caller should print to stderr & exit 2. */
    std::string  error;
};

/**
 * Parse argv in-place. `argv[0]` is the executable name and is skipped.
 * Unknown flags populate {@link Args::error} and return immediately; the
 * caller then prints {@link print_usage}.
 *
 * Supported flags (long-form only; no single-letter aliases to keep the
 * parser trivially auditable):
 *   --fixture <path>         JSON fixture to load; when omitted, SMOKE
 *                            backend runs a hard-coded 16³ concrete cube.
 *   --backend {smoke|cpu|vk} default `smoke` when no fixture, `vk` otherwise.
 *   --ticks <int>            iterations (cpu/vk). Default 1.
 *   --dump-stress <path>     binary float32 SoA dump after the final tick.
 *   --dump-trace <path>      newline-delimited trace event JSON dump.
 *   --seed <int>             RNG seed for fixture generators. Default 0.
 *   --help / -h              print usage and exit.
 */
Args parse(int argc, char** argv);

void print_usage();

} /* namespace pfsf_cli */

#endif /* PFSF_CLI_ARGS_H_ */
