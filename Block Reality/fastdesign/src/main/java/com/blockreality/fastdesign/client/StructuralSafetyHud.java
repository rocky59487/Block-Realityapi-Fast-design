package com.blockreality.fastdesign.client;

import com.blockreality.fastdesign.FastDesignMod;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Quaternionf;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 結構安全 HUD — 每個結構元素的利用率 %（P3-B Killer Feature）
 *
 * <p><b>功能：</b>
 * <ul>
 *   <li>3D 世界空間浮動標籤 — 在每個結構方塊正上方顯示利用率 %，
 *       僅在 {@code RENDER_DISTANCE} = 32 格內渲染，billboard 朝向相機</li>
 *   <li>顏色編碼：
 *       <ul>
 *         <li>§a 綠色  (0–50%)  — 安全</li>
 *         <li>§e 黃色 (50–80%) — 注意</li>
 *         <li>§6 橙色 (80–90%) — 警告</li>
 *         <li>§c 紅色 (90–100%) — 危險</li>
 *         <li>閃爍紫色 (>100%) — 結構失效</li>
 *       </ul>
 *   </li>
 *   <li>2D 摘要面板（右上角）：
 *       最大利用率、臨界元素數量、整體結構健康評分（A–F）</li>
 *   <li>切換按鍵：J 鍵（可在控制設定中更改）</li>
 * </ul>
 *
 * <p><b>資料流：</b>伺服器物理計算後發出 {@code StructuralSyncPacket}，
 * 呼叫 {@link #setBlockStress} 更新客戶端快取；無伺服器資料時使用本地估算。
 *
 * @since P3-B
 */
@OnlyIn(Dist.CLIENT)
public class StructuralSafetyHud {

    // ─── 常數 ─────────────────────────────────────────────────────────────────

    /** 3D 標籤最大渲染距離（方塊） */
    private static final double RENDER_DISTANCE = 32.0;

    /** 超過此利用率（100%）則視為結構失效 */
    private static final float OVERLOAD_THRESHOLD = 1.0f;

    /** 2D 面板：最多顯示多少個臨界元素 */
    private static final int MAX_CRITICAL_ENTRIES = 6;

    /** 攝影機可見錐：忽略在玩家背後超過此距離的方塊（角度餘弦值） */
    private static final double MIN_DOT_FORWARD = -0.3;

    // ─── 按鍵綁定 ─────────────────────────────────────────────────────────────

    public static final KeyMapping TOGGLE_HUD = new KeyMapping(
        "key.fastdesign.structural_hud",
        InputConstants.KEY_J,
        "key.categories.fastdesign"
    );

    // ─── 資料模型 ──────────────────────────────────────────────────────────────

    /**
     * 單一方塊的應力快照（由伺服器同步封包寫入，由渲染執行緒讀取）。
     *
     * @param stressLevel   歸一化應力（0.0 = 無應力，1.0 = 設計極限，>1.0 = 超載）
     * @param materialId    材料 ID（用於工具提示）
     * @param role          結構角色（"COLUMN", "BEAM", "WALL", "SLAB", "GENERIC"）
     * @param timestamp     收到資料的時間戳（用於淡出舊資料）
     */
    public record BlockStressData(
            float stressLevel,
            String materialId,
            String role,
            long timestamp
    ) {
        /** 利用率百分比（0–100+） */
        public float utilizationPct() { return stressLevel * 100f; }

        /** 資料是否過期（超過 10 秒沒有更新） */
        public boolean isStale(long now) { return now - timestamp > 10_000L; }
    }

    // ConcurrentHashMap 保證多執行緒安全（伺服器封包執行緒 ↔ 渲染執行緒）
    private static final ConcurrentHashMap<BlockPos, BlockStressData> STRESS_DATA =
            new ConcurrentHashMap<>(128);

    private static volatile boolean visible = false;

    // ─── 平滑動畫狀態（僅渲染執行緒存取）─────────────────────────────────────

    private static float smoothMaxUtil = 0f;
    private static long lastPanelRenderMs = 0;

    // ─── 公開 API ─────────────────────────────────────────────────────────────

    /**
     * 由網路封包呼叫，更新指定方塊的應力資料。
     * 執行緒安全（ConcurrentHashMap 寫入）。
     *
     * @param pos        方塊座標
     * @param stress     歸一化應力（0.0–∞）
     * @param materialId 材料 ID（e.g. "concrete", "steel"）
     * @param role       結構角色（e.g. "COLUMN"）
     */
    public static void setBlockStress(BlockPos pos, float stress, String materialId, String role) {
        STRESS_DATA.put(pos.immutable(),
            new BlockStressData(stress, materialId, role, System.currentTimeMillis()));
    }

    /**
     * 批量更新（由 StructuralSyncPacket 在一次封包中更新多個方塊）。
     *
     * @param entries Map from BlockPos to stress float
     */
    public static void batchUpdate(Map<BlockPos, float[]> entries) {
        long now = System.currentTimeMillis();
        for (Map.Entry<BlockPos, float[]> e : entries.entrySet()) {
            float[] v = e.getValue();  // [0]=stress, [1]=matId hash (unused), [2]=role ordinal
            String role = v.length > 2 ? roleFromOrdinal((int) v[2]) : "GENERIC";
            STRESS_DATA.put(e.getKey().immutable(),
                new BlockStressData(v[0], "unknown", role, now));
        }
    }

    /**
     * 清空所有應力資料（例如：玩家離開世界、伺服器重置）。
     */
    public static void clearAll() {
        STRESS_DATA.clear();
        smoothMaxUtil = 0f;
    }

    /**
     * 切換 HUD 可見性。
     */
    public static void toggle() {
        visible = !visible;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.displayClientMessage(
                Component.literal(visible
                    ? "§a[BR] §f結構安全 HUD §a已開啟"
                    : "§7[BR] §f結構安全 HUD §7已關閉"),
                true
            );
        }
    }

    public static boolean isVisible() { return visible; }

    // ─── 事件訂閱 ─────────────────────────────────────────────────────────────

    /** Mod 事件匯流排 — 註冊按鍵 */
    @Mod.EventBusSubscriber(modid = FastDesignMod.MOD_ID,
        bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static class ModEvents {
        @SubscribeEvent
        public static void onRegisterKeys(RegisterKeyMappingsEvent event) {
            event.register(TOGGLE_HUD);
        }
    }

    /** Forge 事件匯流排 — 渲染 + 按鍵監聽 */
    @Mod.EventBusSubscriber(modid = FastDesignMod.MOD_ID,
        bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
    public static class ForgeEvents {

        /** 每 tick 處理按鍵切換 + 過期資料清除（每 200 tick ≈ 10 秒） */
        private static int cleanupCounter = 0;

        @SubscribeEvent
        public static void onClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase != TickEvent.Phase.END) return;

            // 切換按鍵
            while (TOGGLE_HUD.consumeClick()) {
                toggle();
            }

            // 定期清除過期資料
            if (++cleanupCounter >= 200) {
                cleanupCounter = 0;
                long now = System.currentTimeMillis();
                STRESS_DATA.entrySet().removeIf(e -> e.getValue().isStale(now));
            }
        }

        /**
         * 3D 世界空間浮動標籤渲染。
         * 在 AFTER_SOLID_BLOCKS 階段注入，確保深度測試正確。
         */
        @SubscribeEvent
        public static void onRenderLevel(RenderLevelStageEvent event) {
            if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_SOLID_BLOCKS) return;
            if (!visible || STRESS_DATA.isEmpty()) return;

            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null || mc.level == null) return;

            Vec3 camPos = mc.gameRenderer.getMainCamera().getPosition();
            PoseStack poseStack = event.getPoseStack();
            Font font = mc.font;

            // 前方方向（相機朝向向量）
            Vec3 forward = new Vec3(
                mc.player.getLookAngle().x,
                mc.player.getLookAngle().y,
                mc.player.getLookAngle().z
            );

            MultiBufferSource.BufferSource bufferSource =
                mc.renderBuffers().bufferSource();

            long now = System.currentTimeMillis();

            for (Map.Entry<BlockPos, BlockStressData> entry : STRESS_DATA.entrySet()) {
                BlockPos pos = entry.getKey();
                BlockStressData data = entry.getValue();

                if (data.isStale(now)) continue;

                // 方塊中心偏移（相對相機）
                double dx = pos.getX() + 0.5 - camPos.x;
                double dy = pos.getY() + 1.5 - camPos.y;  // 浮在方塊上方
                double dz = pos.getZ() + 0.5 - camPos.z;
                double distSq = dx * dx + dy * dy + dz * dz;

                if (distSq > RENDER_DISTANCE * RENDER_DISTANCE) continue;

                // 視野錐剔除（忽略背後方塊）
                double dist = Math.sqrt(distSq);
                double dotFwd = (dx * forward.x + dy * forward.y + dz * forward.z) / dist;
                if (dotFwd < MIN_DOT_FORWARD) continue;

                // 深度縮放：距離越遠字越小（16 格以外開始縮小）
                float scale = (float) Math.min(1.0, 12.0 / Math.max(dist, 6.0)) * 0.025f;
                if (scale < 0.004f) continue;

                String label = formatLabel(data, now);

                poseStack.pushPose();
                poseStack.translate(dx, dy, dz);
                // Billboard：旋轉至朝向相機（與 SelectionOverlayRenderer 相同做法）
                Quaternionf billboardRot = mc.getEntityRenderDispatcher().cameraOrientation();
                poseStack.mulPose(billboardRot);
                poseStack.scale(-scale, -scale, scale);

                // 背景矩形（半透明黑底）
                int textW = font.width(label);
                int bgAlpha = 0x88000000;
                // 使用填充矩形作為背景（需要透過 BufferSource 繪製）
                // 注意：GuiGraphics 在 3D 渲染中不直接可用，使用 font.drawInBatch
                // 半透明背景：繪製在文字後方（z offset -0.01f）
                font.drawInBatch(
                    Component.literal(label),
                    -textW / 2f, -4f,
                    utilizationToColor(data.stressLevel),
                    false,           // shadow
                    poseStack.last().pose(),
                    bufferSource,
                    Font.DisplayMode.SEE_THROUGH,  // 穿透幾何（不被方塊遮擋）
                    bgAlpha,         // background color（黑底）
                    0xF000F0         // packed light
                );

                poseStack.popPose();
            }

            // 刷新 buffer
            bufferSource.endBatch();
        }

        /**
         * 2D 摘要面板（右上角）。
         */
        @SubscribeEvent
        public static void onRenderGuiPost(RenderGuiOverlayEvent.Post event) {
            if (!visible || STRESS_DATA.isEmpty()) return;

            Minecraft mc = Minecraft.getInstance();
            if (mc.player == null) return;

            GuiGraphics gui = event.getGuiGraphics();
            int screenW = gui.guiWidth();

            // ─── 計算統計 ───
            long now = System.currentTimeMillis();
            float maxUtil = 0f;
            int criticalCount = 0;
            int warningCount = 0;
            int totalElements = 0;

            // 按利用率排序的臨界元素清單（前 MAX_CRITICAL_ENTRIES 個）
            List<Map.Entry<BlockPos, BlockStressData>> sortedEntries = new ArrayList<>();
            for (Map.Entry<BlockPos, BlockStressData> e : STRESS_DATA.entrySet()) {
                if (!e.getValue().isStale(now)) {
                    sortedEntries.add(e);
                    totalElements++;
                    float u = e.getValue().stressLevel;
                    if (u > maxUtil) maxUtil = u;
                    if (u >= 0.90f) criticalCount++;
                    else if (u >= 0.50f) warningCount++;
                }
            }
            sortedEntries.sort((a, b) ->
                Float.compare(b.getValue().stressLevel, a.getValue().stressLevel));

            if (totalElements == 0) return;

            // ─── 平滑最大值 ───
            long renderMs = System.currentTimeMillis();
            float dt = lastPanelRenderMs > 0 ? (renderMs - lastPanelRenderMs) / 1000f : 0.016f;
            lastPanelRenderMs = renderMs;
            smoothMaxUtil += (maxUtil - smoothMaxUtil) * Math.min(1f, dt * 4f);

            // ─── 面板尺寸 ───
            int entryCount = Math.min(sortedEntries.size(), MAX_CRITICAL_ENTRIES);
            int panelW = 200;
            int panelH = 30 + entryCount * 14 + 20;
            int px = screenW - panelW - 8;
            int py = 10;

            // ─── 背景 ───
            gui.fill(px, py, px + panelW, py + panelH, 0x90000000);
            // 左邊框（顏色依最大利用率）
            int borderColor = utilizationToBorderColor(smoothMaxUtil);
            gui.fill(px, py, px + 2, py + panelH, borderColor);

            int y = py + 5;

            // ─── 標題 ───
            gui.drawString(mc.font, "§b⚡ 結構安全監控", px + 6, y, 0xFFFFFF);
            y += 12;

            // ─── 整體健康評分 ───
            String grade = structuralGrade(smoothMaxUtil, criticalCount, totalElements);
            String gradeLabel = "健康評分: " + grade;
            String statsLabel = "§7" + totalElements + " 元素";
            gui.drawString(mc.font, gradeLabel, px + 6, y, 0xFFFFFF);
            int statsW = mc.font.width(statsLabel);
            gui.drawString(mc.font, statsLabel, px + panelW - statsW - 6, y, 0xFFFFFF);
            y += 12;

            // ─── 最大利用率進度條 ───
            int barW = panelW - 12;
            float clamped = Math.min(1.2f, smoothMaxUtil);
            int fillW = (int) (barW * clamped / 1.2f);
            gui.fill(px + 6, y, px + 6 + barW, y + 6, 0xFF333333);
            gui.fill(px + 6, y, px + 6 + fillW, y + 6,
                utilizationToArgb(smoothMaxUtil));
            // 100% 標記線
            int mark100 = (int) (barW * (1.0f / 1.2f));
            gui.fill(px + 6 + mark100, y, px + 6 + mark100 + 1, y + 6, 0xFFFFFFFF);
            String maxLabel = String.format("§f最大: %.1f%%", smoothMaxUtil * 100);
            gui.drawString(mc.font, maxLabel, px + 6, y + 8, 0xFFFFFF);
            y += 20;

            // ─── 臨界元素清單 ───
            if (entryCount > 0) {
                gui.fill(px + 6, y, px + panelW - 6, y + 1, 0x40FFFFFF);
                y += 3;
                for (int i = 0; i < entryCount; i++) {
                    Map.Entry<BlockPos, BlockStressData> e = sortedEntries.get(i);
                    BlockPos p = e.getKey();
                    BlockStressData d = e.getValue();

                    String roleShort = roleShort(d.role());
                    String posStr = String.format("(%d,%d,%d)", p.getX(), p.getY(), p.getZ());
                    String pctStr = String.format("%.0f%%", d.utilizationPct());
                    String colorCode = utilizationColorCode(d.stressLevel());

                    // 角色 + 座標
                    String leftText = "§7" + roleShort + " " + posStr;
                    gui.drawString(mc.font, leftText, px + 6, y, 0xFFFFFF);

                    // 利用率（靠右）
                    String rightText = colorCode + pctStr;
                    int rightW = mc.font.width(rightText);
                    gui.drawString(mc.font, rightText, px + panelW - rightW - 6, y, 0xFFFFFF);

                    y += 14;
                }
            }
        }
    }

    // ─── 輔助方法 ─────────────────────────────────────────────────────────────

    /**
     * 格式化 3D 標籤文字，帶有顏色和利用率 %。
     */
    private static String formatLabel(BlockStressData data, long now) {
        float util = data.stressLevel();
        String pct = String.format("%.0f%%", util * 100);
        String colorCode = utilizationColorCode(util);

        // 超載時加前綴圖示
        if (util > OVERLOAD_THRESHOLD) {
            // 閃爍效果：使用時間驅動的字元
            long flashPhase = (now / 400) % 2;
            String warn = flashPhase == 0 ? "§d⚠" : "§c⚠";
            return warn + " " + colorCode + pct;
        }
        return colorCode + pct;
    }

    /**
     * 根據利用率返回 ARGB 整數顏色（用於 font.drawInBatch 的 textColor 參數）。
     */
    private static int utilizationToColor(float stress) {
        if (stress > 1.0f) {
            // 超載：閃爍紫色
            float f = 0.7f + 0.3f * (float) Math.sin(System.currentTimeMillis() * 0.01);
            int v = (int) (255 * f);
            return 0xFF000000 | (v << 16) | v;  // 紫色
        }
        if (stress >= 0.9f) return 0xFFFF2222;  // 紅色：危險
        if (stress >= 0.8f) return 0xFFFF8800;  // 橙色：警告
        if (stress >= 0.5f) return 0xFFFFDD00;  // 黃色：注意
        return 0xFF44FF44;                       // 綠色：安全
    }

    /**
     * 根據利用率返回 §x 格式顏色前綴字串（用於 GuiGraphics.drawString）。
     */
    private static String utilizationColorCode(float stress) {
        if (stress > 1.0f) return "§d";  // 淡紫（超載）
        if (stress >= 0.9f) return "§c"; // 紅
        if (stress >= 0.8f) return "§6"; // 橙
        if (stress >= 0.5f) return "§e"; // 黃
        return "§a";                      // 綠
    }

    /**
     * 利用率 → ARGB 整數（用於 GUI fill 進度條）。
     */
    private static int utilizationToArgb(float stress) {
        if (stress > 1.0f) {
            float f = 0.7f + 0.3f * (float) Math.sin(System.currentTimeMillis() * 0.01);
            return 0xFF000000 | ((int)(220 * f) << 16) | (int)(220 * f);
        }
        if (stress >= 0.9f) return 0xFFFF2222;
        if (stress >= 0.8f) return 0xFFFF8800;
        if (stress >= 0.5f) return 0xFFFFDD00;
        return 0xFF44FF44;
    }

    /**
     * 利用率 → 左邊框顏色（面板邊框）。
     */
    private static int utilizationToBorderColor(float stress) {
        if (stress >= 0.9f) {
            float f = 0.6f + 0.4f * (float) Math.sin(System.currentTimeMillis() * 0.008);
            return 0xFF000000 | ((int)(255 * f) << 16) | 0x1400;
        }
        if (stress >= 0.5f) return 0xFFFFAA00;
        return 0xFF00CC44;
    }

    /**
     * 計算整體結構健康評分（A–F）。
     *
     * <ul>
     *   <li>A — 最大利用率 &lt; 50%，無臨界元素</li>
     *   <li>B — 最大利用率 &lt; 70%</li>
     *   <li>C — 最大利用率 &lt; 80%</li>
     *   <li>D — 最大利用率 &lt; 90%</li>
     *   <li>E — 最大利用率 &lt; 100%</li>
     *   <li>F — 任何元素超載（>100%）</li>
     * </ul>
     */
    private static String structuralGrade(float maxUtil, int criticalCount, int totalElements) {
        if (maxUtil > 1.0f || criticalCount > 0) return "§cF";
        if (maxUtil >= 0.9f) return "§cE";
        if (maxUtil >= 0.8f) return "§6D";
        if (maxUtil >= 0.7f) return "§6C";
        if (maxUtil >= 0.5f) return "§eB";
        return "§aA";
    }

    /**
     * 結構角色縮寫（3 字母，用於 2D 面板清單）。
     */
    private static String roleShort(String role) {
        if (role == null) return "GEN";
        return switch (role) {
            case "COLUMN"  -> "COL";
            case "BEAM"    -> "BEM";
            case "WALL"    -> "WAL";
            case "SLAB"    -> "SLB";
            default        -> "GEN";
        };
    }

    /**
     * 將角色序號還原為字串（由 batchUpdate 使用）。
     */
    private static String roleFromOrdinal(int ordinal) {
        return switch (ordinal) {
            case 0 -> "COLUMN";
            case 1 -> "BEAM";
            case 2 -> "WALL";
            case 3 -> "SLAB";
            default -> "GENERIC";
        };
    }
}
