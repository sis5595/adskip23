package org.adskip.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Transport-level outcome. It contains no selection or playback semantics. */
public final class SegmentFetchResult {
    public enum Kind {
        HTTP_200_JSON,
        HTTP_200_EMPTY,
        HTTP_404,
        HTTP_ERROR,
        MALFORMED_JSON,
        NETWORK_ERROR,
        TIMEOUT,
        CANCELLED
    }

    private final Kind kind;
    private final List<CrowdSegmentRecord> records;
    private final String source;
    private final String version;

    private SegmentFetchResult(Kind kind, List<CrowdSegmentRecord> records, String source, String version) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.records = Collections.unmodifiableList(new ArrayList<>(Objects.requireNonNull(records, "records")));
        this.source = requireText(source, "source");
        this.version = requireText(version, "version");
        if (kind != Kind.HTTP_200_JSON && !this.records.isEmpty()) {
            throw new IllegalArgumentException("only HTTP_200_JSON may carry records");
        }
    }

    public static SegmentFetchResult json(List<CrowdSegmentRecord> records, String source, String version) {
        if (records == null || records.isEmpty()) {
            return empty(source, version);
        }
        return new SegmentFetchResult(Kind.HTTP_200_JSON, records, source, version);
    }
    public static SegmentFetchResult empty(String source, String version) {
        return new SegmentFetchResult(Kind.HTTP_200_EMPTY, Collections.<CrowdSegmentRecord>emptyList(), source, version);
    }
    public static SegmentFetchResult notFound(String source, String version) {
        return new SegmentFetchResult(Kind.HTTP_404, Collections.<CrowdSegmentRecord>emptyList(), source, version);
    }
    public static SegmentFetchResult httpError(String source, String version) {
        return new SegmentFetchResult(Kind.HTTP_ERROR, Collections.<CrowdSegmentRecord>emptyList(), source, version);
    }
    public static SegmentFetchResult malformed(String source, String version) {
        return new SegmentFetchResult(Kind.MALFORMED_JSON, Collections.<CrowdSegmentRecord>emptyList(), source, version);
    }
    public static SegmentFetchResult timeout(String source, String version) {
        return new SegmentFetchResult(Kind.TIMEOUT, Collections.<CrowdSegmentRecord>emptyList(), source, version);
    }
    public static SegmentFetchResult networkError(String source, String version) {
        return new SegmentFetchResult(Kind.NETWORK_ERROR, Collections.<CrowdSegmentRecord>emptyList(), source, version);
    }
    public static SegmentFetchResult cancelled(String source, String version) {
        return new SegmentFetchResult(Kind.CANCELLED, Collections.<CrowdSegmentRecord>emptyList(), source, version);
    }
    public Kind getKind() { return kind; }
    public List<CrowdSegmentRecord> getRecords() { return records; }
    public String getSource() { return source; }
    public String getVersion() { return version; }

    private static String requireText(String value, String name) {
        if (value == null || value.trim().isEmpty() || !value.equals(value.trim())) {
            throw new IllegalArgumentException(name + " must be nonblank and unpadded");
        }
        return value;
    }
}
