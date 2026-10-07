package org.adskip.core;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Explicit local policy for upstream BilibiliSponsorBlock category names. */
public final class CategoryPolicy {
    /** These are upstream category wire names, never application-private categories. */
    public enum Category {
        SPONSOR("sponsor"),
        SELFPROMO("selfpromo"),
        INTERACTION("interaction"),
        INTRO("intro"),
        OUTRO("outro"),
        PREVIEW("preview"),
        PADDING("padding"),
        FILLER("filler"),
        MUSIC_OFFTOPIC("music_offtopic"),
        POI_HIGHLIGHT("poi_highlight");

        private final String wireName;

        Category(String wireName) {
            this.wireName = wireName;
        }

        public String getWireName() {
            return wireName;
        }

        static Category fromWireName(String value) {
            if (value == null) {
                return null;
            }
            for (Category category : values()) {
                if (category.wireName.equals(value)) {
                    return category;
                }
            }
            return null;
        }
    }

    private final Map<Category, Boolean> enabled;

    private CategoryPolicy(Map<Category, Boolean> enabled) {
        this.enabled = Collections.unmodifiableMap(new LinkedHashMap<>(enabled));
    }

    /** Sponsor is the sole opt-in automatic category in the initial product policy. */
    public static CategoryPolicy defaults() {
        Map<Category, Boolean> values = new LinkedHashMap<>();
        for (Category category : Category.values()) {
            values.put(category, category == Category.SPONSOR);
        }
        return new CategoryPolicy(values);
    }

    public CategoryPolicy withAutomaticSkipEnabled(Category category, boolean value) {
        Objects.requireNonNull(category, "category");
        Map<Category, Boolean> values = new LinkedHashMap<>(enabled);
        values.put(category, value);
        return new CategoryPolicy(values);
    }

    public boolean isKnown(String wireCategory) {
        return Category.fromWireName(wireCategory) != null;
    }

    /** Unknown categories fail closed, even if a caller tries to list their string name. */
    public boolean allowsAutomaticSkip(String wireCategory) {
        Category category = Category.fromWireName(wireCategory);
        return category != null && Boolean.TRUE.equals(enabled.get(category));
    }

    public boolean allowsAutomaticSkip(SegmentRule rule) {
        return rule != null && rule.getAction() == SegmentAction.SKIP
                && allowsAutomaticSkip(rule.getCategory());
    }

    public Set<Category> categories() {
        return enabled.keySet();
    }

    public boolean isEnabled(Category category) {
        return Boolean.TRUE.equals(enabled.get(Objects.requireNonNull(category, "category")));
    }
}
