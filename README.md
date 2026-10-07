# 23adskip

23adskip 是一个 Android 小工具，可以在使用哔哩哔哩官方客户端看视频时，自动跳过社区已经标注好的赞助广告等片段。片段数据来自 BilibiliSponsorBlock 社区；不修改 B站 APK，不需要 root 或无障碍服务。

## 基本情况

- 当前处于 Beta 阶段，支持 Android 8.0 及以上。
- 主要支持普通“单集视频”。如果一个稿件里还能切换分 P，目前暂不自动跳过，以避免跳错。
- 自动跳过默认关闭；开启后默认只跳过赞助广告，可自行选择其它类别。
- 没有社区标注的视频不会自动跳过。

## 使用方法

1. 从 [GitHub Releases](https://github.com/sis5595/adskip23/releases) 下载最新版 `adskip-android-universal.apk`。APK 直接安装，无需解压；`.sha256` 是独立的校验附件。
2. 安装 APK，第一次打开后按提示开启“通知使用权”。
3. 回到 23adskip，确认运行状态正常，再开启“自动跳过”。
4. 之后正常使用哔哩哔哩官方客户端即可。需要时，可开启撤销通知，在跳过后撤销本次操作。

Android 把 23adskip 需要的系统能力称为“通知使用权”，它用于获取 B站的播放状态并执行跳转。23adskip 不需要读取 B站账号或 Cookie。

## 为什么有时不跳

不跳不一定是 Bug：视频可能没有社区标注、包含多个分 P，或者网络暂时不可用、手机限制了后台运行、当前播放状态不足以安全确认。

**不能确定时宁可不跳，也不要跳错。**

若出现漏跳、监听失联或明显延迟，可查看设置中的“本机后台设置说明”，按对应品牌的步骤手动检查。正常使用无需预先调整全部后台设置。

## 更新与备用下载

新版正常覆盖安装即可，无需先卸载。应用前台约每天检查一次更新，也可在“关于 / 帮助”中手动检查；下载和最终安装都由你确认。

[GitHub Releases](https://github.com/sis5595/adskip23/releases) 是唯一权威发布源。[蓝奏云国内备用下载](https://wwapq.lanzoub.com/b01n4igtqj)（密码 `2233`）仅为镜像；请以 GitHub 公布的版本和 SHA-256 为准。

当前备用下载为 `23adskip-0.13.1-beta.2.zip`。下载后先解压，再安装其中的 `adskip-android-universal.apk`；随包附带 `.sha256` 校验文件。内含 APK 与 GitHub 正式附件一致。

## 问题反馈

请先搜索 [GitHub Issues](https://github.com/sis5595/adskip23/issues)，确认没有相同问题后提交 Bug 报告。建议在应用“关于 / 帮助”中复制兼容性报告，检查后连同问题现象和复现步骤一起提交。

不使用 GitHub 的用户可邮件联系 [sis5595@gmail.com](mailto:sis5595@gmail.com)。本项目不建立 QQ、微信或 Telegram 群作为 Bug 渠道。

请勿提交账号、Cookie、完整观看历史、私密或带签名的 URL 等不必要的个人信息。权限与数据使用说明见 [隐私说明](PRIVACY.md)。

## 免责声明

本项目由个人开发者维护，与哔哩哔哩及其它被引用项目没有官方关联。软件按现状提供，不保证所有手机、系统或客户端版本均可正常工作；系统后台限制、网络状况和客户端更新可能导致漏跳，社区标注也可能存在错误。请自行决定是否启用，并在遇到错误时关闭自动跳过或撤销操作。

## 许可与致谢

源码采用 [GNU GPL v3](LICENSE)（GPL-3.0-only）。

感谢 [小电视空降助手 / BilibiliSponsorBlock](https://github.com/hanydd/BilibiliSponsorBlock) 的维护者与社区标注者提供片段数据；感谢 [DontKillMyApp / Urbandroid Team](https://dontkillmyapp.com/) 整理各品牌后台限制经验，应用内相关指南按 CC BY 4.0 署名改编。

完整许可、参考来源和使用边界见 [许可与致谢](NOTICE.md)。
