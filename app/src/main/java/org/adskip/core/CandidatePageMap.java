package org.adskip.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/** Sanitized, generation-scoped page→CID research map. It cannot create CidEvidence. */
public final class CandidatePageMap {
    public enum State {
        SUCCESS, EMPTY, MALFORMED, HTTP_ERROR, NETWORK_ERROR, TIMEOUT, CANCELLED, STALE_GENERATION
    }

    private final State state;
    private final EvidenceScope scope;
    private final List<CandidatePart> candidates;
    private final long observedAtElapsedMs;
    private final String sourceVersion;

    CandidatePageMap(State state, EvidenceScope scope, List<CandidatePart> candidates,
            long observedAtElapsedMs, String sourceVersion) {
        this.state = state;
        this.scope = scope;
        this.candidates = Collections.unmodifiableList(new ArrayList<>(candidates));
        this.observedAtElapsedMs = observedAtElapsedMs;
        this.sourceVersion = sourceVersion;
    }

    public State getState() { return state; }
    public EvidenceScope getScope() { return scope; }
    public List<CandidatePart> getCandidates() { return candidates; }
    public long getObservedAtElapsedMs() { return observedAtElapsedMs; }
    public String getSourceVersion() { return sourceVersion; }

    public Optional<CandidatePart> candidateForPage(long page) {
        if (state != State.SUCCESS || page <= 0L) return Optional.empty();
        CandidatePart found = null;
        for (CandidatePart candidate : candidates) {
            if (candidate.getPage() != page) continue;
            if (found != null) return Optional.empty();
            found = candidate;
        }
        return Optional.ofNullable(found);
    }

    public static CandidatePageMap failed(State state, EvidenceScope scope,
            long observedAtElapsedMs, String sourceVersion) {
        if (state == State.SUCCESS) throw new IllegalArgumentException("success needs candidates");
        return new CandidatePageMap(state, scope, Collections.emptyList(),
                observedAtElapsedMs, sourceVersion);
    }
}
