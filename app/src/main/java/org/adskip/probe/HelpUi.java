package org.adskip.probe;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.net.Uri;
import java.util.function.Supplier;

final class HelpUi {
    private HelpUi() { }

    static void show(Activity activity, AppUpdater updater, Supplier<String> report, Runnable copyReport) {
        String[] actions = {"检查更新", "GitHub 项目主页", "反馈问题（GitHub）", "邮件反馈",
                "复制兼容性报告", "隐私说明", "许可与致谢"};
        new AlertDialog.Builder(activity).setTitle("23adskip " + BuildConfig.VERSION_NAME)
                .setItems(actions, (dialog, which) -> {
                    switch (which) {
                        case 0:
                            if (updater != null) updater.check(true);
                            else AppUpdater.openPage(activity, UpdateSources.RELEASES);
                            break;
                        case 1: AppUpdater.openPage(activity, UpdateSources.PROJECT); break;
                        case 2: new AlertDialog.Builder(activity).setTitle("反馈问题")
                                .setMessage("请先搜索已有 Issues。建议复制兼容性报告，并说明问题和复现步骤。请勿提交账号、Cookie、完整观看历史或私密链接。")
                                .setPositiveButton("打开 GitHub Issues", (d, w) ->
                                        AppUpdater.openPage(activity, UpdateSources.PROJECT + "/issues"))
                                .setNeutralButton("复制兼容性报告", (d, w) -> copyReport.run())
                                .setNegativeButton("取消", null).show(); break;
                        case 3: email(activity, report.get()); break;
                        case 4: copyReport.run(); break;
                        case 5: new AlertDialog.Builder(activity).setTitle("隐私说明")
                                .setMessage("用于获取 B站播放状态，不需要 B站账号或 Cookie。更新检查约每天一次访问 GitHub；下载仅由你主动发起。兼容性报告和邮件草稿由你检查后自行提交，没有自动遥测。")
                                .setPositiveButton("完整隐私说明", (d, w) -> AppUpdater.openPage(activity, UpdateSources.PROJECT + "/blob/main/PRIVACY.md"))
                                .setNegativeButton("关闭", null).show(); break;
                        case 6: new AlertDialog.Builder(activity).setTitle("许可与致谢")
                                .setMessage("源码：GNU GPL v3（GPL-3.0-only）。\n\n片段数据：BilibiliSponsorBlock / hanydd 及社区标注者。\n\n后台指南：DontKillMyApp / Urbandroid Team，CC BY 4.0 中文改编。\n\n本项目为独立客户端。")
                                .setPositiveButton("完整许可与致谢", (d, w) -> AppUpdater.openPage(activity, UpdateSources.PROJECT + "/blob/main/NOTICE.md"))
                                .setNegativeButton("关闭", null).show(); break;
                        default: break;
                    }
                }).setNegativeButton("关闭", null).show();
    }

    private static void email(Activity activity, String report) {
        String subject = "[23adskip Bug] " + BuildConfig.VERSION_NAME;
        String body = "请描述问题：\n\n复现步骤：\n\n是否稳定复现：\n\n"
                + "请在发送前检查下方报告，勿添加账号、Cookie、完整观看历史或私密链接。\n\n" + report;
        Uri mail = Uri.parse("mailto:sis5595@gmail.com?subject=" + Uri.encode(subject) + "&body=" + Uri.encode(body));
        try { activity.startActivity(new Intent(Intent.ACTION_SENDTO, mail)); }
        catch (RuntimeException error) {
            new AlertDialog.Builder(activity).setTitle("没有可用的邮件客户端")
                    .setMessage("请自行发送邮件到 sis5595@gmail.com，或通过 GitHub Issues 反馈。")
                    .setPositiveButton("复制邮箱和报告", (d, w) -> {
                        ClipboardManager clipboard = activity.getSystemService(ClipboardManager.class);
                        if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("23adskip feedback",
                                "sis5595@gmail.com\n" + subject + "\n\n" + body));
                    }).setNegativeButton("关闭", null).show();
        }
    }
}
