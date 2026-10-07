package org.adskip.core;

/** Process-local UI hint only. Never authorizes playback or changes a system setting. */
public final class BackgroundCompatibilityHealth {
    public enum State { UNAUTHORIZED, CONNECTED, WAITING_SYSTEM, RECOVERING, CHECK_SETTINGS, SCHEDULING_DELAY }

    public static final long RECOVERY_OBSERVATION_MS = 15_000L;
    public static final long RECOVERY_WARNING_MS = 30_000L;
    private long disconnectedSinceMs = -1L;
    private long lossWindowStartMs = -1L;
    private int losses;
    private boolean lastConnected;
    private long lastConnectionEpoch = Long.MIN_VALUE;

    public State update(boolean authorized, boolean connected, long nowMs) {
        return update(authorized, connected, nowMs, 0);
    }

    public State update(boolean authorized, boolean connected, long nowMs,
            int recentListenerLosses) {
        return update(authorized, connected, nowMs, recentListenerLosses, 0L);
    }

    public State update(boolean authorized, boolean connected, long nowMs,
            int recentListenerLosses, long connectionEpoch) {
        return update(authorized, connected, nowMs, recentListenerLosses, connectionEpoch, false);
    }

    public State update(boolean authorized, boolean connected, long nowMs,
            int recentListenerLosses, long connectionEpoch, boolean recentSchedulingDelay) {
        if (nowMs < 0) throw new IllegalArgumentException("negative clock");
        if (!authorized) {
            disconnectedSinceMs = -1L;
            lossWindowStartMs = -1L;
            losses = 0;
            lastConnected = false;
            lastConnectionEpoch = connectionEpoch;
            return State.UNAUTHORIZED;
        }
        if (connected) {
            disconnectedSinceMs = -1L;
            lastConnected = true;
            lastConnectionEpoch = connectionEpoch;
            return recentSchedulingDelay ? State.SCHEDULING_DELAY : State.CONNECTED;
        }
        // A connect/disconnect pair may have happened while the Activity was backgrounded.
        // Do not call that entire interval one continuous disconnection.
        if (lastConnectionEpoch != connectionEpoch) disconnectedSinceMs = -1L;
        lastConnectionEpoch = connectionEpoch;
        if (disconnectedSinceMs < 0 || nowMs < disconnectedSinceMs) disconnectedSinceMs = nowMs;
        if (lastConnected) {
            if (lossWindowStartMs < 0 || nowMs - lossWindowStartMs > 10 * 60_000L) {
                lossWindowStartMs = nowMs;
                losses = 0;
            }
            losses++;
        }
        lastConnected = false;
        if (losses >= 3 || recentListenerLosses >= 3
                || nowMs - disconnectedSinceMs >= RECOVERY_WARNING_MS) {
            return State.CHECK_SETTINGS;
        }
        return nowMs - disconnectedSinceMs >= RECOVERY_OBSERVATION_MS
                ? State.RECOVERING : State.WAITING_SYSTEM;
    }
}
