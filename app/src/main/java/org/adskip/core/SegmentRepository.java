package org.adskip.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * The sole bridge from a transport result to parsed, policy-authorized rules.
 * It deliberately has no dependency on Android, MediaSession, or {@link SkipEngine}.
 */
public final class SegmentRepository {
    public interface GenerationGuard {
        boolean isCurrent(SessionGeneration generation);
    }

    public enum Status {
        PUBLISHED,
        EMPTY,
        NOT_FOUND,
        HTTP_ERROR,
        MALFORMED,
        NETWORK_ERROR,
        TIMEOUT,
        CANCELLED,
        STALE_GENERATION,
        EVIDENCE_REJECTED
    }

    public static final class Result {
        private final Status status;
        private final List<SegmentRule> rules;
        private final String reason;

        private Result(Status status, List<SegmentRule> rules, String reason) {
            this.status = status;
            this.rules = Collections.unmodifiableList(new ArrayList<>(rules));
            this.reason = reason;
        }
        public Status getStatus() { return status; }
        public List<SegmentRule> getRules() { return rules; }
        public String getReason() { return reason; }
    }

    private final SegmentDataSource dataSource;

    public SegmentRepository(SegmentDataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    public Result loadAutomaticSkips(
            SegmentRequest request,
            CidEvidence evidence,
            long activeDurationMs,
            CategoryPolicy categoryPolicy,
            GenerationGuard generationGuard) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(evidence, "evidence");
        Objects.requireNonNull(categoryPolicy, "categoryPolicy");
        Objects.requireNonNull(generationGuard, "generationGuard");
        if (!generationGuard.isCurrent(request.getGeneration())) {
            return result(Status.STALE_GENERATION, "request-generation-is-stale");
        }
        if (!evidence.permitsAutomaticRulePublishing() || !evidence.matches(request.getVideoKey())) {
            return result(Status.EVIDENCE_REJECTED, "cid-evidence-not-authorized");
        }

        SegmentFetchResult fetched = dataSource.fetch(request);
        if (!generationGuard.isCurrent(request.getGeneration())) {
            return result(Status.STALE_GENERATION, "response-generation-is-stale");
        }
        switch (fetched.getKind()) {
            case HTTP_200_EMPTY:
                return result(Status.EMPTY, "http-200-empty");
            case HTTP_404:
                return result(Status.NOT_FOUND, "http-404");
            case HTTP_ERROR:
                return result(Status.HTTP_ERROR, "http-error");
            case MALFORMED_JSON:
                return result(Status.MALFORMED, "malformed-json");
            case NETWORK_ERROR:
                return result(Status.NETWORK_ERROR, "network-error");
            case TIMEOUT:
                return result(Status.TIMEOUT, "timeout");
            case CANCELLED:
                return result(Status.CANCELLED, "cancelled");
            case HTTP_200_JSON:
                break;
            default:
                return result(Status.MALFORMED, "unrecognized-transport-result");
        }

        List<SegmentRule> parsed = new ArrayList<>();
        for (CrowdSegmentRecord record : fetched.getRecords()) {
            if (record == null || !request.getBvid().equals(record.getBvid())
                    || !request.getCid().equals(record.getCid())) {
                continue;
            }
            CrowdSegmentParser.parse(record.getBvid(), record.getCid(), record.getUuid(),
                    record.getCategory(), record.getAction(), record.getStartSeconds(),
                    record.getEndSeconds(), record.getDurationSeconds(), fetched.getSource(),
                    fetched.getVersion()).ifPresent(parsed::add);
        }
        List<SegmentRule> selected = SegmentRuleSelector.selectAutomaticSkips(
                parsed, evidence, activeDurationMs, categoryPolicy);
        return new Result(Status.PUBLISHED, selected,
                selected.isEmpty() ? "no-policy-authorized-rules" : "rules-published");
    }

    private static Result result(Status status, String reason) {
        return new Result(status, Collections.<SegmentRule>emptyList(), reason);
    }
}
