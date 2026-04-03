package com.blockreality.fastdesign.sidecar;

import com.blockreality.api.block.RBlockEntity;
import com.blockreality.api.command.PlayerSelectionManager;
import com.blockreality.api.material.RMaterial;
import com.blockreality.api.sidecar.SidecarBridge;
import com.blockreality.fastdesign.config.FastDesignConfig;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.fml.loading.FMLPaths;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeoutException;

/**
 * NURBS 匯出器 — v3fix §2.4
 *
 * 透過 SidecarBridge（JSON-RPC 2.0 持久連線）呼叫 MctoNurbs：
 *   method: "dualContouring"
 *   params: { blocks: [{relX,relY,relZ,blockState,rMaterialId,...}],
 *             options: {smoothing,resolution,outputPath} }
 *   result: { success, outputPath, blockCount }
 */
public class NurbsExporter {

    private static final Logger LOGGER = LogManager.getLogger("FD-NURBS");

    // ─────────────────────────────────────────────────────────
    // 匯出選項
    // ─────────────────────────────────────────────────────────

    /**
     * 控制匯出幾何的品質與風格。
     *
     * @param smoothing  0.0 = 完全體素（Greedy Mesh，最快，無平滑）
     *                   0.01~1.0 = SDF + Dual Contouring，數值越大曲面越平滑
     * @param resolution SDF 子體素解析度倍率（1~4）。越高越細緻，指數級增加記憶體與時間。
     * @param outputPath STEP 輸出路徑，null 代表自動生成時間戳路徑
     */
    public record ExportOptions(double smoothing, int resolution, String outputPath) {

        public static final double MIN_SMOOTHING = 0.0;
        public static final double MAX_SMOOTHING = 1.0;
        public static final int    MIN_RESOLUTION = 1;
        public static final int    MAX_RESOLUTION = 4;

        /** 預設：完全體素，解析度 1（最快，最準確） */
        public static ExportOptions defaults() {
            return new ExportOptions(0.0, 1, null);
        }

        /** 平滑預設：中度曲面化 */
        public static ExportOptions smooth() {
            return new ExportOptions(0.5, 1, null);
        }

        public ExportOptions {
            if (smoothing < MIN_SMOOTHING || smoothing > MAX_SMOOTHING)
                throw new IllegalArgumentException(
                    "smoothing must be in [0.0, 1.0], got " + smoothing);
            if (resolution < MIN_RESOLUTION || resolution > MAX_RESOLUTION)
                throw new IllegalArgumentException(
                    "resolution must be in [1, 4], got " + resolution);
        }
    }

    // ─────────────────────────────────────────────────────────
    // 公開 API
    // ─────────────────────────────────────────────────────────

    /** 使用預設選項（完全體素）匯出 */
    public static JsonObject export(ServerLevel level, PlayerSelectionManager.SelectionBox box)
            throws IOException, InterruptedException, TimeoutException {
        return export(level, box, ExportOptions.defaults());
    }

    /** 使用自訂選項匯出 */
    public static JsonObject export(ServerLevel level,
                                    PlayerSelectionManager.SelectionBox box,
                                    ExportOptions opts)
            throws IOException, InterruptedException, TimeoutException {

        JsonArray blockArray = collectBlockData(level, box);

        if (blockArray.isEmpty()) {
            throw new IOException("Selection contains no R-unit blocks. Nothing to export.");
        }

        int maxExport = FastDesignConfig.getExportMaxBlocks();
        if (blockArray.size() > maxExport) {
            throw new IOException("Selection too large for export: " + blockArray.size() +
                " blocks (max " + maxExport + "). Reduce selection size.");
        }

        // 解析輸出路徑 — ★ 安全修復：驗證用戶路徑不超越允許的匯出目錄
        Path outputDir = FMLPaths.CONFIGDIR.get().resolve("blockreality/exports");
        Files.createDirectories(outputDir);

        String resolvedOutputPath;
        if (opts.outputPath() != null && !opts.outputPath().isBlank()) {
            // 驗證用戶提供的路徑不逃逸出 outputDir（防路徑遍歷）
            Path userPath = java.nio.file.Paths.get(opts.outputPath()).toAbsolutePath().normalize();
            Path canonicalDir = outputDir.toAbsolutePath().normalize();
            if (!userPath.startsWith(canonicalDir)) {
                throw new IOException(
                    "Path traversal blocked: output path must be within " + canonicalDir +
                    ", got " + userPath);
            }
            resolvedOutputPath = userPath.toString();
        } else {
            resolvedOutputPath = outputDir.resolve("export_" + System.currentTimeMillis() + ".step").toString();
        }

        // 組建符合 MctoNurbs ConvertRequest 的 JSON
        JsonObject options = new JsonObject();
        options.addProperty("smoothing",   opts.smoothing());
        options.addProperty("resolution",  opts.resolution());
        options.addProperty("outputPath",  resolvedOutputPath);

        JsonObject payload = new JsonObject();
        payload.add("blocks",   blockArray);
        payload.add("options",  options);

        int timeoutSec = FastDesignConfig.getExportTimeoutSeconds();

        // 確保 SidecarBridge 已啟動
        SidecarBridge bridge = SidecarBridge.getInstance();
        if (!bridge.isRunning()) {
            try {
                // ★ Audit fix（程式員A）：統一使用可配置逾時，不再硬編碼 60 秒
                bridge.startAsync().get(timeoutSec, java.util.concurrent.TimeUnit.SECONDS);
                LOGGER.info("[NURBS] SidecarBridge auto-started for export");
            } catch (Exception e) {
                throw new IOException("無法啟動 Sidecar：" + e.getMessage(), e);
            }
        }

        try {
            JsonObject result = bridge.call("dualContouring", payload, (long) timeoutSec * 1000);
            LOGGER.info("[NURBS] Export succeeded via dualContouring (smoothing={}, res={})",
                opts.smoothing(), opts.resolution());
            return result;
        } catch (SidecarBridge.SidecarException e) {
            throw new IOException("NURBS sidecar RPC 失敗: " + e.getMessage(), e);
        }
    }

    // ─────────────────────────────────────────────────────────
    // 內部實作
    // ─────────────────────────────────────────────────────────

    /**
     * 收集選取區域內所有 RBlockEntity 的座標與材料資訊。
     * 欄位名稱符合 MctoNurbs BlueprintBlock 介面：
     *   relX/relY/relZ  — 相對於選取區最小角的本地坐標
     *   blockState      — Minecraft block state 字串（MctoNurbs 用於幾何分組）
     *   rMaterialId     — Block Reality 材料 ID
     *   rcomp/rtens/stressLevel/isAnchored — 擴充欄位，MctoNurbs 忽略但不報錯，
     *                     保留供未來材料感知幾何使用
     */
    private static JsonArray collectBlockData(ServerLevel level,
                                              PlayerSelectionManager.SelectionBox box) {
        JsonArray arr = new JsonArray();
        BlockPos origin = box.min();

        for (BlockPos pos : box.allPositions()) {
            BlockPos immutable = pos.immutable();
            BlockState state = level.getBlockState(immutable);
            if (state.isAir()) continue;

            BlockEntity be = level.getBlockEntity(immutable);
            if (!(be instanceof RBlockEntity rbe)) continue;

            RMaterial mat = rbe.getMaterial();
            JsonObject obj = new JsonObject();
            obj.addProperty("relX",        immutable.getX() - origin.getX());
            obj.addProperty("relY",        immutable.getY() - origin.getY());
            obj.addProperty("relZ",        immutable.getZ() - origin.getZ());
            obj.addProperty("blockState",  state.toString());
            obj.addProperty("rMaterialId", mat.getMaterialId());
            // 擴充欄位（MctoNurbs 忽略，保留供未來材料感知幾何使用）
            obj.addProperty("rcomp",       mat.getRcomp());
            obj.addProperty("rtens",       mat.getRtens());
            obj.addProperty("stressLevel", rbe.getStressLevel());
            obj.addProperty("isAnchored",  rbe.isAnchored());
            arr.add(obj);
        }

        return arr;
    }

}
