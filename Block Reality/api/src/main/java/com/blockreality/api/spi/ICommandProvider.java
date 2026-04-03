package com.blockreality.api.spi;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;

/**
 * Command Provider SPI — Module interface for registering custom commands.
 *
 * Modules implementing this interface can register their own commands
 * with the Brigadier command dispatcher. The ModuleRegistry will invoke
 * registerCommands() during the RegisterCommandsEvent phase.
 *
 * Example implementation (Construction Intern):
 * <pre>
 * public class ConstructionCommands implements ICommandProvider {
 *     @Override
 *     public void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
 *         dispatcher.register(Commands.literal("ci_analyze")
 *             .executes(ctx -> {
 *                 // analyze structure
 *                 return 1;
 *             }));
 *     }
 *
 *     @Override
 *     public String getModuleId() {
 *         return "construction_intern";
 *     }
 * }
 * </pre>
 *
 * @since 1.0.0
 */
@SPIVersion(major = 1, minor = 0)
public interface ICommandProvider {

    /**
     * Register custom commands with the Brigadier dispatcher.
     *
     * This method is called during server command registration and must
     * be thread-safe (called from the main game thread).
     *
     * @param dispatcher The Brigadier CommandDispatcher for CommandSourceStack
     */
    void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher);

    /**
     * Returns the unique ID of this module.
     * Used for logging and conflict resolution.
     *
     * @return A unique, lowercase module identifier (e.g., "construction_intern")
     */
    String getModuleId();
}
