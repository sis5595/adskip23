package org.adskip.probe;

import android.content.Context;
import android.content.SharedPreferences;

import org.adskip.core.BsbEndpointPolicy;
import org.adskip.core.CategoryPolicy;

/** Only user choices are persisted. No video identity, segment, or playback history is stored. */
final class ProductionSettings {
    private static final String FILE = "production_read_only_settings";
    private static final String ENABLED = "automatic_skip_enabled";
    private static final String SERVER = "bsb_server_origin";
    private static final String UNDO_NOTIFICATION = "undo_notification_enabled";
    private static final String UNDO_HEADS_UP = "undo_heads_up_enabled";
    private static final String UNDO_PERMISSION_REQUESTED = "undo_permission_requested";

    private ProductionSettings() { }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    static boolean enabled(Context context) {
        return BuildConfig.PRODUCTION_READ_ONLY_ENABLED
                && prefs(context).getBoolean(ENABLED, false);
    }

    static void setEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(ENABLED, enabled).apply();
    }

    static boolean undoNotificationEnabled(Context context) {
        return BuildConfig.PRODUCTION_READ_ONLY_ENABLED
                && prefs(context).getBoolean(UNDO_NOTIFICATION, false);
    }

    static void setUndoNotificationEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(UNDO_NOTIFICATION, enabled).apply();
    }

    static boolean undoHeadsUpEnabled(Context context) {
        return prefs(context).getBoolean(UNDO_HEADS_UP, false);
    }

    static void setUndoHeadsUpEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(UNDO_HEADS_UP, enabled).apply();
    }

    static boolean undoPermissionRequested(Context context) {
        return prefs(context).getBoolean(UNDO_PERMISSION_REQUESTED, false);
    }

    static void markUndoPermissionRequested(Context context) {
        prefs(context).edit().putBoolean(UNDO_PERMISSION_REQUESTED, true).apply();
    }

    static String serverOrigin(Context context) {
        String stored = prefs(context).getString(SERVER, BsbEndpointPolicy.DEFAULT_ORIGIN);
        try { return BsbEndpointPolicy.normalizeOrigin(stored); }
        catch (IllegalArgumentException ignored) { return BsbEndpointPolicy.DEFAULT_ORIGIN; }
    }

    static void setServerOrigin(Context context, String origin) {
        prefs(context).edit().putString(SERVER,
                BsbEndpointPolicy.normalizeOrigin(origin)).apply();
    }

    static CategoryPolicy categoryPolicy(Context context) {
        CategoryPolicy policy = CategoryPolicy.defaults();
        SharedPreferences preferences = prefs(context);
        for (CategoryPolicy.Category category : policy.categories()) {
            boolean initial = policy.isEnabled(category);
            policy = policy.withAutomaticSkipEnabled(category,
                    preferences.getBoolean("category_" + category.getWireName(), initial));
        }
        return policy;
    }

    static void setCategory(Context context, CategoryPolicy.Category category, boolean enabled) {
        prefs(context).edit().putBoolean("category_" + category.getWireName(), enabled).apply();
    }
}
