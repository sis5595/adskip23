package org.adskip.core;

/** Recent scheduling evidence for the UI, never playback authorization or an OEM diagnosis. */
public final class EntryTimerHealth {
    public static final long WARNING_DELAY_MS = 5_000L;
    public static final long EVIDENCE_WINDOW_MS = 10 * 60_000L;
    private long delayedAtMs = -1L;
    private long delayMs;

    public void record(long expectedAtMs, long observedAtMs) {
        if (expectedAtMs < 0L || observedAtMs < expectedAtMs) return;
        long delay = observedAtMs - expectedAtMs;
        if (delay < WARNING_DELAY_MS) return;
        delayedAtMs = observedAtMs;
        delayMs = delay;
    }

    public long recentDelayMs(long nowMs) {
        return delayedAtMs >= 0L && nowMs >= delayedAtMs
                && nowMs - delayedAtMs < EVIDENCE_WINDOW_MS ? delayMs : 0L;
    }
}
