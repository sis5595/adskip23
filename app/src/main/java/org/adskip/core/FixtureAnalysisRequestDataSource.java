package org.adskip.core;

/** Local-only request transport used to prove the request flow without a production endpoint. */
public final class FixtureAnalysisRequestDataSource implements AnalysisRequestDataSource {
    private final AnalysisRequestResult result;
    private BiliVideoKey lastVideoKey;

    public FixtureAnalysisRequestDataSource(AnalysisRequestResult.State state) {
        result = new AnalysisRequestResult(state, "local-fixture-v1");
    }

    @Override
    public AnalysisRequestResult submit(BiliVideoKey videoKey, String client, String clientVersion) {
        if (videoKey == null || client == null || client.trim().isEmpty()
                || clientVersion == null || clientVersion.trim().isEmpty()) {
            throw new IllegalArgumentException("invalid analysis request");
        }
        lastVideoKey = videoKey;
        return result;
    }

    public BiliVideoKey getLastVideoKey() { return lastVideoKey; }
}
