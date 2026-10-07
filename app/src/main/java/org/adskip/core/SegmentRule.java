package org.adskip.core;

import java.util.Objects;

/**
 * Validated immutable crowd rule, bound to one exact BVID + CID source.
 *
 * <p>The rule does not itself authorize a seek. {@link SegmentRuleSelector} additionally checks
 * the active source, source-duration drift and category policy before a rule reaches SkipEngine.</p>
 */
public final class SegmentRule {
    private final BiliVideoKey videoKey;
    private final long startMs;
    private final long endMs;
    private final long sourceDurationMs;
    private final String segmentId;
    private final String category;
    private final SegmentAction action;
    private final String source;
    private final String ruleVersion;

    public SegmentRule(
            BiliVideoKey videoKey,
            long startMs,
            long endMs,
            long sourceDurationMs,
            String segmentId,
            String category,
            SegmentAction action,
            String source,
            String ruleVersion) {
        this.videoKey = Objects.requireNonNull(videoKey, "videoKey");
        if (startMs < 0L || endMs <= startMs) {
            throw new IllegalArgumentException("segment must satisfy 0 <= start < end");
        }
        if (sourceDurationMs <= 0L || endMs > sourceDurationMs) {
            throw new IllegalArgumentException("segment must end within its positive source duration");
        }
        this.segmentId = requireText(segmentId, "segmentId");
        this.category = requireText(category, "category");
        this.action = Objects.requireNonNull(action, "action");
        this.source = requireText(source, "source");
        this.ruleVersion = requireText(ruleVersion, "ruleVersion");
        this.startMs = startMs;
        this.endMs = endMs;
        this.sourceDurationMs = sourceDurationMs;
    }

    public BiliVideoKey getVideoKey() {
        return videoKey;
    }

    public long getStartMs() {
        return startMs;
    }

    public long getEndMs() {
        return endMs;
    }

    public long getSourceDurationMs() {
        return sourceDurationMs;
    }

    public String getSegmentId() {
        return segmentId;
    }

    public String getCategory() {
        return category;
    }

    public SegmentAction getAction() {
        return action;
    }

    public String getSource() {
        return source;
    }

    public String getRuleVersion() {
        return ruleVersion;
    }

    /**
     * Checks source identity and both source-duration freshness and the live seek boundary.
     * A stale source that is within the nominal duration tolerance still cannot seek past its
     * actual current end.
     */
    public boolean isCompatibleWith(
            BiliVideoKey activeVideo, long activeDurationMs, long maxSourceDurationDriftMs) {
        if (!videoKey.equals(activeVideo) || activeDurationMs <= 0L || maxSourceDurationDriftMs < 0L) {
            return false;
        }
        long durationDelta = sourceDurationMs >= activeDurationMs
                ? sourceDurationMs - activeDurationMs
                : activeDurationMs - sourceDurationMs;
        return durationDelta <= maxSourceDurationDriftMs && endMs <= activeDurationMs;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.trim().isEmpty() || !value.equals(value.trim())) {
            throw new IllegalArgumentException(name + " must be nonblank and unpadded");
        }
        return value;
    }
}
