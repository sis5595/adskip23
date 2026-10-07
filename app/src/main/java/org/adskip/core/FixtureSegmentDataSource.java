package org.adskip.core;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Deterministic in-memory transport used by unit tests and the manually armed P4 fixture. */
public final class FixtureSegmentDataSource implements SegmentDataSource {
    public static final String EXPERIMENT_BVID = "BV1Jo346uEYC";
    public static final String EXPERIMENT_CID = "40439909150";
    public static final String EXPERIMENT_UUID =
            "c45f7319ac899819c71552f0702db4f0e6973872c4009872ca6114dd3833f3327";

    private final Map<BiliVideoKey, SegmentFetchResult> responses;

    public FixtureSegmentDataSource(Map<BiliVideoKey, SegmentFetchResult> responses) {
        this.responses = Collections.unmodifiableMap(new HashMap<>(Objects.requireNonNull(responses, "responses")));
    }

    public static FixtureSegmentDataSource experimentFixture() {
        BiliVideoKey key = new BiliVideoKey(EXPERIMENT_BVID, EXPERIMENT_CID);
        CrowdSegmentRecord record = new CrowdSegmentRecord(
                EXPERIMENT_BVID, EXPERIMENT_CID, EXPERIMENT_UUID, "sponsor", "skip",
                "116.648", "150.265", "318");
        Map<BiliVideoKey, SegmentFetchResult> values = new HashMap<>();
        values.put(key, SegmentFetchResult.json(Collections.singletonList(record),
                "fixture:BV1Jo346uEYC", "p4-contract-v1"));
        return new FixtureSegmentDataSource(values);
    }

    @Override
    public SegmentFetchResult fetch(SegmentRequest request) {
        Objects.requireNonNull(request, "request");
        SegmentFetchResult result = responses.get(request.getVideoKey());
        return result == null ? SegmentFetchResult.notFound("fixture", "none") : result;
    }
}
