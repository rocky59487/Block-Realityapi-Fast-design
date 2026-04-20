package com.blockreality.api.physics.pfsf;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * v0.3e M5 — async-signal-safe crash dump round-trip.
 *
 * <p>The live signal handler can only be exercised by sending a real signal
 * to a forked process, which is awkward inside a JUnit suite. Instead the
 * tests below drive {@code pfsf_dump_now_for_test}, which runs the same
 * formatter as the live handler against a caller-supplied path. The test
 * is feature-gated on {@code crash.handler.test} so it skips cleanly on
 * runners that built libpfsf without the diag module.</p>
 *
 * <p>The byte format under verification is:</p>
 * <pre>
 *   ASCII line: "PFSF-CRASH signo=&lt;dec&gt; pid=&lt;dec&gt; addr=0x&lt;hex&gt; events=&lt;dec&gt;\n"
 *   Followed by &lt;events&gt; raw 64-byte pfsf_trace_event records.
 * </pre>
 */
class PFSFCrashHandlerTest {

    private static final int PFSF_TRACE_EVENT_BYTES = 64;

    private static boolean nativeReady() {
        return NativePFSFBridge.isAvailable()
                && NativePFSFBridge.nativeHasFeature("crash.handler.test");
    }

    @Test
    @DisplayName("dump_now_for_test rejects null path")
    void rejectsNullPath() {
        assumeTrue(nativeReady(), "libblockreality_pfsf without crash.handler.test — skipping");
        int rc = NativePFSFBridge.nativeCrashDumpForTest(null, 11, 0xdeadbeefL);
        assertEquals(NativePFSFBridge.PFSFResult.ERROR_INVALID_ARG, rc,
                "null path must be rejected with INVALID_ARG, got " + rc);
    }

    @Test
    @DisplayName("dump file header records signo / pid / addr / event count")
    void headerRoundTrip(@TempDir Path tmp) throws Exception {
        assumeTrue(nativeReady(), "libblockreality_pfsf without crash.handler.test — skipping");

        // Seed the trace ring with a few deterministic events so the dump
        // has something to write. Each emit goes via the live ring buffer
        // — the same one the handler peeks under SIGSEGV.
        NativePFSFBridge.nativeTraceClear();
        NativePFSFBridge.nativeTraceSetLevel(NativePFSFBridge.TraceLevel.VERBOSE);
        for (int i = 0; i < 5; i++) {
            NativePFSFBridge.nativeTraceEmit(
                    (short) NativePFSFBridge.TraceLevel.INFO,
                    /*epoch*/   100L + i,
                    /*stage*/   1,
                    /*island*/  42,
                    /*voxel*/   i,
                    /*errno*/   0,
                    /*msg*/     "crash-test-" + i);
        }

        Path dump = tmp.resolve("pfsf-crash-test.trace");
        int events = NativePFSFBridge.nativeCrashDumpForTest(
                dump.toString(), 11 /*SIGSEGV*/, 0xcafebabeL);

        assertTrue(events >= 0, "dump_now_for_test returned error code " + events);
        assertTrue(Files.exists(dump), "dump file not created at " + dump);

        byte[] raw = Files.readAllBytes(dump);
        assertTrue(raw.length > 0, "dump file is empty");

        // Locate the LF that terminates the ASCII header.
        int lf = -1;
        for (int i = 0; i < raw.length; i++) {
            if (raw[i] == (byte) '\n') { lf = i; break; }
        }
        assertNotEquals(-1, lf, "header LF terminator not found");

        String header = new String(raw, 0, lf, java.nio.charset.StandardCharsets.US_ASCII);
        assertTrue(header.startsWith("PFSF-CRASH "), "unexpected header prefix: " + header);

        Map<String, String> kv = parseHeader(header);
        assertEquals("11",         kv.get("signo"),  "signo field mismatch in: " + header);
        assertEquals("0xcafebabe", kv.get("addr"),   "addr field mismatch in: " + header);
        assertEquals(String.valueOf(events), kv.get("events"),
                "events field mismatch in: " + header);
        assertNotNull(kv.get("pid"), "pid field absent");

        // Body must be exactly events * sizeof(pfsf_trace_event).
        int bodyBytes = raw.length - (lf + 1);
        assertEquals(events * PFSF_TRACE_EVENT_BYTES, bodyBytes,
                "body length " + bodyBytes + " != events(" + events + ") * 64");

        // First event's epoch (offset 0, int64 LE) should be one of those we
        // emitted — the ring is FIFO drop-oldest so the head matches our seed.
        if (events > 0) {
            ByteBuffer body = ByteBuffer.wrap(raw, lf + 1, PFSF_TRACE_EVENT_BYTES)
                    .order(ByteOrder.LITTLE_ENDIAN);
            long firstEpoch = body.getLong();
            assertTrue(firstEpoch >= 100L && firstEpoch <= 104L,
                    "first event epoch " + firstEpoch + " outside seeded range [100,104]");
        }
    }

    @Test
    @DisplayName("dump tolerates an empty trace ring")
    void emptyRingDumps(@TempDir Path tmp) throws Exception {
        assumeTrue(nativeReady(), "libblockreality_pfsf without crash.handler.test — skipping");

        NativePFSFBridge.nativeTraceClear();
        Path dump = tmp.resolve("empty-ring.trace");

        int events = NativePFSFBridge.nativeCrashDumpForTest(dump.toString(), 6 /*SIGABRT*/, 0L);
        assertEquals(0, events, "empty ring should report zero events, got " + events);
        assertTrue(Files.exists(dump), "header should still be written even with zero events");

        byte[] raw = Files.readAllBytes(dump);
        String header = new String(raw, java.nio.charset.StandardCharsets.US_ASCII);
        assertTrue(header.startsWith("PFSF-CRASH signo=6 "),
                "unexpected header for empty-ring dump: " + header);
        assertTrue(header.contains(" addr=0x0 events=0\n"),
                "addr/events mismatch in: " + header);
        assertEquals(header.indexOf('\n') + 1, raw.length,
                "body should be empty when events=0");
    }

    private static Map<String, String> parseHeader(String header) {
        Map<String, String> kv = new HashMap<>();
        for (String tok : header.substring("PFSF-CRASH ".length()).split(" ")) {
            int eq = tok.indexOf('=');
            if (eq > 0) kv.put(tok.substring(0, eq), tok.substring(eq + 1));
        }
        return kv;
    }
}
