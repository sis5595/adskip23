package org.adskip.core;

import java.util.Objects;

/** Keeps request submission independent from playback evidence, rule selection, and SkipEngine. */
public final class AnalysisRequestRepository {
    private final AnalysisRequestDataSource dataSource;
    private final String client;
    private final String clientVersion;

    public AnalysisRequestRepository(AnalysisRequestDataSource dataSource,
            String client, String clientVersion) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        if (client == null || client.trim().isEmpty()
                || clientVersion == null || clientVersion.trim().isEmpty()) {
            throw new IllegalArgumentException("invalid client identity");
        }
        this.client = client;
        this.clientVersion = clientVersion;
    }

    public AnalysisRequestResult submit(AnalysisRequestTarget target) {
        Objects.requireNonNull(target, "target");
        return dataSource.submit(target.getVideoKey(), client, clientVersion);
    }
}
