package org.adskip.core;

/** Evidence of a discontinuity, not merely an extrapolated position crossing a boundary. */
final class SeekReadback {
    private static final long DISCONTINUITY_MARGIN_MS = 250L;
    private SeekReadback() { }

    static boolean confirms(SkipEngine.PlaybackSnapshot before,
            SkipEngine.PlaybackSnapshot after, long targetMs, long toleranceMs) {
        // Attaching to an already playing session need not produce an initial callback.
        // The pre-dispatch estimate anchors the natural trajectory; evidence must come
        // from a publisher callback strictly newer than that dispatch snapshot.
        if (before == null || after == null || !after.hasPublisherPosition()
                || after.getPublisherUpdatedAtMs() <= before.getObservedAtElapsedMs()
                || !before.getSessionScope().equals(after.getSessionScope())
                || !before.getVideoKey().equals(after.getVideoKey())
                || Float.compare(before.getSpeed(), after.getSpeed()) != 0) return false;
        double elapsed = after.getPublisherUpdatedAtMs() - before.getObservedAtElapsedMs();
        double naturalAdvance = before.isPlaying() ? elapsed * before.getSpeed() : 0D;
        double naturalPosition = Math.min(before.getDurationMs(), before.getPositionMs() + naturalAdvance);
        double actual = after.getPublisherPositionMs();
        // A delayed seek can have landed any time since dispatch. Accept only its forward
        // playback envelope, and require movement distinguishable from uninterrupted playback.
        double upper = Math.min(after.getDurationMs(), targetMs + naturalAdvance + toleranceMs);
        boolean landing = actual >= Math.max(0L, targetMs - toleranceMs) && actual <= upper;
        double margin = Math.max(DISCONTINUITY_MARGIN_MS, toleranceMs);
        boolean jump = targetMs > before.getPositionMs()
                ? actual > naturalPosition + margin : actual < naturalPosition - margin;
        return landing && jump;
    }
}
