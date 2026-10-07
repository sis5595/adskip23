package org.adskip.probe;

import org.adskip.core.UpdatePolicy;
import org.json.JSONObject;

final class UpdateRelease {
    final long versionCode;
    final String versionName, apkUrl, apkMirrorUrl, releaseUrl, sha256, notes;

    UpdateRelease(JSONObject json) throws Exception {
        Object code = json.get("versionCode");
        if (!(code instanceof Number) || ((Number) code).doubleValue() != ((Number) code).longValue())
            throw new IllegalArgumentException("更新版本信息无效");
        versionCode = ((Number) code).longValue();
        versionName = string(json, "versionName");
        apkUrl = string(json, "apkUrl");
        apkMirrorUrl = json.has("apkMirrorUrl") ? string(json, "apkMirrorUrl") : "";
        releaseUrl = string(json, "releaseUrl");
        sha256 = string(json, "sha256");
        String channel = string(json, "channel");
        String summary = json.has("notes") ? string(json, "notes") : "请查看发布页了解本次更新。";
        notes = summary.substring(0, Math.min(summary.length(), 1600));
        if (versionCode < 1 || versionCode > Integer.MAX_VALUE || versionName.trim().isEmpty()
                || versionName.matches("(?s).*[\\x00-\\x1f].*")
                || versionName.length() > 80 || !("beta".equals(channel) || "stable".equals(channel))
                || !UpdatePolicy.validHash(sha256)
                || !UpdatePolicy.allowedUrl(apkUrl, UpdateSources.DOWNLOAD_HOSTS)
                || !apkUrl.startsWith(UpdateSources.APK_PRIMARY_PREFIX)
                || !UpdatePolicy.allowedUrl(releaseUrl, new String[]{"github.com"})
                || !releaseUrl.startsWith(UpdateSources.RELEASES + "/tag/")
                || (!apkMirrorUrl.isEmpty() && !UpdatePolicy.allowedUrl(apkMirrorUrl, UpdateSources.DOWNLOAD_HOSTS)))
            throw new IllegalArgumentException("更新来源或版本信息无效");
        String tag = releaseUrl.substring((UpdateSources.RELEASES + "/tag/").length());
        if (!tag.matches("v[0-9][0-9A-Za-z.\\-]*") || !tag.equals("v" + versionName)
                || !apkUrl.equals(UpdateSources.APK_PRIMARY_PREFIX + tag + "/adskip-android-universal.apk"))
            throw new IllegalArgumentException("安装包与发布版本不匹配");
    }

    private static String string(JSONObject json, String key) throws Exception {
        Object value = json.get(key);
        if (!(value instanceof String)) throw new IllegalArgumentException("更新字段类型无效");
        return (String) value;
    }
}
