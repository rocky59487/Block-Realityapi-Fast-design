/**
 * @file crash.cpp
 * @brief v0.3e M5 — async-signal-safe SIGSEGV/SIGABRT/SIGFPE/SIGBUS dump.
 *
 * Strategy:
 *   - sigaction with SA_SIGINFO + SA_RESTART; previous handlers saved
 *     and chained on exit so the JVM's hs_err_pid.log still produces.
 *   - Inside the handler we call only async-signal-safe primitives
 *     (signal-safety(7)): open(2), write(2), close(2), _exit, getpid,
 *     raise. No malloc, no printf, no mutex acquisition.
 *   - The trace ring is read via pfsf_internal_trace_peek_unsafe which
 *     does relaxed reads without touching the ring's mutex — tearing
 *     is acceptable inside a signal handler since the alternative is
 *     deadlock if the faulting thread already holds the lock.
 *   - Path is composed by hand-formatting the PID into a fixed buffer.
 *
 * On Windows the install/uninstall entries are no-ops; pfsf_dump_now_for_test
 * still works cross-platform so unit tests run on every CI matrix slot.
 */

#include "pfsf/pfsf_trace.h"
#include "pfsf/pfsf_types.h"

#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>

#if defined(_WIN32)
#  include <process.h>  /* _getpid */
#else
#  include <fcntl.h>
#  include <signal.h>
#  include <sys/types.h>
#  include <unistd.h>
#endif

extern "C" int32_t pfsf_internal_trace_peek_unsafe(pfsf_trace_event* out, int32_t cap);

namespace {

#if !defined(_WIN32)
constexpr int kSignals[] = { SIGSEGV, SIGABRT, SIGFPE, SIGBUS };
constexpr int kNumSignals = static_cast<int>(sizeof(kSignals) / sizeof(kSignals[0]));

struct sigaction g_old_actions[kNumSignals];
volatile sig_atomic_t g_installed = 0;
#endif

/* AS-safe decimal formatter. Returns digits written. */
int as_safe_itoa(int64_t v, char* buf) {
    char tmp[24];
    int  i = 0;
    bool neg = (v < 0);
    uint64_t u = neg ? static_cast<uint64_t>(-(v + 1)) + 1u : static_cast<uint64_t>(v);
    if (u == 0) tmp[i++] = '0';
    else while (u > 0) { tmp[i++] = static_cast<char>('0' + (u % 10)); u /= 10; }
    if (neg) tmp[i++] = '-';
    int len = i;
    for (int j = 0; j < i; ++j) buf[j] = tmp[i - 1 - j];
    return len;
}

/* AS-safe lowercase hex formatter (no 0x prefix). Returns digits written. */
int as_safe_xtoa(uintptr_t v, char* buf) {
    if (v == 0) { buf[0] = '0'; return 1; }
    char tmp[24];
    int  i = 0;
    while (v > 0) {
        unsigned d = static_cast<unsigned>(v & 0xFu);
        tmp[i++] = static_cast<char>(d < 10 ? '0' + d : 'a' + (d - 10));
        v >>= 4;
    }
    for (int j = 0; j < i; ++j) buf[j] = tmp[i - 1 - j];
    return i;
}

inline size_t cstrlen(const char* s) {
    size_t n = 0;
    while (s[n] != '\0') ++n;
    return n;
}

#if !defined(_WIN32)
/* AS-safe write helper — silently drops short writes; we are crashing. */
inline void as_safe_writes(int fd, const char* s) {
    ssize_t r = ::write(fd, s, cstrlen(s));
    (void) r;
}
#endif

/**
 * Shared writer used by both the live handler and pfsf_dump_now_for_test.
 * Format:
 *   ASCII header line:
 *     "PFSF-CRASH signo=<dec> pid=<dec> addr=0x<hex> events=<dec>\n"
 *   Followed by `events` raw 64-byte pfsf_trace_event records.
 *
 * Returns the number of trace events written, or a negative pfsf_result.
 * Path semantics:
 *   - When `explicit_path` is non-null, write there.
 *   - Otherwise compose `pfsf-crash-<pid>.trace` in the cwd.
 */
int32_t do_dump(int signo, uintptr_t fault_addr, const char* explicit_path) {
#if defined(_WIN32)
    /* Windows path uses C stdio rather than open(2)/write(2). It is only
     * reached from pfsf_dump_now_for_test on Windows — the live crash
     * handler is a no-op there (Windows uses SetUnhandledExceptionFilter
     * with MiniDumpWriteDump in a future milestone). The byte format
     * matches the POSIX writer exactly. */
    FILE* f = nullptr;
    char buf[512];
    if (explicit_path != nullptr) {
        f = std::fopen(explicit_path, "wb");
    } else {
        std::snprintf(buf, sizeof(buf), "pfsf-crash-%d.trace",
                      static_cast<int>(::_getpid()));
        f = std::fopen(buf, "wb");
    }
    if (!f) return PFSF_ERROR_VULKAN;
    pfsf_trace_event events[PFSF_CRASH_MAX_EVENTS];
    int32_t got = pfsf_internal_trace_peek_unsafe(events, PFSF_CRASH_MAX_EVENTS);
    int n = std::snprintf(buf, sizeof(buf),
                          "PFSF-CRASH signo=%d pid=%d addr=0x%llx events=%d\n",
                          signo, static_cast<int>(::_getpid()),
                          static_cast<unsigned long long>(fault_addr),
                          static_cast<int>(got));
    std::fwrite(buf, 1, static_cast<size_t>(n), f);
    if (got > 0) std::fwrite(events, sizeof(pfsf_trace_event),
                             static_cast<size_t>(got), f);
    std::fclose(f);
    return got;
#else
    /* Compose path. */
    char path_buf[64];
    const char* path = explicit_path;
    if (path == nullptr) {
        const char* pre = "pfsf-crash-";
        int p = 0;
        while (*pre) path_buf[p++] = *pre++;
        p += as_safe_itoa(static_cast<int64_t>(::getpid()), path_buf + p);
        const char* suf = ".trace";
        while (*suf) path_buf[p++] = *suf++;
        path_buf[p] = '\0';
        path = path_buf;
    }

    int fd = ::open(path, O_CREAT | O_WRONLY | O_TRUNC, 0644);
    if (fd < 0) return PFSF_ERROR_VULKAN;

    /* Snapshot events first so the events= field in the header is accurate. */
    pfsf_trace_event events[PFSF_CRASH_MAX_EVENTS];
    int32_t got = pfsf_internal_trace_peek_unsafe(events, PFSF_CRASH_MAX_EVENTS);

    char nbuf[24];
    as_safe_writes(fd, "PFSF-CRASH signo=");
    {
        int n = as_safe_itoa(signo, nbuf);
        ssize_t r = ::write(fd, nbuf, static_cast<size_t>(n)); (void) r;
    }
    as_safe_writes(fd, " pid=");
    {
        int n = as_safe_itoa(static_cast<int64_t>(::getpid()), nbuf);
        ssize_t r = ::write(fd, nbuf, static_cast<size_t>(n)); (void) r;
    }
    as_safe_writes(fd, " addr=0x");
    {
        int n = as_safe_xtoa(fault_addr, nbuf);
        ssize_t r = ::write(fd, nbuf, static_cast<size_t>(n)); (void) r;
    }
    as_safe_writes(fd, " events=");
    {
        int n = as_safe_itoa(got, nbuf);
        ssize_t r = ::write(fd, nbuf, static_cast<size_t>(n)); (void) r;
    }
    as_safe_writes(fd, "\n");

    if (got > 0) {
        const size_t bytes = sizeof(pfsf_trace_event) * static_cast<size_t>(got);
        ssize_t r = ::write(fd, events, bytes); (void) r;
    }

    ::close(fd);
    return got;
#endif
}

#if !defined(_WIN32)
void crash_handler(int signo, siginfo_t* info, void* uctx) {
    uintptr_t addr = info ? reinterpret_cast<uintptr_t>(info->si_addr) : 0;
    do_dump(signo, addr, /*explicit_path=*/nullptr);

    /* Chain to the previous handler so JVM hs_err / ASan still produce. */
    int idx = -1;
    for (int i = 0; i < kNumSignals; ++i) if (kSignals[i] == signo) { idx = i; break; }
    if (idx >= 0) {
        struct sigaction& old = g_old_actions[idx];
        if (old.sa_flags & SA_SIGINFO) {
            if (old.sa_sigaction != nullptr) old.sa_sigaction(signo, info, uctx);
            return;
        }
        if (old.sa_handler == SIG_DFL) {
            /* Restore default disposition and re-raise so the kernel
             * terminates us with the correct exit code (and core dumps). */
            struct sigaction dfl{};
            dfl.sa_handler = SIG_DFL;
            sigemptyset(&dfl.sa_mask);
            sigaction(signo, &dfl, nullptr);
            raise(signo);
            return;
        }
        if (old.sa_handler != SIG_IGN && old.sa_handler != nullptr) {
            old.sa_handler(signo);
        }
    }
}
#endif

} /* namespace */

extern "C" pfsf_result pfsf_install_crash_handler(void) {
#if defined(_WIN32)
    return PFSF_OK;
#else
    if (g_installed) return PFSF_OK;
    if (const char* skip = ::getenv("BR_PFSF_NO_SIGNAL")) {
        if (skip[0] != '\0' && skip[0] != '0') return PFSF_OK;
    }
    struct sigaction sa{};
    sa.sa_sigaction = crash_handler;
    sa.sa_flags     = SA_SIGINFO | SA_RESTART;
    sigemptyset(&sa.sa_mask);
    for (int i = 0; i < kNumSignals; ++i) {
        if (sigaction(kSignals[i], &sa, &g_old_actions[i]) != 0) {
            /* Roll back the partial install so we leave no dangling
             * handlers if the process re-installs later. */
            for (int j = 0; j < i; ++j) sigaction(kSignals[j], &g_old_actions[j], nullptr);
            return PFSF_ERROR_VULKAN;
        }
    }
    g_installed = 1;
    return PFSF_OK;
#endif
}

extern "C" void pfsf_uninstall_crash_handler(void) {
#if !defined(_WIN32)
    if (!g_installed) return;
    for (int i = 0; i < kNumSignals; ++i) {
        sigaction(kSignals[i], &g_old_actions[i], nullptr);
    }
    g_installed = 0;
#endif
}

extern "C" int32_t pfsf_dump_now_for_test(const char* path,
                                            int32_t      signo,
                                            uintptr_t    fault_addr) {
    if (path == nullptr) return PFSF_ERROR_INVALID_ARG;
    return do_dump(static_cast<int>(signo), fault_addr, path);
}
