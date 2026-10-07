package org.adskip.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

/** Bounded, process-local lifecycle for explicit Request Analysis actions. */
public final class AnalysisRequestLedger {
    public enum Decision { NEW, DUPLICATE_RECENT, REVISIT_DUE }
    public enum Outcome { RECEIVED, ALREADY_AVAILABLE, FAILED }

    public static final class Entry {
        private final BiliVideoKey videoKey;
        private final long requestedAtElapsedMs;
        private final Outcome outcome;

        private Entry(BiliVideoKey videoKey, long requestedAtElapsedMs, Outcome outcome) {
            this.videoKey = videoKey;
            this.requestedAtElapsedMs = requestedAtElapsedMs;
            this.outcome = outcome;
        }
        public BiliVideoKey getVideoKey() { return videoKey; }
        public long getRequestedAtElapsedMs() { return requestedAtElapsedMs; }
        public Outcome getOutcome() { return outcome; }
    }

    private final int capacity;
    private final long revisitAfterMs;
    private final LinkedHashMap<BiliVideoKey, Entry> entries = new LinkedHashMap<>();

    public AnalysisRequestLedger(int capacity, long revisitAfterMs) {
        if (capacity <= 0 || revisitAfterMs < 0L) {
            throw new IllegalArgumentException("invalid ledger bounds");
        }
        this.capacity = capacity;
        this.revisitAfterMs = revisitAfterMs;
    }

    public synchronized Decision assess(BiliVideoKey videoKey, long nowElapsedMs) {
        Objects.requireNonNull(videoKey, "videoKey");
        if (nowElapsedMs < 0L) throw new IllegalArgumentException("time must be non-negative");
        Entry previous = entries.get(videoKey);
        if (previous == null) return Decision.NEW;
        long age = Math.max(0L, nowElapsedMs - previous.requestedAtElapsedMs);
        return age < revisitAfterMs ? Decision.DUPLICATE_RECENT : Decision.REVISIT_DUE;
    }

    public synchronized void record(BiliVideoKey videoKey, long nowElapsedMs, Outcome outcome) {
        Objects.requireNonNull(videoKey, "videoKey");
        Objects.requireNonNull(outcome, "outcome");
        if (nowElapsedMs < 0L) throw new IllegalArgumentException("time must be non-negative");
        entries.remove(videoKey);
        entries.put(videoKey, new Entry(videoKey, nowElapsedMs, outcome));
        while (entries.size() > capacity) {
            BiliVideoKey oldest = entries.keySet().iterator().next();
            entries.remove(oldest);
        }
    }

    public synchronized boolean clear(BiliVideoKey videoKey) {
        return entries.remove(Objects.requireNonNull(videoKey, "videoKey")) != null;
    }

    public synchronized void clearAll() { entries.clear(); }

    public synchronized List<Entry> snapshot() {
        return Collections.unmodifiableList(new ArrayList<>(entries.values()));
    }
}
