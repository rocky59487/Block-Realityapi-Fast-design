package com.blockreality.api.construction;

/**
 * 施工工序六階段 — v3fix §3.2
 */
public enum ConstructionPhase {

    EXCAVATION("開挖地基",     "Excavation",  new String[]{}),
    ANCHOR    ("打錨定樁",     "Anchoring",   new String[]{"blockreality:r_concrete"}),
    REBAR     ("綁鋼筋網",     "Rebar Tying", new String[]{"minecraft:iron_bars",
                                                           "blockreality:r_rebar"}),
    FORMWORK  ("架模板",       "Formwork",    new String[]{"minecraft:oak_planks",
                                                           "blockreality:r_timber"}),
    POUR      ("澆灌混凝土",   "Pouring",     new String[]{"blockreality:r_concrete"}),
    CURE      ("養護凝固",     "Curing",      new String[]{});

    private final String displayNameZh;
    private final String displayNameEn;
    private final String[] allowedBlocks;

    ConstructionPhase(String displayNameZh, String displayNameEn, String[] allowedBlocks) {
        this.displayNameZh = displayNameZh;
        this.displayNameEn = displayNameEn;
        this.allowedBlocks = allowedBlocks;
    }

    public String getDisplayNameZh() { return displayNameZh; }
    public String getDisplayNameEn() { return displayNameEn; }
    public String[] getAllowedBlocks() { return allowedBlocks; }

    public ConstructionPhase next() {
        int idx = this.ordinal() + 1;
        return (idx < values().length) ? values()[idx] : CURE;
    }

    public boolean isFinal() {
        return this == CURE;
    }

    public boolean isAllowed(String blockId) {
        if (this == EXCAVATION) return true;
        if (this == CURE) return false;
        for (String allowed : allowedBlocks) {
            if (allowed.equals(blockId)) return true;
        }
        return false;
    }

    public String getAllowedListDisplay() {
        if (this == EXCAVATION) return "(any)";
        if (this == CURE) return "(none — curing)";
        StringBuilder sb = new StringBuilder();
        for (String b : allowedBlocks) {
            if (!sb.isEmpty()) sb.append(", ");
            sb.append(b);
        }
        return sb.toString();
    }
}
