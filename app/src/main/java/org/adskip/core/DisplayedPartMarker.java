package org.adskip.core;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Research parser for explicit display markers such as P78/156; never an identity authority. */
public final class DisplayedPartMarker {
    private static final Pattern MARKER = Pattern.compile(
            "(?:^|[^0-9A-Za-z])P\\s*([1-9][0-9]*)\\s*/\\s*([1-9][0-9]*)(?:$|[^0-9])",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern BILI_SINGLE_LOOP = Pattern.compile(
            "(?:^|.*[^0-9])单集循环\\s*[（(]\\s*([1-9][0-9]*)\\s*/\\s*"
                    + "([1-9][0-9]*)\\s*[)）](?:$|[^0-9].*)");

    private final long page;
    private final long totalParts;

    private DisplayedPartMarker(long page, long totalParts) {
        this.page = page;
        this.totalParts = totalParts;
    }

    public long getPage() { return page; }
    public long getTotalParts() { return totalParts; }

    public static Optional<DisplayedPartMarker> parse(CharSequence text) {
        if (text == null) return Optional.empty();
        Matcher matcher = MARKER.matcher(text);
        if (!matcher.find()) {
            matcher = BILI_SINGLE_LOOP.matcher(text);
            if (!matcher.matches()) return Optional.empty();
        }
        try {
            long page = Long.parseLong(matcher.group(1));
            long total = Long.parseLong(matcher.group(2));
            if (page > total) return Optional.empty();
            return Optional.of(new DisplayedPartMarker(page, total));
        } catch (NumberFormatException exception) {
            return Optional.empty();
        }
    }
}
