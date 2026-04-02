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
import java.nio.file.Paths;
import java.util.concurrent.TimeoutException;

/**
 * IFC 4.x 結構匯出器 — Block Reality 競品殺手特性（P3-A）
 *
 * <p>透過 SidecarBridge（JSON-RPC 2.0 持久連線）呼叫 MctoNurbs ifc4Export 方法，
 * 將選取區域內的所有結構方塊轉換為語意豐富的 IFC 4 檔案：
 *
 * <ul>
 *   <li>每個方塊 → IFC 結構元素（IFCCOLUMN / IFCBEAM / IFCWALL / IFCSLAB）</li>
 *   <li>材料屬性 → Rcomp/Rtens/楊氏模量（MPa → Pa）嵌入 IFCMATERIALPROPERTIES</li>
 *   <li>應力與利用率 → Pset_BlockReality_Structural 屬性集</li>
 *   <li>空間階層 → IFCPROJECT → IFCSITE → IFCBUILDING → IFCBUILDINGSTOREY</li>
 *   <li>結構分析模型 → IFCSTRUCTURALANALYSISMODEL（可直接匯入 Autodesk Robot / Tekla）</li>
 * </ul>
 *
 * <p>RPC 方法：{@code ifc4Export}
 * <p>逾時：與 {@code dualContouring} 共用 {@code FastDesignConfig.getExportTimeoutSeconds()}
 *
 * @since P3-A
 */
public class IfcExporter {

    private static final Logger LOGGER = LogManager.getLogger("FD-IFC");

    /** IFC 匯出允許的最大方塊數（IFC 檔案尺寸限制） */
    public static final int IFC_MAX_BLOCKS = 65_536;

    // ─────────────────────────────────────────────────────────
    // 匯出選項
    // ─────────────────────────────────────────────────────────

    /**
     * IFC 匯出選項。
     *
     * @param outputPath     .ifc 輸出路徑（null → 自動生成時間戳路徑）
     * @param projectName    嵌入 IFC 標頭的專案名稱（null → "Block Reality Structure"）
     * @param authorOrg      作者組織名稱（null → "Block Reality"）
     * @param includeGeometry 是否包含幾何（IFCEXTRUDEDAREASOLID 1m×1m×1m/block）。
     *                       預設 true。設為 false 可生成僅含材料和屬性的輕量 IFC（BIM 元資料用）。
     */
    public record ExportOptions(
            String outputPath,
            String projectName,
            String authorOrg,
            boolean includeGeometry
    ) {
        /** 完整幾何 + 元資料（預設） */
        public static ExportOptions defaults() {
            return new ExportOptions(null, null, null, true);
        }

        /** 僅元資料（無幾何，檔案更小，BIM 工作流） */
        public static ExportOptions metadataOnly() {
            return new ExportOptions(null, null, null, false);
        }

        /**
         * 帶自訂路徑和專案名稱。
         *
         * @param outputPath  .ifc 輸出路徑（null → 自動）
         * @param projectName 專案名稱（null → 預設）
         */
        public static ExportOptions with(String outputPath, String projectName) {
            return new ExportOptions(outputPath, projectName, null, true);
        }
    }

    // ─────────────────────────────────────────────────────────
    // 匯出結果
    // ─────────────────────────────────────────────────────────

    /**
     * IFC 匯出結果摘要。
     *
     * @param success       匯出是否成功
     * @param outputPath    生成的 .ifc 檔案路徑
     * @param blockCount    匯出的方塊總數
     * @param elementCount  生成的 IFC 結構元素數
     * @param materialCount 識別的材料種類數
     * @param columnCount   IFCCOLUMN 元素數
     * @param beamCount     IFCBEAM 元素數
     * @param wallCount     IFCWALL 元素數
     * @param slabCount     IFCSLAB 元素數
     * @param maxStressLevel 最大應力水準（0.0–1.0）
     * @param maxUtilization 最大利用率（%）
     */
    public record ExportResult(
            boolean success,
            String outputPath,
            int blockCount,
            int elementCount,
            int materialCount,
            int columnCount,
            int beamCount,
            int wallCount,
            int slabCount,
            double maxStressLevel,
            double maxUtilization
    ) {
        /** 從 RPC 回傳的 JsonObject 解析為 ExportResult */
        static ExportResult fromJson(JsonObject json) {
            return new ExportResult(
                json.has("success")      && json.get("success").getAsBoolean(),
                json.has("outputPath")   ? json.get("outputPath").getAsString()   : "",
                json.has("blockCount")   ? json.get("blockCount").getAsInt()      : 0,
                json.has("elementCount") ? json.get("elementCount").getAsInt()    : 0,
                json.has("materialCount")? json.get("materialCount").getAsInt()   : 0,
                json.has("columnCount")  ? json.get("columnCount").getAsInt()     : 0,
                json.has("beamCount")    ? json.get("beamCount").getAsInt()       : 0,
                json.has("wallCount")    ? json.get("wallCount").getAsInt()       : 0,
                json.has("slabCount")    ? json.get("slabCount").getAsInt()       : 0,
                json.has("maxStressLevel")   ? json.get("maxStressLevel").getAsDouble()   : 0.0,
                json.has("maxUtilization")   ? json.get("maxUtilization").getAsDouble()   : 0.0
            );
        }

        /**
         * 格式化摘要日誌字串。
         * 範例：IFC export: 512 blocks → 512 elements (48 cols, 128 beams, 200 walls, 136 slabs),
         *        3 materials, max stress 0.72, max util 72.4%
         */
        public String toSummary() {
            return String.format(
                "IFC export: %d blocks → %d elements (%d cols, %d beams, %d walls, %d slabs), "
                    + "%d materials, max stress %.2f, max util %.1f%%",
                blockCount, elementCount,
                columnCount, beamCount, wallCount, slabCount,
                materialCount, maxStressLevel, maxUtilization
            );
        }
    }

    // ─────────────────────────────────────────────────────────
    // 公開 API
    // ─────────────────────────────────────────────────────────

    /**
     * 使用預設選項（完整幾何 + 元資料）匯出選取區域為 IFC 4 檔案。
     *
     * @param level  伺服器世界
     * @param box    玩家選取範圍
     * @return       匯出結果
     * @throws IOException      IO 錯誤或選取範圍為空
     * @throws InterruptedException  執行緒中斷
     * @throws TimeoutException      Sidecar RPC 逾時
     */
    public static ExportResult export(ServerLevel level, PlayerSelectionManager.SelectionBox box)
            throws IOException, InterruptedException, TimeoutException {
        return export(level, box, ExportOptions.defaults());
    }

    /**
     * 使用自訂選項匯出選取區域為 IFC 4 檔案。
     *
     * @param level  伺服器世界
     * @param box    玩家選取範圍
     * @param opts   匯出選項
     * @return       匯出結果
     * @throws IOException      IO 錯誤或選取範圍為空
     * @throws InterruptedException  執行緒中斷
     * @throws TimeoutException      Sidecar RPC 逾時
     */
    public static ExportResult export(ServerLevel level,
                                      PlayerSelectionManager.SelectionBox box,
                                      ExportOptions opts)
            throws IOException, InterruptedException, TimeoutException {

        // 1. 收集方塊資料
        JsonArray blockArray = collectBlockData(level, box);

        if (blockArray.isEmpty()) {
            throw new IOException("Selection contains no R-unit blocks. Nothing to export.");
        }

        if (blockArray.size() > IFC_MAX_BLOCKS) {
            throw new IOException(
                "Selection too large for IFC export: " + blockArray.size()
                    + " blocks (max " + IFC_MAX_BLOCKS + "). Reduce selection size.");
        }

        // 2. 解析輸出路徑（安全：只允許在 blockreality/exports/ 目錄下）
        Path outputDir = FMLPaths.CONFIGDIR.get().resolve("blockreality/exports");
        Files.createDirectories(outputDir);

        String resolvedOutputPath;
        if (opts.outputPath() != null && !opts.outputPath().isBlank()) {
            Path userPath = Paths.get(opts.outputPath()).toAbsolutePath().normalize();
            Path canonicalDir = outputDir.toAbsolutePath().normalize();
            if (!userPath.startsWith(canonicalDir)) {
                throw new IOException(
                    "Path traversal blocked: output path must be within " + canonicalDir
                        + ", got " + userPath);
            }
            // Enforce .ifc extension
            String pathStr = userPath.toString();
            if (!pathStr.toLowerCase().endsWith(".ifc")) {
                pathStr += ".ifc";
            }
            resolvedOutputPath = pathStr;
        } else {
            resolvedOutputPath = outputDir
                .resolve("ifc_export_" + System.currentTimeMillis() + ".ifc")
                .toString();
        }

        // 3. 組建 RPC payload
        JsonObject options = new JsonObject();
        options.addProperty("outputPath",       resolvedOutputPath);
        options.addProperty("includeGeometry",  opts.includeGeometry());
        if (opts.projectName() != null && !opts.projectName().isBlank()) {
            options.addProperty("projectName", opts.projectName());
        }
        if (opts.authorOrg() != null && !opts.authorOrg().isBlank()) {
            options.addProperty("authorOrg", opts.authorOrg());
        }

        JsonObject payload = new JsonObject();
        payload.add("blocks",  blockArray);
        payload.add("options", options);

        int timeoutSec = FastDesignConfig.getExportTimeoutSeconds();

        // 4. 確保 SidecarBridge 已啟動
        SidecarBridge bridge = SidecarBridge.getInstance();
        if (!bridge.isRunning()) {
            try {
                bridge.startAsync().get(60, java.util.concurrent.TimeUnit.SECONDS);
                LOGGER.info("[IFC] SidecarBridge auto-started for IFC export");
            } catch (Exception e) {
                throw new IOException("無法啟動 Sidecar：" + e.getMessage(), e);
            }
        }

        // 5. 呼叫 ifc4Export RPC
        try {
            JsonObject result = bridge.call("ifc4Export", payload, (long) timeoutSec * 1000);
            ExportResult exportResult = ExportResult.fromJson(result);
            LOGGER.info("[IFC] {}", exportResult.toSummary());
            return exportResult;
        } catch (SidecarBridge.SidecarException e) {
            throw new IOException("IFC sidecar RPC 失敗: " + e.getMessage(), e);
        }
    }

    // ─────────────────────────────────────────────────────────
    // 內部實作
    // ─────────────────────────────────────────────────────────

    /**
     * 收集選取區域內所有 RBlockEntity 的座標、材料與物理資訊。
     *
     * <p>欄位名稱符合 MctoNurbs {@code BlueprintBlock} 介面，
     * 與 NurbsExporter 使用的格式完全相容。
     * 額外帶入 {@code stressLevel} 和 {@code isAnchored}，
     * 供 ifc4Export 生成 Pset_BlockReality_Structural 屬性集。
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
            // Geometry coords
            obj.addProperty("relX",        immutable.getX() - origin.getX());
            obj.addProperty("relY",        immutable.getY() - origin.getY());
            obj.addProperty("relZ",        immutable.getZ() - origin.getZ());
            // Block identity
            obj.addProperty("blockState",  state.toString());
            obj.addProperty("rMaterialId", mat.getMaterialId());
            // Material physics (read by ifc-structural-export for Pset_MaterialMechanical)
            obj.addProperty("rcomp",       mat.getRcomp());
            obj.addProperty("rtens",       mat.getRtens());
            // Structural state (read for Pset_BlockReality_Structural)
            obj.addProperty("stressLevel", rbe.getStressLevel());
            obj.addProperty("isAnchored",  rbe.isAnchored());
            arr.add(obj);
        }

        return arr;
    }
}
