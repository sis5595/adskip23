package org.adskip.core;

import java.util.Objects;

/** Exact BVID + CID query request, scoped to one observed session generation. */
public final class SegmentRequest {
    private final SessionGeneration generation;

    public SegmentRequest(SessionGeneration generation) {
        this.generation = Objects.requireNonNull(generation, "generation");
    }

    public SessionGeneration getGeneration() { return generation; }
    public BiliVideoKey getVideoKey() { return generation.getVideoKey(); }
    public String getBvid() { return getVideoKey().getBvid(); }
    public String getCid() { return getVideoKey().getCidString(); }
}
