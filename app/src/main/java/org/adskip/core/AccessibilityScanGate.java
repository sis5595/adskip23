package org.adskip.core;

/** Research-only provenance check before a selected-node snapshot enters the resolver. */
public final class AccessibilityScanGate {
    public enum Result { EVENT_ANCHORED, PASSIVE, NO_EVENT, STALE_EVENT }

    private AccessibilityScanGate() {}

    public static Result assess(boolean activeScan, long eventAtElapsedMs,
            long scanAtElapsedMs, long maxEventAgeMs) {
        if (!activeScan) return Result.PASSIVE;
        if (eventAtElapsedMs <= 0L) return Result.NO_EVENT;
        if (scanAtElapsedMs < eventAtElapsedMs || maxEventAgeMs < 0L
                || scanAtElapsedMs - eventAtElapsedMs > maxEventAgeMs) {
            return Result.STALE_EVENT;
        }
        return Result.EVENT_ANCHORED;
    }
}
