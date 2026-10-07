package org.adskip.probe;

import org.json.JSONArray;
import org.json.JSONObject;

final class UpdateRepository {
    interface Source { String text(String url, long deadline) throws Exception; }
    private final Source source;
    private final String primary, fallback;
    UpdateRepository(UpdateTransport transport) {
        this(transport::text, UpdateSources.METADATA_PRIMARY, UpdateSources.METADATA_FALLBACK);
    }
    UpdateRepository(Source source, String primary, String fallback) {
        this.source = source; this.primary = primary; this.fallback = fallback;
    }

    UpdateRelease newest(long installed) throws Exception {
        long deadline = System.nanoTime() + 40_000_000_000L;
        try {
            // /releases includes prereleases; /releases/latest would omit the beta channel.
            JSONArray releases = new JSONArray(source.text(primary, deadline));
            UpdateRelease best = null;
            boolean metadataFailed = false;
            for (int i = 0; i < Math.min(20, releases.length()); i++) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                if (System.nanoTime() > deadline) { metadataFailed = true; break; }
                JSONObject release = releases.getJSONObject(i);
                if (release.optBoolean("draft", true)) continue;
                JSONArray assets = release.optJSONArray("assets");
                if (assets == null) continue;
                for (int a = 0; a < assets.length(); a++) {
                    JSONObject asset = assets.getJSONObject(a);
                    if (!"release-metadata.json".equals(asset.optString("name"))) continue;
                    try {
                        String metadataUrl = asset.getString("browser_download_url");
                        if (!metadataUrl.startsWith(UpdateSources.APK_PRIMARY_PREFIX)
                                || !org.adskip.core.UpdatePolicy.allowedUrl(metadataUrl, UpdateSources.DOWNLOAD_HOSTS))
                            throw new IllegalArgumentException("更新来源无效");
                        JSONObject metadata = new JSONObject(source.text(metadataUrl, deadline));
                        // Earlier releases lacked update protocol fields and cannot be upgrade candidates.
                        if (!metadata.has("channel") && metadata.optLong("versionCode", Long.MAX_VALUE) <= installed) continue;
                        UpdateRelease parsed = new UpdateRelease(metadata);
                        if (!parsed.releaseUrl.equals(release.getString("html_url"))) throw new IllegalArgumentException("发布页不匹配");
                        if (parsed.versionCode > installed && (best == null || parsed.versionCode > best.versionCode)) best = parsed;
                    } catch (Exception failure) { metadataFailed = true; }
                }
            }
            if (best == null && metadataFailed) throw new IllegalArgumentException("部分更新信息无法验证，请查看发布页");
            return best;
        } catch (Exception primaryFailure) {
            if (fallback.isEmpty() || Thread.currentThread().isInterrupted() || System.nanoTime() >= deadline) throw primaryFailure;
            UpdateRelease parsed = new UpdateRelease(new JSONObject(source.text(fallback, deadline)));
            return parsed.versionCode > installed ? parsed : null;
        }
    }
}
