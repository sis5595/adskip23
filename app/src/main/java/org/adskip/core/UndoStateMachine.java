package org.adskip.core;

import java.util.Objects;
import java.util.List;
import java.util.Collections;
import java.util.ArrayList;

/**
 * Pure, session-scoped undo state. Sending an undo seek is never considered success: callers
 * must feed a same-scope playback readback to {@link #readback(SkipEngine.PlaybackSnapshot)}.
 */
public final class UndoStateMachine {
    public enum State { NONE, AVAILABLE, READBACK_PENDING, COMPLETED, FAILED, EXPIRED, INVALIDATED }
    public enum DecisionType { REQUEST_SEEK, PENDING, CONFIRMED, FAILED, EXPIRED, REJECTED, DUPLICATE }

    public static final class Policy {
        private final long windowMs;
        private final long readbackDelayMs;
        private final long readbackToleranceMs;

        public Policy(long windowMs, long readbackDelayMs, long readbackToleranceMs) {
            if (windowMs <= 0L || readbackDelayMs <= 0L || readbackToleranceMs < 0L) {
                throw new IllegalArgumentException("invalid undo policy");
            }
            this.windowMs = windowMs;
            this.readbackDelayMs = readbackDelayMs;
            this.readbackToleranceMs = readbackToleranceMs;
        }
        public long getWindowMs() { return windowMs; }
        public long getReadbackDelayMs() { return readbackDelayMs; }
        public long getReadbackToleranceMs() { return readbackToleranceMs; }
    }

    /** The minimal data needed to perform a constrained undo, not viewing-history metadata. */
    public static final class Receipt {
        private final String sessionScope;
        private final BiliVideoKey videoKey;
        private final long generation;
        private final String segmentId;
        private final long originalPositionMs;
        private final long skippedToPositionMs;
        private final String category;
        private final long confirmedAtElapsedMs;
        private final SegmentRule rule;
        private final List<SegmentRule> rules;

        private Receipt(String sessionScope, BiliVideoKey videoKey, long generation, SegmentRule rule,
                long originalPositionMs, long skippedToPositionMs, long confirmedAtElapsedMs, List<SegmentRule> rules) {
            this.sessionScope = sessionScope;
            this.videoKey = videoKey;
            this.generation = generation;
            this.rule = rule;
            this.rules = Collections.unmodifiableList(new ArrayList<>(rules));
            this.segmentId = rule.getSegmentId();
            this.originalPositionMs = originalPositionMs;
            this.skippedToPositionMs = skippedToPositionMs;
            this.category = rule.getCategory();
            this.confirmedAtElapsedMs = confirmedAtElapsedMs;
        }
        public String getSessionScope() { return sessionScope; }
        public BiliVideoKey getVideoKey() { return videoKey; }
        public long getGeneration() { return generation; }
        public String getSegmentId() { return segmentId; }
        public long getOriginalPositionMs() { return originalPositionMs; }
        public long getSkippedToPositionMs() { return skippedToPositionMs; }
        public String getCategory() { return category; }
        public long getConfirmedAtElapsedMs() { return confirmedAtElapsedMs; }
        public SegmentRule getRule() { return rule; }
        public List<SegmentRule> getRules() { return rules; }
    }

    public static final class Decision {
        private final DecisionType type;
        private final String reason;
        private final Receipt receipt;
        private final long undoId;
        private final long readbackAtElapsedMs;

        private Decision(DecisionType type, String reason, Receipt receipt, long undoId,
                long readbackAtElapsedMs) {
            this.type = type;
            this.reason = reason;
            this.receipt = receipt;
            this.undoId = undoId;
            this.readbackAtElapsedMs = readbackAtElapsedMs;
        }
        public DecisionType getType() { return type; }
        public String getReason() { return reason; }
        public Receipt getReceipt() { return receipt; }
        public long getUndoId() { return undoId; }
        public long getReadbackAtElapsedMs() { return readbackAtElapsedMs; }
    }

    private final Policy policy;
    private State state = State.NONE;
    private Receipt receipt;
    private long nextUndoId = 1L;
    private long pendingUndoId = -1L;
    private long readbackAtElapsedMs = -1L;
    private SkipEngine.PlaybackSnapshot undoBefore;

    public UndoStateMachine(Policy policy) {
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    public void recordConfirmed(SkipEngine.PlaybackSnapshot snapshot, SegmentRule rule,
            long originalPositionMs, long skippedToPositionMs) {
        recordConfirmed(snapshot, rule, originalPositionMs, skippedToPositionMs, Collections.singletonList(rule));
    }

    public void recordConfirmed(SkipEngine.PlaybackSnapshot snapshot, SegmentRule rule,
            long originalPositionMs, long skippedToPositionMs, List<SegmentRule> rules) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(rule, "rule");
        if (!rule.getVideoKey().equals(snapshot.getVideoKey()) || originalPositionMs < 0L
                || originalPositionMs > snapshot.getDurationMs() || skippedToPositionMs < 0L
                || skippedToPositionMs > snapshot.getDurationMs()) {
            throw new IllegalArgumentException("undo receipt does not match confirmed snapshot");
        }
        receipt = new Receipt(snapshot.getSessionScope(), snapshot.getVideoKey(), snapshot.getGeneration(),
                rule, originalPositionMs, skippedToPositionMs, snapshot.getObservedAtElapsedMs(), rules);
        state = State.AVAILABLE;
        pendingUndoId = -1L;
        readbackAtElapsedMs = -1L;
    }

    public State getState() { return state; }
    public Receipt getReceipt() { return receipt; }

    public boolean isAvailable(SkipEngine.PlaybackSnapshot snapshot) {
        return state == State.AVAILABLE && receipt != null && sameScope(snapshot)
                && snapshot.getObservedAtElapsedMs() - receipt.confirmedAtElapsedMs <= policy.windowMs;
    }

    public Decision requestUndo(SkipEngine.PlaybackSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        if (receipt == null || state == State.NONE || state == State.INVALIDATED) {
            return decision(DecisionType.REJECTED, "no-confirmed-skip");
        }
        if (state == State.READBACK_PENDING || state == State.COMPLETED || state == State.FAILED) {
            return decision(DecisionType.DUPLICATE, "undo-already-requested");
        }
        if (!sameScope(snapshot)) {
            invalidate("undo-session-or-generation-changed");
            return decision(DecisionType.REJECTED, "undo-session-or-generation-changed");
        }
        if (snapshot.getObservedAtElapsedMs() - receipt.confirmedAtElapsedMs > policy.windowMs) {
            state = State.EXPIRED;
            return decision(DecisionType.EXPIRED, "undo-window-expired");
        }
        if (state == State.EXPIRED) {
            return decision(DecisionType.EXPIRED, "undo-window-expired");
        }
        pendingUndoId = nextUndoId++;
        readbackAtElapsedMs = safeAdd(snapshot.getObservedAtElapsedMs(), policy.readbackDelayMs);
        undoBefore = snapshot;
        state = State.READBACK_PENDING;
        return new Decision(DecisionType.REQUEST_SEEK, "undo-seek-requested", receipt, pendingUndoId,
                readbackAtElapsedMs);
    }

    public Decision readback(SkipEngine.PlaybackSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        if (state != State.READBACK_PENDING || receipt == null) {
            return decision(DecisionType.REJECTED, "undo-not-pending");
        }
        // The seek we just requested may itself advance the adapter generation. Readback is
        // passive: retain the original token/session and exact video gate, but do not mistake
        // that seek-induced generation transition for a different playback session.
        if (!sameSessionAndVideo(snapshot)) {
            invalidate("undo-readback-session-or-generation-changed");
            return decision(DecisionType.REJECTED, "undo-readback-session-or-generation-changed");
        }
        if (SeekReadback.confirms(undoBefore, snapshot, receipt.originalPositionMs,
                policy.readbackToleranceMs)) {
            state = State.COMPLETED;
            return decision(DecisionType.CONFIRMED, "undo-readback-confirmed");
        }
        if (snapshot.getObservedAtElapsedMs() >= readbackAtElapsedMs) {
            state = State.FAILED;
            return decision(DecisionType.FAILED, "undo-readback-failed");
        }
        return decision(DecisionType.PENDING, "undo-readback-pending");
    }

    public Decision onDispatchFailed(String reason) {
        if (state != State.READBACK_PENDING) {
            return decision(DecisionType.REJECTED, "undo-dispatch-for-stale-request");
        }
        state = State.FAILED;
        return decision(DecisionType.FAILED,
                reason == null || reason.trim().isEmpty() ? "undo-seek-dispatch-failed" : reason.trim());
    }

    public void invalidate(String reason) {
        if (receipt != null || state != State.NONE) {
            state = State.INVALIDATED;
        }
        pendingUndoId = -1L;
        readbackAtElapsedMs = -1L;
    }

    private Decision decision(DecisionType type, String reason) {
        return new Decision(type, reason, receipt, pendingUndoId, readbackAtElapsedMs);
    }

    private boolean sameScope(SkipEngine.PlaybackSnapshot snapshot) {
        return sameSessionAndVideo(snapshot) && receipt.generation == snapshot.getGeneration();
    }

    private boolean sameSessionAndVideo(SkipEngine.PlaybackSnapshot snapshot) {
        return receipt != null && receipt.sessionScope.equals(snapshot.getSessionScope())
                && receipt.videoKey.equals(snapshot.getVideoKey());
    }

    private static long safeAdd(long first, long second) {
        return second > 0L && first > Long.MAX_VALUE - second ? Long.MAX_VALUE : first + second;
    }
}
