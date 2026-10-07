package org.adskip.probe;

import android.os.SystemClock;

import org.adskip.core.PageCatalog;
import org.adskip.core.PageListMapper;
import org.adskip.core.PageListRecord;
import org.adskip.core.ReadOnlyHttpStatusPolicy;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Production read-only public pagelist source; never determines current P by itself. */
final class BiliSinglePageCatalogSource {
    private static final String VERSION = "bili-pagelist-prod-v1";
    private static volatile int lastHttpStatus;

    static int lastHttpStatus() { return lastHttpStatus; }

    PageCatalog fetch(String bvid, ReadOnlyHttp.Cancellation cancellation) {
        if (cancellation.isCancelled()) return failed(PageCatalog.State.CANCELLED, bvid);
        HttpURLConnection connection = null;
        try {
            connection = ReadOnlyHttp.open(
                    "https://api.bilibili.com/x/player/pagelist?bvid=" + bvid,
                    cancellation, false);
            lastHttpStatus = connection.getResponseCode();
            if (lastHttpStatus != HttpURLConnection.HTTP_OK) {
                return failed(ReadOnlyHttpStatusPolicy.classify(lastHttpStatus)
                        == ReadOnlyHttpStatusPolicy.Outcome.RETRYABLE
                        ? PageCatalog.State.NETWORK_ERROR : PageCatalog.State.HTTP_ERROR, bvid);
            }
            byte[] body = ReadOnlyHttp.readBounded(connection.getInputStream(), cancellation);
            if (cancellation.isCancelled()) return failed(PageCatalog.State.CANCELLED, bvid);
            JSONObject root = new JSONObject(new String(body, StandardCharsets.UTF_8));
            if (root.getInt("code") != 0) return failed(PageCatalog.State.HTTP_ERROR, bvid);
            JSONArray data = root.getJSONArray("data");
            if (data.length() < 1 || data.length() > 1_000) {
                return failed(data.length() == 0 ? PageCatalog.State.EMPTY
                        : PageCatalog.State.MALFORMED, bvid);
            }
            List<PageListRecord> records = new ArrayList<>();
            for (int index = 0; index < data.length(); index++) {
                JSONObject item = data.getJSONObject(index);
                records.add(new PageListRecord(Long.toString(item.getLong("cid")),
                        item.getLong("page"), item.getLong("duration"),
                        item.getString("part")));
            }
            return PageListMapper.mapCatalog(bvid, records,
                    SystemClock.elapsedRealtime(), VERSION);
        } catch (SocketTimeoutException exception) {
            return failed(PageCatalog.State.TIMEOUT, bvid);
        } catch (JSONException | IllegalArgumentException exception) {
            return failed(PageCatalog.State.MALFORMED, bvid);
        } catch (IOException exception) {
            return failed(cancellation.isTimedOut() ? PageCatalog.State.TIMEOUT
                    : cancellation.isCancelled() ? PageCatalog.State.CANCELLED
                    : PageCatalog.State.NETWORK_ERROR, bvid);
        } finally {
            if (connection != null) cancellation.detach(connection);
        }
    }

    private static PageCatalog failed(PageCatalog.State state, String bvid) {
        return PageCatalog.failed(state, bvid, SystemClock.elapsedRealtime(), VERSION);
    }
}
