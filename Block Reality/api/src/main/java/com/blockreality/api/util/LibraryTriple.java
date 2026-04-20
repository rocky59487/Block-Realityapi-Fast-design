package com.blockreality.api.util;

import java.util.Locale;

/**
 * Detects the native triple for the running JVM and maps it to the
 * {@code META-INF/native/<triple>/} folder produced by the multi-platform
 * CI matrix.
 *
 * <p>The triple string is the single source of truth that Gradle
 * ({@code build.gradle}'s {@code currentNativeTriple} closure), the
 * jar layout ({@code META-INF/native/<triple>/}), the per-triple ABI
 * file ({@code pfsf_v1.<triple>.abi.json}) and {@link NativePFSFBridge}
 * extraction all agree on.</p>
 *
 * <p>Valid values (v0.4):
 * <ul>
 *   <li>{@code linux-x64}   — P0, the reference runner</li>
 *   <li>{@code win-x64}     — P0, added in v0.4 M1a</li>
 *   <li>{@code mac-arm64}   — P0, Apple Silicon, added in v0.4 M1a</li>
 *   <li>{@code linux-arm64} — P1, reserved for v0.4.1 (server ops)</li>
 *   <li>{@code win-arm64}   — P2</li>
 *   <li>{@code mac-x64}     — P2</li>
 * </ul>
 * </p>
 */
public final class LibraryTriple {

    private LibraryTriple() {}

    /**
     * Return the triple for the current JVM (never {@code null}).
     *
     * <p>Matches the ids emitted by {@code build.gradle}'s triple
     * detector verbatim so the jar layout and runtime loader resolve
     * the same folder.</p>
     */
    public static String current() {
        String os   = System.getProperty("os.name",  "").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch",  "").toLowerCase(Locale.ROOT);
        boolean arm64 = arch.contains("aarch64") || arch.contains("arm64");

        if (os.contains("win"))  return arm64 ? "win-arm64"   : "win-x64";
        if (os.contains("mac"))  return arm64 ? "mac-arm64"   : "mac-x64";
        return arm64 ? "linux-arm64" : "linux-x64";
    }

    /**
     * The OS-native file prefix + extension for a given library name on
     * the current platform. E.g. {@code libraryFileName("blockreality_pfsf")}
     * returns {@code "libblockreality_pfsf.so"} on Linux,
     * {@code "blockreality_pfsf.dll"} on Windows, and
     * {@code "libblockreality_pfsf.dylib"} on macOS.
     *
     * <p>Does <b>not</b> perform a lookup — the caller composes
     * {@code "META-INF/native/" + current() + "/" + libraryFileName(name)}
     * to find the resource on the classpath.</p>
     */
    public static String libraryFileName(String baseName) {
        if (baseName == null || baseName.isEmpty()) {
            throw new IllegalArgumentException("baseName must be non-empty");
        }
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) return baseName + ".dll";
        if (os.contains("mac")) return "lib" + baseName + ".dylib";
        return "lib" + baseName + ".so";
    }

    /**
     * The in-jar resource path for the given native library on the
     * current platform. Matches what {@code :api:jar} stages via
     * {@code from configurations.lwjglBootstrap ... include 'META-INF/native/**'}.
     */
    public static String resourcePath(String baseName) {
        return "META-INF/native/" + current() + "/" + libraryFileName(baseName);
    }
}
