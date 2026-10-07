package org.adskip.core;

import java.util.Optional;

/** Sanitized transport outcome: it never retains any raw URL, header, body, or account data. */
public final class ShortLinkExpansionResult {
    public enum State {
        SUCCESS,
        INVALID_INPUT,
        TIMEOUT,
        CANCELLED,
        NETWORK_ERROR,
        INVALID_REDIRECT,
        TOO_MANY_REDIRECTS,
        UNSUPPORTED_FINAL,
        STALE_GENERATION
    }

    private final State state;
    private final ShareLinkObservation observation;
    private final int redirectCount;
    private final String requestFingerprint;

    private ShortLinkExpansionResult(State state, ShareLinkObservation observation,
            int redirectCount, String requestFingerprint) {
        if (state == State.SUCCESS && (observation == null
                || observation.getState() != ShareLinkObservation.State.DIRECT)) {
            throw new IllegalArgumentException("success requires one direct observation");
        }
        if (state != State.SUCCESS && observation != null) {
            throw new IllegalArgumentException("failed outcome cannot expose an observation");
        }
        if (redirectCount < 0) throw new IllegalArgumentException("negative redirectCount");
        this.state = state;
        this.observation = observation;
        this.redirectCount = redirectCount;
        this.requestFingerprint = requestFingerprint;
    }

    public static ShortLinkExpansionResult success(ShareLinkObservation observation,
            int redirects, String fingerprint) {
        return new ShortLinkExpansionResult(State.SUCCESS, observation, redirects, fingerprint);
    }

    public static ShortLinkExpansionResult failed(State state, int redirects, String fingerprint) {
        if (state == State.SUCCESS) throw new IllegalArgumentException("use success factory");
        return new ShortLinkExpansionResult(state, null, redirects, fingerprint);
    }

    public State getState() { return state; }
    public Optional<ShareLinkObservation> getObservation() {
        return Optional.ofNullable(observation);
    }
    public int getRedirectCount() { return redirectCount; }
    public String getRequestFingerprint() { return requestFingerprint; }
}
