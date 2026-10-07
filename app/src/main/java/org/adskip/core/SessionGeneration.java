package org.adskip.core;

import java.util.Objects;

/** Immutable identity scope for an asynchronous segment query. */
public final class SessionGeneration {
    private final String sessionScope;
    private final BiliVideoKey videoKey;
    private final long generation;

    public SessionGeneration(String sessionScope, BiliVideoKey videoKey, long generation) {
        if (sessionScope == null || sessionScope.trim().isEmpty()
                || !sessionScope.equals(sessionScope.trim())) {
            throw new IllegalArgumentException("sessionScope must be nonblank and unpadded");
        }
        if (generation < 0L) {
            throw new IllegalArgumentException("generation must be non-negative");
        }
        this.sessionScope = sessionScope;
        this.videoKey = Objects.requireNonNull(videoKey, "videoKey");
        this.generation = generation;
    }

    public String getSessionScope() { return sessionScope; }
    public BiliVideoKey getVideoKey() { return videoKey; }
    public long getGeneration() { return generation; }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof SessionGeneration)) return false;
        SessionGeneration that = (SessionGeneration) other;
        return generation == that.generation && sessionScope.equals(that.sessionScope)
                && videoKey.equals(that.videoKey);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sessionScope, videoKey, generation);
    }
}
