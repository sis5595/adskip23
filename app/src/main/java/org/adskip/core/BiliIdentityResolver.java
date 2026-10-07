package org.adskip.core;

import java.util.Optional;

/** Fail-closed parser for the article-level identity exposed by Bilibili MediaSession metadata. */
public final class BiliIdentityResolver {
    private BiliIdentityResolver() {
    }

    public static Optional<BiliIdentity> resolve(String mediaId, String title, long durationMs) {
        if (!isCanonicalUnsignedDecimal(mediaId)
                || title == null
                || title.trim().isEmpty()
                || durationMs <= 0L) {
            return Optional.empty();
        }

        final long encoded;
        try {
            encoded = Long.parseLong(mediaId);
        } catch (NumberFormatException exception) {
            return Optional.empty();
        }
        if (encoded <= 0L || encoded % 10L != 0L || !Long.toString(encoded).equals(mediaId)) {
            return Optional.empty();
        }

        long aid = encoded / 10L;
        if (aid <= 0L || aid > BiliIdCodec.MAX_AID) {
            return Optional.empty();
        }
        return Optional.of(new BiliIdentity(aid, mediaId, title.trim(), durationMs));
    }

    private static boolean isCanonicalUnsignedDecimal(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character < '0' || character > '9') {
                return false;
            }
        }
        return true;
    }
}
