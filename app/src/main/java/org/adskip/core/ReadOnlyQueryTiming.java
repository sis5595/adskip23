package org.adskip.core;

/** Process-local, monotonic timing for one read-only lookup. No identity or playback history. */
public final class ReadOnlyQueryTiming {
    private final long queuedAtMs;
    private final long workerAtMs;
    private final long catalogAtMs;
    private final long resolvedAtMs;
    private final long dataAtMs;
    private final long deliveredAtMs;
    private final boolean dataRequested;

    public ReadOnlyQueryTiming(long queuedAtMs, long workerAtMs, long catalogAtMs,
            long resolvedAtMs, long dataAtMs, long deliveredAtMs, boolean dataRequested) {
        if (queuedAtMs < 0 || workerAtMs < queuedAtMs || catalogAtMs < workerAtMs
                || resolvedAtMs < catalogAtMs || dataAtMs < resolvedAtMs
                || deliveredAtMs < dataAtMs || (!dataRequested && dataAtMs != resolvedAtMs)) {
            throw new IllegalArgumentException("non-monotonic query timing");
        }
        this.queuedAtMs = queuedAtMs;
        this.workerAtMs = workerAtMs;
        this.catalogAtMs = catalogAtMs;
        this.resolvedAtMs = resolvedAtMs;
        this.dataAtMs = dataAtMs;
        this.deliveredAtMs = deliveredAtMs;
        this.dataRequested = dataRequested;
    }

    public long queueMs() { return workerAtMs - queuedAtMs; }
    public long catalogMs() { return catalogAtMs - workerAtMs; }
    public long resolverMs() { return resolvedAtMs - catalogAtMs; }
    public long dataMs() { return dataAtMs - resolvedAtMs; }
    public long mainHandoffMs() { return deliveredAtMs - dataAtMs; }
    public long totalMs() { return deliveredAtMs - queuedAtMs; }
    public boolean dataRequested() { return dataRequested; }

    /** Negative means the earliest rule started before publication; diagnostic only. */
    public static long earliestRuleLeadMs(long currentPositionMs, java.util.List<SegmentRule> rules) {
        if (currentPositionMs < 0 || rules == null || rules.isEmpty()) return Long.MIN_VALUE;
        long earliest = Long.MAX_VALUE;
        for (SegmentRule rule : rules) earliest = Math.min(earliest, rule.getStartMs());
        return earliest - currentPositionMs;
    }
}
