# 构建 23adskip

需要 JDK 17、Gradle 8.2、Python 3、Android SDK Platform 34、Build Tools 34.0.0，以及提供 `apkanalyzer` 的 Android SDK Command-line Tools。设置 `ANDROID_HOME` 并使 `gradle` 可用；也可用 `GRADLE_BIN` 指定 Gradle 路径。

```sh
gradle :app:assembleProductionDebug
gradle :app:assembleProductionRelease
tools/verify.sh
```

release 输出为 `app/build/outputs/apk/production/release/app-production-release-unsigned.apk`，未包含发行签名。发行私钥不属于源码；自行签名的构建不能覆盖安装官方签名版本。

此源码基线对应 `0.13.1-beta.2` / versionCode 50，包名 `com.sis5595.adskip23`。`app/src/main` 与 `app/src/production` 保留该版本全部源码及资源，包括正式 APK 编译包含的部分历史命名类；这些类的存在不意味着正式应用提供研究或无障碍功能。公开工程只提供 production 构建。

官方发行 APK SHA-256：`7b6f3d17f6dbe0eaab2c22a23803618309c4159613b6182119a13475a6f68a13`。

发行证书 SHA-256：`92a317090b0f24ec56af22852eb6dfba9dcffbbe878d7a16c90820e045de8a03`。

源码可重新编译；不同 SDK/工具环境、ZIP 元数据及发行签名可能影响 APK 的整体字节。构建 CI 不签名、不自动发布，也不需要私密凭据。
