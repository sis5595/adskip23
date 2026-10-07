package org.adskip.core;

/**
 * Transport boundary for exact-CID segment data. Implementations never select rules or seek.
 * The production implementation may perform read-only HTTP. Harness keeps a fixture-only
 * implementation. Neither transport can select rules or authorize a seek.
 */
public interface SegmentDataSource {
    SegmentFetchResult fetch(SegmentRequest request);
}
