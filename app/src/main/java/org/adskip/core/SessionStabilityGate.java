package org.adskip.core;

import java.util.Optional;

/**
 * Stateful, platform-independent gate that rejects transitional or discontinuous session data.
 */
public final class SessionStabilityGate {
    public static final long DEFAULT_MIN_STABLE_MS = 1_500L;
    public static final long DEFAULT_MIN_PROGRESS_MS = 250L;
    public static final long DEFAULT_POSITION_TOLERANCE_MS = 1_500L;

    public enum State {
        REJECTED,
        WAITING,
        STABLE
    }

    private final long minStableMs;
    private final long minProgressMs;
    private final long positionToleranceMs;

    private State state = State.REJECTED;
    private String reason = "not-observed";
    private BiliIdentity identity;
    private long generationNo;
    private long firstObservedAtMs;
    private long firstPositionMs;
    private long lastObservedAtMs;
    private long lastPositionMs;
    private float lastSpeed;

    public SessionStabilityGate() {
        this(DEFAULT_MIN_STABLE_MS, DEFAULT_MIN_PROGRESS_MS, DEFAULT_POSITION_TOLERANCE_MS);
    }

    SessionStabilityGate(long minStableMs, long minProgressMs, long positionToleranceMs) {
        if (minStableMs <= 0L || minProgressMs <= 0L || positionToleranceMs < 0L) {
            throw new IllegalArgumentException("invalid stability thresholds");
        }
        this.minStableMs = minStableMs;
        this.minProgressMs = minProgressMs;
        this.positionToleranceMs = positionToleranceMs;
    }

    public State observe(
            BiliIdentity observedIdentity,
            boolean playing,
            boolean seekable,
            long estimatedPositionMs,
            float speed,
            long observedAtMs) {
        if (observedIdentity == null) {
            return reject("invalid-identity");
        }
        if (!playing) {
            return reject("not-playing");
        }
        if (!seekable) {
            return reject("not-seekable");
        }
        if (estimatedPositionMs < 0L || observedAtMs < 0L
                || !Float.isFinite(speed) || speed <= 0F) {
            return reject("invalid-playback-values");
        }

        if (identity == null || !identity.equals(observedIdentity)) {
            startCycle(observedIdentity, estimatedPositionMs, speed, observedAtMs, "identity-observed");
            return state;
        }
        if (observedAtMs < lastObservedAtMs) {
            startCycle(observedIdentity, estimatedPositionMs, speed, observedAtMs, "clock-regressed");
            return state;
        }
        if (Float.compare(speed, lastSpeed) != 0) {
            startCycle(observedIdentity, estimatedPositionMs, speed, observedAtMs, "speed-changed");
            return state;
        }

        long elapsedSinceLast = observedAtMs - lastObservedAtMs;
        long positionDelta = estimatedPositionMs - lastPositionMs;
        if (elapsedSinceLast > 0L) {
            long expectedDelta = Math.round(elapsedSinceLast * (double) speed);
            long tolerance = Math.max(positionToleranceMs, expectedDelta / 2L);
            if (positionDelta < -tolerance || positionDelta > expectedDelta + tolerance) {
                startCycle(observedIdentity, estimatedPositionMs, speed, observedAtMs,
                        positionDelta < 0L ? "backward-discontinuity" : "forward-discontinuity");
                return state;
            }
        }

        lastObservedAtMs = observedAtMs;
        lastPositionMs = estimatedPositionMs;
        long stableElapsed = observedAtMs - firstObservedAtMs;
        long stableProgress = estimatedPositionMs - firstPositionMs;
        if (stableElapsed >= minStableMs && stableProgress >= minProgressMs) {
            state = State.STABLE;
            reason = "stable";
        } else {
            state = State.WAITING;
            reason = stableElapsed < minStableMs ? "waiting-time" : "waiting-progress";
        }
        return state;
    }

    public State reject(String rejectionReason) {
        if (rejectionReason == null || rejectionReason.trim().isEmpty()) {
            throw new IllegalArgumentException("rejectionReason must not be blank");
        }
        if (identity != null || state != State.REJECTED) {
            generationNo++;
        }
        identity = null;
        state = State.REJECTED;
        reason = rejectionReason;
        return state;
    }

    public State getState() {
        return state;
    }

    public String getReason() {
        return reason;
    }

    public long getGenerationNo() {
        return generationNo;
    }

    public Optional<BiliIdentity> getIdentity() {
        return Optional.ofNullable(identity);
    }

    private void startCycle(
            BiliIdentity observedIdentity,
            long positionMs,
            float speed,
            long observedAtMs,
            String cycleReason) {
        generationNo++;
        identity = observedIdentity;
        firstObservedAtMs = observedAtMs;
        firstPositionMs = positionMs;
        lastObservedAtMs = observedAtMs;
        lastPositionMs = positionMs;
        lastSpeed = speed;
        state = State.WAITING;
        reason = cycleReason;
    }
}
