package org.adskip.core;

/**
 * Requires one unchanged, already-stable evidence scope to survive a short post-share window.
 *
 * <p>This is a research acquisition guard, not an identity upgrader. A changed generation starts
 * the window again; a missing scope clears it. Callers must still discard a network result whose
 * captured scope is no longer current.</p>
 */
public final class ResearchScopeSettleGate {
    public enum State {
        NO_SCOPE,
        WAITING,
        READY
    }

    private final long settleMs;
    private EvidenceScope observedScope;
    private long firstObservedAtMs = -1L;

    public ResearchScopeSettleGate(long settleMs) {
        if (settleMs <= 0L) {
            throw new IllegalArgumentException("settleMs must be positive");
        }
        this.settleMs = settleMs;
    }

    public State observe(EvidenceScope scope, long observedAtMs) {
        if (observedAtMs < 0L) {
            throw new IllegalArgumentException("observedAtMs must be non-negative");
        }
        if (scope == null) {
            reset();
            return State.NO_SCOPE;
        }
        if (observedScope == null || !observedScope.equals(scope)
                || observedAtMs < firstObservedAtMs) {
            observedScope = scope;
            firstObservedAtMs = observedAtMs;
            return State.WAITING;
        }
        return observedAtMs - firstObservedAtMs >= settleMs ? State.READY : State.WAITING;
    }

    public void reset() {
        observedScope = null;
        firstObservedAtMs = -1L;
    }
}
