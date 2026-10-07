package org.adskip.core;

import java.net.URI;
import java.util.Locale;

/** Pure update rules; no dependency on playback, Android state or any particular host. */
public final class UpdatePolicy {
    public static final long CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000;
    private UpdatePolicy() { }

    public static boolean due(long now, long lastAttempt) {
        return lastAttempt <= 0 || now < lastAttempt || now - lastAttempt >= CHECK_INTERVAL_MS;
    }

    public static boolean allowedUrl(String value, String[] hosts) {
        try {
            URI uri = new URI(value);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getUserInfo() != null
                    || uri.getFragment() != null || (uri.getPort() != -1 && uri.getPort() != 443)) return false;
            String host = uri.getHost();
            if (host == null) return false;
            for (String allowed : hosts) if (host.toLowerCase(Locale.ROOT).equals(allowed)) return true;
        } catch (Exception ignored) { }
        return false;
    }

    public static boolean validHash(String value) {
        return value != null && value.matches("[0-9a-fA-F]{64}");
    }

    public static void validateArtifact(String expectedPackage, long installedVersion,
            long metadataVersion, String expectedHash, String actualHash,
            String archivePackage, long archiveVersion, String installedCertificate,
            String archiveCertificate, String releaseCertificate) {
        if (!expectedPackage.equals(archivePackage)) throw new IllegalArgumentException("安装包不属于 23adskip");
        if (archiveVersion <= installedVersion || archiveVersion != metadataVersion)
            throw new IllegalArgumentException("安装包版本与更新说明不一致，或不是更新版本");
        if (!validHash(expectedHash) || !expectedHash.equalsIgnoreCase(actualHash))
            throw new IllegalArgumentException("安装包校验失败，请重新下载");
        if (!validHash(releaseCertificate) || !releaseCertificate.equalsIgnoreCase(installedCertificate)
                || !releaseCertificate.equalsIgnoreCase(archiveCertificate))
            throw new IllegalArgumentException("安装包的发行签名不匹配，已拒绝安装");
    }
}
