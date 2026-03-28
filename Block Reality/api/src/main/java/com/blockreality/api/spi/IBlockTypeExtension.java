package com.blockreality.api.spi;

/**
 * Block Type Extension SPI — Module interface for defining custom block type behaviors.
 *
 * Modules can define and register custom block types with their own physics,
 * rendering, and structural properties. This allows modules like Construction Intern
 * to define reinforced concrete types or Fast Design to define temporary structure types.
 *
 * Block types are registered with ModuleRegistry and can be queried by systems
 * that need to understand block classification (physics engine, rendering, analysis).
 *
 * Example implementation (Construction Intern):
 * <pre>
 * public class ReinforcedConcreteType implements IBlockTypeExtension {
 *     @Override
 *     public String getTypeId() {
 *         return "reinforced_concrete_v1";
 *     }
 *
 *     @Override
 *     public boolean canTransitionTo(String targetTypeId) {
 *         // Can upgrade to higher reinforcement levels
 *         return targetTypeId.startsWith("reinforced_concrete_v");
 *     }
 *
 *     @Override
 *     public boolean isStructural() {
 *         return true;  // Participates in load calculations
 *     }
 *
 *     @Override
 *     public boolean canBeAnchor() {
 *         return false;  // Cannot serve as anchor point
 *     }
 * }
 * </pre>
 *
 * @since 1.0.0
 */
public interface IBlockTypeExtension {

    /**
     * Returns the unique ID for this block type.
     *
     * Should follow naming convention: "{module_id}_{type_name}" or similar.
     * Example: "ci_reinforced_concrete", "fd_temporary_scaffold"
     *
     * @return A unique, lowercase identifier
     */
    String getTypeId();

    /**
     * Check if this block type can transition to another type.
     *
     * Used by physics engines to determine if a block can be upgraded/downgraded
     * or changed during curing/reinforcement processes.
     *
     * @param targetTypeId The target block type ID
     * @return true if transition is allowed, false otherwise
     */
    boolean canTransitionTo(String targetTypeId);

    /**
     * Returns whether this block type participates in structural load calculations.
     *
     * If false, the block is treated as decorative/non-structural and does not
     * contribute to load paths or structural analysis.
     *
     * @return true if this block type participates in physics simulation
     */
    boolean isStructural();

    /**
     * Returns whether this block type can serve as an anchor point.
     *
     * Anchor points are special blocks that cannot collapse and can support
     * entire structures (e.g., bedrock, special anchor piles).
     *
     * @return true if this block type can be used as an anchor point
     */
    boolean canBeAnchor();
}
