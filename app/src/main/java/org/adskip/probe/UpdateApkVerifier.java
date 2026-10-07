package org.adskip.probe;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import java.io.File;
import java.io.FileInputStream;
import java.security.MessageDigest;
import org.adskip.core.UpdatePolicy;

final class UpdateApkVerifier {
    private UpdateApkVerifier() { }

    static void verify(Context context, File apk, UpdateRelease release) throws Exception {
        PackageManager manager = context.getPackageManager();
        int flags = Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
        PackageInfo archive = manager.getPackageArchiveInfo(apk.getAbsolutePath(), flags);
        PackageInfo installed = manager.getPackageInfo(context.getPackageName(), flags);
        if (archive == null || archive.applicationInfo == null) throw new IllegalArgumentException("无法识别安装包");
        if (!release.versionName.equals(archive.versionName)) throw new IllegalArgumentException("安装包版本名称与发布说明不一致");
        if ((archive.applicationInfo.flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0)
            throw new IllegalArgumentException("不能安装开发调试包");
        UpdatePolicy.validateArtifact("com.sis5595.adskip23", version(installed), release.versionCode,
                release.sha256, hash(apk), archive.packageName, version(archive),
                certificate(installed), certificate(archive), UpdateSources.CERTIFICATE);
    }

    private static long version(PackageInfo info) {
        return Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
    }

    private static String certificate(PackageInfo info) throws Exception {
        Signature[] signers = Build.VERSION.SDK_INT >= 28
                ? (info.signingInfo == null ? null : info.signingInfo.getApkContentsSigners()) : info.signatures;
        if (signers == null || signers.length != 1) throw new IllegalArgumentException("无法验证发行签名");
        return hex(MessageDigest.getInstance("SHA-256").digest(signers[0].toByteArray()));
    }

    static String hash(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
        }
        return hex(digest.digest());
    }

    private static String hex(byte[] value) {
        StringBuilder result = new StringBuilder();
        for (byte b : value) result.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return result.toString();
    }
}
