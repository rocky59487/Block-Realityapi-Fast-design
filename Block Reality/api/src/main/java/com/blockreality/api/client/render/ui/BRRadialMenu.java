package com.blockreality.api.client.render.ui;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * 徑向（圓餅）菜單 UI 框架，靈感來自 Inventory HUD+、MineMenu 和 Axiom 工具系統。
 *
 * 提供動畫狀態機、多層級子菜單支持、和實時角度選擇反饋。
 * 渲染邏輯由 BREffectRenderer 或疊加層處理。
 *
 * @author BlockReality Team
 * @version 1.0
 */
@OnlyIn(Dist.CLIENT)
public class BRRadialMenu {
    private static final Logger LOGGER = LoggerFactory.getLogger(BRRadialMenu.class);

    // ========================= 內部類別 =========================

    /**
     * 菜單項目定義。
     */
    public static class MenuItem {
        public final String id;
        public final String displayName;
        public final int iconIndex;
        public boolean enabled;
        public final List<MenuItem> children;
        public final Runnable onSelect;
        /** 工具提示文字（可為 null） */
        public final String tooltip;

        /**
         * 建立菜單項目。
         *
         * @param id 唯一識別碼
         * @param displayName 顯示名稱
         * @param iconIndex 圖標索引（用於渲染）
         * @param enabled 是否啟用
         * @param onSelect 選擇時的回調
         */
        public MenuItem(String id, String displayName, int iconIndex, boolean enabled, Runnable onSelect) {
            this(id, displayName, iconIndex, enabled, onSelect, null);
        }

        /**
         * 建立菜單項目（預設啟用）。
         */
        public MenuItem(String id, String displayName, int iconIndex, Runnable onSelect) {
            this(id, displayName, iconIndex, true, onSelect, null);
        }

        /**
         * 建立菜單項目（含工具提示）。
         *
         * @param id 唯一識別碼
         * @param displayName 顯示名稱
         * @param iconIndex 圖標索引（用於渲染）
         * @param enabled 是否啟用
         * @param onSelect 選擇時的回調
         * @param tooltip 工具提示文字（可為 null）
         */
        public MenuItem(String id, String displayName, int iconIndex, boolean enabled, Runnable onSelect, String tooltip) {
            this.id = id;
            this.displayName = displayName;
            this.iconIndex = iconIndex;
            this.enabled = enabled;
            this.children = new ArrayList<>();
            this.onSelect = onSelect;
            this.tooltip = tooltip;
        }

        /**
         * 新增子菜單項目。
         */
        public MenuItem addChild(MenuItem child) {
            this.children.add(child);
            return this;
        }
    }

    /**
     * 菜單環（圓形扇區的集合）。
     */
    public static class MenuRing {
        public final List<MenuItem> items;
        public float centerX;
        public float centerY;
        public float innerRadius;
        public float outerRadius;
        public int selectedIndex = -1;

        /**
         * 建立菜單環。
         */
        public MenuRing(List<MenuItem> items, float centerX, float centerY,
                       float innerRadius, float outerRadius) {
            this.items = new ArrayList<>(items);
            this.centerX = centerX;
            this.centerY = centerY;
            this.innerRadius = innerRadius;
            this.outerRadius = outerRadius;
        }

        /**
         * 根據角度（弧度）取得扇區索引。
         * 角度範圍：-π 到 π，0 在右側，π/2 在下方。
         *
         * @param angleRadians 角度（弧度）
         * @return 扇區索引，或 -1 如果在死區內
         */
        public int getSectorAtAngle(float angleRadians) {
            int sectorCount = items.size();
            float sectorAngle = (float)(2.0 * Math.PI / sectorCount);

            // 正規化角度到 [0, 2π)
            float normalizedAngle = angleRadians;
            if (normalizedAngle < 0) {
                normalizedAngle += 2.0f * (float)Math.PI;
            }

            // 偏移第一個扇區（使其居中於頂部）
            normalizedAngle -= sectorAngle / 2.0f;
            if (normalizedAngle < 0) {
                normalizedAngle += 2.0f * (float)Math.PI;
            }

            int sectorIndex = (int)(normalizedAngle / sectorAngle);
            return sectorIndex % sectorCount;
        }
    }

    /**
     * 菜單狀態枚舉。
     */
    public enum MenuState {
        /** 菜單已關閉 */
        CLOSED,
        /** 菜單正在打開（動畫中） */
        OPENING,
        /** 菜單已打開 */
        OPEN,
        /** 菜單正在關閉（動畫中） */
        CLOSING,
        /** 正在顯示子菜單 */
        SUB_MENU
    }

    /**
     * 菜單配置。
     */
    public static class MenuConfig {
        /** 啟動菜單的按鍵（GLFW 代碼） */
        public static int ACTIVATION_KEY = 86; // GLFW_KEY_V

        /** 打開動畫持續時間（毫秒） */
        public static int OPEN_DURATION_MS = 150;

        /** 關閉動畫持續時間（毫秒） */
        public static int CLOSE_DURATION_MS = 100;

        /** 子菜單展開延遲（毫秒） */
        public static int SUB_MENU_DELAY_MS = 200;

        /** 死區比例（內半徑比） */
        public static float DEAD_ZONE_RATIO = 0.2f;

        /** 主菜單扇區數 */
        public static int PRIMARY_SECTORS = 8;

        /** 子菜單最大扇區數 */
        public static int MAX_SUB_SECTORS = 6;

        /** 內半徑比例（相對於螢幕高度） */
        public static float INNER_RADIUS_RATIO = 0.25f;

        /** 外半徑比例（相對於螢幕高度） */
        public static float OUTER_RADIUS_RATIO = 0.40f;
    }

    /**
     * 菜單事件監聽器。
     */
    public interface MenuEventListener {
        /**
         * 菜單項目被選擇。
         */
        void onItemSelected(MenuItem item);

        /**
         * 菜單已打開。
         */
        void onMenuOpened();

        /**
         * 菜單已關閉。
         */
        void onMenuClosed();
    }

    /**
     * 菜單渲染資料（供外部渲染器使用）。
     */
    public static class MenuRenderData {
        public MenuState state;
        public float openProgress;
        public float closeProgress;
        public List<MenuItem> items;
        public int selectedIndex;
        public float centerX;
        public float centerY;
        public float innerRadius;
        public float outerRadius;
        public float[] sectorAngles;
        public int highlightColor = 0xFFFFCC00; // ARGB: 黃色
        public int backgroundColor = 0xAA000000; // ARGB: 半透明黑色
        public int subMenuParentIndex = -1;
        public List<MenuItem> subMenuItems;
        public int subMenuSelectedIndex = -1;
        public float subMenuInnerRadius;
        public float subMenuOuterRadius;
        /** 工具提示 X 座標 */
        public float tooltipX;
        /** 工具提示 Y 座標 */
        public float tooltipY;
    }

    // ========================= 靜態狀態 =========================

    private static MenuState state = MenuState.CLOSED;
    private static float openProgress = 0.0f;
    private static float closeProgress = 0.0f;

    private static MenuRing primaryRing;
    private static MenuRing subMenuRing;
    private static int subMenuParentIndex = -1;
    private static long subMenuHoverStartTime = -1;
    private static int lastHoveredSector = -1;

    private static MenuEventListener listener;
    private static int screenWidth = 1280;
    private static int screenHeight = 720;

    private static long lastMouseMoveTime = 0;
    private static double lastMouseX = 0.0;
    private static double lastMouseY = 0.0;

    // ========================= 公開方法 =========================

    /**
     * 初始化菜單，建立預設主菜單環。
     */
    public static void init() {
        LOGGER.info("初始化 BRRadialMenu");

        List<MenuItem> primaryItems = new ArrayList<>();

        // 8 個工具類別（中文命名）
        primaryItems.add(new MenuItem("build", "建造模式", 0, () ->
            fireEvent(item -> { if (listener != null) listener.onItemSelected(item); })));
        primaryItems.add(new MenuItem("select", "選擇工具", 1, () ->
            fireEvent(item -> { if (listener != null) listener.onItemSelected(item); })));
        primaryItems.add(new MenuItem("terraform", "地形工具", 2, () ->
            fireEvent(item -> { if (listener != null) listener.onItemSelected(item); })));
        primaryItems.add(new MenuItem("blueprint", "藍圖系統", 3, () ->
            fireEvent(item -> { if (listener != null) listener.onItemSelected(item); })));
        primaryItems.add(new MenuItem("ai_command", "AI 指令", 4, () ->
            fireEvent(item -> { if (listener != null) listener.onItemSelected(item); })));
        primaryItems.add(new MenuItem("camera", "相機/視角", 5, () ->
            fireEvent(item -> { if (listener != null) listener.onItemSelected(item); })));
        primaryItems.add(new MenuItem("settings", "設定", 6, () ->
            fireEvent(item -> { if (listener != null) listener.onItemSelected(item); })));
        primaryItems.add(new MenuItem("undo_redo", "復原/重做", 7, () ->
            fireEvent(item -> { if (listener != null) listener.onItemSelected(item); })));

        primaryRing = new MenuRing(primaryItems, screenWidth / 2.0f, screenHeight / 2.0f,
                                   screenHeight * MenuConfig.INNER_RADIUS_RATIO,
                                   screenHeight * MenuConfig.OUTER_RADIUS_RATIO);
    }

    /**
     * 清理菜單狀態。
     */
    public static void cleanup() {
        LOGGER.info("清理 BRRadialMenu");
        state = MenuState.CLOSED;
        openProgress = 0.0f;
        closeProgress = 0.0f;
        primaryRing = null;
        subMenuRing = null;
        subMenuParentIndex = -1;
        listener = null;
    }

    /**
     * 打開菜單。
     */
    public static void open() {
        if (state == MenuState.CLOSED || state == MenuState.CLOSING) {
            state = MenuState.OPENING;
            openProgress = 0.0f;
            closeProgress = 0.0f;
            primaryRing.selectedIndex = -1;
            subMenuParentIndex = -1;
            subMenuHoverStartTime = -1;
            lastHoveredSector = -1;

            if (listener != null) {
                listener.onMenuOpened();
            }
            LOGGER.debug("菜單打開");
        }
    }

    /**
     * 關閉菜單。
     */
    public static void close() {
        if (state != MenuState.CLOSED && state != MenuState.CLOSING) {
            state = MenuState.CLOSING;
            closeProgress = 0.0f;
            openProgress = 0.0f;
            subMenuParentIndex = -1;

            if (listener != null) {
                listener.onMenuClosed();
            }
            LOGGER.debug("菜單關閉");
        }
    }

    /**
     * 檢查菜單是否打開（包括動畫中的狀態）。
     */
    public static boolean isOpen() {
        return state == MenuState.OPEN || state == MenuState.OPENING ||
               state == MenuState.SUB_MENU;
    }

    /**
     * 設定主菜單項目。
     */
    public static void setPrimaryItems(List<MenuItem> items) {
        if (primaryRing != null) {
            primaryRing.items.clear();
            primaryRing.items.addAll(items);
            LOGGER.debug("更新主菜單項目，共 {} 項", items.size());
        }
    }

    /**
     * 取得主菜單項目。
     */
    public static List<MenuItem> getPrimaryItems() {
        return primaryRing != null ? Collections.unmodifiableList(primaryRing.items)
                                   : Collections.emptyList();
    }

    /**
     * 打開子菜單。
     */
    public static void openSubMenu(int parentIndex) {
        if (parentIndex >= 0 && parentIndex < primaryRing.items.size()) {
            MenuItem parentItem = primaryRing.items.get(parentIndex);
            if (!parentItem.children.isEmpty()) {
                subMenuParentIndex = parentIndex;
                state = MenuState.SUB_MENU;

                float subInnerRadius = primaryRing.outerRadius + 20.0f;
                float subOuterRadius = subInnerRadius + (primaryRing.outerRadius - primaryRing.innerRadius);

                subMenuRing = new MenuRing(parentItem.children,
                                          primaryRing.centerX, primaryRing.centerY,
                                          subInnerRadius, subOuterRadius);
                subMenuRing.selectedIndex = -1;

                LOGGER.debug("打開子菜單，父級索引: {}", parentIndex);
            }
        }
    }

    /**
     * 關閉子菜單。
     */
    public static void closeSubMenu() {
        if (state == MenuState.SUB_MENU) {
            subMenuRing = null;
            subMenuParentIndex = -1;
            state = MenuState.OPEN;
            subMenuHoverStartTime = -1;
            lastHoveredSector = -1;
            LOGGER.debug("關閉子菜單");
        }
    }

    /**
     * 處理滑鼠移動。
     *
     * @param mouseX 滑鼠 X 座標
     * @param mouseY 滑鼠 Y 座標
     * @param width 螢幕寬度
     * @param height 螢幕高度
     */
    public static void onMouseMove(double mouseX, double mouseY, int width, int height) {
        screenWidth = width;
        screenHeight = height;

        if (!isOpen()) {
            return;
        }

        lastMouseX = mouseX;
        lastMouseY = mouseY;
        lastMouseMoveTime = System.currentTimeMillis();

        if (primaryRing == null) {
            return;
        }

        float centerX = primaryRing.centerX;
        float centerY = primaryRing.centerY;

        float dx = (float)(mouseX - centerX);
        float dy = (float)(mouseY - centerY);
        float distance = (float)Math.sqrt(dx * dx + dy * dy);

        // 計算角度
        float angle = (float)Math.atan2(dy, dx);

        MenuRing activeRing = (state == MenuState.SUB_MENU && subMenuRing != null)
                             ? subMenuRing : primaryRing;

        // 檢查死區
        float deadZoneRadius = activeRing.innerRadius * MenuConfig.DEAD_ZONE_RATIO;
        if (distance < deadZoneRadius) {
            activeRing.selectedIndex = -1;
            lastHoveredSector = -1;
            return;
        }

        // 檢查是否在有效範圍內
        if (distance >= activeRing.innerRadius && distance <= activeRing.outerRadius) {
            int sectorIndex = activeRing.getSectorAtAngle(angle);

            if (sectorIndex >= 0 && sectorIndex < activeRing.items.size()) {
                MenuItem item = activeRing.items.get(sectorIndex);
                if (item.enabled) {
                    int previousIndex = activeRing.selectedIndex;
                    activeRing.selectedIndex = sectorIndex;

                    // 選中索引改變時播放懸停音效
                    if (sectorIndex != previousIndex && soundCallback != null) {
                        soundCallback.playSound("menu_hover");
                    }

                    // 子菜單懸停邏輯
                    if (state == MenuState.OPEN && sectorIndex != lastHoveredSector) {
                        lastHoveredSector = sectorIndex;
                        subMenuHoverStartTime = System.currentTimeMillis();
                    }
                } else {
                    activeRing.selectedIndex = -1;
                }
            }
        } else {
            activeRing.selectedIndex = -1;
        }
    }

    /**
     * 處理滑鼠釋放（確認選擇並關閉）。
     */
    public static void onMouseRelease() {
        if (!isOpen()) {
            return;
        }

        MenuRing activeRing = (state == MenuState.SUB_MENU && subMenuRing != null)
                             ? subMenuRing : primaryRing;

        if (activeRing != null && activeRing.selectedIndex >= 0) {
            MenuItem selectedItem = activeRing.items.get(activeRing.selectedIndex);

            if (selectedItem.enabled) {
                // 播放選擇音效
                if (soundCallback != null) {
                    soundCallback.playSound("menu_select");
                }

                if (listener != null) {
                    listener.onItemSelected(selectedItem);
                }

                if (selectedItem.onSelect != null) {
                    selectedItem.onSelect.run();
                }

                close();
                LOGGER.debug("選擇菜單項: {}", selectedItem.id);
            }
        }
    }

    /**
     * 處理按鍵按下。
     *
     * @param keyCode GLFW 按鍵代碼
     */
    public static void onKeyPress(int keyCode) {
        if (keyCode == MenuConfig.ACTIVATION_KEY) {
            if (isOpen()) {
                close();
            } else {
                open();
            }
        }
    }

    /**
     * 設定事件監聽器。
     */
    public static void setListener(MenuEventListener listener) {
        BRRadialMenu.listener = listener;
    }

    /**
     * 取得渲染資料。
     *
     * @return 包含菜單狀態和幾何資訊的渲染資料
     */
    public static MenuRenderData getRenderData() {
        MenuRenderData data = new MenuRenderData();
        data.state = state;
        data.openProgress = cubicEaseOut(openProgress);
        data.closeProgress = cubicEaseOut(closeProgress);

        if (primaryRing != null) {
            data.centerX = primaryRing.centerX;
            data.centerY = primaryRing.centerY;
            data.innerRadius = primaryRing.innerRadius;
            data.outerRadius = primaryRing.outerRadius;
            data.items = primaryRing.items;
            data.selectedIndex = primaryRing.selectedIndex;

            // 預先計算扇區角度
            int sectorCount = primaryRing.items.size();
            data.sectorAngles = new float[sectorCount];
            float sectorAngle = (float)(2.0 * Math.PI / sectorCount);
            for (int i = 0; i < sectorCount; i++) {
                data.sectorAngles[i] = i * sectorAngle - sectorAngle / 2.0f;
            }
        }

        if (state == MenuState.SUB_MENU && subMenuRing != null) {
            data.subMenuParentIndex = subMenuParentIndex;
            data.subMenuItems = subMenuRing.items;
            data.subMenuSelectedIndex = subMenuRing.selectedIndex;
            data.subMenuInnerRadius = subMenuRing.innerRadius;
            data.subMenuOuterRadius = subMenuRing.outerRadius;
        }

        // 設定工具提示座標（使用最後的滑鼠位置）
        data.tooltipX = (float) lastMouseX;
        data.tooltipY = (float) lastMouseY;

        return data;
    }

    /**
     * 更新菜單動畫和狀態（每幀調用）。
     *
     * @param deltaMs 自上次更新以來的毫秒數
     */
    public static void tick(float deltaMs) {
        if (state == MenuState.OPENING) {
            openProgress += deltaMs / MenuConfig.OPEN_DURATION_MS;
            if (openProgress >= 1.0f) {
                openProgress = 1.0f;
                state = MenuState.OPEN;
            }
        } else if (state == MenuState.CLOSING) {
            closeProgress += deltaMs / MenuConfig.CLOSE_DURATION_MS;
            if (closeProgress >= 1.0f) {
                closeProgress = 1.0f;
                state = MenuState.CLOSED;
            }
        }

        // 子菜單自動展開
        if (state == MenuState.OPEN && subMenuHoverStartTime > 0) {
            long elapsed = System.currentTimeMillis() - subMenuHoverStartTime;
            if (elapsed >= MenuConfig.SUB_MENU_DELAY_MS && lastHoveredSector >= 0) {
                openSubMenu(lastHoveredSector);
                subMenuHoverStartTime = -1;
            }
        }
    }

    /**
     * 檢查菜單是否應該通過按鍵激活。
     * 用於外部輸入處理系統。
     *
     * @return 是否處理了按鍵
     */
    public static boolean shouldActivateOnKey(int keyCode) {
        return keyCode == MenuConfig.ACTIVATION_KEY;
    }

    // ========================= 私有輔助方法 =========================

    /**
     * 觸發事件回調。
     */
    private static void fireEvent(java.util.function.Consumer<MenuItem> callback) {
        // 此方法為事件系統預留
    }

    // ========================= 手柄/控制器支援 =========================

    /** 手柄搖桿死區閾值 */
    private static final float GAMEPAD_DEADZONE = 0.2f;

    /** 手柄輸入是否處於活動狀態 */
    private static boolean gamepadActive = false;

    /**
     * 處理手柄右搖桿輸入，映射到徑向菜單選擇。
     * 搖桿值在 -1.0 到 1.0 範圍內，套用 20% 死區。
     *
     * @param stickX 右搖桿 X 軸值（-1.0 到 1.0）
     * @param stickY 右搖桿 Y 軸值（-1.0 到 1.0）
     */
    public static void onGamepadStick(float stickX, float stickY) {
        if (!isOpen() || primaryRing == null) {
            return;
        }

        float magnitude = (float) Math.sqrt(stickX * stickX + stickY * stickY);

        // 套用死區
        if (magnitude < GAMEPAD_DEADZONE) {
            gamepadActive = false;
            MenuRing activeRing = (state == MenuState.SUB_MENU && subMenuRing != null)
                                 ? subMenuRing : primaryRing;
            activeRing.selectedIndex = -1;
            return;
        }

        gamepadActive = true;

        // 計算搖桿角度並映射到扇區
        float angle = (float) Math.atan2(stickY, stickX);

        MenuRing activeRing = (state == MenuState.SUB_MENU && subMenuRing != null)
                             ? subMenuRing : primaryRing;

        int previousIndex = activeRing.selectedIndex;
        int sectorIndex = activeRing.getSectorAtAngle(angle);

        if (sectorIndex >= 0 && sectorIndex < activeRing.items.size()) {
            MenuItem item = activeRing.items.get(sectorIndex);
            if (item.enabled) {
                activeRing.selectedIndex = sectorIndex;

                // 選中索引改變時播放懸停音效
                if (sectorIndex != previousIndex && soundCallback != null) {
                    soundCallback.playSound("menu_hover");
                }
            } else {
                activeRing.selectedIndex = -1;
            }
        }
    }

    /**
     * 處理手柄確認按鈕（A 鍵），確認當前選擇。
     */
    public static void onGamepadConfirm() {
        if (!isOpen()) {
            return;
        }

        MenuRing activeRing = (state == MenuState.SUB_MENU && subMenuRing != null)
                             ? subMenuRing : primaryRing;

        if (activeRing != null && activeRing.selectedIndex >= 0) {
            MenuItem selectedItem = activeRing.items.get(activeRing.selectedIndex);

            if (selectedItem.enabled) {
                // 播放選擇音效
                if (soundCallback != null) {
                    soundCallback.playSound("menu_select");
                }

                if (listener != null) {
                    listener.onItemSelected(selectedItem);
                }

                if (selectedItem.onSelect != null) {
                    selectedItem.onSelect.run();
                }

                close();
                gamepadActive = false;
                LOGGER.debug("手柄確認選擇菜單項: {}", selectedItem.id);
            }
        }
    }

    /**
     * 處理手柄取消按鈕（B 鍵），關閉菜單。
     */
    public static void onGamepadCancel() {
        if (isOpen()) {
            if (state == MenuState.SUB_MENU) {
                closeSubMenu();
            } else {
                close();
            }
            gamepadActive = false;
            LOGGER.debug("手柄取消關閉菜單");
        }
    }

    // ========================= 三次緩動函式 =========================

    /**
     * 三次緩出函式：1 - (1 - t)^3
     * 提供快速開始、緩慢結束的動畫效果。
     *
     * @param t 線性進度值（0.0 到 1.0）
     * @return 緩動後的進度值
     */
    private static float cubicEaseOut(float t) {
        float inv = 1.0f - t;
        return 1.0f - inv * inv * inv;
    }

    /**
     * 三次緩入緩出函式。
     * 前半段加速，後半段減速，提供平滑的動畫過渡。
     *
     * @param t 線性進度值（0.0 到 1.0）
     * @return 緩動後的進度值
     */
    public static float cubicEaseInOut(float t) {
        if (t < 0.5f) {
            return 4.0f * t * t * t;
        } else {
            float inv = -2.0f * t + 2.0f;
            return 1.0f - inv * inv * inv / 2.0f;
        }
    }

    /**
     * 取得經過緩動處理的打開進度。
     *
     * @return 緩動後的打開進度值（0.0 到 1.0）
     */
    public static float getEasedOpenProgress() {
        return cubicEaseOut(openProgress);
    }

    /**
     * 取得經過緩動處理的關閉進度。
     *
     * @return 緩動後的關閉進度值（0.0 到 1.0）
     */
    public static float getEasedCloseProgress() {
        return cubicEaseOut(closeProgress);
    }

    // ========================= 工具提示 =========================

    /**
     * 取得當前懸停項目的工具提示文字。
     *
     * @return 工具提示文字，如果沒有懸停項目或該項目無工具提示則返回 null
     */
    public static String getActiveTooltip() {
        if (!isOpen()) {
            return null;
        }

        MenuRing activeRing = (state == MenuState.SUB_MENU && subMenuRing != null)
                             ? subMenuRing : primaryRing;

        if (activeRing == null || activeRing.selectedIndex < 0) {
            return null;
        }

        MenuItem hoveredItem = activeRing.items.get(activeRing.selectedIndex);
        return hoveredItem.tooltip;
    }

    // ========================= 音效回調 =========================

    /**
     * 音效回調功能介面。
     * 用於將菜單 UI 事件與遊戲音效系統連接。
     */
    @FunctionalInterface
    public interface SoundCallback {
        /**
         * 播放指定的音效。
         *
         * @param soundId 音效識別碼（例如 "menu_hover"、"menu_select"）
         */
        void playSound(String soundId);
    }

    /** 音效回調實例 */
    private static SoundCallback soundCallback;

    /**
     * 設定音效回調。
     * 設定後，菜單在懸停和選擇時將觸發對應的音效事件。
     *
     * @param callback 音效回調實例，傳入 null 可停用音效
     */
    public static void setSoundCallback(SoundCallback callback) {
        soundCallback = callback;
    }
}
