package com.blockreality.api.event;

import com.blockreality.api.material.RMaterial;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.eventbus.api.Event;

/**
 * Fusion Completed Event — Fired when RC (Rebar + Concrete) fusion is completed.
 *
 * This event is posted on the FORGE event bus when RCFusionDetector successfully
 * fuses a rebar block with a concrete block, creating an RC_NODE or upgrading
 * an existing RC structure.
 *
 * Modules can listen to this event to update material registries, trigger
 * reinforcement logic, or provide visual feedback for fusion operations.
 *
 * Module use case: Construction Intern can listen to track fusion operations
 * for structural analysis and reinforcement planning.
 */
public class FusionCompletedEvent extends Event {

    private final ServerLevel level;
    private final BlockPos pos;
    private final RMaterial originalMaterial;
    private final RMaterial fusedMaterial;

    /**
     * Construct a fusion completed event.
     *
     * @param level The server level where fusion occurred
     * @param pos The block position where fusion was completed
     * @param originalMaterial The original material before fusion
     * @param fusedMaterial The new fused material after fusion
     */
    public FusionCompletedEvent(ServerLevel level, BlockPos pos,
                                RMaterial originalMaterial, RMaterial fusedMaterial) {
        this.level = level;
        this.pos = pos;
        this.originalMaterial = originalMaterial;
        this.fusedMaterial = fusedMaterial;
    }

    /**
     * Get the server level where fusion occurred.
     *
     * @return The ServerLevel
     */
    public ServerLevel getLevel() {
        return level;
    }

    /**
     * Get the block position where fusion was completed.
     *
     * @return The BlockPos
     */
    public BlockPos getPos() {
        return pos;
    }

    /**
     * Get the original material before fusion.
     *
     * @return The RMaterial before RC fusion
     */
    public RMaterial getOriginalMaterial() {
        return originalMaterial;
    }

    /**
     * Get the new fused material after RC fusion.
     *
     * @return The RMaterial after RC fusion (typically with higher strength)
     */
    public RMaterial getFusedMaterial() {
        return fusedMaterial;
    }

    /**
     * Get the combined strength improvement ratio.
     *
     * Calculates the ratio of fused strength to original strength.
     * Returns 1.0 if original strength was 0 to avoid division by zero.
     *
     * @return The strength improvement multiplier (e.g., 1.5 means 50% stronger)
     */
    public double getStrengthImprovement() {
        double originalStrength = originalMaterial.getCombinedStrength();
        double fusedStrength = fusedMaterial.getCombinedStrength();
        if (originalStrength == 0) return 1.0;
        return fusedStrength / originalStrength;
    }

    /**
     * Get the compression strength improvement ratio.
     *
     * Calculates the ratio of fused compression strength to original compression strength.
     * Returns 1.0 if original compression was 0.
     *
     * @return The compression strength improvement multiplier
     */
    public double getCompressionImprovement() {
        if (originalMaterial.getRcomp() == 0) return 1.0;
        return fusedMaterial.getRcomp() / originalMaterial.getRcomp();
    }

    /**
     * Get the tension strength improvement ratio.
     *
     * Calculates the ratio of fused tension strength to original tension strength.
     * Returns 1.0 if original tension was 0.
     *
     * @return The tension strength improvement multiplier
     */
    public double getTensionImprovement() {
        if (originalMaterial.getRtens() == 0) return 1.0;
        return fusedMaterial.getRtens() / originalMaterial.getRtens();
    }
}
