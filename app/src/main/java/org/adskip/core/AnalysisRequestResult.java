package org.adskip.core;

/** Finite, transport-neutral result for the future CandidatePool request endpoint. */
public final class AnalysisRequestResult {
    public enum State { RECEIVED, ALREADY_AVAILABLE, REJECTED, TIMEOUT, CANCELLED, UNAVAILABLE }

    private final State state;
    private final String sourceVersion;

    public AnalysisRequestResult(State state, String sourceVersion) {
        if (state == null || sourceVersion == null || sourceVersion.trim().isEmpty()) {
            throw new IllegalArgumentException("invalid analysis request result");
        }
        this.state = state;
        this.sourceVersion = sourceVersion;
    }

    public State getState() { return state; }
    public String getSourceVersion() { return sourceVersion; }
}
