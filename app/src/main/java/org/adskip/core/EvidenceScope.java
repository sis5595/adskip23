package org.adskip.core;

import java.util.Objects;

/** Session/BVID/generation scope for research evidence; it deliberately does not require a CID. */
public final class EvidenceScope {
    private final String sessionScope;
    private final String bvid;
    private final long generation;

    public EvidenceScope(String sessionScope, String bvid, long generation) {
        if (sessionScope == null || sessionScope.trim().isEmpty()
                || !sessionScope.equals(sessionScope.trim())) {
            throw new IllegalArgumentException("sessionScope must be nonblank and unpadded");
        }
        if (bvid == null || bvid.trim().isEmpty() || !bvid.equals(bvid.trim())) {
            throw new IllegalArgumentException("bvid must be nonblank and unpadded");
        }
        BiliIdCodec.bvidToAid(bvid);
        if (generation < 0L) {
            throw new IllegalArgumentException("generation must be non-negative");
        }
        this.sessionScope = sessionScope;
        this.bvid = bvid;
        this.generation = generation;
    }

    public String getSessionScope() { return sessionScope; }
    public String getBvid() { return bvid; }
    public long getGeneration() { return generation; }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof EvidenceScope)) return false;
        EvidenceScope that = (EvidenceScope) other;
        return generation == that.generation && sessionScope.equals(that.sessionScope)
                && bvid.equals(that.bvid);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sessionScope, bvid, generation);
    }

    @Override
    public String toString() {
        return sessionScope + ":" + bvid + ":g" + generation;
    }
}
