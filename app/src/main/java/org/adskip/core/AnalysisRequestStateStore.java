package org.adskip.core;

/**
 * Single-entry, process-local handoff for an explicit Request Analysis operation.
 *
 * <p>It solves Activity replacement/recreation without persistence. Stored values are already
 * sanitized domain objects: no URL, Cookie, account identity, title, or playback history.</p>
 */
public final class AnalysisRequestStateStore {
    public enum State { PENDING, COMPLETE }

    public static final class Snapshot {
        private final long epoch;
        private final long revision;
        private final State state;
        private final String summary;
        private final ShareLinkObservation observation;
        private final PageCatalog catalog;
        private final AnalysisRequestResolver.Result resolution;

        private Snapshot(long epoch, long revision, State state, String summary,
                ShareLinkObservation observation, PageCatalog catalog,
                AnalysisRequestResolver.Result resolution) {
            this.epoch = epoch;
            this.revision = revision;
            this.state = state;
            this.summary = summary;
            this.observation = observation;
            this.catalog = catalog;
            this.resolution = resolution;
        }

        public long getEpoch() { return epoch; }
        public long getRevision() { return revision; }
        public State getState() { return state; }
        public String getSummary() { return summary; }
        public ShareLinkObservation getObservation() { return observation; }
        public PageCatalog getCatalog() { return catalog; }
        public AnalysisRequestResolver.Result getResolution() { return resolution; }
    }

    private long nextEpoch;
    private Snapshot latest;

    public synchronized long begin() {
        long epoch = ++nextEpoch;
        latest = new Snapshot(epoch, epoch * 2L, State.PENDING,
                "请求分析：解析中（不等待 MediaSession）", null, null, null);
        return epoch;
    }

    /** Returns false for a stale callback or a duplicate terminal callback. */
    public synchronized boolean complete(long epoch, String summary,
            ShareLinkObservation observation, PageCatalog catalog,
            AnalysisRequestResolver.Result resolution) {
        if (latest == null || latest.epoch != epoch || latest.state != State.PENDING) return false;
        latest = new Snapshot(epoch, epoch * 2L + 1L, State.COMPLETE,
                summary == null ? "请求分析：transport 未返回摘要" : summary,
                observation, catalog, resolution);
        return true;
    }

    public synchronized Snapshot latest() {
        return latest;
    }

    /** Explicitly forgets the process-local Activity handoff without resetting epoch ordering. */
    public synchronized void clear() {
        latest = null;
    }
}
