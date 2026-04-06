package com.blockreality.fastdesign.startup;

import java.util.List;

/**
 * Startup scan data model — phase status and result records.
 */
public final class StartupPhase {

    private StartupPhase() {}

    /** Phase completion status. */
    public enum PhaseStatus {
        OK, WARN, ERROR
    }

    /** Immutable result of a single scan phase. */
    public record PhaseResult(
        String name,
        PhaseStatus status,
        long durationMs,
        List<String> details
    ) {}

    /** Environment info captured during Phase 1. */
    public record EnvironmentInfo(
        String jvmVersion,
        long heapMb,
        String os,
        String gpu,
        String forgeVersion
    ) {}
}
