package org.adskip.probe;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.view.View;

import org.adskip.core.UiThemeChoice;

/** Native Views theme with explicit light/dark surface roles; no Force Dark inversion. */
final class UiAppearance {
    private static final String PREFS = "ui_appearance";
    private static final String THEME = "theme";

    final boolean dark;
    final int background;
    final int surface;
    final int outline;
    final int text;
    final int muted;
    final int primary;
    final int primaryContainer;
    final int successContainer;
    final int successText;
    final int warningContainer;
    final int warningText;
    final int errorContainer;
    final int errorText;

    private UiAppearance(boolean dark) {
        this.dark = dark;
        background = color(dark ? "#101319" : "#F6F8FB");
        surface = color(dark ? "#1B2028" : "#FFFFFF");
        outline = color(dark ? "#343C48" : "#E0E6EF");
        text = color(dark ? "#EFF3F8" : "#1A2533");
        muted = color(dark ? "#A9B5C5" : "#596A7E");
        primary = color(dark ? "#9EC2FF" : "#245FCB");
        primaryContainer = color(dark ? "#253B59" : "#E8F0FF");
        successContainer = color(dark ? "#1C3429" : "#EAF5EE");
        successText = color(dark ? "#A4D7B7" : "#256C45");
        warningContainer = color(dark ? "#3B2E1D" : "#FFF3E1");
        warningText = color(dark ? "#F3CD8D" : "#835612");
        errorContainer = color(dark ? "#3B2627" : "#FDEDEC");
        errorText = color(dark ? "#F0B6B4" : "#984443");
    }

    static UiThemeChoice choice(Context context) {
        return UiThemeChoice.fromKey(context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(THEME, null));
    }

    static void save(Context context, UiThemeChoice choice) {
        context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(THEME, choice.key()).apply();
    }

    static UiAppearance apply(Activity activity) {
        boolean systemDark = (activity.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        UiAppearance appearance = new UiAppearance(choice(activity).isDark(systemDark));
        activity.setTheme(appearance.dark ? R.style.AdSkipThemeDark : R.style.AdSkipThemeLight);
        activity.getWindow().setStatusBarColor(appearance.background);
        activity.getWindow().setNavigationBarColor(appearance.background);
        int flags = appearance.dark ? 0 : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        activity.getWindow().getDecorView().setSystemUiVisibility(flags);
        return appearance;
    }

    private static int color(String hex) { return Color.parseColor(hex); }
}
