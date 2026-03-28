package com.blockreality.api.spi;

import com.blockreality.api.material.RMaterial;
import java.util.Collection;
import java.util.Optional;

/**
 * Material Registry SPI — Central registry for all materials in Block Reality.
 *
 * This interface provides a thread-safe way for modules to register and query
 * materials. The ModuleRegistry maintains a singleton implementation backed by
 * ConcurrentHashMap, pre-loaded with all DefaultMaterial entries.
 *
 * Key responsibilities:
 * - Register new custom materials from modules (e.g., Construction Intern's reinforced variants)
 * - Query materials by ID (used by commands, visualization, analysis tools)
 * - Validate material pairing for RC fusion compatibility
 * - Enumerate all registered materials
 *
 * Example module usage (Construction Intern):
 * <pre>
 * public class ConstructionMaterials {
 *     public static void register(IMaterialRegistry registry) {
 *         registry.registerMaterial("reinforced_concrete", new ReinforcedConcreteMaterial());
 *         registry.registerMaterial("high_strength_steel", new HSteelMaterial());
 *     }
 * }
 * </pre>
 *
 * @since 1.0.0
 */
public interface IMaterialRegistry {

    /**
     * Register a new material in the registry.
     *
     * Thread-safe: Can be called from any thread. If a material with the same ID
     * already exists, it will be overwritten (with a warning logged).
     *
     * @param id The unique material identifier (e.g., "reinforced_concrete")
     * @param material The RMaterial implementation
     */
    void registerMaterial(String id, RMaterial material);

    /**
     * Query a material by its unique identifier.
     *
     * Thread-safe for concurrent reads.
     *
     * @param id The material identifier
     * @return An Optional containing the material if found, or empty if not found
     */
    Optional<RMaterial> getMaterial(String id);

    /**
     * Check if two materials can be fused together (RC fusion compatibility).
     *
     * This method can be overridden by modules to implement custom fusion rules.
     * Default behavior: returns true if both materials are registered and at least
     * one has Rtens > 0 (can handle tension, necessary for RC fusion).
     *
     * @param a The first material
     * @param b The second material
     * @return true if the materials can be fused, false otherwise
     */
    boolean canPair(RMaterial a, RMaterial b);

    /**
     * Get all registered material IDs.
     *
     * Useful for enumeration and debugging.
     *
     * @return A Collection of all registered material IDs
     */
    Collection<String> getAllMaterialIds();

    /**
     * Get the total number of registered materials.
     */
    default int getCount() {
        return getAllMaterialIds().size();
    }
}
