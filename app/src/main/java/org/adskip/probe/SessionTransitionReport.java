package org.adskip.probe;

import org.adskip.core.SessionTransitionEvent;

import java.util.List;
import java.util.OptionalInt;
import java.util.OptionalLong;

/** Sanitized rendering of the bounded, process-local MediaController callback corpus. */
public final class SessionTransitionReport {
    private static final int MAX_RENDERED = 64;

    private SessionTransitionReport() { }

    public static String render(List<SessionTransitionEvent> events) {
        StringBuilder output = new StringBuilder();
        output.append("P5T callback 时序（elapsedRealtime；仅进程内；无标题/URL）\n");
        if (events == null || events.isEmpty()) return output.append("events=0").toString();
        int from = Math.max(0, events.size() - MAX_RENDERED);
        long baseline = events.get(from).getObservedAtElapsedMs();
        output.append("events=").append(events.size())
                .append("；showing=").append(events.size() - from).append('\n');
        for (int index = from; index < events.size(); index++) {
            SessionTransitionEvent event = events.get(index);
            output.append('#').append(index + 1)
                    .append(" +").append(event.getObservedAtElapsedMs() - baseline).append("ms ")
                    .append(event.getKind())
                    .append(" token=").append(shortDigest(event.getTokenFingerprint()))
                    .append(" epoch=").append(event.getConnectionEpoch())
                    .append(" gen=").append(event.getGenerationBefore()).append("→")
                    .append(event.getGenerationAfter())
                    .append(" bvid=").append(event.getBvid().orElse("-"))
                    .append(" read=").append(event.getCallbackReadLatencyMs()).append("ms")
                    .append(" sourceAge=").append(optional(event.getSourceAgeMs()))
                    .append(" state=").append(optional(event.getPlaybackState()))
                    .append(" metadataKeys=").append(optional(event.getMetadataKeyCount()))
                    .append(" queue=").append(optional(event.getQueueSize()))
                    .append(" extras=").append(optional(event.getExtrasKeyCount()))
                    .append(" sessionActivity=").append(event.getSessionActivityState())
                    .append('/').append(shortDigest(event.getSessionActivityFingerprint()))
                    .append(" marker=").append(marker(event))
                    .append('@').append(event.getMarkerSource())
                    .append(" payload=").append(shortDigest(event.getPayloadFingerprint()))
                    .append(" reason=").append(event.getReason()).append('\n');
        }
        return output.toString();
    }

    private static String optional(OptionalLong value) {
        return value.isPresent() ? Long.toString(value.getAsLong()) + "ms" : "-";
    }

    private static String optional(OptionalInt value) {
        return value.isPresent() ? Integer.toString(value.getAsInt()) : "-";
    }

    private static String shortDigest(String digest) {
        return digest.substring(0, Math.min(12, digest.length()));
    }

    private static String marker(SessionTransitionEvent event) {
        return event.getDisplayedPage().isPresent()
                ? event.getDisplayedPage().getAsLong() + "/"
                        + event.getDisplayedTotal().getAsLong()
                : "-";
    }
}
