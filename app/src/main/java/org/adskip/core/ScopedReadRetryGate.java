package org.adskip.core;

import java.util.Objects;

/** Monotonic retry backoff that never carries a previous video/session failure into a new scope. */
public final class ScopedReadRetryGate {
    private Object scope;
    private long nextAttemptAtMs;
    private int failures;

    /** Returns true when an old scope (and its scheduled retry) became irrelevant. */
    public boolean selectScope(Object candidate) {
        Objects.requireNonNull(candidate, "candidate");
        if (candidate.equals(scope)) return false;
        scope = candidate;
        nextAttemptAtMs = 0L;
        failures = 0;
        return true;
    }

    public boolean canAttempt(long nowElapsedMs) {
        return scope != null && nowElapsedMs >= nextAttemptAtMs;
    }

    /** Registers a transient failure and returns the bounded delay to the next attempt. */
    public long failed(Object candidate, long nowElapsedMs) {
        selectScope(candidate);
        long delayMs = failures == 0 ? 15_000L : failures == 1 ? 30_000L : 60_000L;
        if (failures < 3) failures++;
        nextAttemptAtMs = nowElapsedMs > Long.MAX_VALUE - delayMs
                ? Long.MAX_VALUE : nowElapsedMs + delayMs;
        return delayMs;
    }

    public void clear() {
        scope = null;
        nextAttemptAtMs = 0L;
        failures = 0;
    }
}
