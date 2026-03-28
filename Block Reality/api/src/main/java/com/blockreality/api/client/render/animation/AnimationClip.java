package com.blockreality.api.client.render.animation;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

/**
 * 動畫 Clip — GeckoLib 風格的具名動畫片段。
 *
 * 一個 Clip 包含多條 Channel（每骨骼一條），每條 Channel 包含關鍵幀序列。
 * Clip 可以循環播放、單次播放、或作為混合來源。
 *
 * GeckoLib 啟發：
 *   - 骨骼名稱作為 channel key（而非 index，方便人類閱讀）
 *   - 每 channel 三組獨立關鍵幀：位置 / 旋轉 / 縮放
 *   - 支援不同緩動函數 per keyframe
 */
@OnlyIn(Dist.CLIENT)
public final class AnimationClip {

    /** 單一關鍵幀 */
    public static final class Keyframe {
        public final float time;       // 秒
        public final float x, y, z;    // 值
        public final EasingFunctions.Type easing;

        public Keyframe(float time, float x, float y, float z, EasingFunctions.Type easing) {
            this.time = time;
            this.x = x; this.y = y; this.z = z;
            this.easing = easing;
        }

        public Keyframe(float time, float x, float y, float z) {
            this(time, x, y, z, EasingFunctions.Type.LINEAR);
        }
    }

    /** 單一骨骼的動畫通道 */
    public static final class BoneChannel {
        public final String boneName;
        public final List<Keyframe> positionKeys;
        public final List<Keyframe> rotationKeys;  // 歐拉角（度）
        public final List<Keyframe> scaleKeys;

        public BoneChannel(String boneName) {
            this.boneName = boneName;
            this.positionKeys = new ArrayList<>();
            this.rotationKeys = new ArrayList<>();
            this.scaleKeys = new ArrayList<>();
        }

        /** 在指定時間插值位置 */
        public float[] samplePosition(float time) {
            return interpolate(positionKeys, time, 0, 0, 0);
        }

        /** 在指定時間插值旋轉（歐拉角度） */
        public float[] sampleRotation(float time) {
            return interpolate(rotationKeys, time, 0, 0, 0);
        }

        /** 在指定時間插值縮放 */
        public float[] sampleScale(float time) {
            return interpolate(scaleKeys, time, 1, 1, 1);
        }

        private float[] interpolate(List<Keyframe> keys, float time,
                                     float dx, float dy, float dz) {
            if (keys.isEmpty()) return new float[]{ dx, dy, dz };
            if (keys.size() == 1) {
                Keyframe k = keys.get(0);
                return new float[]{ k.x, k.y, k.z };
            }

            // 找到前後關鍵幀
            if (time <= keys.get(0).time) {
                Keyframe k = keys.get(0);
                return new float[]{ k.x, k.y, k.z };
            }
            if (time >= keys.get(keys.size() - 1).time) {
                Keyframe k = keys.get(keys.size() - 1);
                return new float[]{ k.x, k.y, k.z };
            }

            for (int i = 0; i < keys.size() - 1; i++) {
                Keyframe a = keys.get(i);
                Keyframe b = keys.get(i + 1);
                if (time >= a.time && time < b.time) {
                    float t = (time - a.time) / (b.time - a.time);
                    float easedT = EasingFunctions.apply(b.easing, t);
                    return new float[]{
                        a.x + (b.x - a.x) * easedT,
                        a.y + (b.y - a.y) * easedT,
                        a.z + (b.z - a.z) * easedT
                    };
                }
            }

            Keyframe last = keys.get(keys.size() - 1);
            return new float[]{ last.x, last.y, last.z };
        }
    }

    // ─── Clip 屬性 ──────────────────────────────────────

    private final String name;
    private final float duration;   // 秒
    private final boolean looping;
    private final Map<String, BoneChannel> channels; // boneName → channel

    private AnimationClip(String name, float duration, boolean looping,
                           Map<String, BoneChannel> channels) {
        this.name = name;
        this.duration = duration;
        this.looping = looping;
        this.channels = Collections.unmodifiableMap(channels);
    }

    public String getName() { return name; }
    public float getDuration() { return duration; }
    public boolean isLooping() { return looping; }
    public Map<String, BoneChannel> getChannels() { return channels; }

    public BoneChannel getChannel(String boneName) {
        return channels.get(boneName);
    }

    /**
     * 在指定時間取樣所有骨骼（處理循環）。
     */
    public float getEffectiveTime(float rawTime) {
        if (!looping) return Math.min(rawTime, duration);
        if (duration <= 0) return 0;
        return rawTime % duration;
    }

    // ─── Builder ────────────────────────────────────────

    public static Builder builder(String name) {
        return new Builder(name);
    }

    public static final class Builder {
        private final String name;
        private float duration = 1.0f;
        private boolean looping = false;
        private final Map<String, BoneChannel> channels = new LinkedHashMap<>();

        private Builder(String name) {
            this.name = name;
        }

        public Builder duration(float seconds) { this.duration = seconds; return this; }
        public Builder looping(boolean loop) { this.looping = loop; return this; }

        public Builder addChannel(BoneChannel channel) {
            channels.put(channel.boneName, channel);
            return this;
        }

        public AnimationClip build() {
            return new AnimationClip(name, duration, looping, channels);
        }
    }

    // ═══════════════════════════════════════════════════════
    //  預設動畫工廠（Block Reality 內建動畫）
    // ═══════════════════════════════════════════════════════

    /** 方塊放置動畫 — 從上方落下 + 輕微彈跳 */
    public static AnimationClip createBlockPlacement() {
        BoneChannel root = new BoneChannel("root");
        root.positionKeys.add(new Keyframe(0.0f, 0, 0.5f, 0, EasingFunctions.Type.QUAD_OUT));
        root.positionKeys.add(new Keyframe(0.15f, 0, 0, 0, EasingFunctions.Type.BOUNCE_OUT));

        root.scaleKeys.add(new Keyframe(0.0f, 0.8f, 0.8f, 0.8f, EasingFunctions.Type.ELASTIC_OUT));
        root.scaleKeys.add(new Keyframe(0.25f, 1.0f, 1.0f, 1.0f));

        return AnimationClip.builder("block_placement")
            .duration(0.25f).looping(false)
            .addChannel(root)
            .build();
    }

    /** 方塊破壞動畫 — 縮小 + 淡出 */
    public static AnimationClip createBlockDestroy() {
        BoneChannel root = new BoneChannel("root");
        root.scaleKeys.add(new Keyframe(0.0f, 1.0f, 1.0f, 1.0f));
        root.scaleKeys.add(new Keyframe(0.2f, 0.0f, 0.0f, 0.0f, EasingFunctions.Type.BACK_IN));

        root.rotationKeys.add(new Keyframe(0.0f, 0, 0, 0));
        root.rotationKeys.add(new Keyframe(0.2f, 15, 30, 10, EasingFunctions.Type.QUAD_IN));

        return AnimationClip.builder("block_destroy")
            .duration(0.2f).looping(false)
            .addChannel(root)
            .build();
    }

    /** 選框脈衝動畫 — 持續呼吸光效 */
    public static AnimationClip createSelectionPulse() {
        BoneChannel root = new BoneChannel("root");
        root.scaleKeys.add(new Keyframe(0.0f, 1.0f, 1.0f, 1.0f));
        root.scaleKeys.add(new Keyframe(1.0f, 1.02f, 1.02f, 1.02f, EasingFunctions.Type.SINE_IN_OUT));
        root.scaleKeys.add(new Keyframe(2.0f, 1.0f, 1.0f, 1.0f, EasingFunctions.Type.SINE_IN_OUT));

        return AnimationClip.builder("selection_pulse")
            .duration(2.0f).looping(true)
            .addChannel(root)
            .build();
    }

    // ═══════════════════════════════════════════════════════
    //  零分配採樣 — 寫入外部緩衝區（AnimationController 用）
    // ═══════════════════════════════════════════════════════

    /**
     * 將指定時間的動畫數據採樣到外部緩衝區中（零堆分配）。
     * 採樣 "root" 通道；如果沒有 root 通道，則使用第一個可用通道。
     *
     * @param time     採樣時間（秒，自動處理循環）
     * @param posOut   位置輸出 [x, y, z]
     * @param rotOut   旋轉輸出 [x, y, z]（歐拉角度）
     * @param scaleOut 縮放輸出 [x, y, z]
     */
    public void sampleIntoBuffers(float time, float[] posOut, float[] rotOut, float[] scaleOut) {
        float t = getEffectiveTime(time);
        BoneChannel ch = channels.get("root");
        if (ch == null && !channels.isEmpty()) {
            ch = channels.values().iterator().next();
        }
        if (ch == null) {
            posOut[0] = 0; posOut[1] = 0; posOut[2] = 0;
            rotOut[0] = 0; rotOut[1] = 0; rotOut[2] = 0;
            scaleOut[0] = 1; scaleOut[1] = 1; scaleOut[2] = 1;
            return;
        }
        float[] p = ch.samplePosition(t);
        posOut[0] = p[0]; posOut[1] = p[1]; posOut[2] = p[2];
        float[] r = ch.sampleRotation(t);
        rotOut[0] = r[0]; rotOut[1] = r[1]; rotOut[2] = r[2];
        float[] s = ch.sampleScale(t);
        scaleOut[0] = s[0]; scaleOut[1] = s[1]; scaleOut[2] = s[2];
    }

    // ═══════════════════════════════════════════════════════
    //  事件支援（預留介面，目前無內建事件）
    // ═══════════════════════════════════════════════════════

    /**
     * 此 Clip 是否包含關鍵幀事件。
     * 目前預設動畫無內建事件，回傳 false。
     */
    public boolean hasEvents() {
        return false;
    }

    /**
     * 取得此 Clip 的關鍵幀事件列表。
     * 目前預設動畫無內建事件，回傳空列表。
     */
    public List<?> getEvents() {
        return Collections.emptyList();
    }

    /** 結構崩塌動畫 — 重力下墜 + 旋轉 */
    public static AnimationClip createStructureCollapse() {
        BoneChannel root = new BoneChannel("root");
        root.positionKeys.add(new Keyframe(0.0f, 0, 0, 0));
        root.positionKeys.add(new Keyframe(0.5f, 0, -3.0f, 0, EasingFunctions.Type.QUAD_IN));
        root.positionKeys.add(new Keyframe(1.0f, 0, -8.0f, 0, EasingFunctions.Type.QUAD_IN));

        root.rotationKeys.add(new Keyframe(0.0f, 0, 0, 0));
        root.rotationKeys.add(new Keyframe(1.0f, 25, 15, -10, EasingFunctions.Type.CUBIC_IN));

        return AnimationClip.builder("structure_collapse")
            .duration(1.0f).looping(false)
            .addChannel(root)
            .build();
    }
}
