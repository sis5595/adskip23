package org.adskip.probe;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.SocketTimeoutException;
import android.os.SystemClock;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.adskip.core.ReadOnlyRequestHeaders;

/** Bounded, cookie-free GET transport shared by the two production read-only sources. */
final class ReadOnlyHttp {
    static final int BILI_CONNECT_TIMEOUT_MS = 4_000;
    static final int BILI_READ_TIMEOUT_MS = 4_000;
    // The BSB CDN was observed taking >4 s to first byte on the test device.
    static final int BSB_CONNECT_TIMEOUT_MS = 8_000;
    static final int BSB_READ_TIMEOUT_MS = 8_000;
    static final int MAX_BODY_BYTES = 1_048_576;
    static final long QUERY_DEADLINE_MS = 20_000L;
    private static final ScheduledExecutorService CLEANUP = Executors.newScheduledThreadPool(2, task -> {
        Thread thread = new Thread(task, "adskip-http-cleanup");
        thread.setDaemon(true);
        return thread;
    });

    private ReadOnlyHttp() { }

    static HttpURLConnection open(String address, Cancellation cancellation,
            boolean bsbEndpoint) throws IOException {
        URL url = new URL(address);
        if (!"https".equals(url.getProtocol())) throw new IOException("non-HTTPS endpoint");
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(bsbEndpoint
                ? BSB_CONNECT_TIMEOUT_MS : BILI_CONNECT_TIMEOUT_MS);
        connection.setReadTimeout(bsbEndpoint
                ? BSB_READ_TIMEOUT_MS : BILI_READ_TIMEOUT_MS);
        connection.setRequestMethod("GET");
        ReadOnlyRequestHeaders.apply(connection, bsbEndpoint
                ? ReadOnlyRequestHeaders.Endpoint.BSB
                : ReadOnlyRequestHeaders.Endpoint.BILIBILI_PAGELIST,
                BuildConfig.VERSION_NAME);
        connection.setUseCaches(true);
        cancellation.attach(connection);
        return connection;
    }

    static byte[] readBounded(InputStream input) throws IOException {
        return readBounded(input, null);
    }

    static byte[] readBounded(InputStream input, Cancellation cancellation) throws IOException {
        try (InputStream source = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8_192];
            int total = 0;
            int read;
            while (true) {
                if (cancellation != null) cancellation.check();
                read = source.read(buffer);
                if (cancellation != null) cancellation.check();
                if (read == -1) break;
                total += read;
                if (total > MAX_BODY_BYTES) throw new IOException("response-too-large");
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    static final class Cancellation {
        private volatile boolean cancelled;
        private volatile HttpURLConnection active;
        private final long deadlineMs;
        private ScheduledFuture<?> watchdog;

        Cancellation() { this(QUERY_DEADLINE_MS); }
        Cancellation(long budgetMs) {
            if (budgetMs <= 0) throw new IllegalArgumentException("positive HTTP budget required");
            deadlineMs = SystemClock.elapsedRealtime() + budgetMs;
        }

        boolean isTimedOut() { return SystemClock.elapsedRealtime() >= deadlineMs; }
        void check() throws IOException {
            if (isTimedOut()) throw new SocketTimeoutException("query-deadline");
            if (isCancelled()) throw new IOException("cancelled");
        }

        boolean isCancelled() { return cancelled || Thread.currentThread().isInterrupted(); }

        synchronized void attach(HttpURLConnection connection) throws IOException {
            check();
            active = connection;
            if (watchdog == null) watchdog = CLEANUP.schedule(this::cancel,
                    Math.max(0L, deadlineMs - SystemClock.elapsedRealtime()), TimeUnit.MILLISECONDS);
        }

        void detach(HttpURLConnection connection) {
            boolean owned;
            synchronized (this) {
                owned = active == connection;
                if (owned) active = null;
            }
            if (owned) CLEANUP.execute(connection::disconnect);
        }

        void cancel() {
            HttpURLConnection connection;
            synchronized (this) {
                cancelled = true;
                connection = active;
                active = null;
                close();
            }
            // Never execute blocking transport cleanup on the Android main thread or while
            // holding this handle's monitor. The network reader also checks its total deadline.
            if (connection != null) CLEANUP.execute(connection::disconnect);
        }

        synchronized void close() {
            if (watchdog != null) watchdog.cancel(false);
            watchdog = null;
        }
    }
}
