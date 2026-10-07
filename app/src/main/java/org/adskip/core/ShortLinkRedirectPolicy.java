package org.adskip.core;

import java.net.URI;
import java.util.Locale;
import java.util.OptionalLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Strict HTTPS/host policy shared by the research short-link transports. */
public final class ShortLinkRedirectPolicy {
    private static final Pattern OGV_EP_PATH = Pattern.compile("^/bangumi/play/ep([1-9][0-9]*)/?$");
    private ShortLinkRedirectPolicy() {
    }

    public static boolean isAllowedInitial(URI uri) {
        return isSafeAuthority(uri) && isShortHost(uri.getHost());
    }

    public static boolean isAllowedRedirectHop(URI uri) {
        if (!isSafeAuthority(uri)) return false;
        String host = lower(uri.getHost());
        return isShortHost(host) || isBilibiliHost(host);
    }

    public static boolean isAllowedFinal(URI uri) {
        return isSafeAuthority(uri) && isBilibiliHost(uri.getHost())
                && ShareLinkEvidenceParser.parse(uri.toASCIIString()).getState()
                == ShareLinkObservation.State.DIRECT;
    }

    /** Returns a sanitized episode id for a strict official OGV episode URL. */
    public static OptionalLong ogvEpisodeId(URI uri) {
        if (!isSafeAuthority(uri) || !isBilibiliHost(uri.getHost())) return OptionalLong.empty();
        Matcher matcher = OGV_EP_PATH.matcher(uri.getPath() == null ? "" : uri.getPath());
        if (!matcher.matches()) return OptionalLong.empty();
        try {
            return OptionalLong.of(Long.parseLong(matcher.group(1)));
        } catch (NumberFormatException exception) {
            return OptionalLong.empty();
        }
    }

    private static boolean isSafeAuthority(URI uri) {
        if (uri == null || !"https".equals(lower(uri.getScheme())) || uri.getHost() == null
                || uri.getUserInfo() != null || (uri.getPort() != -1 && uri.getPort() != 443)) {
            return false;
        }
        String host = lower(uri.getHost());
        return host.indexOf('%') < 0 && !host.endsWith(".");
    }

    private static boolean isShortHost(String host) {
        host = lower(host);
        return "b23.tv".equals(host) || "bili2233.cn".equals(host);
    }

    private static boolean isBilibiliHost(String host) {
        host = lower(host);
        return "bilibili.com".equals(host) || (host != null && host.endsWith(".bilibili.com"));
    }

    private static String lower(String value) {
        return value == null ? null : value.toLowerCase(Locale.ROOT);
    }
}
