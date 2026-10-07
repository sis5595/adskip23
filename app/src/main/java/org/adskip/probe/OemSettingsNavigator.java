package org.adskip.probe;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.provider.Settings;
import android.widget.Toast;

import org.adskip.core.OemCompatibilityGuide;
import org.adskip.core.OemCompatibilityRegistry;
import org.adskip.core.OemCompatibilityRegistry.Destination;

/** User-initiated settings navigation only. Opening a screen never proves a switch is enabled. */
final class OemSettingsNavigator {
    private OemSettingsNavigator() { }

    static void openSuggestedSettings(Activity activity, OemCompatibilityGuide.Brand brand) {
        for (Destination destination : OemCompatibilityRegistry.forBrand(brand).settingsDestinations) {
            if (tryOpen(activity, destination)) return;
        }
        openAppDetails(activity);
    }

    static void openBattery(Activity activity) {
        // App-specific battery/background controls are distinct from the system Doze list.
        openAppDetails(activity);
    }

    static void openBatteryOptimizationList(Activity activity) {
        if (!tryOpen(activity, OemCompatibilityRegistry.BATTERY_LIST)) openAppDetails(activity);
    }

    static void openAppDetails(Activity activity) {
        for (Destination destination : OemCompatibilityRegistry.STANDARD_FALLBACKS) {
            if (tryOpen(activity, destination)) return;
        }
        Toast.makeText(activity, "无法打开设置，请手动在系统设置中搜索 23adskip 或应用启动。",
                Toast.LENGTH_LONG).show();
    }

    private static Intent intent(Activity activity, Destination destination) {
        switch (destination.kind) {
            case COMPONENT:
                return new Intent().setClassName(destination.packageName, destination.componentName);
            case ACTION:
                Intent action = new Intent(destination.action).addCategory(Intent.CATEGORY_DEFAULT);
                if (destination.packageName != null) action.setPackage(destination.packageName);
                if (destination.integerExtraName != null) {
                    action.putExtra(destination.integerExtraName, destination.integerExtraValue);
                }
                return action;
            case APP_DETAILS:
                return new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + activity.getPackageName()));
            case BATTERY_OPTIMIZATION_LIST:
                return new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
            default:
                throw new IllegalArgumentException("Unknown settings destination");
        }
    }

    private static boolean tryOpen(Activity activity, Destination destination) {
        try {
            Intent intent = intent(activity, destination);
            PackageManager manager = activity.getPackageManager();
            if (intent.resolveActivity(manager) == null) return false;
            ResolveInfo resolved = manager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY);
            ActivityInfo info = resolved == null ? null : resolved.activityInfo;
            if (info == null || !info.exported || (info.permission != null
                    && activity.checkSelfPermission(info.permission) != PackageManager.PERMISSION_GRANTED)) {
                return false;
            }
            activity.startActivity(intent);
            return true;
        } catch (RuntimeException ignored) {
            // A resolvable OEM screen can still reject the caller or disappear. Try the next candidate.
            return false;
        }
    }
}
