package com.blockreality.fastdesign.client.node;

import com.blockreality.api.chisel.SubBlockShape;
import com.blockreality.api.material.RMaterial;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import java.util.Objects;

/**
 * BLOCK 端口值型別 — 設計報告 §3.2 (v1.1 新增)
 *
 * 封裝 blockId + RMaterial + texture 引用 + 形狀。
 * 用於 B5 材質調配管線中方塊資料的傳遞。
 *
 * 不可變（immutable）— 每次調配產生新實例。
 */
public final class BRBlockDef {

    private final ResourceLocation blockId;
    private final RMaterial material;
    @Nullable private final ResourceLocation textureId;
    private final SubBlockShape shape;
    @Nullable private final String displayName;
    private final int tintColor;
    private final float tintIntensity;

    public BRBlockDef(ResourceLocation blockId, RMaterial material,
                      @Nullable ResourceLocation textureId, SubBlockShape shape,
                      @Nullable String displayName, int tintColor, float tintIntensity) {
        this.blockId = Objects.requireNonNull(blockId, "blockId");
        this.material = Objects.requireNonNull(material, "material");
        this.textureId = textureId;
        this.shape = shape != null ? shape : SubBlockShape.FULL;
        this.displayName = displayName;
        this.tintColor = tintColor;
        this.tintIntensity = tintIntensity;
    }

    /** 從原版方塊建立（無色調、完整方塊形狀） */
    public static BRBlockDef ofVanilla(ResourceLocation blockId, RMaterial material) {
        return new BRBlockDef(blockId, material, null, SubBlockShape.FULL, null, 0xFFFFFFFF, 0.0f);
    }

    /** 建立自訂方塊定義 */
    public static BRBlockDef ofCustom(String name, RMaterial material,
                                       ResourceLocation baseTextureBlock,
                                       SubBlockShape shape,
                                       String displayName,
                                       int tintColor, float tintIntensity) {
        ResourceLocation id = new ResourceLocation("blockreality", name);
        return new BRBlockDef(id, material, baseTextureBlock, shape,
                displayName, tintColor, tintIntensity);
    }

    // ─── Getters ───

    public ResourceLocation blockId()      { return blockId; }
    public RMaterial material()            { return material; }
    @Nullable public ResourceLocation textureId() { return textureId; }
    public SubBlockShape shape()           { return shape; }
    @Nullable public String displayName()  { return displayName; }
    public int tintColor()                 { return tintColor; }
    public float tintIntensity()           { return tintIntensity; }

    // ─── 便捷委派 ───

    public double rcomp()      { return material.getRcomp(); }
    public double rtens()      { return material.getRtens(); }
    public double rshear()     { return material.getRshear(); }
    public double density()    { return material.getDensity(); }
    public String materialId() { return material.getMaterialId(); }

    // ─── 修改器（回傳新實例） ───

    public BRBlockDef withMaterial(RMaterial newMaterial) {
        return new BRBlockDef(blockId, newMaterial, textureId, shape, displayName, tintColor, tintIntensity);
    }

    public BRBlockDef withShape(SubBlockShape newShape) {
        return new BRBlockDef(blockId, material, textureId, newShape, displayName, tintColor, tintIntensity);
    }

    public BRBlockDef withTint(int color, float intensity) {
        return new BRBlockDef(blockId, material, textureId, shape, displayName, color, intensity);
    }

    public BRBlockDef withDisplayName(String name) {
        return new BRBlockDef(blockId, material, textureId, shape, name, tintColor, tintIntensity);
    }

    // ─── 序列化 ───

    public CompoundTag serialize() {
        CompoundTag tag = new CompoundTag();
        tag.putString("blockId", blockId.toString());
        tag.putString("materialId", material.getMaterialId());
        tag.putDouble("rcomp", material.getRcomp());
        tag.putDouble("rtens", material.getRtens());
        tag.putDouble("rshear", material.getRshear());
        tag.putDouble("density", material.getDensity());
        if (textureId != null) tag.putString("textureId", textureId.toString());
        tag.putString("shape", shape.getSerializedName());
        if (displayName != null) tag.putString("displayName", displayName);
        tag.putInt("tintColor", tintColor);
        tag.putFloat("tintIntensity", tintIntensity);
        return tag;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof BRBlockDef that)) return false;
        return blockId.equals(that.blockId)
                && material.getMaterialId().equals(that.material.getMaterialId());
    }

    @Override
    public int hashCode() {
        return Objects.hash(blockId, material.getMaterialId());
    }

    @Override
    public String toString() {
        return "BRBlockDef{" + blockId + ", mat=" + material.getMaterialId() + "}";
    }
}
