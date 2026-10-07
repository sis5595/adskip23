package org.adskip.probe;

import android.app.PendingIntent;
import android.os.Build;

import org.adskip.core.EvidenceFingerprint;

/** Sanitized, process-local shape of a MediaSession sessionActivity PendingIntent. */
public final class SessionActivitySnapshot {
    public enum State {
        ABSENT,
        TARGET_ACTIVITY,
        TARGET_OTHER_TYPE,
        OTHER_CREATOR,
        UNREADABLE
    }

    private final State state;
    private final String fingerprint;
    private final Boolean immutable;

    private SessionActivitySnapshot(State state, String fingerprint, Boolean immutable) {
        this.state = state;
        this.fingerprint = fingerprint;
        this.immutable = immutable;
    }

    public static SessionActivitySnapshot capture(
            PendingIntent pendingIntent, String expectedCreatorPackage) {
        if (pendingIntent == null) {
            return new SessionActivitySnapshot(
                    State.ABSENT, EvidenceFingerprint.sha256("session-activity-absent"), null);
        }
        try {
            String creator = pendingIntent.getCreatorPackage();
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                // Type inspection was added only in API 31. Never infer "activity" from
                // creator package or token shape on older devices.
                State state = !expectedCreatorPackage.equals(creator)
                        ? State.OTHER_CREATOR : State.UNREADABLE;
                String shape = String.valueOf(creator) + '|' + pendingIntent.getCreatorUid()
                        + "|type-unavailable-api<31|" + pendingIntent.hashCode();
                return new SessionActivitySnapshot(
                        state, EvidenceFingerprint.sha256(shape), null);
            }
            boolean activity = pendingIntent.isActivity();
            String type = activity ? "activity"
                    : pendingIntent.isBroadcast() ? "broadcast"
                    : pendingIntent.isService() ? "service"
                    : pendingIntent.isForegroundService() ? "foreground-service"
                    : "other";
            State state = !expectedCreatorPackage.equals(creator)
                    ? State.OTHER_CREATOR
                    : activity ? State.TARGET_ACTIVITY : State.TARGET_OTHER_TYPE;
            Boolean immutable = pendingIntent.isImmutable();
            // hashCode identifies the opaque system token only within this process. No inner
            // Intent, component, data URI, extras, or PendingIntent is retained or invoked.
            String shape = String.valueOf(creator) + '|' + pendingIntent.getCreatorUid()
                    + '|' + type + '|' + pendingIntent.hashCode()
                    + '|' + String.valueOf(immutable);
            return new SessionActivitySnapshot(
                    state, EvidenceFingerprint.sha256(shape), immutable);
        } catch (RuntimeException | LinkageError exception) {
            return new SessionActivitySnapshot(State.UNREADABLE,
                    EvidenceFingerprint.sha256("session-activity-unreadable|"
                            + exception.getClass().getSimpleName()), null);
        }
    }

    public static SessionActivitySnapshot unreadable(String failureClass) {
        String safeClass = failureClass == null ? "unknown"
                : failureClass.replaceAll("[^A-Za-z0-9_$]", "");
        return new SessionActivitySnapshot(State.UNREADABLE,
                EvidenceFingerprint.sha256("session-activity-unreadable|" + safeClass), null);
    }

    public State getState() { return state; }
    public String getFingerprint() { return fingerprint; }
    public Boolean getImmutable() { return immutable; }
}
