# 许可与致谢

23adskip 源码采用 **GNU GPL v3（GPL-3.0-only）**，全文见 [LICENSE](LICENSE)。

## 片段数据

感谢 [小电视空降助手 / BilibiliSponsorBlock](https://github.com/hanydd/BilibiliSponsorBlock) 的维护者与社区标注者。23adskip 是独立第三方 Android 客户端，与上述项目及哔哩哔哩没有官方关联。

源码许可不改变远端数据库的权利或使用条件。本项目不打包数据库整包，不自动投稿、投票或上报观看统计。

## 后台兼容指南

指南包含对 [DontKillMyApp / Urbandroid Team](https://dontkillmyapp.com/) 资料的中文翻译、节选与改编，按 [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/) 署名使用。相关步骤经过精简并针对 23adskip 使用场景调整；改编不代表上游为本项目背书。

设置入口参考了 Christopher Wolf 的 [autostart_settings](https://github.com/chris-wolf/autostart_settings) 和 Noushath Peer Mohammed M 的 [battery_optimization_permission](https://github.com/nousath/battery_optimization_permission)。两者采用 MIT 许可，版权及许可全文见 [OEM_REFERENCE_NOTICES.txt](licenses/OEM_REFERENCE_NOTICES.txt)。导航实现不依赖这两个运行时库。

Samsung、Motorola、Honor 和 Android 官方文档提供部分菜单及系统接口依据，具体来源 URL 保留在指南源码中。

## 构建工具

Android SDK、Android Gradle Plugin 和 Gradle 遵循各自上游许可。当前正式 APK 不包含第三方运行时库或原生库。
