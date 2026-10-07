package org.adskip.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

/**
 * Bounded in-memory audit only. It intentionally contains no title, playback trajectory,
 * notification body, cookie, or account information.
 */
public final class LocalAuditLog {
    public enum Status { ATTEMPTED, CONFIRMED, FAILED, UNDO, REJECTED }

    public static final class Entry {
        private final long timestampElapsedMs;
        private final String bvid;
        private final String cid;
        private final String segmentId;
        private final String category;
        private final long startMs;
        private final long endMs;
        private final Status status;
        private final String failClosedReason;
        private final String source;
        private final String version;
        private final List<SegmentRule> rules;
        private final long skippedDurationMs;

        private Entry(long timestampElapsedMs, SegmentRule rule, Status status, String reason,
                String bvid, String cid, String source, String version,
                List<SegmentRule> rules, long skippedDurationMs) {
            this.timestampElapsedMs = timestampElapsedMs;
            this.bvid = bvid;
            this.cid = cid;
            this.segmentId = rule == null ? null : rule.getSegmentId();
            this.category = rule == null ? null : rule.getCategory();
            this.startMs = rule == null ? -1L : rule.getStartMs();
            this.endMs = rule == null ? -1L : rule.getEndMs();
            this.status = status;
            this.failClosedReason = reason;
            this.source = source;
            this.version = version;
            this.rules = Collections.unmodifiableList(new ArrayList<>(rules));
            this.skippedDurationMs = skippedDurationMs;
        }
        public long getTimestampElapsedMs() { return timestampElapsedMs; }
        public Optional<String> getBvid() { return Optional.ofNullable(bvid); }
        public Optional<String> getCid() { return Optional.ofNullable(cid); }
        public Optional<String> getSegmentId() { return Optional.ofNullable(segmentId); }
        public Optional<String> getCategory() { return Optional.ofNullable(category); }
        public long getStartMs() { return startMs; }
        public long getEndMs() { return endMs; }
        public Status getStatus() { return status; }
        public Optional<String> getFailClosedReason() { return Optional.ofNullable(failClosedReason); }
        public Optional<String> getSource() { return Optional.ofNullable(source); }
        public Optional<String> getVersion() { return Optional.ofNullable(version); }
        public List<SegmentRule> getRules() { return rules; }
        public long getSkippedDurationMs() { return skippedDurationMs; }
    }

    private final int capacity;
    private final Deque<Entry> entries = new ArrayDeque<>();

    public LocalAuditLog(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("capacity must be positive");
        this.capacity = capacity;
    }

    public void record(long timestampElapsedMs, SegmentRule rule, Status status, String reason) {
        record(timestampElapsedMs, rule, status, reason, Collections.singletonList(rule),
                rule == null ? 0L : rule.getEndMs() - rule.getStartMs());
    }

    public void record(long timestampElapsedMs, SegmentRule rule, Status status, String reason,
            List<SegmentRule> members, long skippedDurationMs) {
        if (timestampElapsedMs < 0L || rule == null || status == null) {
            throw new IllegalArgumentException("audit record requires timestamp, rule and status");
        }
        add(new Entry(timestampElapsedMs, rule, status, reason, rule.getVideoKey().getBvid(),
                rule.getVideoKey().getCidString(), rule.getSource(), rule.getRuleVersion(),
                members, Math.max(0L, skippedDurationMs)));
    }

    public void recordRejected(long timestampElapsedMs, String bvid, String reason,
            String source, String version) {
        if (timestampElapsedMs < 0L || bvid == null || reason == null) {
            throw new IllegalArgumentException("rejected audit record requires timestamp, bvid and reason");
        }
        BiliIdCodec.bvidToAid(bvid);
        add(new Entry(timestampElapsedMs, null, Status.REJECTED, reason, bvid, null, source, version,
                Collections.emptyList(), 0L));
    }

    public List<Entry> entries() {
        return Collections.unmodifiableList(new ArrayList<>(entries));
    }

    private void add(Entry entry) {
        if (entries.size() == capacity) entries.removeFirst();
        entries.addLast(entry);
    }
}
