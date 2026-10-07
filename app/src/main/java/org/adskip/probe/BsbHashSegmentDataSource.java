package org.adskip.probe;

import android.os.SystemClock;

import org.adskip.core.BsbEndpointPolicy;
import org.adskip.core.CrowdSegmentRecord;
import org.adskip.core.SegmentDataSource;
import org.adskip.core.SegmentFetchResult;
import org.adskip.core.SegmentRequest;
import org.adskip.core.ReadOnlyHttpStatusPolicy;
import org.adskip.core.ReadOnlyTransportTiming;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Read-only hash-prefix transport. All bucket entries are locally checked against exact BVID+CID. */
final class BsbHashSegmentDataSource implements SegmentDataSource {
    private static final String SOURCE = "BilibiliSponsorBlock/hash-prefix";
    private static final String VERSION = "api-v1";
    private static final long HIT_TTL_MS = 60_000L;
    private static final long MISS_TTL_MS = 20_000L;
    private static final int MAX_CACHE_ENTRIES = 32;
    private static final int MAX_SEGMENTS = 2_000;

    private final String origin;
    private final ReadOnlyHttp.Cancellation cancellation;
    private ReadOnlyTransportTiming timing;
    private long validUntilMs;
    private final long cacheEpoch;
    private final boolean forceNetwork;
    private static long currentCacheEpoch;
    private static final Map<String, Cached> CACHE = new LinkedHashMap<String, Cached>(32, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Cached> eldest) {
            return size() > MAX_CACHE_ENTRIES;
        }
    };

    BsbHashSegmentDataSource(String origin, ReadOnlyHttp.Cancellation cancellation) {
        this(origin, cancellation, false);
    }

    BsbHashSegmentDataSource(String origin, ReadOnlyHttp.Cancellation cancellation, boolean forceNetwork) {
        this.origin = BsbEndpointPolicy.normalizeOrigin(origin);
        this.cancellation = cancellation;
        this.forceNetwork = forceNetwork;
        synchronized (CACHE) { cacheEpoch = currentCacheEpoch; }
    }

    static void clearCache() {
        synchronized (CACHE) { currentCacheEpoch++; CACHE.clear(); }
    }
    long validUntilMs() { return validUntilMs; }
    ReadOnlyTransportTiming timing() { return timing; }

    @Override
    public SegmentFetchResult fetch(SegmentRequest request) {
        if (cancellation.isCancelled()) {
            timing = ReadOnlyTransportTiming.failedBeforeResponse(
                    ReadOnlyTransportTiming.Failure.CANCELLED, 0);
            return SegmentFetchResult.cancelled(SOURCE, VERSION);
        }
        String key = origin + "/" + request.getVideoKey();
        long now = SystemClock.elapsedRealtime();
        synchronized (CACHE) {
            Cached hit = CACHE.get(key);
            if (!forceNetwork && hit != null && now < hit.expiresAtMs) {
                validUntilMs = hit.expiresAtMs;
                timing = ReadOnlyTransportTiming.cacheHit();
                return hit.result;
            }
            if (hit != null && now >= hit.expiresAtMs) CACHE.remove(key);
        }
        HttpURLConnection connection = null;
        SegmentFetchResult result;
        long startedAtMs = SystemClock.elapsedRealtime();
        long responseAtMs = -1L;
        int statusCode = 0;
        try {
            connection = ReadOnlyHttp.open(
                    BsbEndpointPolicy.queryUrl(origin, request.getBvid()), cancellation, true);
            int status = connection.getResponseCode();
            statusCode = status;
            responseAtMs = SystemClock.elapsedRealtime();
            ReadOnlyHttpStatusPolicy.Outcome outcome = ReadOnlyHttpStatusPolicy.classify(status);
            if (outcome == ReadOnlyHttpStatusPolicy.Outcome.NOT_FOUND) {
                timing = ReadOnlyTransportTiming.completed(status, responseAtMs - startedAtMs, 0);
                result = SegmentFetchResult.notFound(SOURCE, VERSION);
            } else if (outcome == ReadOnlyHttpStatusPolicy.Outcome.RETRYABLE) {
                timing = ReadOnlyTransportTiming.completed(status, responseAtMs - startedAtMs, 0);
                return SegmentFetchResult.networkError(SOURCE, VERSION);
            } else if (outcome == ReadOnlyHttpStatusPolicy.Outcome.REJECTED) {
                timing = ReadOnlyTransportTiming.completed(status, responseAtMs - startedAtMs, 0);
                return SegmentFetchResult.httpError(SOURCE, VERSION);
            } else {
                byte[] body = ReadOnlyHttp.readBounded(connection.getInputStream(), cancellation);
                long bodyReadMs = SystemClock.elapsedRealtime() - responseAtMs;
                if (cancellation.isCancelled()) {
                    timing = ReadOnlyTransportTiming.failedReadingBody(
                            ReadOnlyTransportTiming.Failure.CANCELLED, status,
                            responseAtMs - startedAtMs, bodyReadMs);
                    return SegmentFetchResult.cancelled(SOURCE, VERSION);
                }
                timing = ReadOnlyTransportTiming.completed(status,
                        responseAtMs - startedAtMs, bodyReadMs);
                result = decode(body, request);
            }
        } catch (SocketTimeoutException exception) {
            long failedAtMs = SystemClock.elapsedRealtime();
            timing = responseAtMs < 0L
                    ? ReadOnlyTransportTiming.failedBeforeResponse(
                            ReadOnlyTransportTiming.Failure.TIMEOUT, failedAtMs - startedAtMs)
                    : ReadOnlyTransportTiming.failedReadingBody(
                            ReadOnlyTransportTiming.Failure.TIMEOUT, statusCode,
                            responseAtMs - startedAtMs, failedAtMs - responseAtMs);
            return SegmentFetchResult.timeout(SOURCE, VERSION);
        } catch (IOException exception) {
            long failedAtMs = SystemClock.elapsedRealtime();
            ReadOnlyTransportTiming.Failure failure = cancellation.isTimedOut()
                    ? ReadOnlyTransportTiming.Failure.TIMEOUT : cancellation.isCancelled()
                    ? ReadOnlyTransportTiming.Failure.CANCELLED
                    : ReadOnlyTransportTiming.Failure.NETWORK_ERROR;
            timing = responseAtMs < 0L
                    ? ReadOnlyTransportTiming.failedBeforeResponse(
                            failure, failedAtMs - startedAtMs)
                    : ReadOnlyTransportTiming.failedReadingBody(failure, statusCode,
                            responseAtMs - startedAtMs, failedAtMs - responseAtMs);
            return cancellation.isTimedOut() ? SegmentFetchResult.timeout(SOURCE, VERSION)
                    : cancellation.isCancelled() ? SegmentFetchResult.cancelled(SOURCE, VERSION)
                    : SegmentFetchResult.networkError(SOURCE, VERSION);
        } finally {
            if (connection != null) cancellation.detach(connection);
        }
        if (cancellation.isCancelled()) return SegmentFetchResult.cancelled(SOURCE, VERSION);
        if (result.getKind() == SegmentFetchResult.Kind.HTTP_200_JSON
                || result.getKind() == SegmentFetchResult.Kind.HTTP_200_EMPTY
                || result.getKind() == SegmentFetchResult.Kind.HTTP_404) {
            long ttl = result.getKind() == SegmentFetchResult.Kind.HTTP_200_JSON
                    ? HIT_TTL_MS : MISS_TTL_MS;
            validUntilMs = SystemClock.elapsedRealtime() + ttl;
            synchronized (CACHE) {
                // An older in-flight response must not undo a manual cache invalidation.
                if (cacheEpoch == currentCacheEpoch && !cancellation.isCancelled()) {
                    CACHE.put(key, new Cached(result, validUntilMs));
                }
            }
        }
        return result;
    }

    static SegmentFetchResult decode(byte[] body, SegmentRequest request) {
        if (body.length == 0) return SegmentFetchResult.empty(SOURCE, VERSION);
        try {
            JSONArray bucket = new JSONArray(new String(body, StandardCharsets.UTF_8));
            if (bucket.length() > 1_000) return SegmentFetchResult.malformed(SOURCE, VERSION);
            JSONObject matched = null;
            for (int index = 0; index < bucket.length(); index++) {
                JSONObject item = bucket.getJSONObject(index);
                Object videoId = item.get("videoID");
                if (!(videoId instanceof String)
                        || !request.getBvid().equals(videoId)) continue;
                if (matched != null) return SegmentFetchResult.malformed(SOURCE, VERSION);
                matched = item;
            }
            if (matched == null) return SegmentFetchResult.empty(SOURCE, VERSION);
            JSONArray segments = matched.getJSONArray("segments");
            if (segments.length() > MAX_SEGMENTS) {
                return SegmentFetchResult.malformed(SOURCE, VERSION);
            }
            List<CrowdSegmentRecord> records = new ArrayList<>();
            for (int index = 0; index < segments.length(); index++) {
                JSONObject item = segments.getJSONObject(index);
                Object cidValue = item.get("cid");
                if (!(cidValue instanceof String) && !(cidValue instanceof Number)) {
                    return SegmentFetchResult.malformed(SOURCE, VERSION);
                }
                String cid = cidValue.toString();
                if (!request.getCid().equals(cid)) continue;
                JSONArray interval = item.getJSONArray("segment");
                if (interval.length() != 2
                        || !(interval.get(0) instanceof Number)
                        || !(interval.get(1) instanceof Number)) {
                    return SegmentFetchResult.malformed(SOURCE, VERSION);
                }
                Object duration = item.get("videoDuration");
                if (!(duration instanceof Number)
                        || !(item.get("UUID") instanceof String)
                        || !(item.get("category") instanceof String)
                        || !(item.get("actionType") instanceof String)) {
                    return SegmentFetchResult.malformed(SOURCE, VERSION);
                }
                records.add(new CrowdSegmentRecord(request.getBvid(), cid,
                        item.getString("UUID"), item.getString("category"),
                        item.getString("actionType"), interval.get(0).toString(),
                        interval.get(1).toString(), duration.toString()));
            }
            return SegmentFetchResult.json(records, SOURCE, VERSION);
        } catch (JSONException | IllegalArgumentException exception) {
            return SegmentFetchResult.malformed(SOURCE, VERSION);
        }
    }

    private static final class Cached {
        final SegmentFetchResult result;
        final long expiresAtMs;
        Cached(SegmentFetchResult result, long expiresAtMs) {
            this.result = result;
            this.expiresAtMs = expiresAtMs;
        }
    }
}
