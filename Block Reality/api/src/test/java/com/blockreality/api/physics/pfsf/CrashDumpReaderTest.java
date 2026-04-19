package com.blockreality.api.physics.pfsf;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * v0.4 M3f — end-to-end crash-dump reader test.
 *
 * <p>Drives {@code pfsf_dump_now_for_test} to produce a real
 * {@code pfsf-crash-&lt;pid&gt;.trace} file, then invokes
 * {@code scripts/pfsf_crash_decode.py} as a subprocess and checks the
 * NDJSON output matches the seeded trace events. The binary wire format
 * is also covered by {@link PFSFCrashHandlerTest}; this test is the
 * cross-language half — if Python's {@code struct} unpack ever drifts
 * from the C struct layout, this fails first.</p>
 *
 * <p>Skipped gracefully when either (a) libpfsf wasn't built with
 * {@code crash.handler.test} or (b) no {@code python3} is on PATH.</p>
 */
class CrashDumpReaderTest {

    private static boolean nativeReady() {
        return NativePFSFBridge.isAvailable()
                && NativePFSFBridge.nativeHasFeature("crash.handler.test");
    }

    private static Path resolveDecoder() {
        // JUnit runs from the :api module dir; the script lives at
        // <repo-root>/scripts/pfsf_crash_decode.py.
        Path here = Paths.get("").toAbsolutePath();
        for (Path p = here; p != null; p = p.getParent()) {
            Path candidate = p.resolve("scripts").resolve("pfsf_crash_decode.py");
            if (Files.exists(candidate)) return candidate;
        }
        return null;
    }

    private static boolean pythonAvailable() {
        try {
            Process p = new ProcessBuilder("python3", "--version")
                    .redirectErrorStream(true).start();
            return p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    @Test
    @DisplayName("python decoder round-trips seeded trace events")
    void pythonDecoderRoundTrip(@TempDir Path tmp) throws Exception {
        assumeTrue(nativeReady(),
                "libblockreality_pfsf without crash.handler.test — skipping");
        Path decoder = resolveDecoder();
        assumeTrue(decoder != null, "scripts/pfsf_crash_decode.py not found");
        assumeTrue(pythonAvailable(), "python3 not on PATH");

        NativePFSFBridge.nativeTraceClear();
        NativePFSFBridge.nativeTraceSetLevel(NativePFSFBridge.TraceLevel.VERBOSE);
        final int seeded = 7;
        for (int i = 0; i < seeded; i++) {
            NativePFSFBridge.nativeTraceEmit(
                    (short) NativePFSFBridge.TraceLevel.INFO,
                    /*epoch*/   500L + i,
                    /*stage*/   i,  // distinct stage per event
                    /*island*/  7,
                    /*voxel*/   i * 13,
                    /*errno*/   0,
                    /*msg*/     "reader-test-" + i);
        }

        Path dump = tmp.resolve("crash-reader.trace");
        int events = NativePFSFBridge.nativeCrashDumpForTest(
                dump.toString(), 11 /*SIGSEGV*/, 0xbad0c0deL);
        assertTrue(events >= seeded,
                "dump reported " + events + " events, expected at least " + seeded);
        assertTrue(Files.exists(dump), "dump file not created");

        Path ndjson = tmp.resolve("crash-reader.ndjson");
        Process proc = new ProcessBuilder(
                "python3", decoder.toString(), dump.toString(),
                "--output", ndjson.toString())
                .redirectErrorStream(true)
                .start();
        boolean finished = proc.waitFor(30, TimeUnit.SECONDS);
        byte[] stderr = proc.getInputStream().readAllBytes();
        assertTrue(finished, "decoder did not exit within 30s; stderr=" +
                new String(stderr, StandardCharsets.UTF_8));
        assertEquals(0, proc.exitValue(),
                "decoder exited non-zero; output=" +
                        new String(stderr, StandardCharsets.UTF_8));

        List<String> lines = Files.readAllLines(ndjson, StandardCharsets.UTF_8);
        // Expect 1 header line + `events` event lines.
        assertEquals(1 + events, lines.size(),
                "line count " + lines.size() + " != 1 header + " + events + " events");

        String header = lines.get(0);
        assertTrue(header.contains("\"signo\": 11"),
                "header missing signo=11: " + header);
        assertTrue(header.contains("\"events\": " + events),
                "header events count mismatch: " + header);
        assertTrue(header.contains("\"addr\": " + 0xbad0c0deL),
                "header addr mismatch: " + header);

        // Every event line must contain the island id we seeded, and at least
        // one of the reader-test-N messages must be present (FIFO drop-oldest
        // means the ring is at most 100 deep, our seed of 7 always survives).
        boolean sawReaderTest = false;
        for (int i = 1; i < lines.size(); i++) {
            String ln = lines.get(i);
            assertTrue(ln.startsWith("{") && ln.endsWith("}"),
                    "event line is not a JSON object: " + ln);
            if (ln.contains("\"msg\": \"reader-test-")) sawReaderTest = true;
        }
        assertTrue(sawReaderTest,
                "none of the " + events + " events contain a reader-test-* msg");
    }

    @Test
    @DisplayName("python decoder --header-only emits single JSON line")
    void headerOnlyMode(@TempDir Path tmp) throws Exception {
        assumeTrue(nativeReady(),
                "libblockreality_pfsf without crash.handler.test — skipping");
        Path decoder = resolveDecoder();
        assumeTrue(decoder != null, "scripts/pfsf_crash_decode.py not found");
        assumeTrue(pythonAvailable(), "python3 not on PATH");

        NativePFSFBridge.nativeTraceClear();
        Path dump = tmp.resolve("empty.trace");
        NativePFSFBridge.nativeCrashDumpForTest(dump.toString(), 6 /*SIGABRT*/, 0L);

        Process proc = new ProcessBuilder(
                "python3", decoder.toString(), dump.toString(), "--header-only")
                .redirectErrorStream(true)
                .start();
        boolean finished = proc.waitFor(10, TimeUnit.SECONDS);
        byte[] out = proc.getInputStream().readAllBytes();
        assertTrue(finished, "decoder did not exit within 10s");
        assertEquals(0, proc.exitValue(),
                "decoder exited non-zero; output=" +
                        new String(out, StandardCharsets.UTF_8));

        String[] lines = new String(out, StandardCharsets.UTF_8).split("\n");
        // One content line + possible trailing empty line from split.
        assertTrue(lines.length >= 1 && !lines[0].isEmpty(),
                "expected at least one content line, got: " +
                        new String(out, StandardCharsets.UTF_8));
        assertTrue(lines[0].contains("\"signo\": 6"),
                "header-only output missing signo=6: " + lines[0]);
        assertTrue(lines[0].contains("\"events\": 0"),
                "header-only output missing events=0: " + lines[0]);
    }
}
