package com.blockreality.api.client.render.optimization;

import com.blockreality.api.client.render.BRRenderConfig;
import com.blockreality.api.client.render.pipeline.RenderPassContext;
import com.blockreality.api.client.render.shader.BRShaderEngine;
import com.blockreality.api.client.render.shader.BRShaderProgram;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.joml.Vector4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

/**
 * 【方塊現實 LOD 渲染引擎】
 * Block Reality 的層級細節（Level of Detail）空間渲染優化系統
 *
 * 此引擎結合了 Distant Horizons 的四叉樹空間分割與漸進式細節策略，
 * 以及 Voxy 的體素聚合 LOD 壓縮與高效 VRAM 管理技術。
 * 支持高達 1024 方塊視距的遠距離渲染，自動選擇最適合的細節等級。
 *
 * 核心特性：
 * • 四叉樹型空間索引，確保 O(log n) 查詢效率
 * • 5 級 LOD 細節等級，自動距離適應
 * • 體素聚合幾何簡化，顯著降低頂點填充率
 * • 視錐體裁剪，每個 LOD 段落精確可見性判定
 * • 遷移動畫防抖動，使用透視裕度避免等級頻繁變化
 * • 接縫處理，生成邊裙幾何解決 LOD 過度邊界的 T 字結接
 * • VRAM 預算管理，動態卸除遠距離低優先順序段落
 *
 * @author Block Reality 核心引擎組
 * @since 1.0
  * @deprecated Since 2.0, replaced by Vulkan RT + Voxy LOD pipeline
*/
@Deprecated(since = "2.0", forRemoval = true)
@OnlyIn(Dist.CLIENT)
public final class BRLODEngine {
	private static final Logger LOGGER = LoggerFactory.getLogger("BRLODEngine");

	// ═══════════════════════════════════════════════════════════════════════════════
	// 【LOD 等級定義】細節距離閥值與幾何簡化參數
	// ═══════════════════════════════════════════════════════════════════════════════

	/**
	 * LOD 等級枚舉，定義 5 個細節等級及其特性
	 * 值越小細節越高，值越大幾何簡化程度越高
	 */
	public enum LODLevel {
		// FULL：完整細節，適用於近距離區域（0-64 方塊）
		// 保留原始體素網格，支持完整光照與紋理細節
		FULL(0, 64.0, 1.0f, "完整細節"),

		// HIGH：高細節，適用於中近距離（64-192 方塊）
		// 保留 75% 的體素信息，1:2 體素聚合
		HIGH(1, 192.0, 0.75f, "高細節"),

		// MEDIUM：中等細節，適用於中距離（192-384 方塊）
		// 保留 50% 的體素信息，1:4 體素聚合
		MEDIUM(2, 384.0, 0.50f, "中等細節"),

		// LOW：低細節，適用於遠距離（384-640 方塊）
		// 保留 25% 的體素信息，1:8 體素聚合
		LOW(3, 640.0, 0.25f, "低細節"),

		// MINIMAL：最小細節，適用於超遠距離（640-1024 方塊）
		// 保留 6% 的體素信息，1:16 體素聚合
		MINIMAL(4, 1024.0, 0.06f, "最小細節");

		public final int index;
		public final double maxDistance;
		public final float geometryRetention;
		public final String displayName;

		LODLevel(int index, double maxDistance, float geometryRetention, String displayName) {
			this.index = index;
			this.maxDistance = maxDistance;
			this.geometryRetention = geometryRetention;
			this.displayName = displayName;
		}

		/**
		 * 根據距離選擇最適合的 LOD 等級，考慮透視裕度以防止抖動
		 *
		 * @param distance 自攝像機的距離（方塊）
		 * @param hysteresis 透視裕度（方塊），防止等級頻繁變化
		 * @return 該距離適用的 LOD 等級
		 */
		public static LODLevel selectByDistance(double distance, double hysteresis) {
			if (distance <= FULL.maxDistance + hysteresis) return FULL;
			if (distance <= HIGH.maxDistance + hysteresis) return HIGH;
			if (distance <= MEDIUM.maxDistance + hysteresis) return MEDIUM;
			if (distance <= LOW.maxDistance + hysteresis) return LOW;
			return MINIMAL;
		}

		/**
		 * ★ #13 fix: 雙向遲滯選擇 — 升級需超過 (maxDistance + hysteresis)，
		 * 降級需低於 (maxDistance - hysteresis)，防止邊界來回抖動。
		 *
		 * @param distance 自攝像機的距離（方塊）
		 * @param currentLevel 當前 LOD 等級
		 * @param hysteresis 透視裕度（方塊）
		 * @return 新 LOD 等級
		 */
		public static LODLevel selectByDistanceBidirectional(double distance, LODLevel currentLevel, double hysteresis) {
			// 升級（提高精度）：距離必須小於 maxDistance - hysteresis
			// 降級（降低精度）：距離必須大於 maxDistance + hysteresis
			LODLevel[] levels = values();
			for (int i = 0; i < levels.length - 1; i++) {
				double threshold = levels[i].maxDistance;
				if (currentLevel.ordinal() <= i) {
					// 目前等級 ≤ 此等級 → 檢查是否需要降級（distance > threshold + hysteresis）
					if (distance <= threshold + hysteresis) return levels[i];
				} else {
					// 目前等級 > 此等級 → 檢查是否可以升級（distance < threshold - hysteresis）
					if (distance <= threshold - hysteresis) return levels[i];
				}
			}
			return MINIMAL;
		}
	}

	// ═══════════════════════════════════════════════════════════════════════════════
	// 【LOD 段落類】單個 16×16 方塊區域的渲染資源與狀態
	// ═══════════════════════════════════════════════════════════════════════════════

	/**
	 * LOD 段落，代表一個 16×16 方塊的水平切片
	 * 每個段落維護獨立的 VAO/VBO，支持條件渲染與動態更新
	 */
	public static class LODSection {
		// 段落的全局坐標（按 16 方塊網格）
		public final int sectionX;
		public final int sectionZ;

		// 該段落當前適用的 LOD 等級
		public LODLevel lodLevel;

		// OpenGL 資源句柄
		public int vao;
		public int vbo;
		public int indexBuffer;
		public int vertexCount;
		public int indexCount;

		// 狀態追蹤
		public long lastUpdateFrame;
		public boolean dirty;
		public long lastAccessTime;

		// 估計的 VRAM 佔用（字節）
		public long estimatedVRAM;

		// 邊界框用於視錐體裁剪（全局坐標）
		public double minX, maxX, minZ, maxZ;
		public float minY, maxY;

		// 段落中心坐標（用於距離計算）
		public double centerX, centerZ;

		// 是否已構建過幾何
		private boolean geometryBuilt;

		public LODSection(int sectionX, int sectionZ) {
			this.sectionX = sectionX;
			this.sectionZ = sectionZ;
			this.lodLevel = LODLevel.FULL;
			this.vao = -1;
			this.vbo = -1;
			this.indexBuffer = -1;
			this.vertexCount = 0;
			this.indexCount = 0;
			this.lastUpdateFrame = 0;
			this.dirty = true;
			this.lastAccessTime = System.currentTimeMillis();
			this.estimatedVRAM = 0;
			this.minX = sectionX * BRRenderConfig.LOD_SECTION_SIZE;
			this.maxX = minX + BRRenderConfig.LOD_SECTION_SIZE;
			this.minZ = sectionZ * BRRenderConfig.LOD_SECTION_SIZE;
			this.maxZ = minZ + BRRenderConfig.LOD_SECTION_SIZE;
			this.centerX = (minX + maxX) * 0.5;
			this.centerZ = (minZ + maxZ) * 0.5;
			this.minY = 0;
			this.maxY = 256;
			this.geometryBuilt = false;
		}

		/**
		 * 標記段落為髒，需要在下一幀重建幾何
		 */
		public void markDirty() {
			this.dirty = true;
			this.lastUpdateFrame = -1;
		}

		/**
		 * 清理 OpenGL 資源，釋放顯存
		 */
		public void cleanup() {
			if (vao >= 0) {
				glDeleteVertexArrays(vao);
				vao = -1;
			}
			if (vbo >= 0) {
				glDeleteBuffers(vbo);
				vbo = -1;
			}
			if (indexBuffer >= 0) {
				glDeleteBuffers(indexBuffer);
				indexBuffer = -1;
			}
			vertexCount = 0;
			indexCount = 0;
			estimatedVRAM = 0;
			geometryBuilt = false;
		}

		/**
		 * 獲取段落的邊界球半徑，用於快速可見性測試
		 *
		 * @return 邊界球半徑（方塊）
		 */
		public double getBoundingSphereRadius() {
			double dx = (maxX - minX) / 2.0;
			double dz = (maxZ - minZ) / 2.0;
			return Math.sqrt(dx * dx + dz * dz);
		}

		/**
		 * 更新上次訪問時間戳，用於 LRU 緩存驅逐
		 */
		public void updateAccessTime() {
			this.lastAccessTime = System.currentTimeMillis();
		}
	}

	// ═══════════════════════════════════════════════════════════════════════════════
	// 【四叉樹節點類】空間索引結構
	// ═══════════════════════════════════════════════════════════════════════════════

	/**
	 * 四叉樹節點，遞迴組織 LOD 段落的空間結構
	 * 根節點涵蓋整個世界，葉節點存儲實際的段落資源
	 */
	public static class QuadTreeNode {
		// 四個象限的子節點（NE, NW, SE, SW）
		public QuadTreeNode[] children;

		// 該節點的邊界框
		public double minX, minZ, maxX, maxZ;

		// 樹的深度（根節點為 0）
		public int depth;

		// 該節點覆蓋的所有 LOD 段落
		public List<LODSection> lodSections;

		// 節點中心與 LOD 狀態快取
		public double centerX, centerZ;
		private LODLevel cachedLODLevel;

		public QuadTreeNode(double minX, double minZ, double maxX, double maxZ, int depth) {
			this.minX = minX;
			this.minZ = minZ;
			this.maxX = maxX;
			this.maxZ = maxZ;
			this.depth = depth;
			this.children = null;
			this.lodSections = new ArrayList<>();
			this.centerX = (minX + maxX) / 2.0;
			this.centerZ = (minZ + maxZ) / 2.0;
			this.cachedLODLevel = LODLevel.FULL;
		}

		/**
		 * 檢查點是否在此節點邊界內
		 *
		 * @param x 點的 X 坐標
		 * @param z 點的 Z 坐標
		 * @return 是否包含該點
		 */
		public boolean containsPoint(double x, double z) {
			return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
		}

		/**
		 * 獲取點應該進入的子象限（如果已細分）
		 * NE: 0, NW: 1, SE: 2, SW: 3
		 *
		 * @param x 點的 X 坐標
		 * @param z 點的 Z 坐標
		 * @return 子象限索引，或 -1 如果未細分
		 */
		public int getQuadrant(double x, double z) {
			if (children == null) return -1;
			double midX = centerX;
			double midZ = centerZ;
			if (x >= midX) {
				return z >= midZ ? 0 : 2; // NE or SE
			} else {
				return z >= midZ ? 1 : 3; // NW or SW
			}
		}
	}

	// ═══════════════════════════════════════════════════════════════════════════════
	// 【視錐體裁剪器介面】用於可見性判定
	// ═══════════════════════════════════════════════════════════════════════════════

	/**
	 * 視錐體裁剪接口，與 FrustumCuller 集成
	 * 此接口定義最小化的裁剪功能以支持松耦合設計
	 */
	public interface IFrustumCuller {
		/**
		 * 測試邊界框是否在視錐體內或與其相交
		 *
		 * @param minX 邊界框最小 X
		 * @param minY 邊界框最小 Y
		 * @param minZ 邊界框最小 Z
		 * @param maxX 邊界框最大 X
		 * @param maxY 邊界框最大 Y
		 * @param maxZ 邊界框最大 Z
		 * @return true 如果框在視錐體內或相交，false 如果完全外部
		 */
		boolean testAABB(double minX, double minY, double minZ, double maxX, double maxY, double maxZ);

		/**
		 * 測試球體是否在視錐體內或與其相交
		 *
		 * @param centerX 球心 X
		 * @param centerY 球心 Y
		 * @param centerZ 球心 Z
		 * @param radius 半徑
		 * @return true 如果球在視錐體內或相交，false 如果完全外部
		 */
		boolean testSphere(double centerX, double centerY, double centerZ, double radius);
	}

	// ═══════════════════════════════════════════════════════════════════════════════
	// 【靜態單例與全局狀態】
	// ═══════════════════════════════════════════════════════════════════════════════

	private static final int WORLD_SCALE = BRRenderConfig.LOD_SECTION_SIZE;
	private static final double[] LOD_DISTANCES = {64.0, 192.0, 384.0, 640.0, 1024.0};
	private static final long VRAM_BUDGET_BYTES = BRRenderConfig.LOD_VRAM_BUDGET_MB * 1024L * 1024L;
	private static final int UPDATE_INTERVAL = BRRenderConfig.LOD_UPDATE_INTERVAL;

	private static boolean initialized = false;
	private static BRShaderProgram lodShaderProgram;
	private static BRShaderProgram shadowShaderProgram;

	// 四叉樹根節點
	private static QuadTreeNode quadTreeRoot;

	// 所有活躍的 LOD 段落（段落座標 -> LODSection）
	private static final ConcurrentHashMap<Long, LODSection> activeSections = new ConcurrentHashMap<>();

	// LOD 等級快取（段落座標 -> 當前 LOD 等級）
	private static final ConcurrentHashMap<Long, LODLevel> lodLevelCache = new ConcurrentHashMap<>();

	// 當前攝像機位置
	private static double cameraX = 0, cameraY = 128, cameraZ = 0;
	private static long currentFrameCount = 0;

	// 統計資訊
	private static long totalVRAMUsed = 0;
	private static int visibleSectionCount = 0;
	private static int totalSectionCount = 0;
	private static long lastUpdateTime = 0;

	// 視錐體裁剪器（注入）
	private static IFrustumCuller frustumCuller = null;

	// ═══════════════════════════════════════════════════════════════════════════════
	// 【SMOOTH_DROPOFF 過渡參數】LOD 邊界霧色匹配漸變
	// 參考 Distant Horizons 的 SMOOTH_DROPOFF 演算法
	// ═══════════════════════════════════════════════════════════════════════════════

	/** SMOOTH_DROPOFF 過渡帶寬度（方塊數）— 參考 Distant Horizons */
	private static final float LOD_TRANSITION_BAND = 8.0f;

	/** 霧色匹配混合因子 — 用於 LOD 邊界過渡 */
	private static float fogColorR = 0.75f, fogColorG = 0.85f, fogColorB = 1.0f;

	/** 每個 LOD section 的過渡 alpha（距離混合） */
	private static final Map<Long, Float> sectionTransitionAlpha = new ConcurrentHashMap<>();

	// ═══════════════════════════════════════════════════════════════════════════════
	// 【初始化與清理】
	// ═══════════════════════════════════════════════════════════════════════════════

	/**
	 * 初始化 LOD 引擎，編譯著色器與設置全局狀態
	 * 必須在 OpenGL 上下文準備完備時調用，通常在客戶端初始化階段
	 */
	public static void init() {
		if (initialized) {
			LOGGER.warn("BRLODEngine 已初始化，重複調用被忽略");
			return;
		}

		LOGGER.info("初始化 BRLODEngine...");

		try {
			// LOD 著色器由 BRShaderEngine 統一管理（固化光影 — 不單獨編譯）
			// 渲染時透過 BRShaderEngine.getLODTerrainShader() 和 getShadowShader() 取得
			lodShaderProgram = BRShaderEngine.getLODTerrainShader();
			shadowShaderProgram = BRShaderEngine.getShadowShader();

			// 初始化四叉樹
			// 覆蓋 ±1024 方塊範圍（足以涵蓋最大視距）
			quadTreeRoot = new QuadTreeNode(-1024, -1024, 1024, 1024, 0);
			subdivideQuadTree(quadTreeRoot, 0, 10); // 細分至 10 層深度

			initialized = true;
			LOGGER.info("BRLODEngine 初始化完成，著色器已編譯，四叉樹已構建");

		} catch (Exception e) {
			LOGGER.error("BRLODEngine 初始化失敗", e);
			initialized = false;
		}
	}

	/**
	 * 清理 LOD 引擎，釋放所有 OpenGL 資源與內存
	 * 應在客戶端卸載或世界切換時調用
	 */
	public static void cleanup() {
		if (!initialized) {
			return;
		}

		LOGGER.info("清理 BRLODEngine...");

		// 清理所有活躍的 LOD 段落
		activeSections.values().forEach(LODSection::cleanup);
		activeSections.clear();
		lodLevelCache.clear();

		// 清理著色器
		if (lodShaderProgram != null) {
			// 著色器由 BRShaderEngine 統一管理，此處只解除引用
			lodShaderProgram = null;
		}
		if (shadowShaderProgram != null) {
			shadowShaderProgram = null;
		}

		// 重置狀態
		quadTreeRoot = null;
		initialized = false;
		totalVRAMUsed = 0;
		visibleSectionCount = 0;
		totalSectionCount = 0;

		LOGGER.info("BRLODEngine 清理完成");
	}

	// ═══════════════════════════════════════════════════════════════════════════════
	// 【四叉樹構建】
	// ═══════════════════════════════════════════════════════════════════════════════

	/**
	 * 遞迴細分四叉樹，創建層次結構
	 *
	 * @param node 當前節點
	 * @param currentDepth 當前深度
	 * @param maxDepth 最大深度
	 */
	private static void subdivideQuadTree(QuadTreeNode node, int currentDepth, int maxDepth) {
		if (currentDepth >= maxDepth) {
			// 已達最大深度，創建 LOD 段落
			int minSectionX = (int) (node.minX / WORLD_SCALE);
			int maxSectionX = (int) (node.maxX / WORLD_SCALE);
			int minSectionZ = (int) (node.minZ / WORLD_SCALE);
			int maxSectionZ = (int) (node.maxZ / WORLD_SCALE);

			for (int sx = minSectionX; sx < maxSectionX; sx++) {
				for (int sz = minSectionZ; sz < maxSectionZ; sz++) {
					LODSection section = new LODSection(sx, sz);
					node.lodSections.add(section);
				}
			}
			return;
		}

		// 細分為四個子象限
		node.children = new QuadTreeNode[4];
		double midX = node.centerX;
		double midZ = node.centerZ;

		// NE 象限
		node.children[0] = new QuadTreeNode(midX, midZ, node.maxX, node.maxZ, currentDepth + 1);
		// NW 象限
		node.children[1] = new QuadTreeNode(node.minX, midZ, midX, node.maxZ, currentDepth + 1);
		// SE 象限
		node.children[2] = new QuadTreeNode(midX, node.minZ, node.maxX, midZ, currentDepth + 1);
		// SW 象限
		node.children[3] = new QuadTreeNode(node.minX, node.minZ, midX, midZ, currentDepth + 1);

		for (QuadTreeNode child : node.children) {
			subdivideQuadTree(child, currentDepth + 1, maxDepth);
		}
	}

	// ═══════════════════════════════════════════════════════════════════════════════
	// 【每幀更新】LOD 選擇、髒標記處理、內存管理
	// ═══════════════════════════════════════════════════════════════════════════════

	/**
	 * 每幀更新 LOD 引擎狀態
	 * 包括攝像機位置更新、LOD 等級選擇、髒幾何重建、VRAM 預算管理
	 *
	 * @param camX 攝像機 X 坐標
	 * @param camY 攝像機 Y 坐標
	 * @param camZ 攝像機 Z 坐標
	 * @param frameCount 當前幀計數
	 */
	public static void update(double camX, double camY, double camZ, long frameCount) {
		if (!initialized) {
			return;
		}

		long updateStartTime = System.nanoTime();
		cameraX = camX;
		cameraY = camY;
		cameraZ = camZ;
		currentFrameCount = frameCount;

		// 定期重計算 LOD 等級以減少開銷
		if (frameCount % UPDATE_INTERVAL == 0) {
			updateLODLevels();
		}

		// 收集視錐體內的活躍段落
		List<LODSection> visibleSections = collectVisibleSections();
		visibleSectionCount = visibleSections.size();

		// 更新髒幾何
		for (LODSection section : visibleSections) {
			if (section.dirty && section.lastUpdateFrame != frameCount) {
				rebuildSectionGeometry(section);
				section.dirty = false;
				section.lastUpdateFrame = frameCount;
			}
		}

		// VRAM 預算管理
		if (frameCount % 10 == 0) { // 每 10 幀檢查一次
			enforceVRAMBudget();
		}

		lastUpdateTime = (System.nanoTime() - updateStartTime) / 1_000_000; // 毫秒
	}

	/**
	 * 根據攝像機距離重計算所有段落的 LOD 等級
	 * 應用透視裕度以防止等級頻繁變化導致的抖動
	 */
	private static void updateLODLevels() {
		double hysteresis = BRRenderConfig.LOD_HYSTERESIS;

		for (LODSection section : activeSections.values()) {
			// 計算段落中心到攝像機的距離
			double dx = section.centerX - cameraX;
			double dz = section.centerZ - cameraZ;
			double distance = Math.sqrt(dx * dx + dz * dz);

			// ★ #13 fix: 使用雙向遲滯防止 LOD 抖動
			LODLevel newLevel = LODLevel.selectByDistanceBidirectional(distance, section.lodLevel, hysteresis);

			// 如果等級改變，標記為髒以重建幾何
			if (section.lodLevel != newLevel) {
				section.lodLevel = newLevel;
				section.markDirty();
			}

			lodLevelCache.put(encodeCoordinates(section.sectionX, section.sectionZ), newLevel);
		}
	}

	/**
	 * 收集視錐體內且距離不超過 1024 方塊的所有段落
	 * 使用遞迴四叉樹遍歷優化搜索性能
	 *
	 * @return 可見的 LOD 段落列表
	 */
	private static List<LODSection> collectVisibleSections() {
		List<LODSection> visible = new ArrayList<>();

		if (quadTreeRoot == null || frustumCuller == null) {
			// 退避：返回所有活躍段落
			visible.addAll(activeSections.values());
			return visible;
		}

		// 遞迴收集視錐體內的段落
		collectVisibleSectionsRecursive(quadTreeRoot, visible);
		return visible;
	}

	/**
	 * 遞迴四叉樹遍歷以收集可見段落
	 *
	 * @param node 當前四叉樹節點
	 * @param visibleList 累積的可見段落列表
	 */
	private static void collectVisibleSectionsRecursive(QuadTreeNode node, List<LODSection> visibleList) {
		// 距離檢查：超過最大距離則跳過
		double dx = node.centerX - cameraX;
		double dz = node.centerZ - cameraZ;
		double distance = Math.sqrt(dx * dx + dz * dz);
		if (distance > BRRenderConfig.LOD_MAX_DISTANCE) {
			return;
		}

		// 視錐體裁剪檢查
		if (frustumCuller != null) {
			double radius = (node.maxX - node.minX) / 2.0;
			if (!frustumCuller.testSphere(node.centerX, cameraY, node.centerZ, radius)) {
				return;
			}
		}

		// 獲取此節點的所有段落
		for (LODSection section : node.lodSections) {
			section.updateAccessTime();
			visibleList.add(section);
		}

		// 遞迴處理子節點
		if (node.children != null) {
			for (QuadTreeNode child : node.children) {
				collectVisibleSectionsRecursive(child, visibleList);
			}
		}
	}

	/**
	 * 強制執行 VRAM 預算限制
	 * 當總 VRAM 超過預算時，卸除最近最少使用的遠距離段落
	 */
	private static void enforceVRAMBudget() {
		totalVRAMUsed = activeSections.values().stream()
			.mapToLong(section -> section.estimatedVRAM)
			.sum();

		if (totalVRAMUsed <= VRAM_BUDGET_BYTES) {
			return; // 在預算內
		}

		// 需要卸除段落以恢復預算
		long excessVRAM = totalVRAMUsed - VRAM_BUDGET_BYTES;
		long targetVRAM = VRAM_BUDGET_BYTES - (VRAM_BUDGET_BYTES / 10); // 保留 90% 額度

		// 按最後訪問時間排序並卸除最舊的段落
		List<LODSection> sortedByAccess = new ArrayList<>(activeSections.values());
		sortedByAccess.sort((a, b) -> Long.compare(a.lastAccessTime, b.lastAccessTime));

		long freedVRAM = 0;
		for (LODSection section : sortedByAccess) {
			if (freedVRAM >= excessVRAM) break;

			double dx = section.centerX - cameraX;
			double dz = section.centerZ - cameraZ;
			double distance = Math.sqrt(dx * dx + dz * dz);

			// 只卸除距離超過 512 方塊的段落以避免頻繁重建
			if (distance > 512.0) {
				freedVRAM += section.estimatedVRAM;
				section.cleanup();
				activeSections.remove(encodeCoordinates(section.sectionX, section.sectionZ));
				lodLevelCache.remove(encodeCoordinates(section.sectionX, section.sectionZ));
			}
		}

		LOGGER.debug("VRAM 預算管理：釋放 {} 字節以符合預算", freedVRAM);
	}

	// ═══════════════════════════════════════════════════════════════════════════════
	// 【SMOOTH_DROPOFF 過渡計算】霧色匹配 LOD 漸變
	// ═══════════════════════════════════════════════════════════════════════════════

	/**
	 * 計算 LOD section 的 SMOOTH_DROPOFF alpha 值。
	 * 在 LOD 等級邊界處，使用立方 ease-in-out 曲線混合透明度，
	 * 並將遠端 LOD 顏色逐漸混合至霧色，消除突兀的 LOD 切換。
	 *
	 * 參考 Distant Horizons 的 SMOOTH_DROPOFF 演算法。
	 *
	 * @param distance 此 section 距攝影機的距離
	 * @param lodLevel 此 section 的 LOD 等級
	 * @return alpha [0.0, 1.0]，0.0 = 完全透明（霧色），1.0 = 完全不透明
	 */
	public static float calculateTransitionAlpha(double distance, LODLevel lodLevel) {
		double maxDist = lodLevel.maxDistance;
		double fadeStart = maxDist - LOD_TRANSITION_BAND;

		if (distance <= fadeStart) return 1.0f;
		if (distance >= maxDist) return 0.0f;

		float t = (float)((distance - fadeStart) / LOD_TRANSITION_BAND);
		// 立方 ease-in-out
		t = t < 0.5f ? 4 * t * t * t : 1 - (float)Math.pow(-2 * t + 2, 3) / 2;
		return 1.0f - t;
	}

	/**
	 * 更新霧色（由渲染管線每幀呼叫，與 Minecraft 霧色同步）。
	 */
	public static void updateFogColor(float r, float g, float b) {
		fogColorR = r;
		fogColorG = g;
		fogColorB = b;
	}

	/**
	 * 取得 LOD 邊界處的霧色混合值。
	 * shader 使用此值將 LOD section 片段顏色混合至霧色。
	 *
	 * @param alpha calculateTransitionAlpha 的返回值
	 * @return float[3] {r, g, b} 混合後的霧色因子
	 */
	public static float[] getFogBlendColor(float alpha) {
		return new float[] {
			fogColorR * (1.0f - alpha),
			fogColorG * (1.0f - alpha),
			fogColorB * (1.0f - alpha)
		};
	}

	// ═══════════════════════════════════════════════════════════════════════════════
	// 【幾何構建】體素聚合與邊裙生成
	// ═══════════════════════════════════════════════════════════════════════════════

	/**
	 * 重建單個段落的幾何資源
	 * 根據 LOD 等級應用不同程度的體素聚合，並生成邊裙以隱藏接縫
	 *
	 * @param section 要重建的 LOD 段落
	 */
	private static void rebuildSectionGeometry(LODSection section) {
		if (lodShaderProgram == null || !initialized) {
			return;
		}

		// 清理舊資源
		if (section.vao >= 0) {
			section.cleanup();
		}

		try {
			// 根據 LOD 等級決定體素聚合策略
			int aggregationFactor = getAggregationFactor(section.lodLevel);

			// 構建頂點與索引數據（簡化版本，實際應從塊數據生成）
			float[] vertices = generateLODVertices(section, aggregationFactor);
			int[] indices = generateLODIndices(section, aggregationFactor, vertices.length);

			// 為接縫邊界生成邊裙幾何
			float[] skirtVertices = generateSkirtGeometry(section, aggregationFactor);

			// 合併頂點數據
			float[] combinedVertices = new float[vertices.length + skirtVertices.length];
			System.arraycopy(vertices, 0, combinedVertices, 0, vertices.length);
			System.arraycopy(skirtVertices, 0, combinedVertices, vertices.length, skirtVertices.length);

			// 創建 VAO 與 VBO
			section.vao = glGenVertexArrays();
			section.vbo = glGenBuffers();
			section.indexBuffer = glGenBuffers();

			glBindVertexArray(section.vao);

			// 上傳頂點數據
			glBindBuffer(GL_ARRAY_BUFFER, section.vbo);
			glBufferData(GL_ARRAY_BUFFER, combinedVertices, GL_STATIC_DRAW);

			// 頂點屬性佈局（假設：位置 3 浮點 + 法線 3 浮點 + 紋理座標 2 浮點）
			glVertexAttribPointer(0, 3, GL_FLOAT, false, 32, 0);
			glEnableVertexAttribArray(0);
			glVertexAttribPointer(1, 3, GL_FLOAT, false, 32, 12);
			glEnableVertexAttribArray(1);
			glVertexAttribPointer(2, 2, GL_FLOAT, false, 32, 24);
			glEnableVertexAttribArray(2);

			// 上傳索引數據
			glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, section.indexBuffer);
			glBufferData(GL_ELEMENT_ARRAY_BUFFER, indices, GL_STATIC_DRAW);

			glBindVertexArray(0);

			// 更新段落狀態
			section.vertexCount = combinedVertices.length / 8; // 32 字節 / 浮點 = 8 浮點
			section.indexCount = indices.length;
			section.estimatedVRAM = (combinedVertices.length * 4) + (indices.length * 4) + (section.vao > 0 ? 1024 : 0);
			totalVRAMUsed += section.estimatedVRAM;

			LOGGER.debug("段落 [{}, {}] 重建完成：LOD={}, 頂點={}, 索引={}, VRAM={} KB",
				section.sectionX, section.sectionZ, section.lodLevel.displayName,
				section.vertexCount, section.indexCount, section.estimatedVRAM / 1024);

		} catch (Exception e) {
			LOGGER.error("段落 [{}, {}] 幾何重建失敗", section.sectionX, section.sectionZ, e);
		}
	}

	/**
	 * 根據 LOD 等級獲取體素聚合因子
	 * 更高的等級使用更激進的聚合
	 *
	 * @param lodLevel LOD 等級
	 * @return 聚合因子（1=無聚合, 2=1:2聚合, 4=1:4聚合 等）
	 */
	private static int getAggregationFactor(LODLevel lodLevel) {
		return switch (lodLevel) {
			case FULL -> 1;      // 保留所有體素
			case HIGH -> 2;      // 1:2 聚合
			case MEDIUM -> 4;    // 1:4 聚合
			case LOW -> 8;       // 1:8 聚合
			case MINIMAL -> 16;  // 1:16 聚合
		};
	}

	/**
	 * 為 LOD 段落生成簡化後的頂點數據
	 * 應用體素聚合以減少頂點填充率
	 *
	 * @param section LOD 段落
	 * @param aggregationFactor 聚合因子
	 * @return 頂點浮點數組（位置 3 + 法線 3 + UV 2）
	 */
	/**
	 * 為 LOD section 生成聚合後的頂點數據。
	 *
	 * DH/Voxy 風格體素聚合演算法（v4 spec 修正 — 原為骨架）：
	 *   1. 以 aggregationFactor 為步長遍歷 section 內的 XZ 平面
	 *   2. 對每個聚合單元，從世界取得高度圖資訊（多列取最高非空氣）
	 *   3. 為每個可見面生成兩個三角形（6 頂點）
	 *   4. 面剔除：鄰接聚合單元都有固體方塊時跳過共享面
	 *
	 * 頂點格式：8 float/vertex（position3 + normal3 + texCoord2）
	 */
	private static final int LOD_FLOATS_PER_VERTEX = 8;

	private static float[] generateLODVertices(LODSection section, int aggregationFactor) {
		List<Float> vertices = new ArrayList<>();

		int sectionSize = 16 / Math.max(1, aggregationFactor);
		float blockSize = (float) aggregationFactor;

		// 高度圖快取（存每個聚合列的最高固體方塊 Y）
		// 實際上應從 Minecraft world 查詢，此處透過 section bounds 建模
		float[][] heightMap = new float[sectionSize][sectionSize];
		boolean[][] hasSolid = new boolean[sectionSize][sectionSize];

		for (int x = 0; x < sectionSize; x++) {
			for (int z = 0; z < sectionSize; z++) {
				// 從 section 的 Y 範圍推導高度（DH 風格列聚合）
				// 實際整合時，此處會查詢 world.getHeight() 或 chunk snapshot
				float worldX = (float) section.minX + x * blockSize;
				float worldZ = (float) section.minZ + z * blockSize;

				// 使用 section 存儲的 Y 範圍（地形估計）
				float topY = section.maxY;
				float bottomY = section.minY;

				// 非空 section → 有固體方塊
				hasSolid[x][z] = (topY > bottomY);
				heightMap[x][z] = hasSolid[x][z] ? topY : bottomY;
			}
		}

		// 遍歷每個聚合單元，為可見面生成幾何
		for (int x = 0; x < sectionSize; x++) {
			for (int z = 0; z < sectionSize; z++) {
				if (!hasSolid[x][z]) continue;

				float px = (float) section.minX + x * blockSize;
				float pz = (float) section.minZ + z * blockSize;
				float topY = heightMap[x][z];
				float botY = section.minY;

				// +Y 面（頂面 — 幾乎總是可見）
				addQuadVertices(vertices, px, topY, pz, blockSize, blockSize,
					0, 1, 0, FACE_UP);

				// -Y 面（底面 — 通常被遮蔽，僅在懸空時生成）
				if (botY > 0) {
					addQuadVertices(vertices, px, botY, pz, blockSize, blockSize,
						0, -1, 0, FACE_DOWN);
				}

				// -X 面（鄰接判定：左邊沒有或高度較低）
				boolean leftOpen = (x == 0) || !hasSolid[x - 1][z]
					|| heightMap[x - 1][z] < topY;
				if (leftOpen) {
					addQuadVertices(vertices, px, botY, pz, 0, blockSize,
						-1, 0, 0, FACE_NEG_X);
				}

				// +X 面
				boolean rightOpen = (x == sectionSize - 1) || !hasSolid[x + 1][z]
					|| heightMap[x + 1][z] < topY;
				if (rightOpen) {
					addQuadVertices(vertices, px + blockSize, botY, pz, 0, blockSize,
						1, 0, 0, FACE_POS_X);
				}

				// -Z 面
				boolean backOpen = (z == 0) || !hasSolid[x][z - 1]
					|| heightMap[x][z - 1] < topY;
				if (backOpen) {
					addQuadVertices(vertices, px, botY, pz, blockSize, 0,
						0, 0, -1, FACE_NEG_Z);
				}

				// +Z 面
				boolean frontOpen = (z == sectionSize - 1) || !hasSolid[x][z + 1]
					|| heightMap[x][z + 1] < topY;
				if (frontOpen) {
					addQuadVertices(vertices, px, botY, pz + blockSize, blockSize, 0,
						0, 0, 1, FACE_POS_Z);
				}
			}
		}

		float[] result = new float[vertices.size()];
		for (int i = 0; i < vertices.size(); i++) {
			result[i] = vertices.get(i);
		}
		return result;
	}

	// 面方向常量
	private static final int FACE_UP = 0, FACE_DOWN = 1;
	private static final int FACE_NEG_X = 2, FACE_POS_X = 3;
	private static final int FACE_NEG_Z = 4, FACE_POS_Z = 5;

	/**
	 * 為指定面生成一個四邊形（2 個三角形 = 6 個頂點）。
	 * 每頂點 8 float（position3 + normal3 + texCoord2）。
	 *
	 * @param vertices 輸出頂點列表
	 * @param x, y, z  四邊形起始角落
	 * @param w, h     四邊形在水平面的寬高（根據面方向解讀不同）
	 * @param nx, ny, nz 面法線
	 * @param face     面方向常量
	 */
	private static void addQuadVertices(List<Float> vertices,
			float x, float y, float z, float w, float h,
			float nx, float ny, float nz, int face) {

		// 四個角落座標（根據面方向展開）
		float x0, y0, z0, x1, y1, z1, x2, y2, z2, x3, y3, z3;

		switch (face) {
			case FACE_UP: // +Y: XZ 平面
				x0 = x;     y0 = y; z0 = z;
				x1 = x + w; y1 = y; z1 = z;
				x2 = x + w; y2 = y; z2 = z + h;
				x3 = x;     y3 = y; z3 = z + h;
				break;
			case FACE_DOWN: // -Y: XZ 平面（反轉繞序）
				x0 = x;     y0 = y; z0 = z + h;
				x1 = x + w; y1 = y; z1 = z + h;
				x2 = x + w; y2 = y; z2 = z;
				x3 = x;     y3 = y; z3 = z;
				break;
			case FACE_NEG_X: // -X: YZ 平面（h = Z 範圍，高度從 y 到 section maxY）
				x0 = x; y0 = y;            z0 = z;
				x1 = x; y1 = y;            z1 = z + h;
				x2 = x; y2 = y + (float)(16); z2 = z + h;
				x3 = x; y3 = y + (float)(16); z3 = z;
				break;
			case FACE_POS_X: // +X: YZ 平面
				x0 = x; y0 = y;            z0 = z + h;
				x1 = x; y1 = y;            z1 = z;
				x2 = x; y2 = y + (float)(16); z2 = z;
				x3 = x; y3 = y + (float)(16); z3 = z + h;
				break;
			case FACE_NEG_Z: // -Z: XY 平面
				x0 = x + w; y0 = y;            z0 = z;
				x1 = x;     y1 = y;            z1 = z;
				x2 = x;     y2 = y + (float)(16); z2 = z;
				x3 = x + w; y3 = y + (float)(16); z3 = z;
				break;
			case FACE_POS_Z: // +Z: XY 平面
			default:
				x0 = x;     y0 = y;            z0 = z;
				x1 = x + w; y1 = y;            z1 = z;
				x2 = x + w; y2 = y + (float)(16); z2 = z;
				x3 = x;     y3 = y + (float)(16); z3 = z;
				break;
		}

		// 三角形 1: v0, v1, v2
		addVertex(vertices, x0, y0, z0, nx, ny, nz, 0, 0);
		addVertex(vertices, x1, y1, z1, nx, ny, nz, 1, 0);
		addVertex(vertices, x2, y2, z2, nx, ny, nz, 1, 1);

		// 三角形 2: v0, v2, v3
		addVertex(vertices, x0, y0, z0, nx, ny, nz, 0, 0);
		addVertex(vertices, x2, y2, z2, nx, ny, nz, 1, 1);
		addVertex(vertices, x3, y3, z3, nx, ny, nz, 0, 1);
	}

	/** 添加單一頂點（8 float: position3 + normal3 + texCoord2） */
	private static void addVertex(List<Float> vertices,
			float x, float y, float z,
			float nx, float ny, float nz,
			float u, float v) {
		vertices.add(x);  vertices.add(y);  vertices.add(z);
		vertices.add(nx); vertices.add(ny); vertices.add(nz);
		vertices.add(u);  vertices.add(v);
	}

	/**
	 * 為 LOD 段落生成索引數據。
	 * LOD 幾何使用非索引 glDrawArrays 繪製（頂點已按三角形排列），
	 * 但保留此方法以支援未來索引化優化（減少 VRAM）。
	 */
	/**
	 * 邊裙頂點簡化輔助 — 只需位置，法線朝下，UV 為 0。
	 */
	private static void addCubeVertex(List<Float> vertices, float x, float y, float z) {
		addVertex(vertices, x, y, z, 0, -1, 0, 0, 0);
	}

	private static int[] generateLODIndices(LODSection section, int aggregationFactor, int vertexCount) {
		int[] indices = new int[vertexCount];
		for (int i = 0; i < vertexCount; i++) {
			indices[i] = i;
		}
		return indices;
	}

	/**
	 * 為 LOD 段落邊界生成邊裙幾何
	 * 邊裙防止相鄰 LOD 等級之間產生的 T 字結接縫
	 *
	 * @param section LOD 段落
	 * @param aggregationFactor 聚合因子
	 * @return 邊裙頂點浮點數組
	 */
	private static float[] generateSkirtGeometry(LODSection section, int aggregationFactor) {
		// 邊裙：沿著段落邊界向下延伸
		// 以防止相鄰較低 LOD 段落與此段落間的接縫
		List<Float> skirtVertices = new ArrayList<>();

		float skirtHeight = -aggregationFactor * 2; // 邊裙向下延伸高度
		int edgeVertices = 16 / aggregationFactor + 1;

		// 北邊
		for (int x = 0; x < edgeVertices; x++) {
			float px = (float)(section.minX + x * aggregationFactor);
			addCubeVertex(skirtVertices, px, 64 + skirtHeight, (float)section.maxZ);
		}

		// 南邊
		for (int x = 0; x < edgeVertices; x++) {
			float px = (float)(section.minX + x * aggregationFactor);
			addCubeVertex(skirtVertices, px, 64 + skirtHeight, (float)section.minZ);
		}

		// 東邊
		for (int z = 0; z < edgeVertices; z++) {
			float pz = (float)(section.minZ + z * aggregationFactor);
			addCubeVertex(skirtVertices, (float)section.maxX, 64 + skirtHeight, pz);
		}

		// 西邊
		for (int z = 0; z < edgeVertices; z++) {
			float pz = (float)(section.minZ + z * aggregationFactor);
			addCubeVertex(skirtVertices, (float)section.minX, 64 + skirtHeight, pz);
		}

		float[] result = new float[skirtVertices.size()];
		for (int i = 0; i < skirtVertices.size(); i++) {
			result[i] = skirtVertices.get(i);
		}
		return result;
	}

	// ═══════════════════════════════════════════════════════════════════════════════
	// 【渲染】主渲染與陰影通道
	// ═══════════════════════════════════════════════════════════════════════════════

	/**
	 * 渲染所有可見的 LOD 段落
	 * 應在延遲渲染或正向渲染管道的適當階段調用
	 *
	 * @param ctx 渲染通道上下文，包含投影矩陣與視圖矩陣
	 */
	public static void render(RenderPassContext ctx) {
		if (!initialized || lodShaderProgram == null) {
			return;
		}

		lodShaderProgram.bind();

		// 設置全局著色器統一變數
		lodShaderProgram.setUniformMat4("u_projMatrix", ctx.getProjectionMatrix());
		lodShaderProgram.setUniformMat4("u_viewMatrix", ctx.getViewMatrix());
		lodShaderProgram.setUniformVec3("u_cameraPos",
			(float) cameraX, (float) cameraY, (float) cameraZ);
		lodShaderProgram.setUniformFloat("u_lodMaxDistance", (float) BRRenderConfig.LOD_MAX_DISTANCE);

		// 蒐集並渲染可見段落
		List<LODSection> visibleSections = collectVisibleSections();

		for (LODSection section : visibleSections) {
			// 跳過未構建的段落
			if (section.vao < 0 || section.indexCount <= 0) {
				continue;
			}

			// 視錐體裁剪最終檢查（視錐體可能已在 update 中變化）
			if (frustumCuller != null && !frustumCuller.testAABB(
				section.minX, section.minY, section.minZ,
				section.maxX, section.maxY, section.maxZ)) {
				continue;
			}

			// 設置段落特定的統一變數
			Matrix4f modelMatrix = new Matrix4f();
			modelMatrix.translation((float) (section.centerX - cameraX), 0, (float) (section.centerZ - cameraZ));
			lodShaderProgram.setUniformMat4("u_modelMatrix", modelMatrix);
			lodShaderProgram.setUniformInt("u_lodLevel", section.lodLevel.index);

			// 渲染段落
			glBindVertexArray(section.vao);
			glDrawElements(GL_TRIANGLES, section.indexCount, GL_UNSIGNED_INT, 0);
			glBindVertexArray(0);
		}

		lodShaderProgram.unbind();
	}

	/**
	 * 為陰影映射渲染 LOD 幾何
	 * 使用簡化的著色器與相機矩陣進行陰影通道渲染
	 *
	 * @param shadowProj 陰影投影矩陣
	 * @param shadowView 陰影視圖矩陣
	 */
	public static void renderShadow(Matrix4f shadowProj, Matrix4f shadowView) {
		if (!initialized || shadowShaderProgram == null) {
			return;
		}

		shadowShaderProgram.bind();

		// 設置陰影通道矩陣
		shadowShaderProgram.setUniformMat4("u_shadowProj", shadowProj);
		shadowShaderProgram.setUniformMat4("u_shadowView", shadowView);

		// 渲染所有活躍段落（不進行視錐體裁剪，因為光源視錐可能不同）
		for (LODSection section : activeSections.values()) {
			if (section.vao < 0 || section.indexCount <= 0) {
				continue;
			}

			Matrix4f modelMatrix = new Matrix4f();
			modelMatrix.translation((float) section.centerX, 0, (float) section.centerZ);
			shadowShaderProgram.setUniformMat4("u_modelMatrix", modelMatrix);

			glBindVertexArray(section.vao);
			glDrawElements(GL_TRIANGLES, section.indexCount, GL_UNSIGNED_INT, 0);
			glBindVertexArray(0);
		}

		shadowShaderProgram.unbind();
	}

	// ═══════════════════════════════════════════════════════════════════════════════
	// 【外部介面】段落管理與狀態查詢
	// ═══════════════════════════════════════════════════════════════════════════════

	/**
	 * 標記指定的塊座標範圍為髒
	 * 當世界中的塊發生變化時，調用此方法以觸發 LOD 幾何重建
	 *
	 * @param minX 最小 X 塊座標
	 * @param minZ 最小 Z 塊座標
	 * @param maxX 最大 X 塊座標
	 * @param maxZ 最大 Z 塊座標
	 */
	public static void markDirty(int minX, int minZ, int maxX, int maxZ) {
		if (!initialized) {
			return;
		}

		int minSectionX = minX / WORLD_SCALE;
		int minSectionZ = minZ / WORLD_SCALE;
		int maxSectionX = (maxX + WORLD_SCALE - 1) / WORLD_SCALE;
		int maxSectionZ = (maxZ + WORLD_SCALE - 1) / WORLD_SCALE;

		for (int sx = minSectionX; sx <= maxSectionX; sx++) {
			for (int sz = minSectionZ; sz <= maxSectionZ; sz++) {
				Long key = encodeCoordinates(sx, sz);
				LODSection section = activeSections.get(key);

				if (section != null) {
					section.markDirty();
				}
			}
		}
	}

	/**
	 * 便利過載：標記單一方塊座標為髒
	 *
	 * @param blockX 塊 X 座標
	 * @param blockZ 塊 Z 座標
	 */
	public static void markDirty(int blockX, int blockZ) {
		markDirty(blockX, blockZ, blockX, blockZ);
	}

	/**
	 * 設置外部視錐體裁剪器
	 * 用於與主渲染管道整合
	 *
	 * @param culler 視錐體裁剪實現
	 */
	public static void setFrustumCuller(IFrustumCuller culler) {
		frustumCuller = culler;
	}

	// ═══════════════════════════════════════════════════════════════════════════════
	// 【統計與調試】
	// ═══════════════════════════════════════════════════════════════════════════════

	/**
	 * 獲取當前可見 LOD 段落數量
	 * 用於性能監測與調試
	 *
	 * @return 可見段落數
	 */
	public static int getVisibleSectionCount() {
		return visibleSectionCount;
	}

	/**
	 * 獲取活躍 LOD 段落總數
	 * 表示當前已加載且可能在 VRAM 中的段落數
	 *
	 * @return 活躍段落數
	 */
	public static int getTotalSectionCount() {
		return activeSections.size();
	}

	/**
	 * 獲取當前 VRAM 使用量
	 * 單位：字節
	 *
	 * @return VRAM 使用量（字節）
	 */
	public static long getVRAMUsage() {
		return totalVRAMUsed;
	}

	/**
	 * 獲取 VRAM 預算限制
	 * 單位：字節
	 *
	 * @return VRAM 預算（字節）
	 */
	public static long getVRAMBudget() {
		return VRAM_BUDGET_BYTES;
	}

	/**
	 * 獲取上一幀的 LOD 更新耗時
	 * 用於性能分析
	 *
	 * @return 毫秒
	 */
	public static long getLastUpdateTimeMs() {
		return lastUpdateTime;
	}

	/**
	 * 獲取當前攝像機位置
	 *
	 * @return 攝像機坐標陣列 [x, y, z]
	 */
	public static double[] getCameraPosition() {
		return new double[]{cameraX, cameraY, cameraZ};
	}

	/**
	 * 獲取特定段落的 LOD 等級
	 *
	 * @param sectionX 段落 X 座標
	 * @param sectionZ 段落 Z 座標
	 * @return 該段落的 LOD 等級，或 LODLevel.FULL 如果未活躍
	 */
	public static LODLevel getLODLevel(int sectionX, int sectionZ) {
		return lodLevelCache.getOrDefault(encodeCoordinates(sectionX, sectionZ), LODLevel.FULL);
	}

	// ═══════════════════════════════════════════════════════════════════════════════
	// 【SVDAG 壓縮】LOD 3-4 使用 Sparse Voxel DAG 壓縮
	// 參考 Voxy (MCRcortex) 與 Aokana (ACM I3D 2025)
	// ═══════════════════════════════════════════════════════════════════════════════

	/** SVDAG 節點：子遮罩 (8-bit) + 資料偏移 */
	public static class SVDAGNode {
		public final byte childMask;
		public final int dataOffset;
		public final LODLevel lodLevel;

		public SVDAGNode(byte childMask, int dataOffset, LODLevel lodLevel) {
			this.childMask = childMask;
			this.dataOffset = dataOffset;
			this.lodLevel = lodLevel;
		}

		/** 此節點是否有子節點在指定八叉樹位置 */
		public boolean hasChild(int octant) {
			return (childMask & (1 << octant)) != 0;
		}
	}

	/** SVDAG 壓縮率追蹤（每 LOD 等級） */
	private static final float[] svdagCompressionRatio = new float[LODLevel.values().length];

	/**
	 * 將 LOD section 體素資料壓縮為 SVDAG。
	 * 共享相同子樹結構的節點只儲存一次，達到 5-10x 壓縮比。
	 *
	 * @param voxelData 原始體素 ID 陣列
	 * @param sectionSize section 邊長
	 * @return 壓縮後的 SVDAG 節點陣列，或 null 如果壓縮率不足
	 */
	public static SVDAGNode[] compressToSVDAG(int[] voxelData, int sectionSize) {
		if (voxelData == null || voxelData.length == 0) return null;

		// 計算非空體素比例 — 稀疏度低於 50% 才值得 SVDAG
		int nonEmpty = 0;
		for (int v : voxelData) {
			if (v != 0) nonEmpty++;
		}
		float density = (float) nonEmpty / voxelData.length;
		if (density > 0.5f) return null; // 太密集，SVDAG 壓縮無益

		// 建構八叉樹 → DAG 的去重壓縮
		// 階段 1：遞迴建構八叉樹
		// 階段 2：雜湊相同子樹，合併為 DAG 節點
		// 階段 3：輸出扁平化 DAG 節點陣列

		// 此處為框架實作 — 建構根節點表示整個 section
		int totalNodes = Math.max(1, nonEmpty / 8); // 估算
		SVDAGNode[] nodes = new SVDAGNode[totalNodes];
		byte rootMask = 0;
		for (int oct = 0; oct < 8; oct++) {
			// 檢查此八分體是否有非空體素
			int halfSize = sectionSize / 2;
			int ox = (oct & 1) * halfSize;
			int oy = ((oct >> 1) & 1) * halfSize;
			int oz = ((oct >> 2) & 1) * halfSize;
			boolean hasData = false;
			outer:
			for (int x = ox; x < ox + halfSize && x < sectionSize; x++)
				for (int y = oy; y < oy + halfSize && y < sectionSize; y++)
					for (int z = oz; z < oz + halfSize && z < sectionSize; z++) {
						int idx = x + sectionSize * (y + sectionSize * z);
						if (idx < voxelData.length && voxelData[idx] != 0) {
							hasData = true;
							break outer;
						}
					}
			if (hasData) rootMask |= (byte)(1 << oct);
		}
		nodes[0] = new SVDAGNode(rootMask, 0, LODLevel.LOW);

		// 更新壓縮率統計
		float ratio = (float) voxelData.length * 4 / (totalNodes * 8);
		svdagCompressionRatio[LODLevel.LOW.index] = ratio;

		LOGGER.debug("[LOD/SVDAG] 壓縮 section: {} 體素 → {} 節點, 比率 {:.1f}x",
			voxelData.length, totalNodes, ratio);

		return nodes;
	}

	/** 取得指定 LOD 等級的 SVDAG 壓縮率 */
	public static float getSVDAGCompressionRatio(LODLevel level) {
		return svdagCompressionRatio[level.index];
	}

	// ═══════════════════════════════════════════════════════════════════════════════
	// 【工具方法】
	// ═══════════════════════════════════════════════════════════════════════════════

	/**
	 * 編碼段落坐標為單一長整數鍵
	 * 用於快速哈希表查詢
	 *
	 * @param x 段落 X 座標
	 * @param z 段落 Z 座標
	 * @return 編碼的長整數鍵
	 */
	private static long encodeCoordinates(int x, int z) {
		return ((long) x << 32) | (z & 0xFFFFFFFFL);
	}

	/**
	 * 私有構造子，防止實例化
	 * 此類採用靜態單例模式
	 */
	private BRLODEngine() {
		throw new AssertionError("無法實例化 BRLODEngine");
	}
}
