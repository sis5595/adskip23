package org.adskip.core;

/** Persisted user preference; the system choice is intentionally the default. */
public enum UiThemeChoice {
    SYSTEM("system"), LIGHT("light"), DARK("dark");

    private final String key;
    UiThemeChoice(String key) { this.key = key; }
    public String key() { return key; }

    public static UiThemeChoice fromKey(String key) {
        for (UiThemeChoice choice : values()) {
            if (choice.key.equals(key)) return choice;
        }
        return SYSTEM;
    }

    public boolean isDark(boolean systemDark) {
        return this == DARK || (this == SYSTEM && systemDark);
    }
}
