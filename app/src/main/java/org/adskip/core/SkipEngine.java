package org.adskip.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Pure, single-timer state machine for applying already-authorized skip rules.
 *
 * <p>The Android layer owns the actual Handler and TransportControls call. It passes a trusted
 * {@link PlaybackSnapshot} to this class on state changes and when its one scheduled timer fires,
 * then applies the returned {@link Directive}. This split makes timer and seek behaviour
 * deterministic without allowing this core class to access a MediaController directly.</p>
 *
 * <p>A session scope must include the real MediaSession token and resolved identity. Generation is
 * deliberately separate: an entry timer never survives a generation change, while an already
 * dispatched seek may survive only long enough to read back its result for the same scope/video.
 * This accommodates a seek that itself causes the platform stability gate to start a new
 * generation, without allowing an old seek to act on a different session.</p>
 */
public final class SkipEngine {
    private final Policy policy;
    private final UndoStateMachine undoStateMachine;
    private final LocalAuditLog localAuditLog;
    private String activeSessionScope;
    private BiliVideoKey activeVideo;
    private long activeGeneration = -1L;
    private TimerPlan scheduled;
    private PendingAttempt pending;
    private long nextId = 1L;
    private final Set<String> confirmedAttemptKeys = new HashSet<>();
    private final Set<String> terminalAttemptKeys = new HashSet<>();
    private final Set<String> suppressedSegmentIds = new HashSet<>();
    private final List<AuditEvent> auditTrail = new ArrayList<>();
    private long lastObservedAtElapsedMs;

    public SkipEngine(Policy policy) {
        this(policy, new UndoStateMachine(new UndoStateMachine.Policy(8_000L, 1_500L, 2_000L)),
                new LocalAuditLog(32));
    }

    /** Allows the Android product policy to configure a short undo window without UI coupling. */
    public SkipEngine(Policy policy, UndoStateMachine undoStateMachine, LocalAuditLog localAuditLog) {
        this.policy = Objects.requireNonNull(policy, "policy");
        this.undoStateMachine = Objects.requireNonNull(undoStateMachine, "undoStateMachine");
        this.localAuditLog = Objects.requireNonNull(localAuditLog, "localAuditLog");
    }

    /** Handles a playback/state callback. It never emits a seek without a matching timer fire. */
    public Directive onPlayback(PlaybackSnapshot snapshot, List<SegmentRule> rules) {
        Objects.requireNonNull(snapshot, "snapshot");
        lastObservedAtElapsedMs = snapshot.getObservedAtElapsedMs();
        ScopeChange scopeChange = synchronizeScope(snapshot);
        List<SegmentRule> eligible = eligibleRules(snapshot, rules);

        if (pending != null) {
            return evaluatePending(snapshot, eligible, scopeChange);
        }
        if (undoStateMachine.getState() == UndoStateMachine.State.READBACK_PENDING) {
            return evaluateUndoReadback(snapshot, eligible);
        }
        if (!snapshot.isPlaying()) {
            return cancel(Outcome.IDLE, "not-playing");
        }
        if (!snapshot.isSeekable()) {
            return cancel(Outcome.IDLE, "not-seekable");
        }

        if (scheduled != null && scheduled.kind == TimerKind.ENTRY
                && scheduled.matches(snapshot)
                && containsRule(eligible, scheduled.rule)
                && Float.compare(scheduled.speed, snapshot.getSpeed()) == 0
                && snapshot.getPositionMs() < safeAdd(
                        scheduled.rule.getStartMs(), policy.getEntryLateToleranceMs())) {
            return keep(Outcome.IDLE, "entry-timer-kept");
        }
        scheduled = null;
        return scheduleNext(snapshot, eligible, Outcome.IDLE, "next-segment");
    }

    /** Handles the Android layer's one scheduled timer firing after it has reread live state. */
    public Directive onTimer(
            PlaybackSnapshot snapshot, List<SegmentRule> rules, long timerId) {
        Objects.requireNonNull(snapshot, "snapshot");
        lastObservedAtElapsedMs = snapshot.getObservedAtElapsedMs();
        if (scheduled == null || scheduled.id != timerId) {
            return keep(Outcome.IGNORED, "stale-timer");
        }
        TimerPlan fired = scheduled;
        scheduled = null;
        ScopeChange scopeChange = synchronizeScope(snapshot);
        List<SegmentRule> eligible = eligibleRules(snapshot, rules);

        if (!fired.matchesScopeAndVideo(snapshot)) {
            return ignored("timer-session-or-video-changed");
        }
        if (fired.kind == TimerKind.UNDO_CONFIRMATION) {
            return evaluateUndoReadback(snapshot, eligible);
        }
        if (fired.kind == TimerKind.CONFIRMATION) {
            if (pending == null || pending.attemptId != fired.attemptId) {
                return ignored("confirmation-no-longer-pending");
            }
            return evaluatePending(snapshot, eligible, scopeChange);
        }
        if (scopeChange.generationChanged) {
            return ignored("entry-timer-generation-changed");
        }
        SegmentRule rule = findMatchingRule(eligible, fired.rule);
        if (rule == null) {
            return cancel(Outcome.IGNORED, "entry-rule-no-longer-authorized");
        }
        if (!snapshot.isPlaying()) {
            return cancel(Outcome.IDLE, "timer-fired-not-playing");
        }
        if (!snapshot.isSeekable()) {
            return cancel(Outcome.IDLE, "timer-fired-not-seekable");
        }
        if (Float.compare(fired.speed, snapshot.getSpeed()) != 0) {
            return scheduleNext(snapshot, eligible, Outcome.IDLE, "speed-changed-at-timer");
        }

        long position = snapshot.getPositionMs();
        if (position < rule.getStartMs()) {
            return scheduleNext(snapshot, eligible, Outcome.IDLE, "timer-fired-early");
        }
        if (position >= rule.getEndMs()
                || position - rule.getStartMs() > policy.getEntryLateToleranceMs()) {
            terminalAttemptKeys.add(attemptKey(rule, targetFor(rule, snapshot)));
            appendAudit(AuditStatus.MISSED, rule, -1L, "entry-window-missed");
            return scheduleNext(snapshot, eligible, Outcome.MISSED, "entry-window-missed");
        }

        List<SegmentRule> members = connectedRules(rule, eligible);
        long target = targetFor(members.get(members.size() - 1), snapshot);
        for (SegmentRule member : members) target = Math.max(target, targetFor(member, snapshot));
        long attemptId = nextId++;
        long verifyAt = safeAdd(snapshot.getObservedAtElapsedMs(), policy.getReadbackDelayMs());
        pending = new PendingAttempt(attemptId, rule, target, verifyAt,
                snapshot.getSessionScope(), snapshot.getVideoKey(), snapshot.getPositionMs(), snapshot, members);
        scheduled = new TimerPlan(nextId++, TimerKind.CONFIRMATION, verifyAt,
                snapshot.getSessionScope(), snapshot.getVideoKey(), snapshot.getGeneration(),
                snapshot.getSpeed(), rule, attemptId);
        appendAudit(AuditStatus.ATTEMPTED, rule, attemptId, "seek-requested",
                members, target - snapshot.getPositionMs());
        return withTimer(Outcome.SEEK, "seek-requested", scheduled, rule, target, attemptId);
    }

    /**
     * Records that the Android transport call could not be dispatched. The rule is terminal for
     * this session scope, preventing a callback storm from issuing repeated seeks.
     */
    public Directive onSeekDispatchFailed(long attemptId, String reason) {
        if (pending == null || pending.attemptId != attemptId) {
            return keep(Outcome.IGNORED, "dispatch-failure-for-stale-attempt");
        }
        String failureReason = nonBlank(reason, "seek-dispatch-failed");
        markAttempt(pending.members, pending.before, terminalAttemptKeys);
        appendAudit(AuditStatus.FAILED, pending.rule, pending.attemptId, failureReason,
                pending.members, pending.targetMs - pending.originalPositionMs);
        pending = null;
        scheduled = null;
        return cancel(Outcome.FAILED, failureReason);
    }

    /** Replans future work after dispatch failure using a freshly authorized observation. */
    public Directive onSeekDispatchFailed(long attemptId, String reason,
            PlaybackSnapshot snapshot, List<SegmentRule> rules) {
        return resumeAfterFailure(onSeekDispatchFailed(attemptId, reason), snapshot, rules);
    }

    /** Requests an undo only for the exact session/video/generation which confirmed the skip. */
    public Directive requestUndo(PlaybackSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        lastObservedAtElapsedMs = snapshot.getObservedAtElapsedMs();
        synchronizeScope(snapshot);
        UndoStateMachine.Decision decision = undoStateMachine.requestUndo(snapshot);
        if (decision.getType() != UndoStateMachine.DecisionType.REQUEST_SEEK) {
            return ignored(decision.getReason());
        }
        UndoStateMachine.Receipt receipt = decision.getReceipt();
        for (SegmentRule member : receipt.getRules()) suppressedSegmentIds.add(member.getSegmentId());
        scheduled = new TimerPlan(nextId++, TimerKind.UNDO_CONFIRMATION,
                decision.getReadbackAtElapsedMs(), snapshot.getSessionScope(), snapshot.getVideoKey(),
                snapshot.getGeneration(), snapshot.getSpeed(), receipt.getRule(), decision.getUndoId());
        return withTimer(Outcome.UNDO_SEEK, "undo-seek-requested", scheduled, receipt.getRule(),
                receipt.getOriginalPositionMs(), decision.getUndoId());
    }

    /** Records a failed Android transport dispatch for a pending undo seek. */
    public Directive onUndoDispatchFailed(String reason) {
        UndoStateMachine.Decision decision = undoStateMachine.onDispatchFailed(reason);
        if (decision.getType() == UndoStateMachine.DecisionType.REJECTED) {
            return keep(Outcome.IGNORED, decision.getReason());
        }
        scheduled = null;
        if (decision.getReceipt() != null) {
            appendAudit(AuditStatus.FAILED, decision.getReceipt().getRule(), decision.getUndoId(),
                    decision.getReason());
        }
        return cancel(Outcome.UNDO_FAILED, decision.getReason());
    }

    public Directive onUndoDispatchFailed(String reason,
            PlaybackSnapshot snapshot, List<SegmentRule> rules) {
        return resumeAfterFailure(onUndoDispatchFailed(reason), snapshot, rules);
    }

    private Directive resumeAfterFailure(Directive failure,
            PlaybackSnapshot snapshot, List<SegmentRule> rules) {
        if (snapshot == null || failure.getOutcome() == Outcome.IGNORED
                || !snapshot.getSessionScope().equals(activeSessionScope)
                || !snapshot.getVideoKey().equals(activeVideo)) return failure;
        lastObservedAtElapsedMs = snapshot.getObservedAtElapsedMs();
        synchronizeScope(snapshot);
        return scheduleNext(snapshot, eligibleRules(snapshot, rules),
                failure.getOutcome(), failure.getReason());
    }

    public boolean isUndoAvailable(PlaybackSnapshot snapshot) {
        return snapshot != null && undoStateMachine.isAvailable(snapshot);
    }

    public UndoStateMachine.State getUndoState() {
        return undoStateMachine.getState();
    }

    /** Read-only receipt metadata for an optional UI; callers still use requestUndo for control. */
    public UndoStateMachine.Receipt getUndoReceipt() {
        return undoStateMachine.getReceipt();
    }

    /** Exposed for deterministic harness verification; resetPlaybackCycle clears this set. */
    public boolean isSegmentSuppressedInCurrentCycle(String segmentId) {
        return segmentId != null && suppressedSegmentIds.contains(segmentId);
    }

    public LocalAuditLog localAuditLog() {
        return localAuditLog;
    }

    /** Clears per-cycle dedupe after a separately proven loop/replay reset of the same session. */
    public Directive resetPlaybackCycle(String sessionScope, BiliVideoKey videoKey) {
        if (activeSessionScope == null || !activeSessionScope.equals(sessionScope)
                || !activeVideo.equals(videoKey)) {
            return ignored("cycle-reset-for-inactive-session");
        }
        confirmedAttemptKeys.clear();
        terminalAttemptKeys.clear();
        suppressedSegmentIds.clear();
        undoStateMachine.invalidate("playback-cycle-reset");
        pending = null;
        scheduled = null;
        appendAudit(AuditStatus.CYCLE_RESET, null, -1L, "playback-cycle-reset");
        return cancel(Outcome.IDLE, "playback-cycle-reset");
    }

    public List<AuditEvent> auditTrail() {
        return Collections.unmodifiableList(new ArrayList<>(auditTrail));
    }

    private Directive evaluatePending(
            PlaybackSnapshot snapshot, List<SegmentRule> eligible, ScopeChange scopeChange) {
        PendingAttempt attempt = pending;
        boolean replaceTimer = scopeChange.generationChanged || scheduled == null;
        if (!attempt.matchesScopeAndVideo(snapshot)) {
            pending = null;
            scheduled = null;
            return ignored("pending-session-or-video-changed");
        }
        if (scopeChange.generationChanged) {
            // A seek may cause a new stable generation. Preserve only the passive readback step.
            scheduled = new TimerPlan(nextId++, TimerKind.CONFIRMATION, attempt.verifyAtElapsedMs,
                    snapshot.getSessionScope(), snapshot.getVideoKey(), snapshot.getGeneration(),
                    snapshot.getSpeed(), attempt.rule, attempt.attemptId);
        }
        if (SeekReadback.confirms(attempt.before, snapshot, attempt.targetMs, 0L)) {
            markAttempt(attempt.members, snapshot, confirmedAttemptKeys);
            appendAudit(AuditStatus.CONFIRMED, attempt.rule, attempt.attemptId,
                    "publisher-discontinuity-confirmed", attempt.members,
                    attempt.targetMs - attempt.originalPositionMs);
            undoStateMachine.recordConfirmed(snapshot, attempt.rule, attempt.originalPositionMs,
                    attempt.targetMs, attempt.members);
            pending = null;
            scheduled = null;
            return scheduleNext(snapshot, eligible, Outcome.CONFIRMED, "seek-confirmed");
        }
        if (snapshot.getObservedAtElapsedMs() >= attempt.verifyAtElapsedMs) {
            markAttempt(attempt.members, snapshot, terminalAttemptKeys);
            appendAudit(AuditStatus.FAILED, attempt.rule, attempt.attemptId,
                    "publisher-discontinuity-unconfirmed", attempt.members,
                    attempt.targetMs - attempt.originalPositionMs);
            pending = null;
            scheduled = null;
            return scheduleNext(snapshot, eligible, Outcome.FAILED, "seek-readback-failed");
        }
        if (scheduled == null) {
            scheduled = new TimerPlan(nextId++, TimerKind.CONFIRMATION,
                    attempt.verifyAtElapsedMs, snapshot.getSessionScope(), snapshot.getVideoKey(),
                    snapshot.getGeneration(), snapshot.getSpeed(), attempt.rule, attempt.attemptId);
        }
        return replaceTimer
                ? withTimer(Outcome.IDLE, "seek-readback-pending", scheduled, attempt.rule, -1L, attempt.attemptId)
                : keep(Outcome.IDLE, "seek-readback-pending");
    }

    private Directive evaluateUndoReadback(PlaybackSnapshot snapshot, List<SegmentRule> eligible) {
        UndoStateMachine.Decision decision = undoStateMachine.readback(snapshot);
        if (decision.getType() == UndoStateMachine.DecisionType.CONFIRMED) {
            appendAudit(AuditStatus.UNDO, decision.getReceipt().getRule(), decision.getUndoId(),
                    decision.getReason());
            scheduled = null;
            return scheduleNext(snapshot, eligible, Outcome.UNDO_CONFIRMED, decision.getReason());
        }
        if (decision.getType() == UndoStateMachine.DecisionType.FAILED
                || decision.getType() == UndoStateMachine.DecisionType.REJECTED) {
            if (decision.getReceipt() != null) {
                appendAudit(AuditStatus.FAILED, decision.getReceipt().getRule(), decision.getUndoId(),
                        decision.getReason());
            }
            scheduled = null;
            return scheduleNext(snapshot, eligible, Outcome.UNDO_FAILED, decision.getReason());
        }
        if (decision.getType() == UndoStateMachine.DecisionType.PENDING) {
            UndoStateMachine.Receipt receipt = decision.getReceipt();
            scheduled = new TimerPlan(nextId++, TimerKind.UNDO_CONFIRMATION,
                    decision.getReadbackAtElapsedMs(), snapshot.getSessionScope(), snapshot.getVideoKey(),
                    snapshot.getGeneration(), snapshot.getSpeed(), receipt.getRule(), decision.getUndoId());
            return withTimer(Outcome.IDLE, decision.getReason(), scheduled, receipt.getRule(), -1L,
                    decision.getUndoId());
        }
        return ignored(decision.getReason());
    }

    private Directive scheduleNext(
            PlaybackSnapshot snapshot,
            List<SegmentRule> eligible,
            Outcome outcome,
            String reason) {
        if (!snapshot.isPlaying() || !snapshot.isSeekable() || snapshot.getSpeed() <= 0F) {
            return cancel(outcome, snapshot.isPlaying() ? "not-seekable" : "not-playing");
        }
        SegmentRule next = nextRuleAfter(snapshot, eligible);
        if (next == null) {
            return cancel(outcome, reason + ":no-future-segment");
        }
        long distance = next.getStartMs() - snapshot.getPositionMs();
        long delay = ceilDivideBySpeed(distance, snapshot.getSpeed());
        long wakeAt = safeAdd(snapshot.getObservedAtElapsedMs(), Math.max(1L, delay));
        scheduled = new TimerPlan(nextId++, TimerKind.ENTRY, wakeAt,
                snapshot.getSessionScope(), snapshot.getVideoKey(), snapshot.getGeneration(),
                snapshot.getSpeed(), next, -1L);
        appendAudit(AuditStatus.SCHEDULED, next, -1L, reason);
        return withTimer(outcome, reason, scheduled, next, -1L, -1L);
    }

    private SegmentRule nextRuleAfter(PlaybackSnapshot snapshot, List<SegmentRule> eligible) {
        for (SegmentRule rule : eligible) {
            long target = targetFor(rule, snapshot);
            String key = attemptKey(rule, target);
            if (confirmedAttemptKeys.contains(key) || terminalAttemptKeys.contains(key)
                    || suppressedSegmentIds.contains(rule.getSegmentId())) {
                continue;
            }
            if (rule.getEndMs() <= snapshot.getPositionMs()) {
                continue;
            }
            // Do not infer consent to skip a segment when first observing the session inside it.
            if (rule.getStartMs() <= snapshot.getPositionMs()) {
                continue;
            }
            return rule;
        }
        return null;
    }

    private ScopeChange synchronizeScope(PlaybackSnapshot snapshot) {
        if (activeSessionScope == null || !activeSessionScope.equals(snapshot.getSessionScope())
                || !activeVideo.equals(snapshot.getVideoKey())) {
            activeSessionScope = snapshot.getSessionScope();
            activeVideo = snapshot.getVideoKey();
            activeGeneration = snapshot.getGeneration();
            scheduled = null;
            pending = null;
            confirmedAttemptKeys.clear();
            terminalAttemptKeys.clear();
            suppressedSegmentIds.clear();
            undoStateMachine.invalidate("session-or-video-changed");
            appendAudit(AuditStatus.SCOPE_CHANGED, null, -1L, "session-or-video-changed");
            return ScopeChange.SESSION_CHANGED;
        }
        if (activeGeneration != snapshot.getGeneration()) {
            activeGeneration = snapshot.getGeneration();
            boolean undoReadbackPending = undoStateMachine.getState()
                    == UndoStateMachine.State.READBACK_PENDING;
            if (pending == null && !undoReadbackPending) {
                scheduled = null;
            }
            if (!undoReadbackPending) undoStateMachine.invalidate("generation-changed");
            appendAudit(AuditStatus.GENERATION_CHANGED, null, -1L,
                    undoReadbackPending ? "undo-readback-generation-transition" : "generation-changed");
            return ScopeChange.GENERATION_CHANGED;
        }
        return ScopeChange.NONE;
    }

    private List<SegmentRule> eligibleRules(
            PlaybackSnapshot snapshot, List<SegmentRule> suppliedRules) {
        if (suppliedRules == null || suppliedRules.isEmpty()) {
            return Collections.emptyList();
        }
        List<SegmentRule> eligible = new ArrayList<>();
        for (SegmentRule rule : suppliedRules) {
            if (rule != null
                    && rule.getAction() == SegmentAction.SKIP
                    && rule.getVideoKey().equals(snapshot.getVideoKey())
                    && rule.getEndMs() <= snapshot.getDurationMs()
                    && !suppressedSegmentIds.contains(rule.getSegmentId())
                    && !confirmedAttemptKeys.contains(attemptKey(rule, targetFor(rule, snapshot)))
                    && !terminalAttemptKeys.contains(attemptKey(rule, targetFor(rule, snapshot)))) {
                eligible.add(rule);
            }
        }
        eligible.sort(Comparator.comparingLong(SegmentRule::getStartMs)
                .thenComparingLong(SegmentRule::getEndMs)
                .thenComparing(SegmentRule::getSegmentId));
        return eligible;
    }

    /** Stop control while identity is temporarily unavailable; keep this cycle's suppression. */
    public Directive suspend() {
        if (pending != null) {
            markAttempt(pending.members, pending.before, terminalAttemptKeys);
            appendAudit(AuditStatus.FAILED, pending.rule, pending.attemptId,
                    "readback-unavailable-at-suspension");
        }
        pending = null;
        undoStateMachine.invalidate("temporarily-unavailable");
        return cancel(Outcome.IDLE, "temporarily-unavailable");
    }

    private void markAttempt(List<SegmentRule> members, PlaybackSnapshot snapshot, Set<String> keys) {
        for (SegmentRule member : members) keys.add(attemptKey(member, targetFor(member, snapshot)));
    }

    private static List<SegmentRule> connectedRules(SegmentRule entry, List<SegmentRule> eligible) {
        List<SegmentRule> members = new ArrayList<>();
        long end = entry.getEndMs();
        for (SegmentRule rule : eligible) {
            if (rule.getEndMs() < entry.getStartMs()) continue;
            if (rule.getStartMs() > end) break;
            members.add(rule);
            end = Math.max(end, rule.getEndMs());
        }
        return Collections.unmodifiableList(members);
    }

    private static boolean containsRule(List<SegmentRule> rules, SegmentRule expected) {
        return findMatchingRule(rules, expected) != null;
    }

    private static SegmentRule findMatchingRule(List<SegmentRule> rules, SegmentRule expected) {
        for (SegmentRule rule : rules) {
            if (rule.getSegmentId().equals(expected.getSegmentId())
                    && rule.getStartMs() == expected.getStartMs()
                    && rule.getEndMs() == expected.getEndMs()
                    && rule.getVideoKey().equals(expected.getVideoKey())) {
                return rule;
            }
        }
        return null;
    }

    private long targetFor(SegmentRule rule, PlaybackSnapshot snapshot) {
        return Math.min(snapshot.getDurationMs(), safeAdd(rule.getEndMs(), policy.getSeekEpsilonMs()));
    }

    private static String attemptKey(SegmentRule rule, long targetMs) {
        return rule.getSegmentId() + "@" + targetMs;
    }

    private static long ceilDivideBySpeed(long distanceMs, float speed) {
        if (distanceMs <= 0L) {
            return 0L;
        }
        double delay = Math.ceil(distanceMs / (double) speed);
        return delay >= Long.MAX_VALUE ? Long.MAX_VALUE : (long) delay;
    }

    private static long safeAdd(long first, long second) {
        if (second > 0L && first > Long.MAX_VALUE - second) {
            return Long.MAX_VALUE;
        }
        return first + second;
    }

    private Directive keep(Outcome outcome, String reason) {
        return new Directive(outcome, reason, null, true, null, -1L, -1L);
    }

    private Directive cancel(Outcome outcome, String reason) {
        scheduled = null;
        return new Directive(outcome, reason, null, false, null, -1L, -1L);
    }

    private Directive ignored(String reason) {
        return new Directive(Outcome.IGNORED, reason, null, false, null, -1L, -1L);
    }

    private static Directive withTimer(
            Outcome outcome,
            String reason,
            TimerPlan timer,
            SegmentRule rule,
            long seekTargetMs,
            long attemptId) {
        return new Directive(outcome, reason, timer, false,
                rule == null ? null : rule.getSegmentId(), seekTargetMs, attemptId);
    }

    private void appendAudit(AuditStatus status, SegmentRule rule, long attemptId, String reason) {
        appendAudit(status, rule, attemptId, reason,
                rule == null ? Collections.emptyList() : Collections.singletonList(rule),
                rule == null ? 0L : rule.getEndMs() - rule.getStartMs());
    }

    private void appendAudit(AuditStatus status, SegmentRule rule, long attemptId, String reason,
            List<SegmentRule> members, long skippedDurationMs) {
        if (auditTrail.size() == 32) {
            auditTrail.remove(0);
        }
        auditTrail.add(new AuditEvent(status,
                rule == null ? null : rule.getSegmentId(), attemptId, reason));
        LocalAuditLog.Status localStatus = null;
        switch (status) {
            case ATTEMPTED:
                localStatus = LocalAuditLog.Status.ATTEMPTED;
                break;
            case CONFIRMED:
                localStatus = LocalAuditLog.Status.CONFIRMED;
                break;
            case FAILED:
                localStatus = LocalAuditLog.Status.FAILED;
                break;
            case UNDO:
                localStatus = LocalAuditLog.Status.UNDO;
                break;
            default:
                break;
        }
        if (localStatus != null && rule != null) {
            localAuditLog.record(lastObservedAtElapsedMs, rule, localStatus, reason,
                    members, skippedDurationMs);
        }
    }

    private static String requireText(String value, String name) {
        if (value == null || value.trim().isEmpty() || !value.equals(value.trim())) {
            throw new IllegalArgumentException(name + " must be nonblank and unpadded");
        }
        return value;
    }

    private static String nonBlank(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    private enum ScopeChange {
        NONE(false),
        GENERATION_CHANGED(true),
        SESSION_CHANGED(true);

        final boolean generationChanged;

        ScopeChange(boolean generationChanged) {
            this.generationChanged = generationChanged;
        }
    }

    public enum Outcome {
        IDLE,
        SEEK,
        CONFIRMED,
        FAILED,
        UNDO_SEEK,
        UNDO_CONFIRMED,
        UNDO_FAILED,
        MISSED,
        IGNORED
    }

    public enum AuditStatus {
        SCOPE_CHANGED,
        GENERATION_CHANGED,
        SCHEDULED,
        ATTEMPTED,
        CONFIRMED,
        FAILED,
        UNDO,
        MISSED,
        CYCLE_RESET
    }

    public enum TimerKind {
        ENTRY,
        CONFIRMATION,
        UNDO_CONFIRMATION
    }

    /** Explicit injected values; production values remain a B站 calibration decision. */
    public static final class Policy {
        private final long entryLateToleranceMs;
        private final long seekEpsilonMs;
        private final long readbackDelayMs;

        public Policy(long entryLateToleranceMs, long seekEpsilonMs, long readbackDelayMs) {
            if (entryLateToleranceMs < 0L || seekEpsilonMs < 0L || readbackDelayMs <= 0L) {
                throw new IllegalArgumentException("invalid skip policy");
            }
            this.entryLateToleranceMs = entryLateToleranceMs;
            this.seekEpsilonMs = seekEpsilonMs;
            this.readbackDelayMs = readbackDelayMs;
        }

        public long getEntryLateToleranceMs() {
            return entryLateToleranceMs;
        }

        public long getSeekEpsilonMs() {
            return seekEpsilonMs;
        }

        public long getReadbackDelayMs() {
            return readbackDelayMs;
        }
    }

    /** Position must already be extrapolated to observedAtElapsedMs by the Android adapter. */
    public static final class PlaybackSnapshot {
        private final String sessionScope;
        private final BiliVideoKey videoKey;
        private final long generation;
        private final long positionMs;
        private final long durationMs;
        private final float speed;
        private final boolean playing;
        private final boolean seekable;
        private final long observedAtElapsedMs;
        private final long publisherPositionMs;
        private final long publisherUpdatedAtMs;

        /** Synchronous publisher observation, useful for deterministic fixtures. */
        public PlaybackSnapshot(String sessionScope, BiliVideoKey videoKey, long generation,
                long positionMs, long durationMs, float speed, boolean playing, boolean seekable,
                long observedAtElapsedMs) {
            this(sessionScope, videoKey, generation, positionMs, durationMs, speed, playing,
                    seekable, observedAtElapsedMs, positionMs, observedAtElapsedMs);
        }

        /** Android must supply the unextrapolated publisher position and its update timestamp. */
        public PlaybackSnapshot(
                String sessionScope,
                BiliVideoKey videoKey,
                long generation,
                long positionMs,
                long durationMs,
                float speed,
                boolean playing,
                boolean seekable,
                long observedAtElapsedMs, long publisherPositionMs, long publisherUpdatedAtMs) {
            this.sessionScope = requireText(sessionScope, "sessionScope");
            this.videoKey = Objects.requireNonNull(videoKey, "videoKey");
            if (generation < 0L || durationMs <= 0L || positionMs < 0L || positionMs > durationMs
                    || observedAtElapsedMs < 0L || !Float.isFinite(speed) || speed < 0F
                    || (playing && speed <= 0F)) {
                throw new IllegalArgumentException("invalid playback snapshot");
            }
            this.generation = generation;
            this.positionMs = positionMs;
            this.durationMs = durationMs;
            this.speed = speed;
            this.playing = playing;
            this.seekable = seekable;
            this.observedAtElapsedMs = observedAtElapsedMs;
            this.publisherPositionMs = publisherPositionMs;
            this.publisherUpdatedAtMs = publisherUpdatedAtMs;
        }

        public long getPublisherPositionMs() { return publisherPositionMs; }
        public long getPublisherUpdatedAtMs() { return publisherUpdatedAtMs; }
        public boolean hasPublisherPosition() {
            return publisherPositionMs >= 0L && publisherPositionMs <= durationMs
                    && publisherUpdatedAtMs >= 0L && publisherUpdatedAtMs <= observedAtElapsedMs;
        }

        public String getSessionScope() {
            return sessionScope;
        }

        public BiliVideoKey getVideoKey() {
            return videoKey;
        }

        public long getGeneration() {
            return generation;
        }

        public long getPositionMs() {
            return positionMs;
        }

        public long getDurationMs() {
            return durationMs;
        }

        public float getSpeed() {
            return speed;
        }

        public boolean isPlaying() {
            return playing;
        }

        public boolean isSeekable() {
            return seekable;
        }

        public long getObservedAtElapsedMs() {
            return observedAtElapsedMs;
        }
    }

    /** A physical handler must own no more than one of these plans at any time. */
    public static final class TimerPlan {
        private final long id;
        private final TimerKind kind;
        private final long wakeAtElapsedMs;
        private final String sessionScope;
        private final BiliVideoKey videoKey;
        private final long generation;
        private final float speed;
        private final SegmentRule rule;
        private final long attemptId;

        private TimerPlan(
                long id, TimerKind kind, long wakeAtElapsedMs, String sessionScope,
                BiliVideoKey videoKey, long generation, float speed, SegmentRule rule,
                long attemptId) {
            this.id = id;
            this.kind = kind;
            this.wakeAtElapsedMs = wakeAtElapsedMs;
            this.sessionScope = sessionScope;
            this.videoKey = videoKey;
            this.generation = generation;
            this.speed = speed;
            this.rule = rule;
            this.attemptId = attemptId;
        }

        public long getId() {
            return id;
        }

        public TimerKind getKind() {
            return kind;
        }

        public long getWakeAtElapsedMs() {
            return wakeAtElapsedMs;
        }

        public String getSegmentId() {
            return rule.getSegmentId();
        }

        private boolean matches(PlaybackSnapshot snapshot) {
            return matchesScopeAndVideo(snapshot) && generation == snapshot.getGeneration();
        }

        private boolean matchesScopeAndVideo(PlaybackSnapshot snapshot) {
            return sessionScope.equals(snapshot.getSessionScope())
                    && videoKey.equals(snapshot.getVideoKey());
        }
    }

    /** The caller must replace its timer unless retainCurrentTimer is true. */
    public static final class Directive {
        private final Outcome outcome;
        private final String reason;
        private final TimerPlan timerPlan;
        private final boolean retainCurrentTimer;
        private final String segmentId;
        private final long seekTargetMs;
        private final long attemptId;

        private Directive(
                Outcome outcome, String reason, TimerPlan timerPlan, boolean retainCurrentTimer,
                String segmentId, long seekTargetMs, long attemptId) {
            this.outcome = outcome;
            this.reason = reason;
            this.timerPlan = timerPlan;
            this.retainCurrentTimer = retainCurrentTimer;
            this.segmentId = segmentId;
            this.seekTargetMs = seekTargetMs;
            this.attemptId = attemptId;
        }

        public Outcome getOutcome() {
            return outcome;
        }

        public String getReason() {
            return reason;
        }

        public TimerPlan getTimerPlan() {
            return timerPlan;
        }

        public boolean shouldRetainCurrentTimer() {
            return retainCurrentTimer;
        }

        public String getSegmentId() {
            return segmentId;
        }

        public long getSeekTargetMs() {
            return seekTargetMs;
        }

        public long getAttemptId() {
            return attemptId;
        }
    }

    public static final class AuditEvent {
        private final AuditStatus status;
        private final String segmentId;
        private final long attemptId;
        private final String reason;

        private AuditEvent(AuditStatus status, String segmentId, long attemptId, String reason) {
            this.status = status;
            this.segmentId = segmentId;
            this.attemptId = attemptId;
            this.reason = reason;
        }

        public AuditStatus getStatus() {
            return status;
        }

        public String getSegmentId() {
            return segmentId;
        }

        public long getAttemptId() {
            return attemptId;
        }

        public String getReason() {
            return reason;
        }
    }

    private static final class PendingAttempt {
        private final long attemptId;
        private final SegmentRule rule;
        private final long targetMs;
        private final long verifyAtElapsedMs;
        private final String sessionScope;
        private final BiliVideoKey videoKey;
        private final long originalPositionMs;
        private final PlaybackSnapshot before;
        private final List<SegmentRule> members;

        private PendingAttempt(
                long attemptId, SegmentRule rule, long targetMs, long verifyAtElapsedMs,
                String sessionScope, BiliVideoKey videoKey, long originalPositionMs, PlaybackSnapshot before, List<SegmentRule> members) {
            this.attemptId = attemptId;
            this.rule = rule;
            this.targetMs = targetMs;
            this.verifyAtElapsedMs = verifyAtElapsedMs;
            this.sessionScope = sessionScope;
            this.videoKey = videoKey;
            this.originalPositionMs = originalPositionMs;
            this.before = before;
            this.members = members;
        }

        private boolean matchesScopeAndVideo(PlaybackSnapshot snapshot) {
            return sessionScope.equals(snapshot.getSessionScope())
                    && videoKey.equals(snapshot.getVideoKey());
        }
    }
}
