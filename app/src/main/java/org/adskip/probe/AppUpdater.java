package org.adskip.probe;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Toast;
import java.io.File;
import java.util.Locale;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.adskip.core.UpdatePolicy;

/** Activity-scoped update UI/worker. It never calls the playback coordinator. */
final class AppUpdater {
    private final Activity activity;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final UpdateTransport transport;
    private boolean foreground, closed, busy;
    private Runnable pending;
    private final List<AlertDialog> dialogs = new ArrayList<>();

    AppUpdater(Activity activity) { this(activity, new UpdateTransport()); }
    AppUpdater(Activity activity, UpdateTransport transport) { this.activity = activity; this.transport = transport; }

    void resume() {
        foreground = true;
        if (pending != null) { Runnable task = pending; pending = null; task.run(); return; }
        check(false);
    }
    void pause() { foreground = false; }
    void close() {
        closed = true; pending = null; transport.cancel(); worker.shutdownNow(); main.removeCallbacksAndMessages(null);
        for (AlertDialog dialog : new ArrayList<>(dialogs)) dialog.dismiss();
        dialogs.clear();
    }

    private void show(AlertDialog.Builder builder) {
        if (closed || activity.isFinishing() || activity.isDestroyed()) return;
        AlertDialog dialog = builder.create();
        dialogs.add(dialog);
        dialog.setOnDismissListener(ignored -> dialogs.remove(dialog));
        dialog.show();
    }

    private void deliver(Runnable task) {
        main.post(() -> {
            if (closed || activity.isFinishing() || activity.isDestroyed()) return;
            busy = false;
            if (foreground) task.run(); else pending = task;
        });
    }

    void check(boolean manual) {
        if (closed) return;
        if (busy) {
            if (manual) Toast.makeText(activity, "更新任务正在进行，请稍候", Toast.LENGTH_SHORT).show();
            return;
        }
        SharedPreferences state = activity.getSharedPreferences("app_updates", Activity.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        if (!manual && !UpdatePolicy.due(now, state.getLong("last_attempt", 0))) return;
        state.edit().putLong("last_attempt", now).apply();
        busy = true;
        if (manual) Toast.makeText(activity, "正在检查更新", Toast.LENGTH_SHORT).show();
        worker.execute(() -> {
            try {
                UpdateRelease release = new UpdateRepository(transport).newest(BuildConfig.VERSION_CODE);
                deliver(() -> {
                    if (release != null) offer(release);
                    else if (manual) show(new AlertDialog.Builder(activity).setMessage("当前没有可用的新版本。")
                            .setPositiveButton("确定", null));
                });
            } catch (Exception error) {
                deliver(() -> { if (manual) failure("暂时无法检查更新。你可以稍后重试，或在浏览器查看发布页。", UpdateSources.RELEASES); });
            }
        });
    }

    private void offer(UpdateRelease release) {
        show(new AlertDialog.Builder(activity).setTitle("发现新版本 " + release.versionName)
                .setMessage(release.notes)
                .setPositiveButton("下载并安装", (dialog, which) -> download(release))
                .setNeutralButton("查看发布页", (dialog, which) -> openPage(activity, release.releaseUrl))
                .setNegativeButton("稍后", null));
    }

    private void download(UpdateRelease release) {
        if (busy || closed) return;
        busy = true;
        Toast.makeText(activity, "正在下载，完成校验后会提示安装", Toast.LENGTH_LONG).show();
        worker.execute(() -> {
            File directory = new File(activity.getCacheDir(), "verified-updates");
            File partial = new File(directory, "download-" + java.util.UUID.randomUUID() + ".part");
            File target = new File(directory, release.sha256.toLowerCase(Locale.ROOT) + ".apk");
            try {
                if (!directory.isDirectory() && !directory.mkdirs()) throw new IllegalStateException("无法创建下载目录");
                File[] old = directory.listFiles();
                if (old != null) for (File file : old) {
                    if (!file.equals(target) && System.currentTimeMillis() - file.lastModified() > UpdatePolicy.CHECK_INTERVAL_MS) file.delete();
                }
                try { transport.download(release.apkUrl, partial); }
                catch (Exception primary) {
                    if (release.apkMirrorUrl.isEmpty()) throw primary;
                    transport.download(release.apkMirrorUrl, partial);
                }
                UpdateApkVerifier.verify(activity, partial, release);
                if (target.exists() && !target.delete()) throw new IllegalStateException("无法替换下载文件");
                if (!partial.renameTo(target)) throw new IllegalStateException("无法保存安装包");
                deliver(() -> ready(target, release));
            } catch (Exception error) {
                partial.delete();
                deliver(() -> failure("下载或校验失败：" + safeMessage(error), release.releaseUrl));
            }
        });
    }

    private void ready(File file, UpdateRelease release) {
        show(new AlertDialog.Builder(activity).setTitle("安装包校验通过")
                .setMessage("准备安装 " + release.versionName + "。系统会要求你确认安装。")
                .setPositiveButton("继续安装", (dialog, which) -> install(file, release))
                .setNeutralButton("查看发布页", (dialog, which) -> openPage(activity, release.releaseUrl))
                .setNegativeButton("稍后", null));
    }

    private void install(File file, UpdateRelease release) {
        if (busy || closed) return;
        if (!activity.getPackageManager().canRequestPackageInstalls()) {
            show(new AlertDialog.Builder(activity).setTitle("允许安装更新")
                    .setMessage("请在系统设置中允许 23adskip 安装应用。返回后会再次提示，由你确认继续安装。")
                    .setPositiveButton("打开系统设置", (dialog, which) -> {
                        pending = () -> ready(file, release);
                        try { activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:" + activity.getPackageName()))); }
                        catch (RuntimeException error) { pending = null; failure("无法打开安装授权设置", release.releaseUrl); }
                    }).setNegativeButton("取消", null));
            return;
        }
        busy = true;
        worker.execute(() -> {
            try {
                // Recheck after any permission round-trip or intervening upgrade.
                UpdateApkVerifier.verify(activity, file, release);
                deliver(() -> {
                    Intent intent = installIntent(activity, file);
                    try { activity.startActivity(intent); }
                    catch (RuntimeException error) { failure("无法打开系统安装器", release.releaseUrl); }
                });
            } catch (Exception error) { deliver(() -> failure("安装前校验失败：" + safeMessage(error), release.releaseUrl)); }
        });
    }

    private static String safeMessage(Exception error) {
        // Do not display raw redirect URLs (they can carry temporary signed parameters).
        return error instanceof IllegalArgumentException ? error.getMessage() : "网络不可用、文件不可用或请求超时。请重试。";
    }

    private void failure(String message, String releaseUrl) {
        show(new AlertDialog.Builder(activity).setTitle("更新未完成").setMessage(message)
                .setPositiveButton("查看 GitHub 发布页", (dialog, which) -> openPage(activity, releaseUrl))
                .setNeutralButton("国内备用下载", (dialog, which) -> show(new AlertDialog.Builder(activity)
                        .setMessage("蓝奏云仅为备用镜像，提取密码：2233。请以 GitHub 发布版本为准。")
                        .setPositiveButton("打开蓝奏云", (d, w) -> openPage(activity, UpdateSources.APK_MIRROR_PAGE))
                        .setNegativeButton("取消", null)))
                .setNegativeButton("关闭", null));
    }

    static void openPage(Activity activity, String url) {
        try { activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
        catch (RuntimeException error) { Toast.makeText(activity, "没有可用的浏览器，请稍后重试", Toast.LENGTH_LONG).show(); }
    }

    static Intent installIntent(Activity activity, File file) {
        Uri uri = new Uri.Builder().scheme("content").authority(activity.getPackageName() + ".updates")
                .appendPath(file.getName()).build();
        Intent intent = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.setClipData(ClipData.newRawUri("23adskip update", uri));
        return intent;
    }
}
