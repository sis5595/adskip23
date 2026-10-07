package org.adskip.probe;

import android.content.Context;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/** Keeps the AccessibilityService implementation absent from production and harness APKs. */
public final class ResearchAccessibilityBridge {
    private static final String TYPE =
            "org.adskip.probe.research.ResearchAccessibilityAccess";

    private ResearchAccessibilityBridge() {}

    public static String render() {
        if (!BuildConfig.RESEARCH_NETWORK_ENABLED) return "P5U Accessibility：research-only";
        try {
            Class<?> type = Class.forName(TYPE);
            Method method = type.getMethod("render");
            return String.valueOf(method.invoke(null));
        } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException
                | InvocationTargetException exception) {
            return "P5U Accessibility：组件不可用；" + exception.getClass().getSimpleName();
        }
    }

    public static void clear() {
        if (!BuildConfig.RESEARCH_NETWORK_ENABLED) return;
        try {
            Class<?> type = Class.forName(TYPE);
            Method method = type.getMethod("clear");
            method.invoke(null);
        } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException
                | InvocationTargetException ignored) {
            // Diagnostics-only clear: absence is already visible through render().
        }
    }

    public static boolean isModeEnabled(Context context) {
        if (!BuildConfig.RESEARCH_NETWORK_ENABLED) return false;
        try {
            Class<?> type = Class.forName(TYPE);
            return (Boolean) type.getMethod("isModeEnabled", Context.class)
                    .invoke(null, context);
        } catch (ReflectiveOperationException exception) {
            return false;
        }
    }

    public static void setModeEnabled(Context context, boolean enabled) {
        if (!BuildConfig.RESEARCH_NETWORK_ENABLED) return;
        try {
            Class<?> type = Class.forName(TYPE);
            type.getMethod("setModeEnabled", Context.class, boolean.class)
                    .invoke(null, context, enabled);
        } catch (ReflectiveOperationException ignored) {
            // The UI reports unavailable through modeStatus().
        }
    }

    public static String modeStatus() {
        if (!BuildConfig.RESEARCH_NETWORK_ENABLED) return "basic mode";
        try {
            Class<?> type = Class.forName(TYPE);
            return String.valueOf(type.getMethod("modeStatus").invoke(null));
        } catch (ReflectiveOperationException exception) {
            return "research component unavailable";
        }
    }
}
