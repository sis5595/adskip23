package org.adskip.core;

import java.util.Objects;

/** Stable, validated article-level identity extracted from a Bilibili MediaSession snapshot. */
public final class BiliIdentity {
    private final long aid;
    private final String bvid;
    private final String mediaId;
    private final String title;
    private final long durationMs;

    BiliIdentity(long aid, String mediaId, String title, long durationMs) {
        this.aid = aid;
        this.bvid = BiliIdCodec.aidToBvid(aid);
        this.mediaId = Objects.requireNonNull(mediaId);
        this.title = Objects.requireNonNull(title);
        this.durationMs = durationMs;
    }

    public long getAid() {
        return aid;
    }

    public String getBvid() {
        return bvid;
    }

    public String getMediaId() {
        return mediaId;
    }

    public String getTitle() {
        return title;
    }

    public long getDurationMs() {
        return durationMs;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof BiliIdentity)) {
            return false;
        }
        BiliIdentity that = (BiliIdentity) other;
        return aid == that.aid
                && durationMs == that.durationMs
                && mediaId.equals(that.mediaId)
                && title.equals(that.title);
    }

    @Override
    public int hashCode() {
        return Objects.hash(aid, mediaId, title, durationMs);
    }

    @Override
    public String toString() {
        return "BiliIdentity{aid=" + aid + ", bvid='" + bvid + "', durationMs=" + durationMs + "}";
    }
}
