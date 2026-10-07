package org.adskip.core;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Ephemeral, session-independent transport input for one official Bilibili short link. */
public final class ShareTransportRequest {
    private static final Pattern URL = Pattern.compile("https?://\\S+", Pattern.CASE_INSENSITIVE);
    private static final String TRAILING = ")]}>.,;:!?，。；：！？、」』）】";

    private final URI url;
    private final String inputFingerprint;

    private ShareTransportRequest(URI url, String inputFingerprint) {
        this.url = url;
        this.inputFingerprint = inputFingerprint;
    }

    public static Optional<ShareTransportRequest> fromSharedText(String input) {
        if (input == null || input.length() > 8_192) return Optional.empty();
        Matcher matcher = URL.matcher(input);
        URI found = null;
        while (matcher.find()) {
            String raw = stripTrailing(matcher.group());
            final URI candidate;
            try {
                candidate = new URI(raw);
            } catch (URISyntaxException exception) {
                continue;
            }
            if (!ShortLinkRedirectPolicy.isAllowedInitial(candidate)) continue;
            if (found != null && !found.equals(candidate)) return Optional.empty();
            found = candidate;
        }
        return found == null ? Optional.empty() : Optional.of(new ShareTransportRequest(
                found, EvidenceFingerprint.sha256(input)));
    }

    public URI getUrlForTransportOnly() { return url; }
    public String getInputFingerprint() { return inputFingerprint; }

    private static String stripTrailing(String value) {
        int end = value.length();
        while (end > 0 && TRAILING.indexOf(value.charAt(end - 1)) >= 0) end--;
        return value.substring(0, end);
    }
}
