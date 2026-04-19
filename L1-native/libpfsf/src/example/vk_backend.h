/**
 * @file vk_backend.h
 * @brief v0.4 M3c — full pfsf_tick replay driver for pfsf_cli.
 *
 * <p>Drives the production {@code pfsf_create → pfsf_init → pfsf_tick}
 * path. The fixture's material registry + anchor list feeds the lookup
 * callbacks; the global wind vector is pushed via {@link pfsf_set_wind}.
 * After the final tick, {@link pfsf_read_stress} dumps the island's
 * stress field as raw float32 SoA — byte-comparable against the CPU
 * backend's {@code --dump-stress} output via {@code cmp -l} on a machine
 * where both backends run.</p>
 *
 * <p>On GPU-less hosts, {@code pfsf_init} returns
 * {@link PFSF_ERROR_NO_DEVICE} or {@link PFSF_ERROR_VULKAN}; the driver
 * prints a clearly labelled skip banner and returns 0 so the CI matrix
 * stays green.</p>
 */
#ifndef PFSF_CLI_VK_BACKEND_H_
#define PFSF_CLI_VK_BACKEND_H_

#include "fixture_loader.h"
#include "pfsf_cli_args.h"

namespace pfsf_cli {

int run_vk(const Fixture& fx, const Args& args);

} /* namespace pfsf_cli */

#endif /* PFSF_CLI_VK_BACKEND_H_ */
