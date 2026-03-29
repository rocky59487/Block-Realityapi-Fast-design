package com.blockreality.api.client.render.animation;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.ArrayList;
import java.util.List;

/**
 * 動畫控制器 - Block Reality 動畫系統的核心類別
 * 支援完整的骨骼層級結構、四元數插值、關鍵幀事件和零分配 tick
 * 架構參考 GeckoLib，提供業界級的動畫品質
 */
@OnlyIn(Dist.CLIENT)
public final class AnimationController {

    // ============================================
    // 列舉定義與事件類別
    // ============================================

    /**
     * 動畫控制器的播放狀態
     */
    public enum State {
        /** 停止 - 不播放任何動畫 */
        STOPPED,
        /** 播放中 - 正在播放當前動畫片段 */
        PLAYING,
        /** 暫停 - 保持當前時間，不更新 */
        PAUSED,
        /** 過渡中 - 在兩個動畫片段之間進行交叉淡入 */
        TRANSITIONING
    }

    /**
     * 關鍵幀事件的類型
     */
    public enum EventType {
        /** 聲音事件 - 播放指定的聲音 */
        SOUND,
        /** 粒子事件 - 生成指定的粒子效果 */
        PARTICLE,
        /** 自訂事件 - 用於遊戲邏輯回調 */
        CUSTOM
    }

    /**
     * 關鍵幀事件 - 在動畫特定時間點觸發的事件
     * 設計參考 GeckoLib 的 KeyframeEvent
     */
    public static final class KeyframeEvent {
        /** 事件在動畫中觸發的時間（秒） */
        public final float time;
        /** 事件類型 */
        public final EventType type;
        /** 事件數據 - 聲音 ID、粒子 ID 或自訂字串 */
        public final String data;

        /**
         * 建立新的關鍵幀事件
         *
         * @param time 事件時間（秒）
         * @param type 事件類型
         * @param data 事件數據
         */
        public KeyframeEvent(float time, EventType type, String data) {
            this.time = time;
            this.type = type;
            this.data = data;
        }
    }

    /**
     * 動畫回調 - 用於播放完成和循環事件
     */
    @FunctionalInterface
    public interface AnimationCallback {
        /**
         * 當動畫事件發生時呼叫
         *
         * @param controller 觸發事件的動畫控制器
         * @param eventName 事件名稱（如 "playCompleted"、"looped"）
         */
        void onEvent(AnimationController controller, String eventName);
    }

    /**
     * 關鍵幀事件監聽器 - 用於處理關鍵幀事件
     */
    @FunctionalInterface
    public interface KeyframeEventListener {
        /**
         * 當關鍵幀事件觸發時呼叫
         *
         * @param controller 觸發事件的動畫控制器
         * @param event 關鍵幀事件
         */
        void onKeyframeEvent(AnimationController controller, KeyframeEvent event);
    }

    // ============================================
    // 實例字段
    // ============================================

    /** 控制器識別名稱（如 "body"、"arms"、"head"） */
    private final String name;

    /** 當前播放狀態 */
    private State state = State.STOPPED;

    /** 當前正在播放的動畫片段 */
    private AnimationClip currentClip;

    /** 過渡目標動畫片段（在 TRANSITIONING 狀態時使用） */
    private AnimationClip nextClip;

    /** 當前動畫播放時間（秒） */
    private float currentTime = 0.0f;

    /** 過渡進度（0.0 到 1.0，其中 1.0 表示完全轉換到下一個動畫） */
    private float transitionProgress = 0.0f;

    /** 過渡持續時間（秒） */
    private float transitionDuration = 0.0f;

    /** 動畫播放速度倍數（預設 1.0） */
    private float speedMultiplier = 1.0f;

    /** 動畫回調 - 用於播放完成和循環事件 */
    private AnimationCallback callback;

    /** 關鍵幀事件監聽器 */
    private KeyframeEventListener eventListener;

    /** 當前動畫片段的關鍵幀事件列表 */
    private List<KeyframeEvent> events = new ArrayList<>();

    /** 上次檢查過的關鍵幀事件索引（防止事件重複觸發） */
    private int lastEventIndex = -1;

    /** 綁定的骨骼層級結構（可選） */
    private BoneHierarchy boneHierarchy;

    // ============================================
    // 零分配採樣緩衝區
    // ============================================

    /** 採樣位置緩衝區 [x, y, z] */
    private final float[] samplePos = new float[3];

    /** 採樣旋轉緩衝區 [x, y, z] - 歐拉角 */
    private final float[] sampleRot = new float[3];

    /** 採樣縮放緩衝區 [x, y, z] */
    private final float[] sampleScale = new float[3];

    // ============================================
    // 零分配混合緩衝區
    // ============================================

    /** 混合緩衝區 A - 位置 [x, y, z] */
    private final float[] blendPosA = new float[3];

    /** 混合緩衝區 A - 旋轉 [x, y, z] */
    private final float[] blendRotA = new float[3];

    /** 混合緩衝區 A - 縮放 [x, y, z] */
    private final float[] blendScaleA = new float[3];

    /** 混合緩衝區 B - 位置 [x, y, z] */
    private final float[] blendPosB = new float[3];

    /** 混合緩衝區 B - 旋轉 [x, y, z] */
    private final float[] blendRotB = new float[3];

    /** 混合緩衝區 B - 縮放 [x, y, z] */
    private final float[] blendScaleB = new float[3];

    // ============================================
    // 建構函數
    // ============================================

    /**
     * 建立新的動畫控制器
     *
     * @param name 控制器識別名稱
     */
    public AnimationController(String name) {
        this.name = name;
    }

    // ============================================
    // 播放控制方法
    // ============================================

    /**
     * 立即播放指定的動畫片段
     *
     * @param clip 要播放的動畫片段
     */
    public void play(AnimationClip clip) {
        if (clip == null) {
            return;
        }

        this.currentClip = clip;
        this.nextClip = null;
        this.currentTime = 0.0f;
        this.state = State.PLAYING;
        this.transitionProgress = 0.0f;
        this.lastEventIndex = -1;

        // 更新事件列表
        updateEventList();
    }

    /**
     * 過渡到另一個動畫片段（交叉淡入）
     *
     * @param clip 目標動畫片段
     * @param transitionSeconds 過渡時間（秒）
     */
    public void transitionTo(AnimationClip clip, float transitionSeconds) {
        if (clip == null || transitionSeconds <= 0.0f) {
            play(clip);
            return;
        }

        if (this.state == State.STOPPED || this.currentClip == null) {
            play(clip);
            return;
        }

        this.nextClip = clip;
        this.transitionDuration = transitionSeconds;
        this.transitionProgress = 0.0f;
        this.state = State.TRANSITIONING;
        this.lastEventIndex = -1;

        // 保持當前時間以便混合
    }

    /**
     * 暫停動畫播放
     */
    public void pause() {
        if (this.state == State.PLAYING) {
            this.state = State.PAUSED;
        }
    }

    /**
     * 恢復動畫播放
     */
    public void resume() {
        if (this.state == State.PAUSED) {
            this.state = State.PLAYING;
        }
    }

    /**
     * 停止動畫播放並重設到初始狀態
     */
    public void stop() {
        this.state = State.STOPPED;
        this.currentClip = null;
        this.nextClip = null;
        this.currentTime = 0.0f;
        this.transitionProgress = 0.0f;
        this.lastEventIndex = -1;
        this.events.clear();
    }

    // ============================================
    // 設定方法
    // ============================================

    /**
     * 設定動畫播放速度倍數
     *
     * @param multiplier 速度倍數（1.0 為正常速度）
     */
    public void setSpeedMultiplier(float multiplier) {
        this.speedMultiplier = Math.max(0.0f, multiplier);
    }

    /**
     * 設定動畫回調
     *
     * @param callback 回調函數
     */
    public void setCallback(AnimationCallback callback) {
        this.callback = callback;
    }

    /**
     * 設定關鍵幀事件監聽器
     *
     * @param eventListener 事件監聽器
     */
    public void setEventListener(KeyframeEventListener eventListener) {
        this.eventListener = eventListener;
    }

    /**
     * 綁定骨骼層級結構以進行完整骨骼動畫
     *
     * @param hierarchy 骨骼層級結構（可為 null 以解除綁定）
     */
    public void setBoneHierarchy(BoneHierarchy hierarchy) {
        this.boneHierarchy = hierarchy;
    }

    // ============================================
    // 事件管理方法
    // ============================================

    /**
     * 新增關鍵幀事件
     *
     * @param time 事件時間（秒）
     * @param type 事件類型
     * @param data 事件數據
     */
    public void addEvent(float time, EventType type, String data) {
        this.events.add(new KeyframeEvent(time, type, data));
        // 保持事件列表按時間排序
        this.events.sort((a, b) -> Float.compare(a.time, b.time));
    }

    /**
     * 清除所有關鍵幀事件
     */
    public void clearEvents() {
        this.events.clear();
        this.lastEventIndex = -1;
    }

    /**
     * 更新事件列表（當動畫片段改變時呼叫）。
     * 從 AnimationClip 提取內嵌事件，轉換為 KeyframeEvent 加入列表。
     */
    private void updateEventList() {
        this.events.clear();

        // 從 AnimationClip 提取內嵌 ClipEvent（GeckoLib 風格 Clip-embedded events）
        if (this.currentClip != null && this.currentClip.hasEvents()) {
            for (AnimationClip.ClipEvent clipEvent : this.currentClip.getClipEvents()) {
                EventType eventType;
                switch (clipEvent.type) {
                    case SOUND:    eventType = EventType.SOUND; break;
                    case PARTICLE: eventType = EventType.PARTICLE; break;
                    default:       eventType = EventType.CUSTOM; break;
                }
                this.events.add(new KeyframeEvent(clipEvent.time, eventType, clipEvent.data));
            }
        }

        this.lastEventIndex = -1;
    }

    /**
     * 檢查並觸發已越過的關鍵幀事件
     *
     * @param lastTime 上一個刻度的時間
     * @param currentTime 當前時間
     */
    private void checkAndFireKeyframeEvents(float lastTime, float currentTime) {
        if (this.events.isEmpty() || this.eventListener == null) {
            return;
        }

        // 遍歷所有事件
        for (int i = 0; i < this.events.size(); i++) {
            KeyframeEvent event = this.events.get(i);

            // 檢查事件是否在當前刻度中被越過
            // 處理循環的情況：如果 currentTime < lastTime，則發生了循環
            boolean eventFired = false;

            if (currentTime >= lastTime) {
                // 正常情況：時間向前推進
                if (event.time > lastTime && event.time <= currentTime) {
                    eventFired = true;
                }
            } else {
                // 循環情況：時間從動畫末尾重置到開始
                if (event.time > lastTime || event.time <= currentTime) {
                    eventFired = true;
                }
            }

            if (eventFired && i > this.lastEventIndex) {
                this.eventListener.onKeyframeEvent(this, event);
            }
        }

        // 更新最後檢查的事件索引
        if (!this.events.isEmpty()) {
            for (int i = this.events.size() - 1; i >= 0; i--) {
                if (this.events.get(i).time <= currentTime) {
                    this.lastEventIndex = i;
                    break;
                }
            }
        }
    }

    // ============================================
    // 主要更新方法 - tick()
    // ============================================

    /**
     * 更新動畫狀態（每幀呼叫）
     * 此方法保證零堆分配
     *
     * @param deltaSeconds 自上次更新以來經過的時間（秒）
     */
    public void tick(float deltaSeconds) {
        if (this.state == State.STOPPED || this.currentClip == null) {
            return;
        }

        float lastTime = this.currentTime;

        // ========================================
        // 更新時間和狀態
        // ========================================

        if (this.state == State.PLAYING) {
            this.currentTime += deltaSeconds * this.speedMultiplier;

            // 處理動畫循環和播放完成
            float clipDuration = this.currentClip.getDuration();
            if (this.currentTime >= clipDuration) {
                if (this.currentClip.isLooping()) {
                    this.currentTime = this.currentTime % clipDuration;
                    if (this.callback != null) {
                        this.callback.onEvent(this, "looped");
                    }
                } else {
                    this.currentTime = clipDuration;
                    this.state = State.STOPPED;
                    if (this.callback != null) {
                        this.callback.onEvent(this, "playCompleted");
                    }
                    return;
                }
            }
        } else if (this.state == State.TRANSITIONING && this.nextClip != null) {
            this.transitionProgress += deltaSeconds / this.transitionDuration;

            if (this.transitionProgress >= 1.0f) {
                // 過渡完成
                this.transitionProgress = 1.0f;
                this.currentClip = this.nextClip;
                this.nextClip = null;
                this.currentTime = 0.0f;
                this.state = State.PLAYING;
                this.lastEventIndex = -1;
                updateEventList();

                if (this.callback != null) {
                    this.callback.onEvent(this, "transitionCompleted");
                }
            } else {
                // 更新過渡時的時間
                this.currentTime += deltaSeconds * this.speedMultiplier;
                float nextClipDuration = this.nextClip.getDuration();
                if (this.currentTime >= nextClipDuration && this.nextClip.isLooping()) {
                    this.currentTime = this.currentTime % nextClipDuration;
                }
            }
        }

        // ========================================
        // 應用動畫到骨骼層級結構（如果綁定）
        // ========================================

        if (this.boneHierarchy != null) {
            if (this.state == State.TRANSITIONING && this.transitionProgress < 1.0f) {
                // 混合兩個動畫片段
                applyBlendedAnimation();
            } else if (this.state == State.PLAYING) {
                // 應用當前動畫片段
                applyCurrentAnimation();
            }
        }

        // ========================================
        // 觸發關鍵幀事件
        // ========================================

        if (this.state == State.PLAYING) {
            checkAndFireKeyframeEvents(lastTime, this.currentTime);
        }

        // 評估狀態謂詞過渡規則
        evaluateTransitions();
    }

    /**
     * 將當前動畫片段應用到骨骼層級結構
     */
    private void applyCurrentAnimation() {
        if (this.currentClip == null || this.boneHierarchy == null) {
            return;
        }

        this.boneHierarchy.applyAnimationClip(this.currentClip, this.currentTime);
    }

    /**
     * 在過渡期間將兩個動畫片段混合應用到骨骼層級結構
     */
    private void applyBlendedAnimation() {
        if (this.currentClip == null || this.nextClip == null || this.boneHierarchy == null) {
            return;
        }

        // 使用 BoneHierarchy 的混合功能（Quaternion SLERP）
        this.boneHierarchy.applyBlendedClips(
            this.currentClip, this.currentTime,
            this.nextClip, 0.0f,
            this.transitionProgress
        );
    }

    // ============================================
    // 零分配採樣方法
    // ============================================

    /**
     * 將動畫片段在指定時間的數據採樣到預先分配的緩衝區中
     * 此方法保證零堆分配
     *
     * @param clip 要採樣的動畫片段
     * @param time 採樣時間（秒）
     */
    public void sampleClipIntoBuffers(AnimationClip clip, float time) {
        if (clip == null) {
            // 重設緩衝區為預設值
            samplePos[0] = 0.0f;
            samplePos[1] = 0.0f;
            samplePos[2] = 0.0f;
            sampleRot[0] = 0.0f;
            sampleRot[1] = 0.0f;
            sampleRot[2] = 0.0f;
            sampleScale[0] = 1.0f;
            sampleScale[1] = 1.0f;
            sampleScale[2] = 1.0f;
            return;
        }

        // 從動畫片段採樣數據
        clip.sampleIntoBuffers(time, samplePos, sampleRot, sampleScale);
    }

    /**
     * 將兩個動畫片段的混合數據採樣到預先分配的緩衝區中
     * 此方法保證零堆分配
     *
     * @param blendFactor 混合因子（0.0 = 第一個片段，1.0 = 第二個片段）
     */
    public void sampleBlendedIntoBuffers(float blendFactor) {
        if (this.currentClip == null || this.nextClip == null) {
            sampleClipIntoBuffers(this.currentClip, this.currentTime);
            return;
        }

        // 採樣兩個片段
        this.currentClip.sampleIntoBuffers(this.currentTime, blendPosA, blendRotA, blendScaleA);
        this.nextClip.sampleIntoBuffers(0.0f, blendPosB, blendRotB, blendScaleB);

        // 混合位置（線性插值）
        samplePos[0] = lerp(blendPosA[0], blendPosB[0], blendFactor);
        samplePos[1] = lerp(blendPosA[1], blendPosB[1], blendFactor);
        samplePos[2] = lerp(blendPosA[2], blendPosB[2], blendFactor);

        // 混合縮放（線性插值）
        sampleScale[0] = lerp(blendScaleA[0], blendScaleB[0], blendFactor);
        sampleScale[1] = lerp(blendScaleA[1], blendScaleB[1], blendFactor);
        sampleScale[2] = lerp(blendScaleA[2], blendScaleB[2], blendFactor);

        // 混合旋轉（使用四元數 SLERP 避免萬向鎖）
        {
            float[] qA = BoneHierarchy.eulerToQuaternion(blendRotA[0], blendRotA[1], blendRotA[2]);
            float[] qB = BoneHierarchy.eulerToQuaternion(blendRotB[0], blendRotB[1], blendRotB[2]);
            float[] qR = BoneHierarchy.quaternionSlerp(qA, qB, blendFactor);

            // 四元數轉回歐拉角（度）供外部讀取
            // atan2 解法：roll(X) / pitch(Y) / yaw(Z)
            float qx = qR[0], qy = qR[1], qz = qR[2], qw = qR[3];
            float sinrCosp = 2.0f * (qw * qx + qy * qz);
            float cosrCosp = 1.0f - 2.0f * (qx * qx + qy * qy);
            sampleRot[0] = (float) Math.toDegrees(Math.atan2(sinrCosp, cosrCosp));

            float sinp = 2.0f * (qw * qy - qz * qx);
            if (Math.abs(sinp) >= 1.0f) {
                sampleRot[1] = (float) Math.toDegrees(Math.copySign(Math.PI / 2, sinp));
            } else {
                sampleRot[1] = (float) Math.toDegrees(Math.asin(sinp));
            }

            float sinyCosp = 2.0f * (qw * qz + qx * qy);
            float cosyCosp = 1.0f - 2.0f * (qy * qy + qz * qz);
            sampleRot[2] = (float) Math.toDegrees(Math.atan2(sinyCosp, cosyCosp));
        }
    }

    /**
     * 線性插值輔助方法
     *
     * @param a 起點值
     * @param b 終點值
     * @param t 插值因子（0.0 到 1.0）
     * @return 插值結果
     */
    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    // ============================================
    // 查詢方法
    // ============================================

    /**
     * 取得控制器的名稱
     *
     * @return 控制器識別名稱
     */
    public String getName() {
        return this.name;
    }

    /**
     * 取得當前播放狀態
     *
     * @return 播放狀態
     */
    public State getState() {
        return this.state;
    }

    /**
     * 取得當前正在播放的動畫片段
     *
     * @return 當前動畫片段（如果沒有則為 null）
     */
    public AnimationClip getCurrentClip() {
        return this.currentClip;
    }

    /**
     * 取得當前動畫播放時間
     *
     * @return 播放時間（秒）
     */
    public float getCurrentTime() {
        return this.currentTime;
    }

    /**
     * 取得動畫播放速度倍數
     *
     * @return 速度倍數
     */
    public float getSpeedMultiplier() {
        return this.speedMultiplier;
    }

    /**
     * 取得最後一次採樣的位置數據
     * 返回的陣列為 [x, y, z]
     *
     * @return 位置陣列
     */
    public float[] getLastPosition() {
        return this.samplePos;
    }

    /**
     * 取得最後一次採樣的旋轉數據（歐拉角）
     * 返回的陣列為 [x, y, z]
     *
     * @return 旋轉陣列
     */
    public float[] getLastRotation() {
        return this.sampleRot;
    }

    /**
     * 取得最後一次採樣的縮放數據
     * 返回的陣列為 [x, y, z]
     *
     * @return 縮放陣列
     */
    public float[] getLastScale() {
        return this.sampleScale;
    }

    /**
     * 檢查動畫是否正在播放
     *
     * @return 如果正在播放則為 true
     */
    public boolean isPlaying() {
        return this.state == State.PLAYING || this.state == State.TRANSITIONING;
    }

    /**
     * 取得綁定的骨骼層級結構
     *
     * @return 骨骼層級結構（如果未綁定則為 null）
     */
    public BoneHierarchy getBoneHierarchy() {
        return this.boneHierarchy;
    }

    /**
     * 取得過渡進度
     *
     * @return 過渡進度（0.0 到 1.0，僅在 TRANSITIONING 狀態時有效）
     */
    public float getTransitionProgress() {
        return this.transitionProgress;
    }

    // ============================================
    // 狀態謂詞 + 優先級分層（GeckoLib 增強）
    // ============================================

    /**
     * 狀態謂詞 — 條件式動畫過渡。
     * 定義在何種條件下自動切換到目標動畫。
     * 參考 GeckoLib 4/5 的 AnimationController transition predicates。
     */
    @FunctionalInterface
    public interface StatePredicate {
        /**
         * 評估此謂詞是否成立。
         *
         * @param controller 當前動畫控制器
         * @param context 外部狀態上下文（由使用者提供）
         * @return true 則觸發過渡到目標動畫
         */
        boolean test(AnimationController controller, Object context);
    }

    /**
     * 優先級過渡規則 — 帶優先級的條件式動畫過渡。
     */
    public static final class PriorityTransition {
        /** 優先級（數字越小越高，0 = 最高） */
        public final int priority;
        /** 觸發條件 */
        public final StatePredicate predicate;
        /** 目標動畫片段 */
        public final AnimationClip targetClip;
        /** 過渡持續時間（秒） */
        public final float transitionDuration;

        public PriorityTransition(int priority, StatePredicate predicate,
                                   AnimationClip targetClip, float transitionDuration) {
            this.priority = priority;
            this.predicate = predicate;
            this.targetClip = targetClip;
            this.transitionDuration = transitionDuration;
        }
    }

    /** 已註冊的優先級過渡規則（按優先級排序） */
    private final List<PriorityTransition> transitions = new ArrayList<>();

    /**
     * 註冊條件式動畫過渡規則。
     * 每幀 tick 結束時，按優先級順序評估所有謂詞，
     * 第一個成立的謂詞觸發過渡。
     *
     * @param priority 優先級（0 = 最高）
     * @param predicate 觸發條件
     * @param targetClip 目標動畫
     * @param transitionSeconds 過渡時間（秒）
     */
    public void addTransitionRule(int priority, StatePredicate predicate,
                                   AnimationClip targetClip, float transitionSeconds) {
        PriorityTransition rule = new PriorityTransition(priority, predicate, targetClip, transitionSeconds);
        transitions.add(rule);
        transitions.sort((a, b) -> Integer.compare(a.priority, b.priority));
    }

    /**
     * 移除所有過渡規則。
     */
    public void clearTransitionRules() {
        transitions.clear();
    }

    /** 外部狀態上下文（由使用者透過 setContext 設定） */
    private Object stateContext;

    /**
     * 設定外部狀態上下文，供狀態謂詞評估使用。
     *
     * @param context 任意上下文物件（如實體狀態、輸入狀態等）
     */
    public void setStateContext(Object context) {
        this.stateContext = context;
    }

    /**
     * 評估所有過渡規則（在 tick 結束時呼叫）。
     * 按優先級順序評估，第一個成立的規則觸發過渡。
     */
    private void evaluateTransitions() {
        if (transitions.isEmpty() || state == State.TRANSITIONING) return;

        for (PriorityTransition rule : transitions) {
            if (rule.predicate.test(this, stateContext)) {
                // 避免過渡到已在播放的同一動畫
                if (currentClip != null && currentClip == rule.targetClip) continue;

                transitionTo(rule.targetClip, rule.transitionDuration);
                break;
            }
        }
    }
}
