package org.adskip.core;

/** Recognizes a fresh end-to-start playback transition without treating ordinary rewinds as replay. */
public final class PlaybackCycleGate {
    private SkipEngine.PlaybackSnapshot previous;

    public void clear() { previous = null; }

    public boolean observe(SkipEngine.PlaybackSnapshot current, boolean controlPending) {
        SkipEngine.PlaybackSnapshot before = previous;
        previous = current;
        if (controlPending || current == null || before == null
                || !current.isPlaying() || !before.isPlaying()
                || !current.isSeekable() || !current.hasPublisherPosition()
                || !current.getSessionScope().equals(before.getSessionScope())
                || !current.getVideoKey().equals(before.getVideoKey())
                || current.getDurationMs() != before.getDurationMs()
                || Float.compare(current.getSpeed(), before.getSpeed()) != 0
                || current.getDurationMs() < 30_000L) return false;
        long published = current.getPublisherUpdatedAtMs();
        long elapsed = published - before.getObservedAtElapsedMs();
        long freshness = current.getObservedAtElapsedMs() - published;
        if (elapsed <= 0L || elapsed > 65_000L || freshness < 0L || freshness > 5_000L
                || current.getPublisherPositionMs() > 3_000L
                || current.getPositionMs() > 15_000L
                || before.getPositionMs() - current.getPublisherPositionMs() < 20_000L) return false;
        double predicted = before.getPositionMs() + elapsed * (double) before.getSpeed();
        // A new raw callback near zero must arrive when the previous trajectory reached the end.
        return Math.abs(predicted - current.getDurationMs()) <= 5_000D;
    }
}
