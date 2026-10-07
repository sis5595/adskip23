package org.adskip.core;

import java.util.Objects;

/** Exact user-request target derived from a share; deliberately has no playback session scope. */
public final class AnalysisRequestTarget {
    private final BiliVideoKey videoKey;
    private final long page;
    private final String shareFingerprint;
    private final String sourceVersion;

    AnalysisRequestTarget(BiliVideoKey videoKey, long page, String shareFingerprint,
            String sourceVersion) {
        this.videoKey = Objects.requireNonNull(videoKey, "videoKey");
        if (page <= 0L || shareFingerprint == null || !shareFingerprint.matches("[0-9a-f]{64}")
                || sourceVersion == null || sourceVersion.trim().isEmpty()) {
            throw new IllegalArgumentException("invalid analysis request target");
        }
        this.page = page;
        this.shareFingerprint = shareFingerprint;
        this.sourceVersion = sourceVersion;
    }

    public BiliVideoKey getVideoKey() { return videoKey; }
    public long getPage() { return page; }
    public String getShareFingerprint() { return shareFingerprint; }
    public String getSourceVersion() { return sourceVersion; }
}
