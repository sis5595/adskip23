package org.adskip.core;

import java.util.Optional;

/** One externally supplied BVID page/CID candidate for research-only set intersection. */
public final class CandidatePart {
    private final BiliVideoKey videoKey;
    private final long page;
    private final long totalParts;
    private final long durationMs;
    private final String labelFingerprint;

    public CandidatePart(String bvid, String cid, long page, long totalParts, long durationMs,
            String labelFingerprint) {
        this.videoKey = new BiliVideoKey(bvid, cid);
        if (page <= 0L || totalParts <= 0L || page > totalParts || durationMs <= 0L) {
            throw new IllegalArgumentException("invalid candidate part");
        }
        if (labelFingerprint != null
                && !labelFingerprint.matches("[0-9a-f]{8,64}")) {
            throw new IllegalArgumentException("labelFingerprint must be an 8-64 lowercase hex digest");
        }
        this.page = page;
        this.totalParts = totalParts;
        this.durationMs = durationMs;
        this.labelFingerprint = labelFingerprint;
    }

    public BiliVideoKey getVideoKey() { return videoKey; }
    public long getPage() { return page; }
    public long getTotalParts() { return totalParts; }
    public long getDurationMs() { return durationMs; }
    public Optional<String> getLabelFingerprint() { return Optional.ofNullable(labelFingerprint); }
}
