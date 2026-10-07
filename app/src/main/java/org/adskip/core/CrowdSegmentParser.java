package org.adskip.core;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Strict parser for the small, relevant subset of a BilibiliSponsorBlock API record.
 *
 * <p>This is deliberately transport-agnostic: a future HTTP/JSON adapter passes lexical JSON
 * values here, and malformed records are discarded before reaching the playback layer.</p>
 */
public final class CrowdSegmentParser {
    // SponsorBlock-compatible data commonly uses 64 hex chars; the complete BSB 2026-09-15
    // snapshot uses 65. Accept only these observed fixed-width forms, never arbitrary IDs.
    private static final Pattern UUID_PATTERN = Pattern.compile("[0-9a-f]{64}|[0-9a-f]{65}");

    private CrowdSegmentParser() {
    }

    public static Optional<SegmentRule> parse(
            String bvid,
            String cid,
            String uuid,
            String category,
            String actionType,
            String startSeconds,
            String endSeconds,
            String sourceDurationSeconds,
            String source,
            String ruleVersion) {
        try {
            BiliVideoKey videoKey = new BiliVideoKey(bvid, cid);
            long startMs = secondsToMilliseconds(startSeconds, "startSeconds", true);
            long endMs = secondsToMilliseconds(endSeconds, "endSeconds", false);
            long sourceDurationMs = secondsToMilliseconds(
                    sourceDurationSeconds, "sourceDurationSeconds", false);
            return Optional.of(new SegmentRule(
                    videoKey,
                    startMs,
                    endMs,
                    sourceDurationMs,
                    requireUuid(uuid),
                    requireText(category, "category"),
                    SegmentAction.fromWireValue(actionType),
                    source,
                    ruleVersion));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    /** Converts finite decimal seconds to milliseconds using the documented HALF_UP policy. */
    static long secondsToMilliseconds(String value, String name, boolean allowZero) {
        if (value == null || value.isEmpty() || !value.equals(value.trim())) {
            throw new IllegalArgumentException(name + " must be an unpadded decimal");
        }
        final BigDecimal seconds;
        try {
            seconds = new BigDecimal(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(name + " must be a finite decimal", exception);
        }
        if (seconds.signum() < 0 || (!allowZero && seconds.signum() == 0)) {
            throw new IllegalArgumentException(name + " must be " + (allowZero ? "non-negative" : "positive"));
        }
        try {
            return seconds.movePointRight(3).setScale(0, RoundingMode.HALF_UP).longValueExact();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(name + " cannot be represented as milliseconds", exception);
        }
    }

    private static String requireUuid(String value) {
        if (value == null || !UUID_PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException("uuid must be a 64- or 65-character lowercase hexadecimal value");
        }
        return value;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.trim().isEmpty() || !value.equals(value.trim())) {
            throw new IllegalArgumentException(name + " must be nonblank and unpadded");
        }
        return value;
    }
}
