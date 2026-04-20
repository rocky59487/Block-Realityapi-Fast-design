/**
 * @file cpu_backend.h
 * @brief v0.4 M3b — pure-CPU replay loop for pfsf_cli.
 *
 * <p>Drives the stateless host primitives in {@code pfsf_compute.h} through
 * a simplified Jacobi loop to reproduce the solver's numeric contract on
 * machines without a Vulkan device. This is "verify numerics, not
 * convergence" (plan doc V8) — a 6-connectivity CPU smoother, not the
 * 26-connectivity RBGS+PCG the GPU dispatcher uses.</p>
 *
 * <p>The banner printed at dispatch time surfaces this caveat so devs
 * reading the output cannot confuse parity-of-primitives with parity-of-
 * convergence.</p>
 */
#ifndef PFSF_CLI_CPU_BACKEND_H_
#define PFSF_CLI_CPU_BACKEND_H_

#include "fixture_loader.h"
#include "pfsf_cli_args.h"

namespace pfsf_cli {

int run_cpu(const Fixture& fx, const Args& args);

} /* namespace pfsf_cli */

#endif /* PFSF_CLI_CPU_BACKEND_H_ */
