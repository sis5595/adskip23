package org.adskip.core;

import java.net.HttpURLConnection;

/** Shared, offline-testable header policy for the two production GET transports. */
public final class ReadOnlyRequestHeaders {
    public enum Endpoint { BSB, BILIBILI_PAGELIST }

    private ReadOnlyRequestHeaders() { }

    public static void apply(HttpURLConnection connection, Endpoint endpoint, String version) {
        if (connection == null || endpoint == null || version == null || version.isEmpty()) {
            throw new IllegalArgumentException("missing request header inputs");
        }
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("User-Agent", "AdSkipAndroid/" + version);
        if (endpoint == Endpoint.BSB) {
            connection.setRequestProperty("origin", "adskip-android");
            connection.setRequestProperty("x-ext-version", version);
        } else {
            connection.setRequestProperty("Referer", "https://www.bilibili.com/");
        }
    }
}
