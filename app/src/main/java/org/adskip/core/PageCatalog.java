package org.adskip.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/** Session-independent, sanitized BVID page catalog returned by a pagelist transport. */
public final class PageCatalog {
    public enum State { SUCCESS, EMPTY, MALFORMED, HTTP_ERROR, NETWORK_ERROR, TIMEOUT, CANCELLED }

    private final State state;
    private final String bvid;
    private final List<CandidatePart> candidates;
    private final long observedAtElapsedMs;
    private final String sourceVersion;

    PageCatalog(State state, String bvid, List<CandidatePart> candidates,
            long observedAtElapsedMs, String sourceVersion) {
        BiliIdCodec.bvidToAid(bvid);
        if (observedAtElapsedMs < 0L || sourceVersion == null || sourceVersion.trim().isEmpty()) {
            throw new IllegalArgumentException("invalid page catalog context");
        }
        this.state = state;
        this.bvid = bvid;
        this.candidates = Collections.unmodifiableList(new ArrayList<>(candidates));
        this.observedAtElapsedMs = observedAtElapsedMs;
        this.sourceVersion = sourceVersion;
    }

    public static PageCatalog failed(State state, String bvid, long time, String version) {
        if (state == State.SUCCESS) throw new IllegalArgumentException("success needs candidates");
        return new PageCatalog(state, bvid, Collections.emptyList(), time, version);
    }

    public State getState() { return state; }
    public String getBvid() { return bvid; }
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
}
