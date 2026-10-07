package org.adskip.probe;

import android.app.Notification;
import android.os.SystemClock;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import org.adskip.core.EvidenceFingerprint;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/** Research-only, bounded notification shape log. It never reads text or sends PendingIntents. */
public final class NotificationSurfaceLog {
    private static final int CAPACITY = 128;
    private static final Deque<Event> EVENTS = new ArrayDeque<>();
    private static long ordinal;

    private NotificationSurfaceLog() { }

    public static synchronized void record(StatusBarNotification sbn, boolean removed) {
        if (!BuildConfig.RESEARCH_NETWORK_ENABLED || sbn == null
                || !SessionProbe.BILIBILI_PACKAGE.equals(sbn.getPackageName())) return;
        Notification notification = sbn.getNotification();
        SessionActivitySnapshot content = pending(notification == null
                ? null : notification.contentIntent);
        SessionActivitySnapshot delete = pending(notification == null
                ? null : notification.deleteIntent);
        Notification.Action[] actions = notification == null ? null : notification.actions;
        int actionCount = actions == null ? 0 : actions.length;
        StringBuilder actionShape = new StringBuilder();
        if (actions != null) {
            for (Notification.Action action : actions) {
                SessionActivitySnapshot snapshot = pending(
                        action == null ? null : action.actionIntent);
                actionShape.append(snapshot.getState()).append('/')
                        .append(snapshot.getFingerprint()).append(';');
            }
        }
        int extrasKeyCount = 0;
        try {
            if (notification != null && notification.extras != null) {
                extrasKeyCount = notification.extras.keySet().size();
            }
        } catch (RuntimeException | LinkageError ignored) { }
        Event event = new Event(++ordinal, SystemClock.elapsedRealtime(),
                removed ? "REMOVED" : "POSTED",
                EvidenceFingerprint.sha256(String.valueOf(sbn.getKey())),
                content.getState().name(), content.getFingerprint(),
                delete.getState().name(), delete.getFingerprint(),
                actionCount, EvidenceFingerprint.sha256(actionShape.toString()), extrasKeyCount);
        if (EVENTS.size() == CAPACITY) EVENTS.removeFirst();
        EVENTS.addLast(event);
        Log.i("AdSkipNotificationShape", event.logLine());
    }

    private static SessionActivitySnapshot pending(android.app.PendingIntent value) {
        try {
            return SessionActivitySnapshot.capture(value, SessionProbe.BILIBILI_PACKAGE);
        } catch (RuntimeException | LinkageError exception) {
            return SessionActivitySnapshot.unreadable(exception.getClass().getSimpleName());
        }
    }

    public static synchronized List<Event> snapshot() { return new ArrayList<>(EVENTS); }
    public static synchronized void clear() { EVENTS.clear(); }

    public static final class Event {
        final long ordinal;
        final long elapsedMs;
        final String kind;
        final String notificationKeyFingerprint;
        final String contentState;
        final String contentFingerprint;
        final String deleteState;
        final String deleteFingerprint;
        final int actionCount;
        final String actionFingerprint;
        final int extrasKeyCount;

        Event(long ordinal, long elapsedMs, String kind, String notificationKeyFingerprint,
                String contentState, String contentFingerprint, String deleteState,
                String deleteFingerprint, int actionCount, String actionFingerprint,
                int extrasKeyCount) {
            this.ordinal = ordinal;
            this.elapsedMs = elapsedMs;
            this.kind = kind;
            this.notificationKeyFingerprint = notificationKeyFingerprint;
            this.contentState = contentState;
            this.contentFingerprint = contentFingerprint;
            this.deleteState = deleteState;
            this.deleteFingerprint = deleteFingerprint;
            this.actionCount = actionCount;
            this.actionFingerprint = actionFingerprint;
            this.extrasKeyCount = extrasKeyCount;
        }

        String logLine() {
            return "event=" + ordinal + " elapsed=" + elapsedMs + " kind=" + kind
                    + " key=" + notificationKeyFingerprint.substring(0, 12)
                    + " content=" + contentState + '/' + contentFingerprint.substring(0, 12)
                    + " delete=" + deleteState + '/' + deleteFingerprint.substring(0, 12)
                    + " actions=" + actionCount + '/' + actionFingerprint.substring(0, 12)
                    + " extrasKeys=" + extrasKeyCount;
        }
    }
}
