package org.adskip.core;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.regex.Pattern;

/**
 * One bounded, sanitized research observation. Presence of a CID here is data, not trust: this
 * type has no conversion to {@link CidEvidence} and cannot publish segment rules.
 */
public final class CurrentPartEvidence {
    public enum Freshness { UNKNOWN, FRESH, STALE }

    private static final Pattern DIGEST = Pattern.compile("[0-9a-f]{8,64}");

    private final EvidenceScope scope;
    private final EvidenceSource source;
    private final long observedAtElapsedMs;
    private final Long page;
    private final Long totalParts;
    private final String cid;
    private final String labelFingerprint;
    private final Long durationMs;
    private final Long positionMs;
    private final String rawFingerprint;
    private final Freshness freshness;
    private final Long latencyMs;
    private final String lineageFingerprint;

    private CurrentPartEvidence(Builder builder) {
        this.scope = Objects.requireNonNull(builder.scope, "scope");
        this.source = Objects.requireNonNull(builder.source, "source");
        if (builder.observedAtElapsedMs < 0L) {
            throw new IllegalArgumentException("observedAtElapsedMs must be non-negative");
        }
        if (builder.page != null && builder.page <= 0L) {
            throw new IllegalArgumentException("page must be positive");
        }
        if (builder.totalParts != null && builder.totalParts <= 0L) {
            throw new IllegalArgumentException("totalParts must be positive");
        }
        if (builder.page != null && builder.totalParts != null && builder.page > builder.totalParts) {
            throw new IllegalArgumentException("page cannot exceed totalParts");
        }
        if (builder.cid != null) {
            new BiliVideoKey(scope.getBvid(), builder.cid);
        }
        requireDigest(builder.labelFingerprint, "labelFingerprint");
        requireDigest(builder.rawFingerprint, "rawFingerprint");
        requireDigest(builder.lineageFingerprint, "lineageFingerprint");
        if (builder.durationMs != null && builder.durationMs <= 0L) {
            throw new IllegalArgumentException("durationMs must be positive");
        }
        if (builder.positionMs != null && builder.positionMs < 0L) {
            throw new IllegalArgumentException("positionMs must be non-negative");
        }
        if (builder.latencyMs != null && builder.latencyMs < 0L) {
            throw new IllegalArgumentException("latencyMs must be non-negative");
        }
        this.observedAtElapsedMs = builder.observedAtElapsedMs;
        this.page = builder.page;
        this.totalParts = builder.totalParts;
        this.cid = builder.cid;
        this.labelFingerprint = builder.labelFingerprint;
        this.durationMs = builder.durationMs;
        this.positionMs = builder.positionMs;
        this.rawFingerprint = builder.rawFingerprint;
        this.freshness = Objects.requireNonNull(builder.freshness, "freshness");
        this.latencyMs = builder.latencyMs;
        this.lineageFingerprint = builder.lineageFingerprint;
    }

    public static Builder builder(EvidenceScope scope, EvidenceSource source,
            long observedAtElapsedMs) {
        return new Builder(scope, source, observedAtElapsedMs);
    }

    public EvidenceScope getScope() { return scope; }
    public EvidenceSource getSource() { return source; }
    public long getObservedAtElapsedMs() { return observedAtElapsedMs; }
    public OptionalLong getPage() { return optional(page); }
    public OptionalLong getTotalParts() { return optional(totalParts); }
    public Optional<String> getCid() { return Optional.ofNullable(cid); }
    public Optional<String> getLabelFingerprint() { return Optional.ofNullable(labelFingerprint); }
    public OptionalLong getDurationMs() { return optional(durationMs); }
    public OptionalLong getPositionMs() { return optional(positionMs); }
    public Optional<String> getRawFingerprint() { return Optional.ofNullable(rawFingerprint); }
    public Freshness getFreshness() { return freshness; }
    public OptionalLong getLatencyMs() { return optional(latencyMs); }
    public Optional<String> getLineageFingerprint() {
        return Optional.ofNullable(lineageFingerprint);
    }

    /** True only when this observation can reduce a candidate set; it still cannot authorize. */
    public boolean hasCandidateConstraint() {
        return page != null || totalParts != null || cid != null || labelFingerprint != null
                || durationMs != null;
    }

    private static OptionalLong optional(Long value) {
        return value == null ? OptionalLong.empty() : OptionalLong.of(value);
    }

    private static void requireDigest(String value, String name) {
        if (value != null && !DIGEST.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " must be an 8-64 lowercase hex digest");
        }
    }

    public static final class Builder {
        private final EvidenceScope scope;
        private final EvidenceSource source;
        private final long observedAtElapsedMs;
        private Long page;
        private Long totalParts;
        private String cid;
        private String labelFingerprint;
        private Long durationMs;
        private Long positionMs;
        private String rawFingerprint;
        private Freshness freshness = Freshness.UNKNOWN;
        private Long latencyMs;
        private String lineageFingerprint;

        private Builder(EvidenceScope scope, EvidenceSource source, long observedAtElapsedMs) {
            this.scope = scope;
            this.source = source;
            this.observedAtElapsedMs = observedAtElapsedMs;
        }
        public Builder page(long value) { page = value; return this; }
        public Builder totalParts(long value) { totalParts = value; return this; }
        public Builder cid(String value) { cid = value; return this; }
        public Builder labelFingerprint(String value) { labelFingerprint = value; return this; }
        public Builder durationMs(long value) { durationMs = value; return this; }
        public Builder positionMs(long value) { positionMs = value; return this; }
        public Builder rawFingerprint(String value) { rawFingerprint = value; return this; }
        public Builder freshness(Freshness value) { freshness = value; return this; }
        public Builder latencyMs(long value) { latencyMs = value; return this; }
        public Builder lineageFingerprint(String value) {
            lineageFingerprint = value;
            return this;
        }
        public CurrentPartEvidence build() { return new CurrentPartEvidence(this); }
    }
}
