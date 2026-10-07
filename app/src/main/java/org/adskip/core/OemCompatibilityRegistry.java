package org.adskip.core;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import org.adskip.core.OemCompatibilityGuide.Brand;

/** Reviewed data ships with the APK. No network lookup, hidden ROM probes or policy changes.
 * Guide adaptations: DontKillMyApp/Urbandroid Team, CC BY 4.0. Intent facts were cross-checked
 * against MIT community references and vendor docs; implementation is independent.
 * Review/negative evidence and upstream licenses: OEM_GUIDE_SNAPSHOT.md, NOTICE.md.
 */
public final class OemCompatibilityRegistry {
    public static final String SNAPSHOT_DATE = "2026-10-06";
    public enum Kind { COMPONENT, ACTION, APP_DETAILS, BATTERY_OPTIMIZATION_LIST }
    public enum Confidence { VENDOR_DOCUMENTED, COMMUNITY_CANDIDATE, ANDROID_STANDARD }

    public static final class Source {
        public final String id, url, license, reference, reviewedAt;
        private Source(String id, String url, String license, String reference) {
            this.id=id; this.url=url; this.license=license; this.reference=reference;
            this.reviewedAt=SNAPSHOT_DATE;
        }
    }

    public static final class Destination {
        public final Kind kind;
        public final String packageName, componentName, action, integerExtraName, sourceId, applicability;
        public final int integerExtraValue;
        public final Confidence confidence;
        private Destination(Kind kind, String pkg, String component, String action,
                String extra, int value, String sourceId, String applicability, Confidence confidence) {
            this.kind=kind; this.packageName=pkg; this.componentName=component; this.action=action;
            this.integerExtraName=extra; this.integerExtraValue=value; this.sourceId=sourceId;
            this.applicability=applicability; this.confidence=confidence;
        }
    }

    public static final class Profile {
        public final Brand brand;
        public final String label, battery, autostart, recentTasks, applicability, settingsLabel;
        public final List<String> manufacturerAliases, brandAliases, guideSourceIds;
        public final List<Destination> settingsDestinations;
        private Profile(Brand brand, String label, List<String> manufacturerAliases, List<String> brandAliases,
                String battery, String autostart, String recentTasks, String applicability,
                String settingsLabel, List<String> sources, Destination... destinations) {
            this.brand=brand; this.label=label; this.manufacturerAliases=manufacturerAliases;
            this.brandAliases=brandAliases; this.battery=battery; this.autostart=autostart;
            this.recentTasks=recentTasks; this.applicability=applicability;
            this.settingsLabel=settingsLabel; this.guideSourceIds=sources;
            this.settingsDestinations=list(destinations);
        }
    }

    private static final String DKMA_COMMIT = "3616bc8b3b6b3910ac204e107ff0dfd6064f877c";
    private static Source dkma(String vendor) {
        return new Source("dkma-"+vendor, "https://dontkillmyapp.com/"+vendor, "CC-BY-4.0",
                "urbandroid-team/dont-kill-my-app@"+DKMA_COMMIT+"/_vendors/"+vendor+".md");
    }
    private static final List<Source> SOURCES = list(
            dkma("xiaomi"), dkma("oppo"), dkma("realme"), dkma("oneplus"), dkma("vivo"),
            dkma("huawei"), dkma("samsung"), dkma("motorola"), dkma("general"),
            new Source("autostart-settings", "https://github.com/chris-wolf/autostart_settings", "MIT",
                    "46a700d5d6107b51aa3d0d56a8fe140c8eee6dad/android/src/main/kotlin/dev/cwolf/autostartSettings/autostart_settings/AutostartSettingsPlugin.kt"),
            new Source("battery-permission", "https://github.com/nousath/battery_optimization_permission", "MIT",
                    "ab10f6e932019b6918320fef8973b95240f17936/android/src/main/kotlin/in/co/nh97/battery_optimization_permission/BatteryOptimizationPermissionPlugin.kt"),
            new Source("samsung-official", "https://developer.samsung.com/mobile/app-management.html", "vendor-documentation",
                    "App management: ACTION_OPEN_CHECKABLE_LISTACTIVITY, activity_type=2; reviewed 2026-10-06"),
            new Source("motorola-official", "https://en-us.support.motorola.com/app/answers/detail/a_id/159435", "vendor-documentation",
                    "Model-dependent Manage background apps / Always allow; reviewed 2026-10-06"),
            new Source("honor-official", "https://www.honor.com/uk/support/content/en-us00428704/", "vendor-documentation",
                    "Manual App launch / Run in background; reviewed 2026-10-06"),
            new Source("android-settings", "https://developer.android.com/reference/android/provider/Settings", "Android-API-contract",
                    "Public settings actions; reviewed 2026-10-06")
    );

    public static final Destination APP_DETAILS = standard(Kind.APP_DETAILS);
    public static final Destination BATTERY_LIST = standard(Kind.BATTERY_OPTIMIZATION_LIST);
    public static final List<Destination> STANDARD_FALLBACKS = list(APP_DETAILS, BATTERY_LIST);
    private static final String TASK_LOCK = "前两项无效时，可在最近任务中锁定 23adskip（若有此选项）。锁定不等于允许自启动或后台运行。仍失败可复制兼容性报告反馈。";
    private static final String NO_AUTOSTART = "没有通用自启动开关；不要寻找或申请额外的自启动权限。优先按上面的电池说明排查。";
    private static final String NO_TASK_LOCK = "通常无需最近任务锁定。若仍漏跳或失联，可复制兼容性报告反馈。";
    private static final Destination[] COLOROS = {
            component("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity", "autostart-settings", "ColorOS 部分版本；未按机型验收"),
            component("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity", "battery-permission", "ColorOS 部分版本；未按机型验收"),
            component("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity", "autostart-settings", "旧 ColorOS 候选；未按机型验收")
    };

    private static final List<Profile> PROFILES = list(
        new Profile(Brand.XIAOMI, "Xiaomi / Redmi / POCO", list("xiaomi","redmi","poco"), list("xiaomi","redmi","poco"),
            "应用详情 → 省电策略 / 应用电池设置 → 选择“无限制 / No restrictions”。小米实测中智能限制可能使 23adskip 暂停执行。",
            "应用设置或安全中心 → 自启动 / 后台自启动 → 按需允许 23adskip。", TASK_LOCK,
            "适用于 MIUI / HyperOS 的 Android 机型；Android One 机型按通用应用电池设置排查。菜单因版本与地区而异。",
            "打开自启动设置", list("dkma-xiaomi"),
            component("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity", "autostart-settings", "MIUI / HyperOS 候选；非所有版本保证"),
            action("miui.intent.action.OP_AUTO_START", null, null, 0, "autostart-settings", "MIUI 备用入口", Confidence.COMMUNITY_CANDIDATE)),
        new Profile(Brand.OPPO, "OPPO", list("oppo"), list("oppo"),
            "应用详情 → 电池使用 → 允许后台活动 / 后台运行；如提供应用级“不优化 / 无限制”，可按需选择。",
            "应用管理或手机管家 → 自动启动 / 启动管理 → 允许 23adskip。", TASK_LOCK,
            "ColorOS 菜单与入口会随版本、地区变化；找不到时使用应用详情或系统设置搜索。",
            "打开自启动设置", list("dkma-oppo"), COLOROS),
        new Profile(Brand.REALME, "realme", list("realme"), list("realme"),
            "应用详情 → 电池使用 → 允许后台活动；如提供“不优化 / 无限制”，只为 23adskip 按需调整。",
            "应用管理或手机管家 → 自动启动 / 应用启动 → 允许 23adskip（若提供此项）。", TASK_LOCK,
            "realme UI 的部分入口与 ColorOS 共用，但具体菜单不保证相同。", "打开自启动设置", list("dkma-realme"), COLOROS),
        new Profile(Brand.ONEPLUS, "OnePlus", list("oneplus"), list("oneplus"),
            "应用详情 → 电池 / 后台活动 → 允许后台运行，并查看“不优化 / 无限制”。旧 OxygenOS 可能使用不同菜单名称。",
            "若系统提供自动启动 / 关联启动管理，按需允许 23adskip。旧 OxygenOS 与较新的 ColorOS 系入口不同。", TASK_LOCK,
            "先尝试设备实际提供的启动管理页；旧入口不存在或被拒绝时回退应用详情，不根据品牌猜测 ROM 版本。",
            "打开启动管理", list("dkma-oneplus"),
            COLOROS[0], COLOROS[1], COLOROS[2],
            component("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity", "autostart-settings", "仅旧 OxygenOS 的候选；未按机型验收")),
        new Profile(Brand.VIVO, "vivo / iQOO", list("vivo","iqoo"), list("vivo","iqoo"),
            "设置 → 电池 → 高后台耗电 / 后台电源管理 → 按需允许 23adskip 后台运行；如提供应用级“不优化”，再检查该项。",
            "应用管理 / i管家 → 自启动管理 → 允许 23adskip。", TASK_LOCK,
            "Funtouch OS、OriginOS 的版本及地区菜单不同；较旧 i管家入口只作为备用。",
            "打开自启动设置", list("dkma-vivo"),
            component("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity", "autostart-settings", "vivo 自启动候选；未按机型验收"),
            component("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager", "autostart-settings", "旧 i管家候选；未按机型验收")),
        new Profile(Brand.HUAWEI, "Huawei（Android 系）", list("huawei"), list("huawei"),
            "设置 → 应用启动 → 找到 23adskip → 关闭该应用的自动管理，改为手动并允许“后台运行”；仍异常再查看应用电池优化。",
            "在同一应用启动页面中，按需检查 23adskip 的“自动启动、关联启动”。", TASK_LOCK,
            "仅 Android 兼容系统；不支持 HarmonyOS 5+。旧 EMUI 私有入口可能被系统拒绝；此时使用应用详情或设置搜索“应用启动”。",
            "打开应用启动设置", list("dkma-huawei"),
            component("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity", "autostart-settings", "EMUI 候选；有权限拒绝报告，必须回退")),
        new Profile(Brand.HONOR, "Honor（Android 系）", list("honor"), list("honor"),
            "设置中搜索“应用启动” → 23adskip → 关闭该应用的自动管理 → 允许“后台运行”；仍异常再检查应用电池优化。",
            "在应用启动中按需检查自动启动、关联启动。请使用本机实际菜单，不套用旧 Huawei 的界面。", TASK_LOCK,
            "Honor / MagicOS 与旧 Huawei / EMUI 分开指导；未知版本使用应用详情和设置搜索，不猜测私有入口。",
            "打开应用详情", list("dkma-huawei","honor-official")),
        new Profile(Brand.SAMSUNG, "Samsung", list("samsung"), list("samsung"),
            "设置 → 电池 / 设备维护 → 后台使用限制：检查 23adskip 是否在“休眠 / 深度休眠应用”中，移出后按需加入“永不休眠应用”；再查看应用电池的后台限制。",
            NO_AUTOSTART, NO_TASK_LOCK, "One UI 版本与地区可能影响菜单；下方休眠名单入口是三星公开的导航方式，打开失败时可从应用详情进入。",
            "打开永不休眠名单", list("dkma-samsung","samsung-official"),
            action("com.samsung.android.sm.ACTION_OPEN_CHECKABLE_LISTACTIVITY", "com.samsung.android.lool", "activity_type", 2,
                    "samsung-official", "厂商公开导航；仍需 resolve 和异常回退，未在 23adskip 三星实机验证", Confidence.VENDOR_DOCUMENTED)),
        new Profile(Brand.MOTOROLA, "Motorola", list("motorola"), list("motorola","moto"),
            "应用详情 → 应用电池使用 → 允许后台使用；部分版本需再点该选项进入“无限制”。若有“设置 → 电池 → 管理后台应用”，可将 23adskip 设为“始终允许 / Always allow”。",
            NO_AUTOSTART, NO_TASK_LOCK, "“管理后台应用”不是每个型号都有。这里只调整 23adskip，不卸载系统电池组件；其它 Lenovo 机型使用通用指导。",
            "打开应用详情", list("dkma-motorola","motorola-official")),
        new Profile(Brand.GENERIC, "其它 Android", list(), list(),
            "应用详情 → 电池 → 允许后台运行；若提供“不优化 / 无限制”，仅在异常时为 23adskip 调整。菜单可能位于系统电池设置。",
            NO_AUTOSTART, NO_TASK_LOCK, "未知厂商或系统版本使用标准设置入口；品牌名称不能证明后台限制是故障原因。",
            "打开应用详情", list("dkma-general"))
    );

    private OemCompatibilityRegistry() { }
    public static List<Profile> profiles() { return PROFILES; }
    public static List<Source> sources() { return SOURCES; }
    public static Source source(String id) {
        for (Source source : SOURCES) if (source.id.equals(id)) return source;
        throw new IllegalArgumentException("unknown OEM source: "+id);
    }
    public static Profile forBrand(Brand brand) {
        for (Profile profile : PROFILES) if (profile.brand==brand) return profile;
        return PROFILES.get(PROFILES.size()-1);
    }
    public static Brand classify(String manufacturer, String brand) {
        // Product brand takes precedence (e.g. Honor on a Huawei-manufactured device).
        String name=normalize(brand);
        for (Profile profile : PROFILES) if (profile.brandAliases.contains(name)) return profile.brand;
        name=normalize(manufacturer);
        for (Profile profile : PROFILES) if (profile.manufacturerAliases.contains(name)) return profile.brand;
        return Brand.GENERIC;
    }
    private static String normalize(String value) {
        return value==null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
    private static Destination standard(Kind kind) {
        return new Destination(kind, null, null, null, null, 0, "android-settings", "Android 8.0+ 标准入口", Confidence.ANDROID_STANDARD);
    }
    private static Destination component(String pkg, String cls, String sourceId, String applicability) {
        return new Destination(Kind.COMPONENT, pkg, cls, null, null, 0, sourceId, applicability, Confidence.COMMUNITY_CANDIDATE);
    }
    private static Destination action(String action, String pkg, String extra, int value,
            String sourceId, String applicability, Confidence confidence) {
        return new Destination(Kind.ACTION, pkg, null, action, extra, value, sourceId, applicability, confidence);
    }
    @SafeVarargs private static <T> List<T> list(T... values) {
        return Collections.unmodifiableList(Arrays.asList(values.clone()));
    }
}
