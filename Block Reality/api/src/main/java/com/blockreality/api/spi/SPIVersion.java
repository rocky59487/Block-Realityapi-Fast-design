package com.blockreality.api.spi;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * ★ Audit fix (API 設計師): SPI 介面版本標記。
 *
 * <p>標記 SPI 介面的合約版本。{@link ModuleRegistry} 在註冊實作時
 * 驗證版本相容性，防止舊版實作在新版介面上產生 AbstractMethodError。
 *
 * <h3>版本規則</h3>
 * <ul>
 *   <li>新增方法（帶 default 實作）：minor version bump (1.0 → 1.1)</li>
 *   <li>移除/修改既有方法簽名：major version bump (1.x → 2.0)</li>
 *   <li>實作者標記的版本必須 ≥ 介面的 major version</li>
 * </ul>
 *
 * <h3>使用範例</h3>
 * <pre>{@code
 * @SPIVersion(major = 1, minor = 1)
 * public interface ICableManager {
 *     void updateTension(BlockPos pos, float tension);
 *
 *     @since 1.1
 *     default float getMaxTension() { return 1000.0f; }
 * }
 * }</pre>
 *
 * @since 1.1.0
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface SPIVersion {

    /**
     * 主版本號 — 破壞性變更時遞增。
     * 實作者的 major 必須匹配介面的 major。
     */
    int major() default 1;

    /**
     * 次版本號 — 新增 default 方法時遞增。
     * 實作者的 minor 可以低於介面的 minor（因為 default 方法提供向後相容）。
     */
    int minor() default 0;
}
