package org.adskip.core;

import java.util.Optional;

/** Only a complete one-entry pagelist can authorize a single-P CID. */
public final class SinglePageCidResolver {
    private static final long MAX_DURATION_DRIFT_MS = 2_000L;

    private SinglePageCidResolver() { }

    public static Optional<CidEvidence> resolve(PageCatalog catalog, String activeBvid,
            long activeDurationMs) {
        if (catalog == null || activeBvid == null || activeDurationMs <= 0L
                || catalog.getState() != PageCatalog.State.SUCCESS
                || !activeBvid.equals(catalog.getBvid())
                || catalog.getCandidates().size() != 1) return Optional.empty();
        CandidatePart only = catalog.getCandidates().get(0);
        long catalogDuration = only.getDurationMs();
        if (only.getPage() != 1L || only.getTotalParts() != 1L
                || catalogDuration <= 0L
                || (catalogDuration >= activeDurationMs
                    ? catalogDuration - activeDurationMs
                    : activeDurationMs - catalogDuration) > MAX_DURATION_DRIFT_MS) {
            return Optional.empty();
        }
        return Optional.of(CidEvidence.exactSinglePage(only.getVideoKey()));
    }
}
