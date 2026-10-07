package org.adskip.core;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

/** One sanitized, monotonic observation from a platform MediaController callback or scan. */
public final class SessionTransitionEvent {
    public enum Kind {
        SESSION_ADDED,
        SCAN_REFRESH,
        STABILITY_TIMER,
        METADATA_CALLBACK,
        PLAYBACK_CALLBACK,
        QUEUE_CALLBACK,
        EXTRAS_CALLBACK,
        SESSION_DESTROYED,
        SESSION_REMOVED
    }

    /** Which public metadata field produced the displayed P marker; never raw text. */
    public enum MarkerSource {
        NONE,
        METADATA_TITLE,
        METADATA_DISPLAY_TITLE,
        METADATA_DISPLAY_SUBTITLE,
        METADATA_DISPLAY_DESCRIPTION,
        DESCRIPTION_TITLE_DERIVED,
        DESCRIPTION_SUBTITLE_DERIVED,
        DESCRIPTION_TEXT_DERIVED
    }

    private final Kind kind;
    private final long observedAtElapsedMs;
    private final long connectionEpoch;
    private final String tokenFingerprint;
    private final long generationBefore;
    private final long generationAfter;
    private final String bvid;
    private final String payloadFingerprint;
    private final long callbackReadLatencyMs;
    private final Long sourceAgeMs;
    private final Integer playbackState;
    private final Integer metadataKeyCount;
    private final Integer queueSize;
    private final Integer extrasKeyCount;
    private final String sessionActivityState;
    private final String sessionActivityFingerprint;
    private final Long displayedPage;
    private final Long displayedTotal;
    private final MarkerSource markerSource;
    private final String reason;

    public SessionTransitionEvent(Kind kind, long observedAtElapsedMs, long connectionEpoch,
            String tokenFingerprint, long generationBefore, long generationAfter, String bvid,
            String payloadFingerprint, long callbackReadLatencyMs, Long sourceAgeMs,
            Integer playbackState, Integer metadataKeyCount, Integer queueSize,
            Integer extrasKeyCount, String sessionActivityState,
            String sessionActivityFingerprint, Long displayedPage,
            Long displayedTotal, MarkerSource markerSource, String reason) {
        this.kind = Objects.requireNonNull(kind, "kind");
        if (observedAtElapsedMs < 0L || connectionEpoch < 0L || generationBefore < 0L
                || generationAfter < 0L || callbackReadLatencyMs < 0L
                || sourceAgeMs != null && sourceAgeMs < 0L) {
            throw new IllegalArgumentException("transition times/generations must be non-negative");
        }
        requireDigest(tokenFingerprint, "tokenFingerprint");
        requireDigest(payloadFingerprint, "payloadFingerprint");
        requireDigest(sessionActivityFingerprint, "sessionActivityFingerprint");
        if (bvid != null) BiliIdCodec.bvidToAid(bvid);
        if (metadataKeyCount != null && metadataKeyCount < 0
                || queueSize != null && queueSize < -1
                || extrasKeyCount != null && extrasKeyCount < 0) {
            throw new IllegalArgumentException("invalid transition shape count");
        }
        if (reason == null || reason.trim().isEmpty() || reason.length() > 96) {
            throw new IllegalArgumentException("invalid transition reason");
        }
        if (sessionActivityState == null
                || !sessionActivityState.matches("ABSENT|TARGET_ACTIVITY|TARGET_OTHER_TYPE|OTHER_CREATOR|UNREADABLE")) {
            throw new IllegalArgumentException("invalid sessionActivityState");
        }
        if ((displayedPage == null) != (displayedTotal == null)
                || displayedPage != null && (displayedPage <= 0L
                || displayedTotal <= 0L || displayedPage > displayedTotal)) {
            throw new IllegalArgumentException("invalid displayed part marker");
        }
        this.markerSource = Objects.requireNonNull(markerSource, "markerSource");
        if ((displayedPage == null) != (markerSource == MarkerSource.NONE)) {
            throw new IllegalArgumentException("marker source must match marker presence");
        }
        this.observedAtElapsedMs = observedAtElapsedMs;
        this.connectionEpoch = connectionEpoch;
        this.tokenFingerprint = tokenFingerprint;
        this.generationBefore = generationBefore;
        this.generationAfter = generationAfter;
        this.bvid = bvid;
        this.payloadFingerprint = payloadFingerprint;
        this.callbackReadLatencyMs = callbackReadLatencyMs;
        this.sourceAgeMs = sourceAgeMs;
        this.playbackState = playbackState;
        this.metadataKeyCount = metadataKeyCount;
        this.queueSize = queueSize;
        this.extrasKeyCount = extrasKeyCount;
        this.sessionActivityState = sessionActivityState;
        this.sessionActivityFingerprint = sessionActivityFingerprint;
        this.displayedPage = displayedPage;
        this.displayedTotal = displayedTotal;
        this.reason = reason;
    }

    public Kind getKind() { return kind; }
    public long getObservedAtElapsedMs() { return observedAtElapsedMs; }
    public long getConnectionEpoch() { return connectionEpoch; }
    public String getTokenFingerprint() { return tokenFingerprint; }
    public long getGenerationBefore() { return generationBefore; }
    public long getGenerationAfter() { return generationAfter; }
    public Optional<String> getBvid() { return Optional.ofNullable(bvid); }
    public String getPayloadFingerprint() { return payloadFingerprint; }
    public long getCallbackReadLatencyMs() { return callbackReadLatencyMs; }
    public OptionalLong getSourceAgeMs() {
        return sourceAgeMs == null ? OptionalLong.empty() : OptionalLong.of(sourceAgeMs);
    }
    public OptionalInt getPlaybackState() { return optional(playbackState); }
    public OptionalInt getMetadataKeyCount() { return optional(metadataKeyCount); }
    /** -1 means the producer explicitly returned a null queue. */
    public OptionalInt getQueueSize() { return optional(queueSize); }
    public OptionalInt getExtrasKeyCount() { return optional(extrasKeyCount); }
    public String getSessionActivityState() { return sessionActivityState; }
    public String getSessionActivityFingerprint() { return sessionActivityFingerprint; }
    public OptionalLong getDisplayedPage() {
        return displayedPage == null ? OptionalLong.empty() : OptionalLong.of(displayedPage);
    }
    public OptionalLong getDisplayedTotal() {
        return displayedTotal == null ? OptionalLong.empty() : OptionalLong.of(displayedTotal);
    }
    public MarkerSource getMarkerSource() { return markerSource; }
    public String getReason() { return reason; }

    private static OptionalInt optional(Integer value) {
        return value == null ? OptionalInt.empty() : OptionalInt.of(value);
    }

    private static void requireDigest(String value, String name) {
        if (value == null || !value.matches("[0-9a-f]{8,64}")) {
            throw new IllegalArgumentException(name + " must be an 8-64 lowercase hex digest");
        }
    }
}
