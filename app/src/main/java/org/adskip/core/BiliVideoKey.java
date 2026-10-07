package org.adskip.core;

import java.util.Objects;

/**
 * Exact Bilibili playable-content identity.
 *
 * <p>An aid/BVID names a submission, while a CID names the current playable source within it.
 * A crowd-sourced segment must never be applied without both values.</p>
 */
public final class BiliVideoKey {
    private final long aid;
    private final String bvid;
    private final long cid;

    public BiliVideoKey(String bvid, String cid) {
        this.aid = BiliIdCodec.bvidToAid(requireText(bvid, "bvid"));
        this.bvid = bvid;
        this.cid = parseCanonicalPositiveLong(cid, "cid");
    }

    public long getAid() {
        return aid;
    }

    public String getBvid() {
        return bvid;
    }

    public long getCid() {
        return cid;
    }

    public String getCidString() {
        return Long.toString(cid);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof BiliVideoKey)) {
            return false;
        }
        BiliVideoKey that = (BiliVideoKey) other;
        return aid == that.aid && cid == that.cid && bvid.equals(that.bvid);
    }

    @Override
    public int hashCode() {
        return Objects.hash(aid, bvid, cid);
    }

    @Override
    public String toString() {
        return bvid + "+" + cid;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.trim().isEmpty() || !value.equals(value.trim())) {
            throw new IllegalArgumentException(name + " must be nonblank and unpadded");
        }
        return value;
    }

    private static long parseCanonicalPositiveLong(String value, String name) {
        requireText(value, name);
        if (value.length() > 1 && value.charAt(0) == '0') {
            throw new IllegalArgumentException(name + " must not have leading zeroes");
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character < '0' || character > '9') {
                throw new IllegalArgumentException(name + " must be an unsigned decimal integer");
            }
        }
        try {
            long parsed = Long.parseLong(value);
            if (parsed <= 0L) {
                throw new IllegalArgumentException(name + " must be positive");
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(name + " is outside signed 64-bit range", exception);
        }
    }
}
