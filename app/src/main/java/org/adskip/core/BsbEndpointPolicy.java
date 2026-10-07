package org.adskip.core;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/** Pure URL policy for read-only BSB hash-prefix requests. */
public final class BsbEndpointPolicy {
    public static final String DEFAULT_ORIGIN = "https://bsbsb.top";

    private BsbEndpointPolicy() { }

    public static String normalizeOrigin(String raw) {
        if (raw == null || raw.trim().isEmpty()) throw new IllegalArgumentException("empty server");
        URI uri;
        try {
            uri = URI.create(raw.trim());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("invalid server", exception);
        }
        String host = uri.getHost();
        if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null || host.isEmpty()
                || uri.getRawUserInfo() != null || uri.getRawQuery() != null
                || uri.getRawFragment() != null
                || (uri.getPort() != -1 && (uri.getPort() <= 0 || uri.getPort() > 65_535))
                || !(uri.getRawPath() == null || uri.getRawPath().isEmpty()
                     || "/".equals(uri.getRawPath()))) {
            throw new IllegalArgumentException("server must be HTTPS origin only");
        }
        return "https://" + host.toLowerCase(Locale.ROOT)
                + (uri.getPort() == -1 ? "" : ":" + uri.getPort());
    }

    public static String hashPrefix(String bvid) {
        BiliIdCodec.bvidToAid(bvid);
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(bvid.getBytes(StandardCharsets.UTF_8));
            char[] digits = "0123456789abcdef".toCharArray();
            return "" + digits[(hash[0] >> 4) & 15] + digits[hash[0] & 15]
                    + digits[(hash[1] >> 4) & 15] + digits[hash[1] & 15];
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError("SHA-256 unavailable", exception);
        }
    }

    public static String queryUrl(String origin, String bvid) {
        return normalizeOrigin(origin) + "/api/skipSegments/" + hashPrefix(bvid);
    }
}
