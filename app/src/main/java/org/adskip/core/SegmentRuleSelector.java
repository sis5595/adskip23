package org.adskip.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Pure, fail-closed selection of rules for one already-resolved BVID + CID session. */
public final class SegmentRuleSelector {
    public static final long MAX_SOURCE_DURATION_DRIFT_MS = 2_000L;

    private SegmentRuleSelector() {
    }

    /**
     * Returns deterministic, exact-CID skip rules only for evidence authorized by the identity
     * layer and categories explicitly enabled by {@link CategoryPolicy}.
     * Exact duplicate logical segments are coalesced; overlapping but non-identical segments stay
     * separate because they can carry different category/user-policy semantics.
     */
    public static List<SegmentRule> selectAutomaticSkips(
            List<SegmentRule> candidates,
            CidEvidence evidence,
            long activeDurationMs,
            CategoryPolicy categoryPolicy) {
        if (candidates == null || activeDurationMs <= 0L || evidence == null
                || categoryPolicy == null || !evidence.permitsAutomaticRulePublishing()) {
            return Collections.emptyList();
        }
        BiliVideoKey activeVideo = evidence.getExactVideo();

        Map<LogicalSegmentKey, SegmentRule> canonicalRules = new HashMap<>();
        for (SegmentRule candidate : candidates) {
            if (candidate == null
                    || candidate.getAction() != SegmentAction.SKIP
                    || !categoryPolicy.allowsAutomaticSkip(candidate)
                    || !candidate.isCompatibleWith(activeVideo, activeDurationMs, MAX_SOURCE_DURATION_DRIFT_MS)) {
                continue;
            }
            LogicalSegmentKey logicalKey = LogicalSegmentKey.from(candidate);
            SegmentRule previous = canonicalRules.get(logicalKey);
            if (previous == null || candidate.getSegmentId().compareTo(previous.getSegmentId()) < 0) {
                canonicalRules.put(logicalKey, candidate);
            }
        }
        List<SegmentRule> selected = new ArrayList<>(canonicalRules.values());
        selected.sort(Comparator
                .comparingLong(SegmentRule::getStartMs)
                .thenComparingLong(SegmentRule::getEndMs)
                .thenComparing(SegmentRule::getCategory)
                .thenComparing(SegmentRule::getSegmentId));
        return Collections.unmodifiableList(selected);
    }

    private static final class LogicalSegmentKey {
        private final BiliVideoKey videoKey;
        private final long startMs;
        private final long endMs;
        private final String category;
        private final SegmentAction action;

        private LogicalSegmentKey(
                BiliVideoKey videoKey, long startMs, long endMs, String category, SegmentAction action) {
            this.videoKey = videoKey;
            this.startMs = startMs;
            this.endMs = endMs;
            this.category = category;
            this.action = action;
        }

        static LogicalSegmentKey from(SegmentRule rule) {
            return new LogicalSegmentKey(
                    rule.getVideoKey(), rule.getStartMs(), rule.getEndMs(),
                    rule.getCategory(), rule.getAction());
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof LogicalSegmentKey)) {
                return false;
            }
            LogicalSegmentKey that = (LogicalSegmentKey) other;
            return startMs == that.startMs
                    && endMs == that.endMs
                    && videoKey.equals(that.videoKey)
                    && category.equals(that.category)
                    && action == that.action;
        }

        @Override
        public int hashCode() {
            return Objects.hash(videoKey, startMs, endMs, category, action);
        }
    }
}
