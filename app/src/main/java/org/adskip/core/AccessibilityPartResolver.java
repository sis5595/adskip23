package org.adskip.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** A research-only, single-snapshot selected-part to exact catalog row check. */
public final class AccessibilityPartResolver {
    public enum State {
        RESEARCH_UNIQUE, NO_SCOPE, STALE_SCOPE, STALE_SCAN, STALE_CATALOG,
        CATALOG_UNAVAILABLE, NO_SELECTED_PART, AMBIGUOUS, CONFLICT
    }

    public static final class Result {
        private final State state;
        private final CandidatePart part;
        private Result(State state, CandidatePart part) {
            this.state = state;
            this.part = part;
        }
        public State getState() { return state; }
        public java.util.Optional<CandidatePart> getPart() {
            return java.util.Optional.ofNullable(part);
        }
    }

    private AccessibilityPartResolver() {}

    public static Result resolve(EvidenceScope before, EvidenceScope after,
            long scanAtElapsedMs, long nowElapsedMs, long maxScanAgeMs,
            PageCatalog catalog, long maxCatalogAgeMs,
            List<AccessibilitySelectionSignal> selectedSignals) {
        if (maxScanAgeMs < 0 || maxCatalogAgeMs < 0 || nowElapsedMs < 0) {
            throw new IllegalArgumentException("invalid freshness limits");
        }
        if (before == null || after == null) return result(State.NO_SCOPE, null);
        if (!before.equals(after)) return result(State.STALE_SCOPE, null);
        if (scanAtElapsedMs < 0 || scanAtElapsedMs > nowElapsedMs
                || nowElapsedMs - scanAtElapsedMs > maxScanAgeMs) {
            return result(State.STALE_SCAN, null);
        }
        if (catalog == null || catalog.getState() != PageCatalog.State.SUCCESS
                || !before.getBvid().equals(catalog.getBvid())) {
            return result(State.CATALOG_UNAVAILABLE, null);
        }
        if (catalog.getObservedAtElapsedMs() > nowElapsedMs
                || nowElapsedMs - catalog.getObservedAtElapsedMs() > maxCatalogAgeMs) {
            return result(State.STALE_CATALOG, null);
        }
        if (selectedSignals == null || selectedSignals.isEmpty()) {
            return result(State.NO_SELECTED_PART, null);
        }
        List<CandidatePart> matched = new ArrayList<>();
        boolean sawPartShape = false;
        for (AccessibilitySelectionSignal signal : selectedSignals) {
            if (signal == null || signal.getState() != AccessibilitySelectionSignal.State.ELIGIBLE) {
                continue;
            }
            CandidatePart candidate = null;
            boolean thisPartShape = false;
            if (signal.getPage().isPresent() && signal.getTotal().isPresent()) {
                thisPartShape = true;
                int page = signal.getPage().getAsInt();
                int total = signal.getTotal().getAsInt();
                if (total != catalog.getCandidates().size()) return result(State.CONFLICT, null);
                candidate = catalog.candidateForPage(page).orElse(null);
                // A selected numeric UI label (e.g. "3") is not the pagelist part title.
                // If the label names another catalog row, however, this snapshot conflicts.
                if (signal.getLabelFingerprint().isPresent()) {
                    for (CandidatePart other : catalog.getCandidates()) {
                        if (other.getPage() != page && other.getLabelFingerprint().isPresent()
                                && Objects.equals(other.getLabelFingerprint().get(),
                                signal.getLabelFingerprint().get())) {
                            return result(State.CONFLICT, null);
                        }
                    }
                }
            } else if (signal.getLabelFingerprint().isPresent()) {
                List<CandidatePart> labelMatches = new ArrayList<>();
                for (CandidatePart part : catalog.getCandidates()) {
                    if (part.getLabelFingerprint().isPresent()
                            && part.getLabelFingerprint().get().equals(
                            signal.getLabelFingerprint().get())) labelMatches.add(part);
                }
                if (!labelMatches.isEmpty()) {
                    thisPartShape = true;
                    if (labelMatches.size() > 1) return result(State.AMBIGUOUS, null);
                    candidate = labelMatches.get(0);
                }
            }
            if (thisPartShape) sawPartShape = true;
            if (thisPartShape && candidate == null) return result(State.CONFLICT, null);
            if (candidate != null && !matched.contains(candidate)) matched.add(candidate);
        }
        if (!sawPartShape) return result(State.NO_SELECTED_PART, null);
        if (matched.size() != 1) return result(State.AMBIGUOUS, null);
        return result(State.RESEARCH_UNIQUE, matched.get(0));
    }

    private static Result result(State state, CandidatePart candidate) {
        return new Result(state, candidate);
    }
}
