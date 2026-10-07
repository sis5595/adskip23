package org.adskip.core;

/** Explicit allowlist of support-report fields; no playback identity or network address accepted. */
public final class CompatibilityReport {
    private CompatibilityReport() { }

    public static String build(String version, long versionCode, String manufacturer,
            String brand, String model, int api, boolean listenerAuthorized,
            boolean listenerConnected, boolean biliInstalled, boolean sessionReady,
            String pagelistState, String bsbState, String postNotifications) {
        return "23adskip compatibility report (user-copied)\n"
                + "app=" + safe(version) + " (" + versionCode + ")\n"
                + "device=" + safe(manufacturer) + " / " + safe(brand) + " / "
                + safe(model) + "; API=" + api + "\n"
                + "notification_listener_authorized=" + listenerAuthorized + "\n"
                + "notification_listener_connected=" + listenerConnected + "\n"
                + "bilibili_package_installed=" + biliInstalled + "\n"
                + "bilibili_session_ready=" + sessionReady + "\n"
                + "recent_pagelist=" + safe(pagelistState) + "\n"
                + "recent_bsb=" + safe(bsbState) + "\n"
                + "post_notifications=" + safe(postNotifications) + "\n";
    }

    private static String safe(String value) {
        if (value == null) return "unknown";
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < value.length() && result.length() < 60; i++) {
            char c = value.charAt(i);
            if (Character.isLetterOrDigit(c) || c == ' ' || c == '-' || c == '_'
                    || c == '.' || c == '/') result.append(c);
        }
        return result.length() == 0 ? "unknown" : result.toString();
    }
}
