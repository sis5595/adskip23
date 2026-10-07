package org.adskip.core;

import java.util.Optional;

/** Resolves an explicit user share to an exact request target without consulting MediaSession. */
public final class AnalysisRequestResolver {
    public enum State {
        READY,
        INVALID_SHARE,
        PAGE_REQUIRED,
        BVID_CONFLICT,
        CATALOG_UNAVAILABLE,
        PAGE_NOT_UNIQUE
    }

    public static final class Result {
        private final State state;
        private final AnalysisRequestTarget target;

        private Result(State state, AnalysisRequestTarget target) {
            this.state = state;
            this.target = target;
        }

        public State getState() { return state; }
        public Optional<AnalysisRequestTarget> getTarget() {
            return Optional.ofNullable(target);
        }
    }

    private AnalysisRequestResolver() {
    }

    public static Result resolve(ShareLinkObservation share, PageCatalog catalog) {
        if (share == null || share.getState() != ShareLinkObservation.State.DIRECT
                || !share.getBvid().isPresent()) {
            return failed(State.INVALID_SHARE);
        }
        if (!share.getPage().isPresent()) return failed(State.PAGE_REQUIRED);
        if (catalog == null || catalog.getState() != PageCatalog.State.SUCCESS) {
            return failed(State.CATALOG_UNAVAILABLE);
        }
        String bvid = share.getBvid().get();
        if (!bvid.equals(catalog.getBvid())) return failed(State.BVID_CONFLICT);
        long page = share.getPage().getAsLong();
        Optional<CandidatePart> part = catalog.candidateForPage(page);
        if (!part.isPresent()) return failed(State.PAGE_NOT_UNIQUE);
        return new Result(State.READY, new AnalysisRequestTarget(
                part.get().getVideoKey(), page, share.getInputFingerprint(),
                catalog.getSourceVersion()));
    }

    private static Result failed(State state) {
        return new Result(state, null);
    }
}
