package org.adskip.core;

/** User-facing facade over the reviewed, offline OEM registry. */
public final class OemCompatibilityGuide {
    public enum Brand { XIAOMI, OPPO, REALME, ONEPLUS, VIVO, HUAWEI, HONOR, SAMSUNG, MOTOROLA, GENERIC }
    private OemCompatibilityGuide() { }

    public static Brand classify(String manufacturer, String brand) {
        return OemCompatibilityRegistry.classify(manufacturer, brand);
    }

    public static String label(Brand brand) { return OemCompatibilityRegistry.forBrand(brand).label; }
    public static String autostart(Brand brand) { return OemCompatibilityRegistry.forBrand(brand).autostart; }
    public static String battery(Brand brand) { return OemCompatibilityRegistry.forBrand(brand).battery; }
    public static String recentTasks(Brand brand) { return OemCompatibilityRegistry.forBrand(brand).recentTasks; }

    public static String text(Brand brand) {
        OemCompatibilityRegistry.Profile profile = OemCompatibilityRegistry.forBrand(brand);
        StringBuilder text = new StringBuilder("若播放时漏跳、监听失联或出现明显延迟，可按下面顺序检查。正常使用时无需改动。只调整 23adskip 的设置。\n\n")
                .append("1. 电池 / 后台运行\n").append(profile.battery)
                .append("\n\n2. 自启动 / 应用启动\n").append(profile.autostart)
                .append("\n\n3. 仍然异常时\n").append(profile.recentTasks)
                .append("\n\n").append(profile.applicability)
                .append("\n设置只能尽量减少后台限制，不能保证始终运行。打开页面不代表开关已启用；返回后继续播放观察是否恢复。无需关闭全局省电功能。\n\n")
                .append("资料：DontKillMyApp / Urbandroid Team（CC BY 4.0，中文节选与调整）。\nhttps://creativecommons.org/licenses/by/4.0/\n");
        for (String sourceId : profile.guideSourceIds) {
            text.append(OemCompatibilityRegistry.source(sourceId).url).append('\n');
        }
        return text.toString().trim();
    }
}
