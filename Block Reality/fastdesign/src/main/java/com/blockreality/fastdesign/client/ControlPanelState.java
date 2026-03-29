package com.blockreality.fastdesign.client;

/**
 * Control Panel 客戶端狀態管理 — Level 3
 *
 * 追蹤當前選中的材質和參數，供 ControlPanelScreen 和 FdActionPacket 使用。
 */
public class ControlPanelState {

    /**
     * 支持的材質類型
     */
    public enum MaterialChoice {
        CONCRETE("concrete", "混凝土"),
        REBAR("rebar", "鋼筋"),
        STEEL("steel", "鋼材"),
        TIMBER("timber", "木材"),
        CUSTOM("custom", "自訂方塊");

        private final String id;
        private final String label;

        MaterialChoice(String id, String label) {
            this.id = id;
            this.label = label;
        }

        public String getId() { return id; }
        public String getLabel() { return label; }
    }

    // ─── 狀態 ───

    private static MaterialChoice selectedMaterial = MaterialChoice.CONCRETE;
    private static String customBlockId = "minecraft:stone";
    private static int rebarSpacing = 4;

    // ─── Getters ───

    public static MaterialChoice getSelectedMaterial() {
        return selectedMaterial;
    }

    public static String getCustomBlockId() {
        return customBlockId;
    }

    public static int getRebarSpacing() {
        return rebarSpacing;
    }

    // ─── Setters ───

    public static void setSelectedMaterial(MaterialChoice material) {
        selectedMaterial = material;
    }

    public static void setCustomBlockId(String blockId) {
        customBlockId = blockId;
    }

    public static void setRebarSpacing(int spacing) {
        rebarSpacing = Math.max(1, Math.min(16, spacing));
    }

    /**
     * 將當前狀態編碼為 payload 字串（用於 FdActionPacket）
     * 格式: "material=concrete,spacing=4" 或 "material=custom,block=minecraft:stone"
     */
    public static String encodePayload() {
        StringBuilder sb = new StringBuilder();
        sb.append("material=").append(selectedMaterial.getId());
        sb.append(",spacing=").append(rebarSpacing);
        if (selectedMaterial == MaterialChoice.CUSTOM) {
            sb.append(",block=").append(customBlockId);
        }
        return sb.toString();
    }

    /**
     * 從 payload 字串解碼材質 ID
     */
    public static String parseMaterialId(String payload) {
        for (String part : payload.split(",")) {
            if (part.startsWith("material=")) {
                return part.substring("material=".length());
            }
        }
        return "concrete";
    }

    /**
     * 從 payload 字串解碼鋼筋間距
     */
    public static int parseSpacing(String payload) {
        for (String part : payload.split(",")) {
            if (part.startsWith("spacing=")) {
                try {
                    return Integer.parseInt(part.substring("spacing=".length()));
                } catch (NumberFormatException e) {
                    return 4;
                }
            }
        }
        return 4;
    }

    /**
     * 從 payload 字串解碼自訂方塊 ID
     */
    public static String parseCustomBlock(String payload) {
        for (String part : payload.split(",")) {
            if (part.startsWith("block=")) {
                return part.substring("block=".length());
            }
        }
        return "minecraft:stone";
    }
}
