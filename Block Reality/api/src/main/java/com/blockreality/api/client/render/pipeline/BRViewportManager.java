package com.blockreality.api.client.render.pipeline;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.blockreality.api.client.render.BRRenderConfig;

/**
 * 多视口相机系统管理器
 *
 * 灵感来自 Create Aeronautics 和 Axiom，实现 Rhino 风格的多视图编辑功能。
 * 支持单视口、双视口（水平/垂直）、四视口（QUAD）布局，
 * 每个视口均有独立的 FBO、投影矩阵和相机参数。
 *
 * 正交视图自动同步到透视视图的目标位置，
 * 提供完整的输入路由、FBO 管理和视口布局切换功能。
 *
 * @author Block Reality Team
 * @since 1.0.0
 * @deprecated Since 2.0, replaced by Vulkan RT + Voxy LOD pipeline in com.blockreality.api.client.rendering
 */
@Deprecated(since = "2.0", forRemoval = true)
@OnlyIn(Dist.CLIENT)
public class BRViewportManager {

    private static final Logger LOG = LoggerFactory.getLogger(BRViewportManager.class);

    // ═══════════════════════════════════════════════════════════════════════════════
    // 枚举与配置类
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * 视口模式枚举
     *
     * 定义不同的多视口布局配置。
     */
    public enum ViewportMode {
        /** 单视口模式（默认） */
        SINGLE,
        /** 双视口水平分割 */
        DUAL_HORIZONTAL,
        /** 双视口垂直分割 */
        DUAL_VERTICAL,
        /** 四视口 Rhino 风格 */
        QUAD
    }

    /**
     * 投影类型枚举
     *
     * 定义视口的投影方式。
     */
    public enum ProjectionType {
        /** 透视投影 */
        PERSPECTIVE,
        /** 顶视正交投影（俯视图） */
        ORTHOGRAPHIC_TOP,
        /** 前视正交投影（前视图） */
        ORTHOGRAPHIC_FRONT,
        /** 右视正交投影（右视图） */
        ORTHOGRAPHIC_RIGHT
    }

    /**
     * 视口配置类
     *
     * 存储单个视口的位置、大小、投影类型和启用状态。
     */
    public static class ViewportConfig {
        /** 视口编号（0-3） */
        public final int id;
        /** 视口左上角 X 坐标（归一化 0-1） */
        public final float x;
        /** 视口左上角 Y 坐标（归一化 0-1） */
        public final float y;
        /** 视口宽度（归一化 0-1） */
        public final float width;
        /** 视口高度（归一化 0-1） */
        public final float height;
        /** 投影类型 */
        public final ProjectionType projectionType;
        /** 缩放等级（用于正交视图） */
        public float zoom;
        /** 视口是否启用 */
        public boolean enabled;

        /**
         * 构造视口配置
         *
         * @param id 视口编号
         * @param x 左上角 X 坐标
         * @param y 左上角 Y 坐标
         * @param width 宽度
         * @param height 高度
         * @param projectionType 投影类型
         */
        public ViewportConfig(int id, float x, float y, float width, float height,
                            ProjectionType projectionType) {
            this.id = id;
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            this.projectionType = projectionType;
            this.zoom = 1.0f;
            this.enabled = true;
        }
    }

    /**
     * 视口相机类
     *
     * 管理单个视口的相机状态，包括位置、旋转、投影矩阵计算。
     */
    public static class ViewportCamera {
        /** 相机 X 坐标 */
        public double camX;
        /** 相机 Y 坐标 */
        public double camY;
        /** 相机 Z 坐标 */
        public double camZ;
        /** 相机偏航角（仅用于透视投影） */
        public float yaw;
        /** 相机俯仰角（仅用于透视投影） */
        public float pitch;
        /** 缩放级别（用于正交投影） */
        public float zoom;
        /** 近平面距离 */
        public float nearPlane;
        /** 远平面距离 */
        public float farPlane;

        /**
         * 构造相机实例，使用默认的近远平面。
         */
        public ViewportCamera() {
            this.camX = 0.0;
            this.camY = 0.0;
            this.camZ = 0.0;
            this.yaw = 0.0f;
            this.pitch = 0.0f;
            this.zoom = 1.0f;
            this.nearPlane = 0.1f;
            this.farPlane = (float) BRRenderConfig.LOD_MAX_DISTANCE;
        }

        /**
         * 计算投影矩阵
         *
         * 根据投影类型返回透视或正交投影矩阵。
         *
         * @param aspectRatio 宽高比
         * @param projectionType 投影类型
         * @return 投影矩阵
         */
        public Matrix4f computeProjectionMatrix(float aspectRatio, ProjectionType projectionType) {
            Matrix4f projection = new Matrix4f();

            if (projectionType == ProjectionType.PERSPECTIVE) {
                // 透视投影：视场角 45 度
                float fov = (float) Math.toRadians(45.0);
                projection.perspective(fov, aspectRatio, nearPlane, farPlane);
            } else {
                // 正交投影：根据缩放级别计算视域大小
                float orthoHeight = 50.0f / zoom;
                float orthoWidth = orthoHeight * aspectRatio;
                projection.ortho(-orthoWidth / 2.0f, orthoWidth / 2.0f,
                               -orthoHeight / 2.0f, orthoHeight / 2.0f,
                               nearPlane, farPlane);
            }

            return projection;
        }

        /**
         * 计算视图矩阵
         *
         * 基于相机位置和旋转角度，为透视投影计算视图矩阵；
         * 对于正交投影，则返回一个基于相机位置的简单平移矩阵。
         *
         * @param projectionType 投影类型
         * @return 视图矩阵
         */
        public Matrix4f computeViewMatrix(ProjectionType projectionType) {
            Matrix4f view = new Matrix4f();

            if (projectionType == ProjectionType.PERSPECTIVE) {
                // 透视投影：使用 yaw 和 pitch 旋转
                view.identity();
                view.rotateX((float) Math.toRadians(pitch));
                view.rotateY((float) Math.toRadians(yaw));
                view.translate((float) -camX, (float) -camY, (float) -camZ);
            } else if (projectionType == ProjectionType.ORTHOGRAPHIC_TOP) {
                // 顶视图：俯视 XZ 平面
                view.identity();
                view.translate(0.0f, 0.0f, -30.0f);
                view.translate((float) -camX, 0.0f, (float) -camZ);
            } else if (projectionType == ProjectionType.ORTHOGRAPHIC_FRONT) {
                // 前视图：沿 Z 轴看 XY 平面
                view.identity();
                view.translate(0.0f, 0.0f, -30.0f);
                view.translate((float) -camX, (float) -camY, 0.0f);
            } else if (projectionType == ProjectionType.ORTHOGRAPHIC_RIGHT) {
                // 右视图：沿 X 轴看 YZ 平面
                view.identity();
                view.translate(0.0f, 0.0f, -30.0f);
                view.translate(0.0f, (float) -camY, (float) -camZ);
            }

            return view;
        }

        /**
         * 检测点是否在视锥体内（简化实现）
         *
         * 基于相机位置和投影类型进行简单的距离和范围检测。
         *
         * @param x 点 X 坐标
         * @param y 点 Y 坐标
         * @param z 点 Z 坐标
         * @param projectionType 投影类型
         * @return 点是否在视锥体内
         */
        public boolean isPointInFrustum(double x, double y, double z, ProjectionType projectionType) {
            double dx = x - camX;
            double dy = y - camY;
            double dz = z - camZ;
            double distSq = dx * dx + dy * dy + dz * dz;

            // 检查是否在远平面距离内
            double farDistSq = farPlane * farPlane;
            if (distSq > farDistSq) {
                return false;
            }

            // 检查是否在近平面之外
            double nearDistSq = nearPlane * nearPlane;
            if (distSq < nearDistSq) {
                return false;
            }

            // 对于正交投影，检查是否在视域范围内
            if (projectionType == ProjectionType.ORTHOGRAPHIC_TOP) {
                float orthoSize = 50.0f / zoom;
                return Math.abs(dx) <= orthoSize && Math.abs(dz) <= orthoSize;
            } else if (projectionType == ProjectionType.ORTHOGRAPHIC_FRONT) {
                float orthoSize = 50.0f / zoom;
                return Math.abs(dx) <= orthoSize && Math.abs(dy) <= orthoSize;
            } else if (projectionType == ProjectionType.ORTHOGRAPHIC_RIGHT) {
                float orthoSize = 50.0f / zoom;
                return Math.abs(dy) <= orthoSize && Math.abs(dz) <= orthoSize;
            }

            return true;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // 静态单例状态
    // ═══════════════════════════════════════════════════════════════════════════════

    private static boolean initialized = false;
    private static ViewportMode currentMode = ViewportMode.SINGLE;
    private static final ViewportConfig[] allConfigs = new ViewportConfig[4];
    private static final ViewportCamera[] cameras = new ViewportCamera[4];
    private static int activeViewportId = 0;

    // FBO 和纹理存储
    private static final int[] viewportFBOs = new int[4];
    private static final int[] colorTextures = new int[4];
    private static final int[] depthTextures = new int[4];

    // 屏幕分辨率缓存
    private static int cachedScreenWidth = 0;
    private static int cachedScreenHeight = 0;

    // ═══════════════════════════════════════════════════════════════════════════════
    // 初始化与清理
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * 初始化视口管理器
     *
     * 创建所有 FBO 和相机实例，设置默认视口布局。
     * 必须在 OpenGL 上下文中调用。
     *
     * @param screenWidth 屏幕宽度（像素）
     * @param screenHeight 屏幕高度（像素）
     */
    public static void init(int screenWidth, int screenHeight) {
        if (initialized) {
            LOG.warn("BRViewportManager 已初始化，跳过重复初始化");
            return;
        }

        LOG.info("初始化 BRViewportManager，屏幕分辨率: {}x{}", screenWidth, screenHeight);

        cachedScreenWidth = screenWidth;
        cachedScreenHeight = screenHeight;

        // 初始化所有相机
        for (int i = 0; i < 4; i++) {
            cameras[i] = new ViewportCamera();
        }

        // 创建 FBO
        initViewportFBOs(screenWidth, screenHeight);

        // 设置默认视口模式
        setViewportMode(ViewportMode.SINGLE);

        initialized = true;
        LOG.info("BRViewportManager 初始化完成");
    }

    /**
     * 清理视口管理器
     *
     * 删除所有 FBO 和关联的纹理资源。
     * 必须在 OpenGL 上下文中调用。
     */
    public static void cleanup() {
        if (!initialized) {
            return;
        }

        LOG.info("清理 BRViewportManager 资源");

        // 删除 FBO 和纹理
        for (int i = 0; i < 4; i++) {
            if (viewportFBOs[i] != 0) {
                GL30.glDeleteFramebuffers(viewportFBOs[i]);
                viewportFBOs[i] = 0;
            }
            if (colorTextures[i] != 0) {
                GL11.glDeleteTextures(colorTextures[i]);
                colorTextures[i] = 0;
            }
            if (depthTextures[i] != 0) {
                GL11.glDeleteTextures(depthTextures[i]);
                depthTextures[i] = 0;
            }
        }

        initialized = false;
        LOG.info("BRViewportManager 清理完成");
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // FBO 管理
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * 初始化视口帧缓冲对象
     *
     * 为每个视口创建 FBO、颜色纹理和深度纹理。
     * 分辨率根据视口大小缩放（例如 QUAD 模式中每个视口为屏幕分辨率的一半）。
     *
     * @param screenWidth 屏幕宽度
     * @param screenHeight 屏幕高度
     */
    private static void initViewportFBOs(int screenWidth, int screenHeight) {
        LOG.debug("初始化视口 FBO，屏幕分辨率: {}x{}", screenWidth, screenHeight);

        for (int i = 0; i < 4; i++) {
            // 计算此视口的分辨率
            ViewportConfig config = allConfigs[i];
            if (config == null) {
                continue;
            }

            int fboWidth = (int) (screenWidth * config.width);
            int fboHeight = (int) (screenHeight * config.height);

            // 删除旧 FBO（如果存在）
            if (viewportFBOs[i] != 0) {
                GL30.glDeleteFramebuffers(viewportFBOs[i]);
                viewportFBOs[i] = 0;
            }
            if (colorTextures[i] != 0) {
                GL11.glDeleteTextures(colorTextures[i]);
                colorTextures[i] = 0;
            }
            if (depthTextures[i] != 0) {
                GL11.glDeleteTextures(depthTextures[i]);
                depthTextures[i] = 0;
            }

            // 创建 FBO
            int fbo = GL30.glGenFramebuffers();
            viewportFBOs[i] = fbo;

            // 创建颜色纹理
            int colorTex = GL11.glGenTextures();
            colorTextures[i] = colorTex;
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, colorTex);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGB, fboWidth, fboHeight, 0,
                            GL11.GL_RGB, GL11.GL_UNSIGNED_BYTE, (java.nio.ByteBuffer) null);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);

            // 创建深度纹理
            int depthTex = GL11.glGenTextures();
            depthTextures[i] = depthTex;
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, depthTex);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_DEPTH_COMPONENT, fboWidth, fboHeight, 0,
                            GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, (java.nio.ByteBuffer) null);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);

            // 绑定附件到 FBO
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
                                        GL11.GL_TEXTURE_2D, colorTex, 0);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT,
                                        GL11.GL_TEXTURE_2D, depthTex, 0);

            // 验证 FBO 完整性
            int status = GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER);
            if (status != GL30.GL_FRAMEBUFFER_COMPLETE) {
                LOG.error("视口 {} FBO 不完整，状态: 0x{}", i, Integer.toHexString(status));
            }

            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        }
    }

    /**
     * 获取指定视口的 FBO 句柄
     *
     * @param viewportId 视口编号（0-3）
     * @return FBO 句柄，如果视口无效则返回 0
     */
    public static int getViewportFBO(int viewportId) {
        if (viewportId < 0 || viewportId >= 4) {
            return 0;
        }
        return viewportFBOs[viewportId];
    }

    /**
     * 获取指定视口的颜色纹理句柄
     *
     * @param viewportId 视口编号（0-3）
     * @return 颜色纹理句柄，如果视口无效则返回 0
     */
    public static int getViewportColorTex(int viewportId) {
        if (viewportId < 0 || viewportId >= 4) {
            return 0;
        }
        return colorTextures[viewportId];
    }

    /**
     * 调整所有视口 FBO 大小
     *
     * 当屏幕尺寸变化时调用此方法。
     *
     * @param screenWidth 新屏幕宽度
     * @param screenHeight 新屏幕高度
     */
    public static void resizeViewportFBOs(int screenWidth, int screenHeight) {
        if (!initialized) {
            return;
        }

        LOG.debug("调整视口 FBO 大小: {}x{}", screenWidth, screenHeight);

        cachedScreenWidth = screenWidth;
        cachedScreenHeight = screenHeight;

        // 重新创建所有 FBO
        initViewportFBOs(screenWidth, screenHeight);
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // 视口模式与布局
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * 设置视口模式
     *
     * 切换视口布局（单视口、双视口、四视口）并重新初始化配置。
     *
     * @param mode 目标视口模式
     */
    public static void setViewportMode(ViewportMode mode) {
        if (!initialized) {
            LOG.warn("BRViewportManager 未初始化，无法设置视口模式");
            return;
        }

        LOG.info("切换视口模式: {} -> {}", currentMode, mode);

        currentMode = mode;
        activeViewportId = 0;

        // 重置所有配置
        for (int i = 0; i < 4; i++) {
            allConfigs[i] = null;
        }

        // 根据模式设置布局
        switch (mode) {
            case SINGLE:
                allConfigs[0] = new ViewportConfig(0, 0.0f, 0.0f, 1.0f, 1.0f, ProjectionType.PERSPECTIVE);
                break;

            case DUAL_HORIZONTAL:
                allConfigs[0] = new ViewportConfig(0, 0.0f, 0.0f, 0.5f, 1.0f, ProjectionType.PERSPECTIVE);
                allConfigs[1] = new ViewportConfig(1, 0.5f, 0.0f, 0.5f, 1.0f, ProjectionType.ORTHOGRAPHIC_TOP);
                break;

            case DUAL_VERTICAL:
                allConfigs[0] = new ViewportConfig(0, 0.0f, 0.0f, 1.0f, 0.5f, ProjectionType.PERSPECTIVE);
                allConfigs[1] = new ViewportConfig(1, 0.0f, 0.5f, 1.0f, 0.5f, ProjectionType.ORTHOGRAPHIC_TOP);
                break;

            case QUAD:
                allConfigs[0] = new ViewportConfig(0, 0.0f, 0.0f, 0.5f, 0.5f, ProjectionType.PERSPECTIVE);
                allConfigs[1] = new ViewportConfig(1, 0.5f, 0.0f, 0.5f, 0.5f, ProjectionType.ORTHOGRAPHIC_TOP);
                allConfigs[2] = new ViewportConfig(2, 0.0f, 0.5f, 0.5f, 0.5f, ProjectionType.ORTHOGRAPHIC_FRONT);
                allConfigs[3] = new ViewportConfig(3, 0.5f, 0.5f, 0.5f, 0.5f, ProjectionType.ORTHOGRAPHIC_RIGHT);
                break;

            default:
                LOG.warn("未知的视口模式: {}", mode);
                return;
        }

        // 重新创建 FBO
        if (cachedScreenWidth > 0 && cachedScreenHeight > 0) {
            initViewportFBOs(cachedScreenWidth, cachedScreenHeight);
        }
    }

    /**
     * 获取当前视口模式
     *
     * @return 当前视口模式
     */
    public static ViewportMode getViewportMode() {
        return currentMode;
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // 活跃视口追踪
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * 获取活跃视口编号
     *
     * 活跃视口接收鼠标和键盘输入。
     *
     * @return 活跃视口编号（0-3）
     */
    public static int getActiveViewportId() {
        return activeViewportId;
    }

    /**
     * 设置活跃视口
     *
     * 指定哪个视口将接收输入。
     *
     * @param id 视口编号（0-3）
     */
    public static void setActiveViewport(int id) {
        if (id >= 0 && id < 4) {
            activeViewportId = id;
            LOG.debug("设置活跃视口: {}", id);
        }
    }

    /**
     * 获取活跃视口的配置
     *
     * @return 活跃视口的配置，如果无效则返回 null
     */
    public static ViewportConfig getActiveViewportConfig() {
        return allConfigs[activeViewportId];
    }

    /**
     * 根据屏幕坐标查询视口
     *
     * 执行命中测试以确定鼠标指针位于哪个视口。
     *
     * @param mouseX 鼠标 X 坐标（像素）
     * @param mouseY 鼠标 Y 坐标（像素）
     * @param screenW 屏幕宽度
     * @param screenH 屏幕高度
     * @return 视口编号，如果未击中任何视口则返回 -1
     */
    public static int viewportAtScreenPosition(double mouseX, double mouseY, int screenW, int screenH) {
        // 将屏幕坐标转换为归一化坐标（0-1）
        float normX = (float) (mouseX / screenW);
        float normY = (float) (mouseY / screenH);

        // 检查每个活跃视口
        for (int i = 0; i < 4; i++) {
            ViewportConfig config = allConfigs[i];
            if (config == null || !config.enabled) {
                continue;
            }

            // 检查点是否在视口矩形内
            if (normX >= config.x && normX < config.x + config.width &&
                normY >= config.y && normY < config.y + config.height) {
                return i;
            }
        }

        return -1;
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // 相机访问与控制
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * 获取指定视口的相机实例
     *
     * @param viewportId 视口编号（0-3）
     * @return 相机实例，如果视口无效则返回 null
     */
    public static ViewportCamera getCamera(int viewportId) {
        if (viewportId < 0 || viewportId >= 4) {
            return null;
        }
        return cameras[viewportId];
    }

    /**
     * 获取活跃视口的相机
     *
     * @return 活跃视口的相机实例
     */
    public static ViewportCamera getActiveCamera() {
        return cameras[activeViewportId];
    }

    /**
     * 获取透视视口的相机（通常是视口 0）
     *
     * @return 透视视口的相机，如果不存在则返回 null
     */
    public static ViewportCamera getPerspectiveCamera() {
        for (int i = 0; i < 4; i++) {
            ViewportConfig config = allConfigs[i];
            if (config != null && config.projectionType == ProjectionType.PERSPECTIVE) {
                return cameras[i];
            }
        }
        return null;
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // 正交视图同步
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * 将正交视图同步到目标位置
     *
     * 所有正交视图的相机都会被定位为朝向指定的目标点。
     * 这使得多个正交视图始终显示同一区域的不同面。
     *
     * @param targetX 目标 X 坐标
     * @param targetY 目标 Y 坐标
     * @param targetZ 目标 Z 坐标
     */
    public static void syncOrthoViewsToTarget(double targetX, double targetY, double targetZ) {
        // 更新所有正交视口的相机位置
        for (int i = 0; i < 4; i++) {
            ViewportConfig config = allConfigs[i];
            if (config == null || !config.enabled) {
                continue;
            }

            ViewportCamera camera = cameras[i];

            if (config.projectionType == ProjectionType.ORTHOGRAPHIC_TOP) {
                // 顶视图：跟随 X 和 Z，保持 Y 高度
                camera.camX = targetX;
                camera.camZ = targetZ;
                camera.camY = 50.0;
            } else if (config.projectionType == ProjectionType.ORTHOGRAPHIC_FRONT) {
                // 前视图：跟随 X 和 Y，保持 Z 深度
                camera.camX = targetX;
                camera.camY = targetY;
                camera.camZ = 50.0;
            } else if (config.projectionType == ProjectionType.ORTHOGRAPHIC_RIGHT) {
                // 右视图：跟随 Y 和 Z，保持 X 宽度
                camera.camY = targetY;
                camera.camZ = targetZ;
                camera.camX = 50.0;
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // 视口查询
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * 获取当前模式下所有活跃的视口配置
     *
     * @return 活跃视口配置数组，仅包含已启用的视口
     */
    public static ViewportConfig[] getActiveViewports() {
        java.util.List<ViewportConfig> activeList = new java.util.ArrayList<>();

        for (int i = 0; i < 4; i++) {
            ViewportConfig config = allConfigs[i];
            if (config != null && config.enabled) {
                activeList.add(config);
            }
        }

        return activeList.toArray(new ViewportConfig[0]);
    }

    /**
     * 获取指定视口的配置
     *
     * @param viewportId 视口编号（0-3）
     * @return 视口配置，如果无效则返回 null
     */
    public static ViewportConfig getViewportConfig(int viewportId) {
        if (viewportId < 0 || viewportId >= 4) {
            return null;
        }
        return allConfigs[viewportId];
    }

    /**
     * 检查视口管理器是否已初始化
     *
     * @return 初始化状态
     */
    public static boolean isInitialized() {
        return initialized;
    }

    /**
     * 获取缓存的屏幕宽度
     *
     * @return 屏幕宽度（像素）
     */
    public static int getCachedScreenWidth() {
        return cachedScreenWidth;
    }

    /**
     * 获取缓存的屏幕高度
     *
     * @return 屏幕高度（像素）
     */
    public static int getCachedScreenHeight() {
        return cachedScreenHeight;
    }

    // ═══════════════════════════════════════════════════════════════════════════════
    // 通用生命周期
    // ═══════════════════════════════════════════════════════════════════════════════

    /**
     * 处理屏幕窗口大小变化事件
     *
     * @param width 新宽度
     * @param height 新高度
     */
    public static void onResize(int width, int height) {
        if (initialized && (width != cachedScreenWidth || height != cachedScreenHeight)) {
            LOG.info("屏幕尺寸变化事件: {}x{} -> {}x{}", cachedScreenWidth, cachedScreenHeight, width, height);
            resizeViewportFBOs(width, height);
        }
    }
}
