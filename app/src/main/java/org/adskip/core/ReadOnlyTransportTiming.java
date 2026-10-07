package org.adskip.core;

/** One in-memory GET timing; deliberately contains no URL, identity, headers or response body. */
public final class ReadOnlyTransportTiming {
    public enum Phase { CACHE_HIT, AWAITING_RESPONSE, READING_BODY, COMPLETE }
    public enum Failure { NONE, TIMEOUT, NETWORK_ERROR, CANCELLED }

    private final Phase phase;
    private final Failure failure;
    private final int statusCode;
    private final long responseWaitMs;
    private final long bodyReadMs;

    private ReadOnlyTransportTiming(Phase phase, Failure failure, int statusCode,
            long responseWaitMs, long bodyReadMs) {
        if (phase == null || failure == null || responseWaitMs < 0 || bodyReadMs < 0
                || (phase == Phase.CACHE_HIT && (failure != Failure.NONE || statusCode != 0
                || responseWaitMs != 0 || bodyReadMs != 0))
                || (phase == Phase.AWAITING_RESPONSE && (failure == Failure.NONE
                || statusCode != 0 || bodyReadMs != 0))
                || ((phase == Phase.READING_BODY || phase == Phase.COMPLETE)
                && (statusCode != -1 && (statusCode < 100 || statusCode > 599)))
                || (phase == Phase.READING_BODY && failure == Failure.NONE)
                || (phase == Phase.COMPLETE && failure != Failure.NONE)) {
            throw new IllegalArgumentException("invalid transport timing");
        }
        this.phase = phase;
        this.failure = failure;
        this.statusCode = statusCode;
        this.responseWaitMs = responseWaitMs;
        this.bodyReadMs = bodyReadMs;
    }

    public static ReadOnlyTransportTiming cacheHit() {
        return new ReadOnlyTransportTiming(Phase.CACHE_HIT, Failure.NONE, 0, 0, 0);
    }

    public static ReadOnlyTransportTiming failedBeforeResponse(Failure failure, long waitMs) {
        return new ReadOnlyTransportTiming(Phase.AWAITING_RESPONSE, failure, 0, waitMs, 0);
    }

    public static ReadOnlyTransportTiming failedReadingBody(Failure failure, int statusCode,
            long responseWaitMs, long bodyReadMs) {
        return new ReadOnlyTransportTiming(Phase.READING_BODY, failure, statusCode,
                responseWaitMs, bodyReadMs);
    }

    public static ReadOnlyTransportTiming completed(int statusCode, long responseWaitMs,
            long bodyReadMs) {
        return new ReadOnlyTransportTiming(Phase.COMPLETE, Failure.NONE, statusCode,
                responseWaitMs, bodyReadMs);
    }

    public Phase getPhase() { return phase; }
    public Failure getFailure() { return failure; }
    public int getStatusCode() { return statusCode; }
    public long getResponseWaitMs() { return responseWaitMs; }
    public long getBodyReadMs() { return bodyReadMs; }

    public String report() {
        if (phase == Phase.CACHE_HIT) return "cache-hit";
        String status = statusCode == 0 ? "无状态码"
                : statusCode == -1 ? "不可识别响应码" : "HTTP " + statusCode;
        return phase + "/" + failure + "；" + status + "；响应码前="
                + responseWaitMs + "ms（含 DNS/TCP/TLS/首字节）；body=" + bodyReadMs + "ms";
    }
}
