package com.blockreality.api.util;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shared JAR-in-JAR native-library loader used by
 * {@code NativePFSFBridge}, {@code NativeFluidBridge} and
 * {@code NativeRenderBridge}.
 *
 * <p>Each native .so/.dll/.dylib is bundled under
 * {@code META-INF/native/<triple>/} (see {@link LibraryTriple}). The JVM's
 * built-in {@link System#loadLibrary(String)} only searches
 * {@code java.library.path} — on Windows/macOS where the runtime jar is
 * the only source of the binaries, it returns {@link UnsatisfiedLinkError}.
 * This loader extracts the resource to a per-jar-version tmpdir and then
 * calls {@link System#load(String)} on the extracted file, falling back
 * to {@link System#loadLibrary(String)} only when the jar does not bundle
 * a binary for the current triple (developer local cmake builds).
 *
 * <p>Two invariants that matter and are easy to get wrong:
 * <ul>
 *   <li><b>Shared {@code libbr_core}</b> — all three Java bridges link
 *       against the same {@code libbr_core} singleton.
 *       {@link System#load(String)} is keyed by absolute path, so loading
 *       the same library from two different paths instantiates the
 *       singleton twice, producing two {@code br_core::Core} objects
 *       sharing no state. The loader de-duplicates via a static
 *       {@code LOADED} set: once a base name is loaded by any bridge,
 *       subsequent bridges skip it.</li>
 *   <li><b>Concurrent JVMs</b> — two JVMs launched from the same jar
 *       write to the same digest-keyed tmpdir. A naive copy-direct lets
 *       JVM B see the target mid-write and {@code System.load} a
 *       truncated binary. All writes go to a per-PID staging file,
 *       then atomic-move into place.</li>
 * </ul>
 *
 * <p>The digest dir naming is
 * {@code <tmpdir>/blockreality-native-<triple>-<digest>/}, keyed on the
 * SHA-256 of every known native library in the jar. A jar upgrade
 * changes the digest and forces re-extraction; stale tmpdirs from older
 * jar versions linger until the OS tmpdir cleaner runs, which is the
 * cost of not poking at other processes' files.
 */
public final class NativeLibLoader {

    private static final Logger LOGGER = LoggerFactory.getLogger("BR-NativeLoader");

    /**
     * Every native library the jar may bundle, in no particular order.
     * Used to compute the digest that names the shared extraction dir,
     * so all three bridges resolve to the same dir regardless of which
     * loads first. Adding a new library here is harmless — absent
     * entries don't contribute to the digest.
     */
    private static final List<String> ALL_KNOWN = List.of(
            "br_core",
            "pfsf", "blockreality_pfsf",
            "fluid", "blockreality_fluid",
            "render", "blockreality_render");

    /** Set of base names already passed to {@code System.load*}. */
    private static final ConcurrentHashMap<String, Boolean> LOADED = new ConcurrentHashMap<>();

    /** Memoised per-JVM extraction dir (null when creation failed). */
    private static volatile Path sharedDir;
    private static volatile boolean sharedDirResolved = false;

    private NativeLibLoader() {}

    /**
     * Load the given libraries in order, extracting each from the jar
     * when bundled and falling back to {@link System#loadLibrary(String)}
     * when absent.
     *
     * <p>Each base name is loaded at most once per JVM, so the usual
     * {@code br_core + <impl>} dependency chain loaded by every bridge
     * results in a single {@code br_core} System.load.
     *
     * @param baseNames dependency-ordered list, e.g.
     *                  {@code ["br_core", "pfsf", "blockreality_pfsf"]}
     * @throws UnsatisfiedLinkError when the jar bundles some libraries
     *         but is missing one on the requested list (targeted error
     *         so operators know which .so/.dll/.dylib to rebuild)
     */
    public static synchronized void loadInOrder(List<String> baseNames) {
        Path dir = ensureSharedDir();
        boolean anyExtractedThisCall = false;

        for (String baseName : baseNames) {
            if (LOADED.containsKey(baseName)) {
                // Already loaded by a prior bridge — skip so the shared
                // libbr_core (and any other transitively-shared lib)
                // does not get System.load'd twice from different paths.
                anyExtractedThisCall = true;
                continue;
            }

            String resourcePath = LibraryTriple.resourcePath(baseName);
            URL    resource     = NativeLibLoader.class.getClassLoader().getResource(resourcePath);

            if (resource != null && dir != null) {
                try {
                    Path target = extractAtomic(dir, baseName, resource);
                    System.load(target.toAbsolutePath().toString());
                    LOADED.put(baseName, Boolean.TRUE);
                    anyExtractedThisCall = true;
                    continue;
                } catch (IOException | UnsatisfiedLinkError e) {
                    LOGGER.warn("NativeLibLoader: extract-and-load failed for {} at {}: {}",
                            baseName, resourcePath, e.toString());
                    // fall through to System.loadLibrary
                }
            }

            // No resource in the jar, or extraction failed. Only valid
            // on the first library of the chain — subsequent libs need
            // co-located copies that System.loadLibrary can't satisfy
            // ($ORIGIN rpath points at the extraction dir, not the OS
            // search path).
            if (!anyExtractedThisCall) {
                System.loadLibrary(baseName);
                LOADED.put(baseName, Boolean.TRUE);
            } else {
                throw new UnsatisfiedLinkError(
                        "missing native artefact " + resourcePath + " in jar");
            }
        }
    }

    /**
     * @return whether the given base name was passed to
     *         {@link System#load(String)} or {@link System#loadLibrary(String)}
     *         in this JVM process.
     */
    public static boolean isLoaded(String baseName) {
        return LOADED.containsKey(baseName);
    }

    // ─── Internal ──────────────────────────────────────────────────────────

    private static Path ensureSharedDir() {
        if (sharedDirResolved) return sharedDir;
        synchronized (NativeLibLoader.class) {
            if (sharedDirResolved) return sharedDir;
            Path dir = null;
            try {
                String triple = LibraryTriple.current();
                String digest = computeJarDigest();
                Path baseTmp  = Paths.get(System.getProperty("java.io.tmpdir"));
                dir = baseTmp.resolve("blockreality-native-" + triple + "-" + digest);
                Files.createDirectories(dir);
            } catch (IOException e) {
                LOGGER.warn("NativeLibLoader: failed to create extraction dir ({}); "
                        + "falling back to java.library.path", e.toString());
                dir = null;
            }
            sharedDir = dir;
            sharedDirResolved = true;
            return dir;
        }
    }

    /**
     * Copy the resource to {@code <dir>/<libFile>.<pid>.tmp}, then
     * {@link StandardCopyOption#ATOMIC_MOVE} it into place. A peer JVM
     * that wins the rename race leaves us observing
     * {@link FileAlreadyExistsException} — we drop the staged copy and
     * use theirs.
     */
    private static Path extractAtomic(Path dir, String baseName, URL resource) throws IOException {
        Path target  = dir.resolve(LibraryTriple.libraryFileName(baseName));
        if (Files.exists(target)) return target;

        Path staging = dir.resolve(
                LibraryTriple.libraryFileName(baseName)
                        + "." + ProcessHandle.current().pid()
                        + ".tmp");
        try (InputStream in = resource.openStream()) {
            Files.copy(in, staging, StandardCopyOption.REPLACE_EXISTING);
        }
        try {
            Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (FileAlreadyExistsException raced) {
            Files.deleteIfExists(staging);
        } catch (IOException atomicFail) {
            // Filesystem without ATOMIC_MOVE semantics.
            Files.move(staging, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return target;
    }

    /**
     * SHA-256 prefix across every bundled binary for the current triple.
     * Returns {@code "nobin"} when none are bundled (developer build).
     */
    private static String computeJarDigest() {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            boolean any = false;
            for (String baseName : ALL_KNOWN) {
                URL u = NativeLibLoader.class.getClassLoader()
                        .getResource(LibraryTriple.resourcePath(baseName));
                if (u == null) continue;
                try (InputStream in = u.openStream();
                     OutputStream sink = new OutputStream() {
                         @Override public void write(int b) { sha.update((byte) b); }
                         @Override public void write(byte[] buf, int off, int len) {
                             sha.update(buf, off, len);
                         }
                     }) {
                    in.transferTo(sink);
                    any = true;
                }
            }
            if (!any) return "nobin";
            byte[] d = sha.digest();
            StringBuilder sb = new StringBuilder(16);
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", d[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            return "nodigest";
        }
    }
}
