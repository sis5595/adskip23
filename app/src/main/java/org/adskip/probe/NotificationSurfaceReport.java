package org.adskip.probe;

import java.util.List;

/** Renders only sanitized notification shape fields from the research-only bounded log. */
public final class NotificationSurfaceReport {
    private NotificationSurfaceReport() { }

    public static String render(List<NotificationSurfaceLog.Event> events) {
        StringBuilder output = new StringBuilder(
                "P5N notification shape（research only；不读文字；不发送 PendingIntent）\n");
        if (events == null || events.isEmpty()) return output.append("events=0").toString();
        int from = Math.max(0, events.size() - 64);
        long baseline = events.get(from).elapsedMs;
        output.append("events=").append(events.size()).append("；showing=")
                .append(events.size() - from).append('\n');
        for (int index = from; index < events.size(); index++) {
            NotificationSurfaceLog.Event event = events.get(index);
            output.append('#').append(event.ordinal).append(" +")
                    .append(event.elapsedMs - baseline).append("ms ").append(event.kind)
                    .append(" key=").append(shortDigest(event.notificationKeyFingerprint))
                    .append(" content=").append(event.contentState).append('/')
                    .append(shortDigest(event.contentFingerprint))
                    .append(" delete=").append(event.deleteState).append('/')
                    .append(shortDigest(event.deleteFingerprint))
                    .append(" actions=").append(event.actionCount).append('/')
                    .append(shortDigest(event.actionFingerprint))
                    .append(" extrasKeys=").append(event.extrasKeyCount).append('\n');
        }
        return output.toString();
    }

    private static String shortDigest(String value) {
        return value.substring(0, Math.min(12, value.length()));
    }
}
