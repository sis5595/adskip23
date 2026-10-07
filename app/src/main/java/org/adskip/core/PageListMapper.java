package org.adskip.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Fail-closed conversion from transport records to a complete research candidate map. */
public final class PageListMapper {
    private PageListMapper() {
    }

    public static CandidatePageMap map(EvidenceScope scope, List<PageListRecord> records,
            long observedAtElapsedMs, String sourceVersion) {
        if (scope == null || observedAtElapsedMs < 0L || sourceVersion == null
                || sourceVersion.trim().isEmpty()) {
            throw new IllegalArgumentException("invalid pagelist map context");
        }
        PageCatalog catalog = mapCatalog(scope.getBvid(), records, observedAtElapsedMs, sourceVersion);
        return bind(scope, catalog);
    }

    public static PageCatalog mapCatalog(String bvid, List<PageListRecord> records,
            long observedAtElapsedMs, String sourceVersion) {
        if (bvid == null || observedAtElapsedMs < 0L || sourceVersion == null
                || sourceVersion.trim().isEmpty()) {
            throw new IllegalArgumentException("invalid pagelist catalog context");
        }
        BiliIdCodec.bvidToAid(bvid);
        if (records == null || records.isEmpty()) {
            return catalog(PageCatalog.State.EMPTY, bvid, Collections.emptyList(),
                    observedAtElapsedMs, sourceVersion);
        }
        long total = records.size();
        List<CandidatePart> candidates = new ArrayList<>();
        Set<Long> pages = new HashSet<>();
        Set<String> cids = new HashSet<>();
        try {
            for (PageListRecord record : records) {
                if (record == null || record.getPage() <= 0L || record.getPage() > total
                        || record.getDurationSeconds() <= 0L || record.getPart() == null
                        || !pages.add(record.getPage()) || !cids.add(record.getCid())) {
                    return malformedCatalog(bvid, observedAtElapsedMs, sourceVersion);
                }
                long durationMs = Math.multiplyExact(record.getDurationSeconds(), 1_000L);
                candidates.add(new CandidatePart(bvid, record.getCid(),
                        record.getPage(), total, durationMs,
                        EvidenceFingerprint.sha256(record.getPart())));
            }
        } catch (IllegalArgumentException | ArithmeticException exception) {
            return malformedCatalog(bvid, observedAtElapsedMs, sourceVersion);
        }
        if (pages.size() != total) return malformedCatalog(bvid, observedAtElapsedMs, sourceVersion);
        for (long page = 1L; page <= total; page++) {
            if (!pages.contains(page)) return malformedCatalog(bvid, observedAtElapsedMs, sourceVersion);
        }
        candidates.sort(Comparator.comparingLong(CandidatePart::getPage));
        return catalog(PageCatalog.State.SUCCESS, bvid, candidates,
                observedAtElapsedMs, sourceVersion);
    }

    public static CandidatePageMap bind(EvidenceScope scope, PageCatalog catalog) {
        if (scope == null || catalog == null || !scope.getBvid().equals(catalog.getBvid())) {
            throw new IllegalArgumentException("catalog/scope mismatch");
        }
        CandidatePageMap.State state = CandidatePageMap.State.valueOf(catalog.getState().name());
        return result(state, scope, catalog.getCandidates(), catalog.getObservedAtElapsedMs(),
                catalog.getSourceVersion());
    }

    public static CandidatePageMap stale(EvidenceScope scope, long observedAtElapsedMs,
            String sourceVersion) {
        return CandidatePageMap.failed(CandidatePageMap.State.STALE_GENERATION, scope,
                observedAtElapsedMs, sourceVersion);
    }

    private static CandidatePageMap malformed(EvidenceScope scope, long time, String version) {
        return result(CandidatePageMap.State.MALFORMED, scope, Collections.emptyList(), time, version);
    }

    private static PageCatalog malformedCatalog(String bvid, long time, String version) {
        return catalog(PageCatalog.State.MALFORMED, bvid, Collections.emptyList(), time, version);
    }

    private static PageCatalog catalog(PageCatalog.State state, String bvid,
            List<CandidatePart> candidates, long time, String version) {
        return new PageCatalog(state, bvid, candidates, time, version);
    }

    private static CandidatePageMap result(CandidatePageMap.State state, EvidenceScope scope,
            List<CandidatePart> candidates, long time, String version) {
        return new CandidatePageMap(state, scope, candidates, time, version);
    }
}
