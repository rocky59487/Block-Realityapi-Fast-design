package com.blockreality.api.spi;

import com.blockreality.api.material.DefaultMaterial;
import com.blockreality.api.material.RMaterial;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.concurrent.ThreadSafe;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Module Registry — Central hub for extensibility points in Block Reality.
 *
 * This singleton maintains lists of service providers (SPI) and registries
 * that allow modules to hook into the core mod. The registry is thread-safe
 * and pre-loaded with DefaultMaterial entries at startup.
 *
 * <h3>API 存取慣例（★ 統一說明）</h3>
 * 所有公開方法為 {@code static}，內部委派到 {@code INSTANCE}。
 * 這是刻意的設計決策 — 呼叫端統一使用 {@code ModuleRegistry.getXxx()} 而非
 * {@code ModuleRegistry.getInstance().getXxx()}，減少樣板代碼。
 * {@link #getInstance()} 僅供需要傳遞 registry 引用的進階場景。
 *
 * Responsibilities:
 * - Hold lists of ICommandProvider, IRenderLayerProvider, IBlockTypeExtension
 * - Maintain a thread-safe IMaterialRegistry implementation
 * - Fire render events to all registered render layers
 *
 * Registration is typically done during mod initialization or when a module
 * is loaded. Modules must call register*() methods with their implementations.
 *
 * @since 1.0.0
 */
@ThreadSafe
public class ModuleRegistry {

    private static final Logger LOGGER = LogManager.getLogger("BR-ModuleRegistry");

    private static final ModuleRegistry INSTANCE = new ModuleRegistry();

    // ─── Service Provider Lists (thread-safe) ───
    private final List<ICommandProvider> commandProviders = new CopyOnWriteArrayList<>();
    private final List<IRenderLayerProvider> renderProviders = new CopyOnWriteArrayList<>();
    private final List<IBlockTypeExtension> blockTypeExtensions = new CopyOnWriteArrayList<>();

    // ─── Material Registry (singleton, backed by ConcurrentHashMap) ───
    private final IMaterialRegistry materialRegistry = new DefaultMaterialRegistry();

    // ─── Curing Manager (singleton) ───
    private final ICuringManager curingManager = new DefaultCuringManager();

    // ─── Cable Manager (singleton) ───
    // ★ R3-2 fix: volatile 保證 setCableManager() 後其他執行緒立即可見新實現
    private volatile ICableManager cableManager = new DefaultCableManager();

    // ─── Fusion Detector (singleton) ───
    // ★ R3-2 fix: volatile 保證 setFusionDetector() 後其他執行緒立即可見新實現
    // ★ 審計修復：委託給 DefaultProviders，解除 ModuleRegistry 對 RCFusionDetector 的直接依賴
    private volatile IFusionDetector fusionDetector = DefaultProviders.createFusionDetector();

    // ─── Fluid Manager (singleton, opt-in) ───
    // ★ PFSF-Fluid: volatile 保證 setFluidManager() 後其他執行緒立即可見新實現
    private volatile IFluidManager fluidManager = null;

    // ─── Multi-domain physics managers (opt-in) ───
    private volatile IThermalManager thermalManager = null;
    private volatile IWindManager windManager = null;
    private volatile IElectromagneticManager emManager = null;

    // ─── VS2 bridge (optional — NoOp if VS2 not installed) ───
    private volatile IVS2Bridge vs2Bridge = NoOpVS2Bridge.INSTANCE;

    private ModuleRegistry() {
        // Pre-load all DefaultMaterial entries into the registry
        for (DefaultMaterial material : DefaultMaterial.values()) {
            materialRegistry.registerMaterial(material.getMaterialId(), material);
        }
        LOGGER.info("[BR-ModuleRegistry] Initialized with {} default materials",
            DefaultMaterial.values().length);
    }

    // ═══════════════════════════════════════════════════════
    //  Singleton Access
    // ═══════════════════════════════════════════════════════

    /**
     * Get the singleton instance of the ModuleRegistry.
     *
     * @return The global ModuleRegistry instance
     */
    public static ModuleRegistry getInstance() {
        return INSTANCE;
    }

    // ═══════════════════════════════════════════════════════
    //  Command Provider Registration
    // ═══════════════════════════════════════════════════════

    /**
     * Register a command provider with the module registry.
     *
     * The provider's registerCommands() method will be invoked during the
     * RegisterCommandsEvent phase, allowing the module to register custom commands.
     *
     * @param provider The ICommandProvider implementation to register
     */
    public static void registerCommandProvider(ICommandProvider provider) {
        INSTANCE.commandProviders.add(provider);
        LOGGER.debug("[BR-ModuleRegistry] Registered command provider: {}",
            provider.getModuleId());
    }

    /**
     * Unregister a previously registered command provider.
     *
     * @param provider The ICommandProvider implementation to unregister
     */
    public static void unregisterCommandProvider(ICommandProvider provider) {
        INSTANCE.commandProviders.remove(provider);
        LOGGER.debug("[BR-ModuleRegistry] Unregistered command provider: {}",
            provider.getModuleId());
    }

    /**
     * Get all registered command providers.
     *
     * Returns a copy of the internal list to prevent concurrent modification exceptions.
     *
     * @return A list of all registered ICommandProvider instances
     */
    public static List<ICommandProvider> getCommandProviders() {
        return new ArrayList<>(INSTANCE.commandProviders);
    }

    // ═══════════════════════════════════════════════════════
    //  Render Layer Provider Registration
    // ═══════════════════════════════════════════════════════

    /**
     * Register a render layer provider with the module registry.
     *
     * The provider's onRenderLevelStage() method will be called each frame
     * if isEnabled() returns true, allowing the module to inject custom rendering.
     *
     * This registration is client-side only.
     *
     * @param provider The IRenderLayerProvider implementation to register
     */
    public static void registerRenderLayerProvider(IRenderLayerProvider provider) {
        INSTANCE.renderProviders.add(provider);
        LOGGER.debug("[BR-ModuleRegistry] Registered render layer provider: {}",
            provider.getRenderLayerId());
    }

    /**
     * Unregister a previously registered render layer provider.
     *
     * @param provider The IRenderLayerProvider implementation to unregister
     */
    public static void unregisterRenderLayerProvider(IRenderLayerProvider provider) {
        INSTANCE.renderProviders.remove(provider);
        LOGGER.debug("[BR-ModuleRegistry] Unregistered render layer provider: {}",
            provider.getRenderLayerId());
    }

    /**
     * Get all registered render layer providers.
     *
     * Returns a copy of the internal list to prevent concurrent modification exceptions.
     *
     * @return A list of all registered IRenderLayerProvider instances
     */
    public static List<IRenderLayerProvider> getRenderLayerProviders() {
        return new ArrayList<>(INSTANCE.renderProviders);
    }

    // ═══════════════════════════════════════════════════════
    //  Block Type Extension Registration
    // ═══════════════════════════════════════════════════════

    /**
     * Register a block type extension with the module registry.
     *
     * Block type extensions define custom block classification behaviors
     * (structural properties, transition rules, anchor capabilities).
     * These are used by physics engines and analysis tools for block categorization.
     *
     * @param extension The IBlockTypeExtension implementation to register
     */
    public static void registerBlockTypeExtension(IBlockTypeExtension extension) {
        INSTANCE.blockTypeExtensions.add(extension);
        LOGGER.debug("[BR-ModuleRegistry] Registered block type extension: {}",
            extension.getTypeId());
    }

    /**
     * Unregister a previously registered block type extension.
     *
     * @param extension The IBlockTypeExtension implementation to unregister
     */
    public static void unregisterBlockTypeExtension(IBlockTypeExtension extension) {
        INSTANCE.blockTypeExtensions.remove(extension);
        LOGGER.debug("[BR-ModuleRegistry] Unregistered block type extension: {}",
            extension.getTypeId());
    }

    /**
     * Get all registered block type extensions.
     *
     * Returns a copy of the internal list to prevent concurrent modification exceptions.
     *
     * @return A list of all registered IBlockTypeExtension instances
     */
    public static List<IBlockTypeExtension> getBlockTypeExtensions() {
        return new ArrayList<>(INSTANCE.blockTypeExtensions);
    }

    // ═══════════════════════════════════════════════════════
    //  Material Registry Access
    // ═══════════════════════════════════════════════════════

    /**
     * Get the global material registry.
     *
     * The material registry is thread-safe and pre-loaded with all DefaultMaterial entries
     * at startup. Modules use this to register and query materials.
     *
     * @return The singleton IMaterialRegistry instance
     */
    public static IMaterialRegistry getMaterialRegistry() {
        return INSTANCE.materialRegistry;
    }

    // ═══════════════════════════════════════════════════════
    //  Curing Manager Access
    // ═══════════════════════════════════════════════════════

    /**
     * Get the global curing manager.
     *
     * The curing manager tracks material curing/hardening processes and fires
     * CuringProgressEvent updates to subscribers.
     *
     * @return The singleton ICuringManager instance
     */
    public static ICuringManager getCuringManager() {
        return INSTANCE.curingManager;
    }

    // ═══════════════════════════════════════════════════════
    //  Cable Manager Access
    // ═══════════════════════════════════════════════════════

    /**
     * Get the global cable manager.
     *
     * The cable manager maintains rope/cable elements between blocks and handles
     * tension physics calculations. By default, uses DefaultCableManager.
     *
     * @return The singleton ICableManager instance
     */
    public static ICableManager getCableManager() {
        return INSTANCE.cableManager;
    }

    /**
     * Set a custom cable manager implementation.
     *
     * Allows modules to provide alternative cable management behavior.
     * Should typically be called during mod initialization before cables are created.
     *
     * @param manager The ICableManager implementation to use
     * @throws NullPointerException if manager is null
     */
    public static synchronized void setCableManager(ICableManager manager) {
        if (manager == null) {
            throw new NullPointerException("Cable manager cannot be null");
        }
        INSTANCE.cableManager = manager;
        LOGGER.info("[BR-ModuleRegistry] Set cable manager to: {}", manager.getClass().getSimpleName());
    }

    // ═══════════════════════════════════════════════════════
    //  Load Path Manager Access
    // ═══════════════════════════════════════════════════════

    // ═══════════════════════════════════════════════════════
    //  Fusion Detector Access
    // ═══════════════════════════════════════════════════════

    /**
     * Get the global RC fusion detector.
     *
     * The fusion detector handles detection and creation of reinforced concrete (RC)
     * fusion nodes when rebar and concrete are placed adjacent to each other.
     * By default, uses RCFusionDetector static methods.
     *
     * @return The singleton IFusionDetector instance
     */
    public static IFusionDetector getFusionDetector() {
        return INSTANCE.fusionDetector;
    }

    /**
     * Set a custom fusion detector implementation.
     *
     * Allows modules to provide alternative RC fusion detection behavior.
     * Should typically be called during mod initialization before blocks are placed.
     *
     * @param detector The IFusionDetector implementation to use
     * @throws NullPointerException if detector is null
     */
    public static synchronized void setFusionDetector(IFusionDetector detector) {
        if (detector == null) {
            throw new NullPointerException("Fusion detector cannot be null");
        }
        INSTANCE.fusionDetector = detector;
        LOGGER.info("[BR-ModuleRegistry] Set fusion detector to: {}", detector.getClass().getSimpleName());
    }

    // ═══════════════════════════════════════════════════════
    //  Fluid Manager Access
    // ═══════════════════════════════════════════════════════

    /**
     * Get the global fluid manager, if registered.
     *
     * <p>流體系統為 opt-in 模組。若未啟用或未註冊實作，返回 null。
     * 呼叫端應檢查 null 或先確認 {@code BRConfig.isFluidEnabled()}。
     *
     * @return The IFluidManager instance, or null if not registered
     */
    @javax.annotation.Nullable
    public static IFluidManager getFluidManager() {
        return INSTANCE.fluidManager;
    }

    /**
     * Set a fluid manager implementation.
     *
     * <p>在 Mod 初始化階段呼叫，啟用流體模擬子系統。
     * 傳入 null 可停用流體管理器。
     *
     * @param manager The IFluidManager implementation to use, or null to disable
     */
    public static synchronized void setFluidManager(@javax.annotation.Nullable IFluidManager manager) {
        INSTANCE.fluidManager = manager;
        if (manager != null) {
            LOGGER.info("[BR-ModuleRegistry] Set fluid manager to: {}", manager.getClass().getSimpleName());
        } else {
            LOGGER.info("[BR-ModuleRegistry] Fluid manager cleared (disabled)");
        }
    }

    // ─── Thermal Manager ───

    @javax.annotation.Nullable
    public static IThermalManager getThermalManager() { return INSTANCE.thermalManager; }

    public static synchronized void setThermalManager(@javax.annotation.Nullable IThermalManager manager) {
        INSTANCE.thermalManager = manager;
        LOGGER.info("[BR-ModuleRegistry] Thermal manager {}", manager != null ? "set: " + manager.getClass().getSimpleName() : "cleared");
    }

    // ─── Wind Manager ───

    @javax.annotation.Nullable
    public static IWindManager getWindManager() { return INSTANCE.windManager; }

    public static synchronized void setWindManager(@javax.annotation.Nullable IWindManager manager) {
        INSTANCE.windManager = manager;
        LOGGER.info("[BR-ModuleRegistry] Wind manager {}", manager != null ? "set: " + manager.getClass().getSimpleName() : "cleared");
    }

    // ─── Electromagnetic Manager ───

    @javax.annotation.Nullable
    public static IElectromagneticManager getEmManager() { return INSTANCE.emManager; }

    public static synchronized void setEmManager(@javax.annotation.Nullable IElectromagneticManager manager) {
        INSTANCE.emManager = manager;
        LOGGER.info("[BR-ModuleRegistry] EM manager {}", manager != null ? "set: " + manager.getClass().getSimpleName() : "cleared");
    }

    // ─── VS2 Bridge ───

    /**
     * Returns the active VS2 bridge. Default is {@link NoOpVS2Bridge} (always-false).
     * Replaced by {@link com.blockreality.api.vs2.VS2ShipBridge} at mod init if VS2 is loaded.
     */
    public static IVS2Bridge getVS2Bridge() { return INSTANCE.vs2Bridge; }

    public static synchronized void setVS2Bridge(IVS2Bridge bridge) {
        INSTANCE.vs2Bridge = bridge != null ? bridge : NoOpVS2Bridge.INSTANCE;
        LOGGER.info("[BR-ModuleRegistry] VS2 bridge set: {}", INSTANCE.vs2Bridge.getClass().getSimpleName());
    }

    // ═══════════════════════════════════════════════════════
    //  Render Event Dispatch (CLIENT-SIDE ONLY)
    // ═══════════════════════════════════════════════════════

    /**
     * Fire the RenderLevelStageEvent to all registered render layer providers.
     *
     * This method should be called from ClientSetup.ClientForgeEvents.onRenderLevel()
     * to allow modules to inject their custom render layers.
     *
     * Only renders providers where isEnabled() returns true.
     *
     * @param event The RenderLevelStageEvent from the client render pipeline
     */
    public static void fireRenderEvent(RenderLevelStageEvent event) {
        for (IRenderLayerProvider provider : INSTANCE.renderProviders) {
            try {
                if (provider.isEnabled()) {
                    provider.onRenderLevelStage(event);
                }
            } catch (RuntimeException e) {
                LOGGER.error("[BR-ModuleRegistry] Error in render provider {}: {}",
                    provider.getRenderLayerId(), e.getMessage(), e);
            }
        }
    }

    // ═══════════════════════════════════════════════════════
    //  Diagnostic
    // ═══════════════════════════════════════════════════════

    /**
     * Get a formatted summary of the module registry state.
     *
     * Useful for debugging and logging the current configuration of all
     * registered providers, extensions, materials, and active curing/cable/physics processes.
     *
     * @return A multi-line string containing registry statistics
     */
    public static String getRegistrySummary() {
        return String.format(
            "ModuleRegistry Summary:\n" +
            "  Command Providers: %d\n" +
            "  Render Layers: %d\n" +
            "  Block Types: %d\n" +
            "  Materials: %d\n" +
            "  Active Curing Blocks: %d\n" +
            "  Active Cables: %d\n" +
            "  Fusion Detector: %s",
            INSTANCE.commandProviders.size(),
            INSTANCE.renderProviders.size(),
            INSTANCE.blockTypeExtensions.size(),
            INSTANCE.materialRegistry.getCount(),
            INSTANCE.curingManager.getActiveCuringCount(),
            INSTANCE.cableManager.getCableCount(),
            INSTANCE.fusionDetector.getClass().getSimpleName()
        );
    }

    // ═══════════════════════════════════════════════════════
    //  Default Material Registry Implementation
    // ═══════════════════════════════════════════════════════

    private static class DefaultMaterialRegistry implements IMaterialRegistry {

        private final Map<String, RMaterial> materials = new ConcurrentHashMap<>();

        @Override
        public void registerMaterial(String id, RMaterial material) {
            if (materials.containsKey(id)) {
                LOGGER.warn("[BR-MaterialRegistry] Overwriting material: {}", id);
            }
            materials.put(id, material);
        }

        @Override
        public Optional<RMaterial> getMaterial(String id) {
            return Optional.ofNullable(materials.get(id));
        }

        @Override
        public boolean canPair(RMaterial a, RMaterial b) {
            // RC fusion requires at least one material with tension strength
            // to handle the composite forces
            return a != null && b != null &&
                (a.getRtens() > 0 || b.getRtens() > 0);
        }

        @Override
        public Collection<String> getAllMaterialIds() {
            return new ArrayList<>(materials.keySet());
        }

        @Override
        public int getCount() {
            return materials.size();
        }
    }
}
