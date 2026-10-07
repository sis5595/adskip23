package org.adskip.core;

import java.util.Optional;
import java.util.OptionalInt;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sanitized, action-free description of one selected accessibility node.
 *
 * <p>Raw UI strings are accepted only as transient inputs. This value retains one-way digests and
 * conservative numeric page markers; it has no Android dependency and no API that can perform a
 * node action.</p>
 */
public final class AccessibilitySelectionSignal {
    public enum State { ELIGIBLE, UNSELECTED, SENSITIVE_REJECTED }
    public enum SelectionKind { SELECTED, CHECKED, SELECTED_AND_CHECKED }
    public enum LabelSource { NONE, TEXT, CONTENT_DESCRIPTION, STATE_DESCRIPTION }

    private static final Pattern TOTAL_SUFFIX = Pattern.compile(
            "[，,]\\s*共\\s*\\d{1,4}\\s*[集话Pp]\\s*$");
    private static final Pattern SLASH_MARKER = Pattern.compile(
            "(?:^|[^0-9])(?:P|p|第)?\\s*(\\d{1,4})\\s*[/／]\\s*(\\d{1,4})(?:\\s*[集话Pp])?(?:$|[^0-9])");
    private static final Pattern CHINESE_MARKER = Pattern.compile(
            "第\\s*(\\d{1,4})\\s*[集话]\\s*[，, ]*共\\s*(\\d{1,4})\\s*[集话]");
    private static final Pattern BARE_CHINESE_MARKER = Pattern.compile(
            "(?:^|[^0-9])(\\d{1,4})\\s*[，,]\\s*共\\s*(\\d{1,4})\\s*[集话](?:$|[^0-9])");

    private final State state;
    private final SelectionKind selectionKind;
    private final LabelSource labelSource;
    private final String labelFingerprint;
    private final String viewIdFingerprint;
    private final Integer page;
    private final Integer total;
    private final Integer collectionRow;
    private final Integer collectionColumn;

    private AccessibilitySelectionSignal(State state, SelectionKind selectionKind,
            LabelSource labelSource, String labelFingerprint, String viewIdFingerprint,
            Integer page, Integer total, Integer collectionRow, Integer collectionColumn) {
        this.state = state;
        this.selectionKind = selectionKind;
        this.labelSource = labelSource;
        this.labelFingerprint = labelFingerprint;
        this.viewIdFingerprint = viewIdFingerprint;
        this.page = page;
        this.total = total;
        this.collectionRow = collectionRow;
        this.collectionColumn = collectionColumn;
    }

    public static AccessibilitySelectionSignal sanitize(boolean selected, boolean checked,
            boolean accessibilityDataSensitive, CharSequence text,
            CharSequence contentDescription, CharSequence stateDescription, String viewId,
            int collectionRow, int collectionColumn) {
        SelectionKind selectionKind = selected && checked
                ? SelectionKind.SELECTED_AND_CHECKED
                : selected ? SelectionKind.SELECTED : checked ? SelectionKind.CHECKED : null;
        if (selectionKind == null) {
            return empty(State.UNSELECTED);
        }
        if (accessibilityDataSensitive) {
            return empty(State.SENSITIVE_REJECTED);
        }
        LabelSource source = LabelSource.NONE;
        String rawLabel = clean(text);
        if (rawLabel != null) {
            source = LabelSource.TEXT;
        } else {
            rawLabel = clean(contentDescription);
            if (rawLabel != null) {
                source = LabelSource.CONTENT_DESCRIPTION;
            } else {
                rawLabel = clean(stateDescription);
                if (rawLabel != null) source = LabelSource.STATE_DESCRIPTION;
            }
        }
        String normalized = rawLabel == null ? null
                : TOTAL_SUFFIX.matcher(rawLabel).replaceFirst("").trim();
        if (normalized != null && normalized.isEmpty()) normalized = null;
        int[] marker = firstPageMarker(text, contentDescription, stateDescription);
        String cleanViewId = clean(viewId);
        return new AccessibilitySelectionSignal(State.ELIGIBLE, selectionKind, source,
                normalized == null ? null : EvidenceFingerprint.sha256(normalized),
                cleanViewId == null ? null : EvidenceFingerprint.sha256(cleanViewId),
                marker == null ? null : marker[0], marker == null ? null : marker[1],
                collectionRow < 0 ? null : collectionRow,
                collectionColumn < 0 ? null : collectionColumn);
    }

    private static AccessibilitySelectionSignal empty(State state) {
        return new AccessibilitySelectionSignal(state, null, LabelSource.NONE,
                null, null, null, null, null, null);
    }

    private static String clean(CharSequence value) {
        if (value == null) return null;
        String clean = value.toString().trim();
        return clean.isEmpty() ? null : clean;
    }

    private static int[] pageMarker(String value) {
        if (value == null) return null;
        Matcher matcher = CHINESE_MARKER.matcher(value);
        if (!matcher.find()) matcher = BARE_CHINESE_MARKER.matcher(value);
        if (!matcher.find(0)) matcher = SLASH_MARKER.matcher(value);
        if (!matcher.find(0)) return null;
        int page;
        int total;
        try {
            page = Integer.parseInt(matcher.group(1));
            total = Integer.parseInt(matcher.group(2));
        } catch (NumberFormatException exception) {
            return null;
        }
        return page > 0 && total > 0 && page <= total ? new int[] {page, total} : null;
    }

    private static int[] firstPageMarker(CharSequence... values) {
        for (CharSequence value : values) {
            int[] marker = pageMarker(value == null ? null : value.toString());
            if (marker != null) return marker;
        }
        return null;
    }

    public State getState() { return state; }
    public Optional<SelectionKind> getSelectionKind() { return Optional.ofNullable(selectionKind); }
    public LabelSource getLabelSource() { return labelSource; }
    public Optional<String> getLabelFingerprint() { return Optional.ofNullable(labelFingerprint); }
    public Optional<String> getViewIdFingerprint() { return Optional.ofNullable(viewIdFingerprint); }
    public OptionalInt getPage() { return page == null ? OptionalInt.empty() : OptionalInt.of(page); }
    public OptionalInt getTotal() { return total == null ? OptionalInt.empty() : OptionalInt.of(total); }
    public OptionalInt getCollectionRow() { return collectionRow == null
            ? OptionalInt.empty() : OptionalInt.of(collectionRow); }
    public OptionalInt getCollectionColumn() { return collectionColumn == null
            ? OptionalInt.empty() : OptionalInt.of(collectionColumn); }
}
