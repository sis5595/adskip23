package org.adskip.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Research-only candidate intersection. A single survivor is RESEARCH_UNIQUE, never EXACT, and
 * this class intentionally cannot create {@link CidEvidence}.
 */
public final class CurrentPartResolver {
    public enum State { UNKNOWN, AMBIGUOUS, RESEARCH_UNIQUE, CONFLICT }
    public enum StepDisposition {
        APPLIED_INDEPENDENT, APPLIED_DEPENDENT, REDUNDANT_SAME_LINEAGE, IGNORED_STALE
    }
    public enum ResultReason {
        SURVIVORS, NO_INITIAL_CANDIDATES, NO_MATCHING_BVID_CANDIDATES, EVIDENCE_EXCLUDED_ALL
    }

    public static final class Step {
        private final EvidenceSource source;
        private final int beforeCount;
        private final int afterCount;
        private final String criteria;
        private final CurrentPartEvidence.Freshness freshness;
        private final String lineageFingerprint;
        private final boolean firstInLineage;
        private final StepDisposition disposition;
        private final List<BiliVideoKey> supportedCandidates;
        private final List<BiliVideoKey> excludedCandidates;

        private Step(CurrentPartEvidence evidence, int beforeCount, int afterCount, String criteria,
                boolean firstInLineage, StepDisposition disposition,
                List<BiliVideoKey> supportedCandidates,
                List<BiliVideoKey> excludedCandidates) {
            this.source = evidence.getSource();
            this.beforeCount = beforeCount;
            this.afterCount = afterCount;
            this.criteria = criteria;
            this.freshness = evidence.getFreshness();
            this.lineageFingerprint = evidence.getLineageFingerprint().orElse(null);
            this.firstInLineage = firstInLineage;
            this.disposition = disposition;
            this.supportedCandidates = Collections.unmodifiableList(
                    new ArrayList<>(supportedCandidates));
            this.excludedCandidates = Collections.unmodifiableList(
                    new ArrayList<>(excludedCandidates));
        }
        public EvidenceSource getSource() { return source; }
        public int getBeforeCount() { return beforeCount; }
        public int getAfterCount() { return afterCount; }
        public String getCriteria() { return criteria; }
        public CurrentPartEvidence.Freshness getFreshness() { return freshness; }
        public Optional<String> getLineageFingerprint() {
            return Optional.ofNullable(lineageFingerprint);
        }
        /** False means this step derives from a fact already seen in the same lineage. */
        public boolean isFirstInLineage() { return firstInLineage; }
        public StepDisposition getDisposition() { return disposition; }
        public List<BiliVideoKey> getSupportedCandidates() { return supportedCandidates; }
        public List<BiliVideoKey> getExcludedCandidates() { return excludedCandidates; }
    }

    public static final class Result {
        private final State state;
        private final List<CandidatePart> candidates;
        private final List<Step> steps;
        private final ResultReason reason;

        private Result(State state, List<CandidatePart> candidates, List<Step> steps,
                ResultReason reason) {
            this.state = state;
            this.candidates = Collections.unmodifiableList(new ArrayList<>(candidates));
            this.steps = Collections.unmodifiableList(new ArrayList<>(steps));
            this.reason = reason;
        }
        public State getState() { return state; }
        public List<CandidatePart> getCandidates() { return candidates; }
        public List<Step> getSteps() { return steps; }
        public ResultReason getReason() { return reason; }
    }

    private CurrentPartResolver() {
    }

    public static Result resolve(EvidenceScope scope, List<CandidatePart> initialCandidates,
            List<CurrentPartEvidence> evidence, long durationToleranceMs) {
        Objects.requireNonNull(scope, "scope");
        if (durationToleranceMs < 0L) {
            throw new IllegalArgumentException("durationToleranceMs must be non-negative");
        }
        if (initialCandidates == null || initialCandidates.isEmpty()) {
            return new Result(State.UNKNOWN, Collections.<CandidatePart>emptyList(),
                    Collections.<Step>emptyList(), ResultReason.NO_INITIAL_CANDIDATES);
        }
        List<CandidatePart> candidates = new ArrayList<>();
        for (CandidatePart candidate : initialCandidates) {
            if (candidate == null || !scope.getBvid().equals(candidate.getVideoKey().getBvid())) {
                continue;
            }
            candidates.add(candidate);
        }
        candidates.sort(Comparator.comparingLong(CandidatePart::getPage)
                .thenComparing(value -> value.getVideoKey().getCidString()));
        if (candidates.isEmpty()) {
            return new Result(State.CONFLICT, candidates, Collections.<Step>emptyList(),
                    ResultReason.NO_MATCHING_BVID_CANDIDATES);
        }

        List<CurrentPartEvidence> ordered = new ArrayList<>();
        if (evidence != null) {
            for (CurrentPartEvidence item : evidence) {
                if (item != null && scope.equals(item.getScope()) && item.hasCandidateConstraint()) {
                    ordered.add(item);
                }
            }
        }
        ordered.sort(Comparator.comparingLong(CurrentPartEvidence::getObservedAtElapsedMs)
                .thenComparing(value -> value.getSource().name()));
        List<Step> steps = new ArrayList<>();
        Set<String> seenLineages = new HashSet<>();
        for (CurrentPartEvidence item : ordered) {
            int before = candidates.size();
            List<CandidatePart> filtered = new ArrayList<>();
            List<BiliVideoKey> supported = new ArrayList<>();
            List<BiliVideoKey> excluded = new ArrayList<>();
            for (CandidatePart candidate : candidates) {
                if (matches(candidate, item, durationToleranceMs)) {
                    filtered.add(candidate);
                    supported.add(candidate.getVideoKey());
                } else {
                    excluded.add(candidate.getVideoKey());
                }
            }
            String criteria = criteria(item);
            String lineage = item.getLineageFingerprint().orElse(null);
            boolean firstInLineage = lineage == null || seenLineages.add(lineage);
            StepDisposition disposition;
            if (item.getFreshness() == CurrentPartEvidence.Freshness.STALE) {
                disposition = StepDisposition.IGNORED_STALE;
            } else if (firstInLineage) {
                disposition = StepDisposition.APPLIED_INDEPENDENT;
                candidates = filtered;
            } else if (excluded.isEmpty()) {
                disposition = StepDisposition.REDUNDANT_SAME_LINEAGE;
            } else {
                disposition = StepDisposition.APPLIED_DEPENDENT;
                candidates = filtered;
            }
            steps.add(new Step(item, before, candidates.size(), criteria, firstInLineage,
                    disposition,
                    supported, excluded));
            if (candidates.isEmpty()) {
                return new Result(State.CONFLICT, candidates, steps,
                        ResultReason.EVIDENCE_EXCLUDED_ALL);
            }
        }
        State state = candidates.size() == 1 ? State.RESEARCH_UNIQUE : State.AMBIGUOUS;
        return new Result(state, candidates, steps, ResultReason.SURVIVORS);
    }

    private static boolean matches(CandidatePart candidate, CurrentPartEvidence evidence,
            long durationToleranceMs) {
        if (evidence.getCid().isPresent()
                && !evidence.getCid().get().equals(candidate.getVideoKey().getCidString())) return false;
        if (evidence.getPage().isPresent()
                && evidence.getPage().getAsLong() != candidate.getPage()) return false;
        if (evidence.getTotalParts().isPresent()
                && evidence.getTotalParts().getAsLong() != candidate.getTotalParts()) return false;
        if (evidence.getLabelFingerprint().isPresent()
                && (!candidate.getLabelFingerprint().isPresent()
                || !evidence.getLabelFingerprint().get().equals(
                        candidate.getLabelFingerprint().get()))) return false;
        if (evidence.getDurationMs().isPresent()) {
            long observed = evidence.getDurationMs().getAsLong();
            long delta = observed >= candidate.getDurationMs()
                    ? observed - candidate.getDurationMs() : candidate.getDurationMs() - observed;
            if (delta > durationToleranceMs) return false;
        }
        return true;
    }

    private static String criteria(CurrentPartEvidence evidence) {
        List<String> fields = new ArrayList<>();
        if (evidence.getCid().isPresent()) fields.add("cid");
        if (evidence.getPage().isPresent()) fields.add("page");
        if (evidence.getTotalParts().isPresent()) fields.add("totalParts");
        if (evidence.getLabelFingerprint().isPresent()) fields.add("labelFingerprint");
        if (evidence.getDurationMs().isPresent()) fields.add("durationMs");
        return String.join("+", fields);
    }
}
