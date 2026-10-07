package org.adskip.core;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Pure, network-free parser for Bilibili share text and direct video links. */
public final class ShareLinkEvidenceParser {
    private static final Pattern URL = Pattern.compile("https?://\\S+", Pattern.CASE_INSENSITIVE);
    private static final Pattern BVID_PATH = Pattern.compile("(?:^|/)(BV1[0-9A-Za-z]{9})(?:/|$)");
    private static final String TRAILING = ")]}>.,;:!?，。；：！？、」』）】";

    private ShareLinkEvidenceParser() {
    }

    public static ShareLinkObservation parse(String input) {
        String fingerprint = EvidenceFingerprint.sha256(input == null ? "<null>" : input);
        if (input == null || input.trim().isEmpty()) {
            return result(ShareLinkObservation.State.MALFORMED, null, null, null,
                    fingerprint, 0);
        }
        Matcher matcher = URL.matcher(input);
        List<ParsedLink> direct = new ArrayList<>();
        int recognized = 0;
        boolean sawShort = false;
        boolean sawMalformedBilibili = false;
        while (matcher.find()) {
            String raw = stripTrailing(matcher.group());
            ParsedLink link = parseUrl(raw);
            if (link.kind == Kind.DIRECT) {
                direct.add(link);
                recognized++;
            } else if (link.kind == Kind.SHORT) {
                sawShort = true;
                recognized++;
            } else if (link.kind == Kind.MALFORMED_BILIBILI) {
                sawMalformedBilibili = true;
            }
        }
        if (!direct.isEmpty()) {
            ParsedLink first = direct.get(0);
            for (int index = 1; index < direct.size(); index++) {
                if (!first.sameIdentityAndPosition(direct.get(index))) {
                    return result(ShareLinkObservation.State.CONFLICT, null, null, null,
                            fingerprint, recognized);
                }
            }
            if (sawShort) {
                return result(ShareLinkObservation.State.CONFLICT, null, null, null,
                        fingerprint, recognized);
            }
            return result(ShareLinkObservation.State.DIRECT, first.bvid, first.page,
                    first.positionMs, fingerprint, recognized);
        }
        if (sawShort) {
            return result(ShareLinkObservation.State.SHORT_LINK_NEEDS_EXPANSION,
                    null, null, null, fingerprint, recognized);
        }
        return result(sawMalformedBilibili ? ShareLinkObservation.State.MALFORMED
                        : ShareLinkObservation.State.UNSUPPORTED,
                null, null, null, fingerprint, 0);
    }

    private static ParsedLink parseUrl(String raw) {
        final URI uri;
        try {
            uri = new URI(raw);
        } catch (URISyntaxException exception) {
            return ParsedLink.of(Kind.UNSUPPORTED);
        }
        String scheme = lower(uri.getScheme());
        String host = lower(uri.getHost());
        if (!("http".equals(scheme) || "https".equals(scheme)) || host == null) {
            return ParsedLink.of(Kind.UNSUPPORTED);
        }
        if ("b23.tv".equals(host) || "bili2233.cn".equals(host)) {
            return ParsedLink.of(Kind.SHORT);
        }
        if (!("bilibili.com".equals(host) || host.endsWith(".bilibili.com"))) {
            return ParsedLink.of(Kind.UNSUPPORTED);
        }
        String path = uri.getPath() == null ? "" : uri.getPath();
        Matcher pathMatcher = BVID_PATH.matcher(path);
        String bvid = pathMatcher.find() ? pathMatcher.group(1) : query(uri, "bvid");
        if (bvid == null) return ParsedLink.of(Kind.MALFORMED_BILIBILI);
        try {
            BiliIdCodec.bvidToAid(bvid);
        } catch (IllegalArgumentException exception) {
            return ParsedLink.of(Kind.MALFORMED_BILIBILI);
        }

        ParsedLong page = positiveLong(query(uri, "p"));
        if (page.invalid) return ParsedLink.of(Kind.MALFORMED_BILIBILI);
        ParsedLong startProgress = nonNegativeLong(query(uri, "start_progress"));
        if (startProgress.invalid) return ParsedLink.of(Kind.MALFORMED_BILIBILI);
        ParsedLong time = secondsToMillis(query(uri, "t"));
        if (time.invalid) return ParsedLink.of(Kind.MALFORMED_BILIBILI);
        Long position = startProgress.value != null ? startProgress.value : time.value;
        return new ParsedLink(Kind.DIRECT, bvid, page.value, position);
    }

    private static String query(URI uri, String wanted) {
        String raw = uri.getRawQuery();
        if (raw == null) return null;
        String found = null;
        for (String pair : raw.split("&", -1)) {
            int equals = pair.indexOf('=');
            String key = decode(equals < 0 ? pair : pair.substring(0, equals));
            if (!wanted.equals(key)) continue;
            String value = decode(equals < 0 ? "" : pair.substring(equals + 1));
            if (found != null && !found.equals(value)) return "<conflict>";
            found = value;
        }
        return found;
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8.name());
        } catch (Exception exception) {
            return "<invalid-encoding>";
        }
    }

    private static ParsedLong positiveLong(String value) {
        ParsedLong parsed = nonNegativeLong(value);
        if (!parsed.invalid && parsed.value != null && parsed.value == 0L) {
            return ParsedLong.invalid();
        }
        return parsed;
    }

    private static ParsedLong nonNegativeLong(String value) {
        if (value == null) return ParsedLong.absent();
        if (value.isEmpty() || (value.length() > 1 && value.charAt(0) == '0')) {
            return ParsedLong.invalid();
        }
        for (int index = 0; index < value.length(); index++) {
            if (value.charAt(index) < '0' || value.charAt(index) > '9') {
                return ParsedLong.invalid();
            }
        }
        try {
            return ParsedLong.of(Long.parseLong(value));
        } catch (NumberFormatException exception) {
            return ParsedLong.invalid();
        }
    }

    private static ParsedLong secondsToMillis(String value) {
        if (value == null) return ParsedLong.absent();
        try {
            BigDecimal seconds = new BigDecimal(value);
            if (seconds.signum() < 0) return ParsedLong.invalid();
            long millis = seconds.movePointRight(3).setScale(0, RoundingMode.HALF_UP)
                    .longValueExact();
            return ParsedLong.of(millis);
        } catch (ArithmeticException | NumberFormatException exception) {
            return ParsedLong.invalid();
        }
    }

    private static String stripTrailing(String value) {
        int end = value.length();
        while (end > 0 && TRAILING.indexOf(value.charAt(end - 1)) >= 0) end--;
        return value.substring(0, end);
    }

    private static String lower(String value) {
        return value == null ? null : value.toLowerCase(Locale.ROOT);
    }

    private static ShareLinkObservation result(ShareLinkObservation.State state, String bvid,
            Long page, Long positionMs, String fingerprint, int recognized) {
        return new ShareLinkObservation(state, bvid, page, positionMs, fingerprint, recognized);
    }

    private enum Kind { DIRECT, SHORT, MALFORMED_BILIBILI, UNSUPPORTED }

    private static final class ParsedLink {
        final Kind kind;
        final String bvid;
        final Long page;
        final Long positionMs;

        ParsedLink(Kind kind, String bvid, Long page, Long positionMs) {
            this.kind = kind;
            this.bvid = bvid;
            this.page = page;
            this.positionMs = positionMs;
        }

        static ParsedLink of(Kind kind) { return new ParsedLink(kind, null, null, null); }

        boolean sameIdentityAndPosition(ParsedLink other) {
            return bvid.equals(other.bvid) && equal(page, other.page)
                    && equal(positionMs, other.positionMs);
        }

        private static boolean equal(Object left, Object right) {
            return left == null ? right == null : left.equals(right);
        }
    }

    private static final class ParsedLong {
        final Long value;
        final boolean invalid;

        private ParsedLong(Long value, boolean invalid) {
            this.value = value;
            this.invalid = invalid;
        }

        static ParsedLong absent() { return new ParsedLong(null, false); }
        static ParsedLong of(long value) { return new ParsedLong(value, false); }
        static ParsedLong invalid() { return new ParsedLong(null, true); }
    }
}
