package org.adskip.core;

import java.util.UUID;

/** Process-only, one-shot authorization to attempt an already-confirmed Undo. */
public final class UndoNotificationLease {
    private String token;
    private long expiresAtElapsedMs;

    public String arm(long nowElapsedMs, long windowMs) {
        if (nowElapsedMs < 0L || windowMs <= 0L || nowElapsedMs > Long.MAX_VALUE - windowMs) {
            throw new IllegalArgumentException("invalid notification window");
        }
        token = UUID.randomUUID().toString();
        expiresAtElapsedMs = nowElapsedMs + windowMs;
        return token;
    }

    public boolean consume(String candidate, long nowElapsedMs) {
        if (candidate == null || token == null || !token.equals(candidate)
                || nowElapsedMs < 0L || nowElapsedMs > expiresAtElapsedMs) return false;
        invalidate();
        return true;
    }

    public boolean active(long nowElapsedMs) {
        return token != null && nowElapsedMs >= 0L && nowElapsedMs <= expiresAtElapsedMs;
    }

    public void invalidate() {
        token = null;
        expiresAtElapsedMs = 0L;
    }
}
