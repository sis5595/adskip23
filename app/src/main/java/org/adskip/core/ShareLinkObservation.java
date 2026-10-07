package org.adskip.core;

import java.util.Optional;
import java.util.OptionalLong;

/** Sanitized result of parsing user-supplied share text; raw URLs are never retained. */
public final class ShareLinkObservation {
    public enum State {
        DIRECT,
        SHORT_LINK_NEEDS_EXPANSION,
        UNSUPPORTED,
        MALFORMED,
        CONFLICT
    }

    private final State state;
    private final String bvid;
    private final Long page;
    private final Long positionMs;
    private final String inputFingerprint;
    private final int recognizedUrlCount;

    ShareLinkObservation(State state, String bvid, Long page, Long positionMs,
            String inputFingerprint, int recognizedUrlCount) {
        this.state = state;
        this.bvid = bvid;
        this.page = page;
        this.positionMs = positionMs;
        this.inputFingerprint = inputFingerprint;
        this.recognizedUrlCount = recognizedUrlCount;
    }

    /** Creates a direct observation after a transport has independently resolved a safe locator. */
    public static ShareLinkObservation resolvedTransport(String bvid, long page,
            String inputFingerprint) {
        BiliIdCodec.bvidToAid(bvid);
        if (page <= 0L || inputFingerprint == null
                || !inputFingerprint.matches("[0-9a-f]{8,64}")) {
            throw new IllegalArgumentException("invalid resolved transport observation");
        }
        return new ShareLinkObservation(State.DIRECT, bvid, page, null,
                inputFingerprint, 1);
    }

    public State getState() { return state; }
    public Optional<String> getBvid() { return Optional.ofNullable(bvid); }
    public OptionalLong getPage() { return optional(page); }
    public OptionalLong getPositionMs() { return optional(positionMs); }
    public String getInputFingerprint() { return inputFingerprint; }
    public int getRecognizedUrlCount() { return recognizedUrlCount; }

    /**
     * Converts only a matching direct link into research evidence. This method cannot produce
     * CidEvidence, and a link without an explicit page remains a non-constraining timeline event.
     */
    public Optional<CurrentPartEvidence> toEvidence(EvidenceScope scope, long observedAtElapsedMs) {
        if (scope == null || state != State.DIRECT || bvid == null
                || !bvid.equals(scope.getBvid())) {
            return Optional.empty();
        }
        CurrentPartEvidence.Builder builder = CurrentPartEvidence.builder(
                        scope, EvidenceSource.SHARE_LINK, observedAtElapsedMs)
                .rawFingerprint(inputFingerprint)
                .lineageFingerprint(inputFingerprint)
                .freshness(CurrentPartEvidence.Freshness.FRESH);
        if (page != null) builder.page(page);
        if (positionMs != null) builder.positionMs(positionMs);
        return Optional.of(builder.build());
    }

    private static OptionalLong optional(Long value) {
        return value == null ? OptionalLong.empty() : OptionalLong.of(value);
    }
}
