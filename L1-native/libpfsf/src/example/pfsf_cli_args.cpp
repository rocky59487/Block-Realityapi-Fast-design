#include "pfsf_cli_args.h"

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>

namespace pfsf_cli {

namespace {

/** Parse an optional int argument following a flag. Sets `error_out` when
 *  the value is missing, non-numeric, or overflows int32. */
bool parse_int(const char* flag, const char* val, int32_t& dst, std::string& error_out) {
    if (val == nullptr) {
        error_out = std::string("missing value for ") + flag;
        return false;
    }
    char* end = nullptr;
    long v = std::strtol(val, &end, 10);
    if (end == val || *end != '\0') {
        error_out = std::string("non-numeric value for ") + flag + ": " + val;
        return false;
    }
    if (v < INT32_MIN || v > INT32_MAX) {
        error_out = std::string("int32 overflow for ") + flag + ": " + val;
        return false;
    }
    dst = static_cast<int32_t>(v);
    return true;
}

bool parse_backend(const char* val, Backend& dst, std::string& error_out) {
    if (val == nullptr) {
        error_out = "missing value for --backend";
        return false;
    }
    if (std::strcmp(val, "smoke") == 0) { dst = Backend::SMOKE; return true; }
    if (std::strcmp(val, "cpu")   == 0) { dst = Backend::CPU;   return true; }
    if (std::strcmp(val, "vk")    == 0) { dst = Backend::VK;    return true; }
    error_out = std::string("unknown backend: ") + val +
                " (expected smoke|cpu|vk)";
    return false;
}

/** next(i) returns argv[++i] or nullptr if we'd walk past argc. */
const char* next(int argc, char** argv, int& i) {
    ++i;
    return (i < argc) ? argv[i] : nullptr;
}

} /* namespace */

void print_usage() {
    std::fprintf(stderr,
        "Usage: pfsf_cli [options]\n"
        "\n"
        "  --fixture <path>         JSON fixture to load (schema v1)\n"
        "  --backend {smoke|cpu|vk} default smoke when no fixture, else vk\n"
        "  --ticks <int>            iterations for cpu/vk backends (>= 1)\n"
        "  --dump-stress <path>     binary float32 SoA stress dump\n"
        "  --dump-trace <path>      newline-delimited trace event JSON dump\n"
        "  --seed <int>             RNG seed for fixture generators\n"
        "  --help / -h              print this message\n"
        "\n"
        "  Default (no flags): smoke test — init Vulkan, add a concrete cube,\n"
        "  run one tick, print stats. Matches pre-v0.4 behaviour.\n"
    );
}

Args parse(int argc, char** argv) {
    Args out;
    bool explicit_backend = false;

    /* Accepts either "--flag value" or "--flag=value" forms. The split
     * produces (name, inline_value). When inline_value is nullptr the
     * caller may pull the next argv token; otherwise it must use the
     * inline one (consuming an extra token would corrupt --flag=x with
     * a following flag). */
    auto split_flag = [](const char* a, std::string& name_out) -> const char* {
        const char* eq = std::strchr(a, '=');
        if (eq == nullptr) { name_out = a; return nullptr; }
        name_out.assign(a, eq - a);
        return eq + 1;
    };
    auto grab_value = [&](const char* flag_name, const char* inline_val, int& i) -> const char* {
        if (inline_val != nullptr) return inline_val;
        const char* v = next(argc, argv, i);
        if (v == nullptr) {
            out.error = std::string("missing value for ") + flag_name;
        }
        return v;
    };

    for (int i = 1; i < argc; ++i) {
        const char* a = argv[i];
        std::string name;
        const char* inline_val = split_flag(a, name);

        if (name == "--help" || name == "-h") {
            out.want_help = true;
            continue;
        }
        if (name == "--fixture") {
            const char* v = grab_value("--fixture", inline_val, i);
            if (!out.error.empty()) return out;
            out.fixture_path = v;
            continue;
        }
        if (name == "--backend") {
            const char* v = grab_value("--backend", inline_val, i);
            if (!out.error.empty()) return out;
            if (!parse_backend(v, out.backend, out.error)) return out;
            explicit_backend = true;
            continue;
        }
        if (name == "--ticks") {
            const char* v = grab_value("--ticks", inline_val, i);
            if (!out.error.empty()) return out;
            if (!parse_int("--ticks", v, out.ticks, out.error)) return out;
            if (out.ticks < 1) {
                out.error = "--ticks must be >= 1";
                return out;
            }
            continue;
        }
        if (name == "--dump-stress") {
            const char* v = grab_value("--dump-stress", inline_val, i);
            if (!out.error.empty()) return out;
            out.dump_stress = v;
            continue;
        }
        if (name == "--dump-trace") {
            const char* v = grab_value("--dump-trace", inline_val, i);
            if (!out.error.empty()) return out;
            out.dump_trace = v;
            continue;
        }
        if (name == "--seed") {
            const char* v = grab_value("--seed", inline_val, i);
            if (!out.error.empty()) return out;
            if (!parse_int("--seed", v, out.seed, out.error)) return out;
            continue;
        }

        out.error = std::string("unknown flag: ") + a;
        return out;
    }

    /* Default backend inference: if a fixture was named but --backend was
     * not, we prefer vk because it exercises the production path; CPU is
     * opt-in because it skips solver convergence. */
    if (!explicit_backend && !out.fixture_path.empty()) {
        out.backend = Backend::VK;
    }

    return out;
}

} /* namespace pfsf_cli */
