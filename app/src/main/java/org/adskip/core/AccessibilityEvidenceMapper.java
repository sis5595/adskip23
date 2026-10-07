package org.adskip.core;

import java.util.Optional;

/**
 * Research-only bridge from one sanitized selected node into the candidate timeline.
 *
 * <p>The caller must supply an already-established session/BVID/generation scope. This mapper
 * never infers identity, never creates {@link CidEvidence}, and keeps page plus label constraints
 * in one evidence object so fields derived from one UI node cannot be counted twice.</p>
 */
public final class AccessibilityEvidenceMapper {
    private AccessibilityEvidenceMapper() {}

    public static Optional<CurrentPartEvidence> map(AccessibilitySelectionSignal signal,
            EvidenceScope explicitScope, long observedAtElapsedMs, long scanLatencyMs,
            CurrentPartEvidence.Freshness freshness) {
        if (signal == null || explicitScope == null || freshness == null
                || signal.getState() != AccessibilitySelectionSignal.State.ELIGIBLE
                || (!signal.getPage().isPresent()
                && !signal.getLabelFingerprint().isPresent())) {
            return Optional.empty();
        }
        String lineageMaterial = "accessibility-selected-node|"
                + signal.getSelectionKind().map(Enum::name).orElse("-") + "|"
                + signal.getLabelFingerprint().orElse("-") + "|"
                + signal.getViewIdFingerprint().orElse("-") + "|"
                + (signal.getPage().isPresent() ? signal.getPage().getAsInt() : "-") + "|"
                + (signal.getTotal().isPresent() ? signal.getTotal().getAsInt() : "-") + "|"
                + (signal.getCollectionRow().isPresent()
                ? signal.getCollectionRow().getAsInt() : "-") + "|"
                + (signal.getCollectionColumn().isPresent()
                ? signal.getCollectionColumn().getAsInt() : "-");
        String lineage = EvidenceFingerprint.sha256(lineageMaterial);
        CurrentPartEvidence.Builder builder = CurrentPartEvidence.builder(
                        explicitScope, EvidenceSource.ACCESSIBILITY, observedAtElapsedMs)
                .freshness(freshness)
                .latencyMs(scanLatencyMs)
                .lineageFingerprint(lineage)
                .rawFingerprint(lineage);
        if (signal.getPage().isPresent()) builder.page(signal.getPage().getAsInt());
        if (signal.getTotal().isPresent()) builder.totalParts(signal.getTotal().getAsInt());
        signal.getLabelFingerprint().ifPresent(builder::labelFingerprint);
        return Optional.of(builder.build());
    }
}
