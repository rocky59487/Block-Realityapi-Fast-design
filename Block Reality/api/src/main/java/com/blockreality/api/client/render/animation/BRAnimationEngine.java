package com.blockreality.api.client.render.animation;

import com.blockreality.api.client.render.BRRenderConfig;
import com.blockreality.api.client.render.shader.BRShaderProgram;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.FloatBuffer;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Block Reality 動畫引擎 - 完整的每實體骨骼動畫系統
 *
 * 功能特性：
 *   • 每實體的 AnimatableInstance (類似 GeckoLib 的 AnimatableInstanceCache)
 *   • 每實體支援多個 AnimationController (例如: 身體、手臂、頭部)
 *   • 正確的骨骼矩陣計算 (worldMatrix × inverseBindMatrix)
 *   • 預先編譯的動畫片段 (方塊放置、破壞、選擇脈衝、結構崩塌)
 *
 * 架構類比：
 *   GeckoLib AnimatableInstanceCache → AnimatableInstance
 *   GeckoLib AnimationController → AnimationController
 *   GeckoLib 骨骼計算 → BoneHierarchy.computeSkinningMatrices()
 */
@OnlyIn(Dist.CLIENT)
public final class BRAnimationEngine {
    private static final Logger LOG = LoggerFactory.getLogger("BR-Animation");

    // ═══════════════════════════════════════════════════════════════
    //                    全域靜態狀態
    // ═══════════════════════════════════════════════════════════════

    /** 所有活躍的實體動畫狀態快取 (UUID → 該實體的動畫實例) */
    private static final ConcurrentHashMap<UUID, AnimatableInstance> activeInstances =
            new ConcurrentHashMap<>();

    /** 預先編譯的動畫片段 */
    private static AnimationClip blockPlacementClip;
    private static AnimationClip blockDestroyClip;
    private static AnimationClip selectionPulseClip;
    private static AnimationClip structureCollapseClip;

    /** 共用的骨骼矩陣上傳緩衝區 (最多 128 個骨骼) */
    private static final Matrix4f[] boneMatrixUploadBuffer = new Matrix4f[128];

    /** 引擎初始化旗標 */
    private static boolean initialized = false;

    // ═══════════════════════════════════════════════════════════════
    //                    骨骼階層類型列舉
    // ═══════════════════════════════════════════════════════════════

    /**
     * 骨骼階層的類型列舉。決定要建立的骨骼結構複雜度。
     */
    public enum BoneHierarchyType {
        /**
         * 簡單的方塊層級骨骼。
         * 用於基本的方塊實體或簡單物件，骨骼樹最小化。
         */
        BLOCK,

        /**
         * 完整的角色骨骼結構。
         * 支援複雜的裝備、肢體動畫和多個子骨骼。
         */
        CHARACTER
    }

    // ═══════════════════════════════════════════════════════════════
    //              AnimatableInstance 內部類別 - 每實體動畫狀態
    // ═══════════════════════════════════════════════════════════════

    /**
     * 單個實體的動畫狀態容器。
     *
     * 職責：
     *   1. 管理此實體的骨骼階層
     *   2. 管理多個 AnimationController (支援並行播放)
     *   3. 快取計算後的蒙皮矩陣
     *   4. 提供 dirty flag 以最佳化矩陣重算
     */
    public static final class AnimatableInstance {
        private final UUID entityId;
        private final BoneHierarchy hierarchy;
        private final Map<String, AnimationController> controllers;
        private final Matrix4f[] skinningMatrices;
        private boolean isDirty;

        /**
         * 建立新的動畫實例。
         *
         * @param entityId  實體的唯一識別碼
         * @param hierarchy 此實體的骨骼階層
         */
        private AnimatableInstance(UUID entityId, BoneHierarchy hierarchy) {
            this.entityId = entityId;
            this.hierarchy = hierarchy;
            this.controllers = new ConcurrentHashMap<>();
            this.skinningMatrices = new Matrix4f[hierarchy.getBoneCount()];
            this.isDirty = true;

            // 初始化所有蒙皮矩陣為單位矩陣
            for (int i = 0; i < this.skinningMatrices.length; i++) {
                this.skinningMatrices[i] = new Matrix4f();
            }
        }

        // ─────────── 控制器管理 ───────────

        /**
         * 取得或建立指定名稱的動畫控制器。
         * 若該名稱的控制器已存在，直接回傳。否則建立新的。
         *
         * @param name 控制器的唯一名稱 (例如 "root", "arms", "head")
         * @return 此實體的動畫控制器
         */
        public AnimationController getOrCreateController(String name) {
            return this.controllers.computeIfAbsent(name, k -> new AnimationController(k));
        }

        /**
         * 取得指定名稱的動畫控制器。若不存在則回傳 null。
         *
         * @param name 控制器名稱
         * @return 動畫控制器或 null
         */
        public AnimationController getController(String name) {
            return this.controllers.get(name);
        }

        /**
         * 取得所有此實體的動畫控制器。
         *
         * @return 控制器集合的檢視
         */
        public Collection<AnimationController> getAllControllers() {
            return this.controllers.values();
        }

        /**
         * 此實體目前有多少個活躍的控制器。
         *
         * @return 控制器數量
         */
        public int getControllerCount() {
            return this.controllers.size();
        }

        // ─────────── 動畫更新 ───────────

        /**
         * 更新此實體的所有動畫控制器一個時間步。
         * 設定 isDirty 旗標以標記需要重算蒙皮矩陣。
         *
         * @param partialTick 部份時間步 (0.0 ~ 1.0)
         */
        public void tick(float partialTick) {
            // 估算實際時間增量 (1 tick = 0.05 秒)
            float deltaSeconds = 1.0f / 20.0f;

            // 更新所有控制器
            for (AnimationController controller : this.controllers.values()) {
                controller.tick(deltaSeconds);
            }
            this.isDirty = true;
        }

        // ─────────── 矩陣計算 ───────────

        /**
         * 計算此實體的骨骼蒙皮矩陣。
         * 此方法必須在上傳到著色器前呼叫。
         * 使用 isDirty 快取以避免重複計算。
         */
        private void computeSkinningMatrices() {
            if (!this.isDirty) {
                return; // 快取命中，無須重算
            }

            // 第一步: 根據所有控制器的動畫狀態應用到骨骼
            // 取得主控制器 "root" 的當前動畫，並應用到骨骼階層
            this.hierarchy.resetAllBones();
            for (AnimationController ctrl : this.controllers.values()) {
                if (ctrl.isPlaying() && ctrl.getCurrentClip() != null) {
                    float time = ctrl.getCurrentClip().getEffectiveTime(ctrl.getCurrentTime());
                    this.hierarchy.applyAnimationClip(ctrl.getCurrentClip(), time);
                }
            }

            // 第二步: 計算世界座標轉換矩陣（從根到葉累積父變換）
            this.hierarchy.computeWorldTransforms();

            // 第三步: 計算蒙皮矩陣 (worldMatrix × inverseBindMatrix)
            // 這是 GPU 頂點著色器中所需的最終矩陣
            this.hierarchy.computeSkinningMatrices(this.skinningMatrices);

            this.isDirty = false;
        }

        /**
         * 取得計算後的蒙皮矩陣陣列。
         * 必須先呼叫 computeSkinningMatrices() 以確保矩陣最新。
         *
         * @return 蒙皮矩陣陣列
         */
        public Matrix4f[] getSkinningMatrices() {
            this.computeSkinningMatrices();
            return this.skinningMatrices;
        }

        /**
         * 取得此實體的骨骼階層。
         *
         * @return 骨骼階層物件
         */
        public BoneHierarchy getHierarchy() {
            return this.hierarchy;
        }

        /**
         * 取得此實體的 UUID。
         *
         * @return 實體識別碼
         */
        public UUID getEntityId() {
            return this.entityId;
        }

        /**
         * 檢查此實例是否還在執行任何動畫。
         * 用於判斷何時可以安全地移除此實例。
         *
         * @return true 若有至少一個控制器在播放中
         */
        public boolean isAnimating() {
            return this.controllers.values().stream()
                    .anyMatch(AnimationController::isPlaying);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //          公開靜態方法 - 引擎生命週期
    // ═══════════════════════════════════════════════════════════════

    /**
     * 初始化動畫引擎。
     * 必須在遊戲啟動時呼叫一次。
     * 建立預先編譯的動畫片段和共用資源。
     */
    public static void init() {
        if (initialized) {
            LOG.warn("BRAnimationEngine 已經初始化");
            return;
        }

        try {
            // 初始化共用的矩陣上傳緩衝區
            for (int i = 0; i < boneMatrixUploadBuffer.length; i++) {
                boneMatrixUploadBuffer[i] = new Matrix4f();
            }

            // 建立預先編譯的動畫片段（使用 AnimationClip 工廠方法）
            blockPlacementClip = AnimationClip.createBlockPlacement();
            blockDestroyClip = AnimationClip.createBlockDestroy();
            selectionPulseClip = AnimationClip.createSelectionPulse();
            structureCollapseClip = AnimationClip.createStructureCollapse();

            initialized = true;
            LOG.info("BRAnimationEngine 初始化成功 — 4 預製片段, 最大 128 骨骼");
        } catch (Exception e) {
            LOG.error("BRAnimationEngine 初始化失敗", e);
        }
    }

    /**
     * 清理並重置動畫引擎。
     * 移除所有活躍實例和資源。
     * 應在遊戲關閉時呼叫。
     */
    public static void cleanup() {
        activeInstances.clear();
        blockPlacementClip = null;
        blockDestroyClip = null;
        selectionPulseClip = null;
        structureCollapseClip = null;
        initialized = false;
        LOG.info("BRAnimationEngine 已清理");
    }

    // ═══════════════════════════════════════════════════════════════
    //          公開靜態方法 - 實例管理
    // ═══════════════════════════════════════════════════════════════

    /**
     * 取得或建立指定實體的動畫實例。
     * 若實例已存在，直接回傳。否則建立新的實例。
     *
     * @param entityId 實體的唯一識別碼
     * @param type     骨骼階層類型 (簡單或完整)
     * @return 此實體的動畫實例
     */
    public static AnimatableInstance getOrCreateInstance(UUID entityId, BoneHierarchyType type) {
        return activeInstances.computeIfAbsent(entityId, id -> {
            BoneHierarchy hierarchy;

            // 根據類型建立適當的骨骼階層
            switch (type) {
                case BLOCK:
                    hierarchy = BoneHierarchy.createBlockHierarchy();
                    break;
                case CHARACTER:
                    hierarchy = BoneHierarchy.createCharacterHierarchy();
                    break;
                default:
                    hierarchy = BoneHierarchy.createBlockHierarchy();
            }

            LOG.debug("為實體 {} 建立新的 {} 階層", id, type);
            return new AnimatableInstance(id, hierarchy);
        });
    }

    /**
     * 取得指定實體的動畫實例。
     * 若實例不存在則回傳 null。
     *
     * @param entityId 實體識別碼
     * @return 動畫實例或 null
     */
    public static AnimatableInstance getInstance(UUID entityId) {
        return activeInstances.get(entityId);
    }

    /**
     * 移除指定實體的動畫實例。
     * 應在實體被刪除或不再需要動畫時呼叫。
     *
     * @param entityId 要移除的實體識別碼
     * @return 若實例存在則回傳 true
     */
    public static boolean remove(UUID entityId) {
        AnimatableInstance removed = activeInstances.remove(entityId);
        if (removed != null) {
            LOG.debug("移除實體 {} 的動畫實例", entityId);
            return true;
        }
        return false;
    }

    // ═══════════════════════════════════════════════════════════════
    //          公開靜態方法 - 動畫更新
    // ═══════════════════════════════════════════════════════════════

    /**
     * 更新所有活躍實例的動畫一個時間步。
     * 此方法應在遊戲更新迴圈中每幀呼叫。
     *
     * 此方法會：
     *   1. Tick 所有活躍實例的控制器
     *   2. 自動移除不再播放動畫的單控制器實例 (非迴圈)
     *
     * @param partialTick 部份時間步 (0.0 ~ 1.0)
     */
    public static void tick(float partialTick) {
        if (!initialized) {
            return;
        }

        // 更新所有實例，並收集要移除的
        List<UUID> toRemove = new ArrayList<>();

        for (Map.Entry<UUID, AnimatableInstance> entry : activeInstances.entrySet()) {
            AnimatableInstance instance = entry.getValue();
            instance.tick(partialTick);

            // 若實例只有一個控制器且不再播放，標記為移除候選
            if (!instance.isAnimating() && instance.getControllerCount() == 1) {
                toRemove.add(entry.getKey());
            }
        }

        // 移除標記的實例 (以避免在迭代中修改 map)
        for (UUID id : toRemove) {
            activeInstances.remove(id);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //          公開靜態方法 - 矩陣上傳到著色器
    // ═══════════════════════════════════════════════════════════════

    /**
     * 計算並上傳指定實體的骨骼蒙皮矩陣到著色器。
     * 若實體不存在，上傳單位矩陣 (回退)。
     *
     * 此方法執行以下步驟：
     *   1. 呼叫 instance.hierarchy.computeWorldTransforms(controllers)
     *   2. 呼叫 hierarchy.computeSkinningMatrices(buffer)
     *   3. 使用 MemoryStack 將矩陣上傳到 GPU
     *
     * @param shader   目標著色器程式
     * @param entityId 要上傳的實體識別碼
     */
    public static void uploadBoneMatrices(BRShaderProgram shader, UUID entityId) {
        if (!initialized || shader == null) {
            return;
        }

        AnimatableInstance instance = activeInstances.get(entityId);

        if (instance == null) {
            // 實體不存在: 上傳單位矩陣
            uploadIdentityMatrices(shader);
            return;
        }

        // 取得實例的蒙皮矩陣 (自動呼叫 computeSkinningMatrices 若需要)
        Matrix4f[] skinningMatrices = instance.getSkinningMatrices();
        int boneCount = Math.min(skinningMatrices.length, 128);

        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer buffer = stack.mallocFloat(boneCount * 16);

            // 將所有蒙皮矩陣寫入到 FloatBuffer
            for (int i = 0; i < boneCount; i++) {
                skinningMatrices[i].get(buffer);
            }

            buffer.flip();

            // 上傳到著色器的 uniform 陣列
            shader.setUniformMat4Array("u_boneMatrices", buffer, boneCount);
        }
    }

    /**
     * 上傳單位矩陣到著色器。
     * 此方法用於不需要骨骼動畫的實體，提供向後相容性。
     *
     * @param shader 目標著色器程式
     */
    public static void uploadBoneMatrices(BRShaderProgram shader) {
        if (!initialized || shader == null) {
            return;
        }

        uploadIdentityMatrices(shader);
    }

    /**
     * 上傳全部單位矩陣到著色器。
     * 用於非動畫實體或當實例不存在時的回退。
     *
     * @param shader 目標著色器程式
     */
    private static void uploadIdentityMatrices(BRShaderProgram shader) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer buffer = stack.mallocFloat(128 * 16);

            // 填充 128 個單位矩陣
            for (int i = 0; i < 128; i++) {
                boneMatrixUploadBuffer[i].identity().get(buffer);
            }

            buffer.flip();

            // 上傳到著色器
            shader.setUniformMat4Array("u_boneMatrices", buffer, 128);
        }
    }

    // ═══════════════════════════════════════════════════════════════
    //          便利方法 - 播放預設動畫
    // ═══════════════════════════════════════════════════════════════

    /**
     * 在指定實體上播放「方塊放置」動畫。
     * 自動建立實例 (若不存在) 並播放到預設的 "root" 控制器。
     *
     * @param entityId 目標實體識別碼
     */
    public static void playBlockPlacement(UUID entityId) {
        if (!initialized || blockPlacementClip == null) {
            return;
        }

        AnimatableInstance instance = getOrCreateInstance(entityId, BoneHierarchyType.BLOCK);
        AnimationController controller = instance.getOrCreateController("root");
        controller.play(blockPlacementClip);
    }

    /**
     * 在指定實體上播放「方塊破壞」動畫。
     *
     * @param entityId 目標實體識別碼
     */
    public static void playBlockDestroy(UUID entityId) {
        if (!initialized || blockDestroyClip == null) {
            return;
        }

        AnimatableInstance instance = getOrCreateInstance(entityId, BoneHierarchyType.BLOCK);
        AnimationController controller = instance.getOrCreateController("root");
        controller.play(blockDestroyClip);
    }

    /**
     * 在指定實體上播放「選擇脈衝」動畫。
     * 此動畫會連續循環。
     *
     * @param entityId 目標實體識別碼
     */
    public static void playSelectionPulse(UUID entityId) {
        if (!initialized || selectionPulseClip == null) {
            return;
        }

        AnimatableInstance instance = getOrCreateInstance(entityId, BoneHierarchyType.BLOCK);
        AnimationController controller = instance.getOrCreateController("root");
        controller.play(selectionPulseClip);
    }

    /**
     * 在指定實體上播放「結構崩塌」動畫。
     * 此動畫只播放一次，不循環。
     *
     * @param entityId 目標實體識別碼
     */
    public static void playStructureCollapse(UUID entityId) {
        if (!initialized || structureCollapseClip == null) {
            return;
        }

        AnimatableInstance instance = getOrCreateInstance(entityId, BoneHierarchyType.BLOCK);
        AnimationController controller = instance.getOrCreateController("root");
        controller.play(structureCollapseClip);
    }

    // ═══════════════════════════════════════════════════════════════
    //          公開靜態方法 - 查詢與統計
    // ═══════════════════════════════════════════════════════════════

    /**
     * 取得目前所有活躍實例中的總控制器數量。
     *
     * @return 活躍控制器總數
     */
    public static int getActiveControllerCount() {
        return activeInstances.values().stream()
                .mapToInt(AnimatableInstance::getControllerCount)
                .sum();
    }

    /**
     * 取得目前活躍的實體動畫實例數量。
     *
     * @return 實例數量
     */
    public static int getActiveInstanceCount() {
        return activeInstances.size();
    }

    /**
     * 取得「方塊放置」動畫片段。
     *
     * @return 動畫片段或 null (若引擎未初始化)
     */
    public static AnimationClip getBlockPlacementClip() {
        return blockPlacementClip;
    }

    /**
     * 取得「方塊破壞」動畫片段。
     *
     * @return 動畫片段或 null
     */
    public static AnimationClip getBlockDestroyClip() {
        return blockDestroyClip;
    }

    /**
     * 取得「選擇脈衝」動畫片段。
     *
     * @return 動畫片段或 null
     */
    public static AnimationClip getSelectionPulseClip() {
        return selectionPulseClip;
    }

    /**
     * 取得「結構崩塌」動畫片段。
     *
     * @return 動畫片段或 null
     */
    public static AnimationClip getStructureCollapseClip() {
        return structureCollapseClip;
    }

    /**
     * 取得引擎的初始化狀態。
     *
     * @return true 若引擎已初始化
     */
    public static boolean isInitialized() {
        return initialized;
    }

    // ═══════════════════════════════════════════════════════════════
    //          私有建構子 (防止實例化)
    // ═══════════════════════════════════════════════════════════════

    /**
     * 私有建構子。
     * BRAnimationEngine 是靜態工具類別，不應被實例化。
     */
    private BRAnimationEngine() {
        throw new AssertionError("無法實例化 BRAnimationEngine");
    }
}
