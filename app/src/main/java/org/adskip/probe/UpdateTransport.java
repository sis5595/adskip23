package org.adskip.probe;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.HttpsURLConnection;
import org.adskip.core.UpdatePolicy;

/** Bounded HTTPS reader, isolated from playback's HTTP transport and caches. */
final class UpdateTransport {
    interface Connections { HttpsURLConnection open(URL url) throws IOException; }
    private static final ScheduledExecutorService DEADLINES = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "23adskip-update-deadline");
        thread.setDaemon(true);
        return thread;
    });
    private final Connections connections;
    private volatile HttpsURLConnection active;
    private volatile boolean cancelled;

    UpdateTransport() { this(url -> (HttpsURLConnection) url.openConnection()); }
    UpdateTransport(Connections connections) { this.connections = connections; }

    void cancel() { cancelled = true; HttpsURLConnection c = active; if (c != null) c.disconnect(); }

    String text(String url) throws IOException {
        return text(url, System.nanoTime() + 20_000_000_000L);
    }

    String text(String url, long deadline) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        transfer(url, output, 1024 * 1024, deadline);
        return output.toString(StandardCharsets.UTF_8.name());
    }

    void download(String url, File file) throws IOException {
        try (OutputStream output = new FileOutputStream(file)) {
            transfer(url, output, 100L * 1024 * 1024, System.nanoTime() + 180_000_000_000L);
        } catch (IOException | RuntimeException error) { file.delete(); throw error; }
    }

    private void ensureActive(long deadline) throws IOException {
        if (cancelled || Thread.currentThread().isInterrupted() || System.nanoTime() >= deadline)
            throw new IOException("已取消或超时");
    }

    private void transfer(String start, OutputStream output, long maximum, long deadline) throws IOException {
        String next = start;
        for (int redirects = 0; redirects <= 5; redirects++) {
            ensureActive(deadline);
            if (!UpdatePolicy.allowedUrl(next, UpdateSources.DOWNLOAD_HOSTS)) throw new IOException("下载来源不受支持");
            HttpsURLConnection connection = connections.open(new URL(next));
            active = connection;
            ScheduledFuture<?> watchdog = DEADLINES.schedule(connection::disconnect,
                    Math.max(0L, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            try {
                ensureActive(deadline);
                connection.setInstanceFollowRedirects(false);
                int remaining = (int) Math.max(1, Math.min(8000L, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())));
                connection.setConnectTimeout(remaining);
                connection.setReadTimeout(remaining);
                connection.setRequestProperty("User-Agent", "23adskip-Update/" + BuildConfig.VERSION_NAME);
                connection.setRequestProperty("Accept", "application/json, application/octet-stream");
                connection.setRequestProperty("Accept-Encoding", "identity");
                connection.setUseCaches(false);
                int status = connection.getResponseCode();
                ensureActive(deadline);
                if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                    String location = connection.getHeaderField("Location");
                    if (location == null) throw new IOException("下载地址无效");
                    next = new URL(new URL(next), location).toString();
                    continue;
                }
                if (status != 200) throw new IOException("更新服务暂时不可用（" + status + "）");
                long length = connection.getContentLengthLong();
                if (length > maximum) throw new IOException("下载文件过大");
                long total = 0;
                try (InputStream input = connection.getInputStream()) {
                    byte[] buffer = new byte[16 * 1024];
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        ensureActive(deadline);
                        total += count;
                        if (total > maximum) throw new IOException("下载文件过大");
                        output.write(buffer, 0, count);
                    }
                }
                ensureActive(deadline);
                if (length >= 0 && length != total) throw new IOException("下载文件不完整");
                return;
            } finally { watchdog.cancel(false); connection.disconnect(); active = null; }
        }
        throw new IOException("下载跳转次数过多");
    }
}
