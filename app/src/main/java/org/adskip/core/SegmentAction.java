package org.adskip.core;

/** Actions currently returned by the crowdsourced service. Unknown values are never auto-applied. */
public enum SegmentAction {
    SKIP("skip"),
    MUTE("mute"),
    FULL("full"),
    POI("poi"),
    UNKNOWN("");

    private final String wireValue;

    SegmentAction(String wireValue) {
        this.wireValue = wireValue;
    }

    public String getWireValue() {
        return wireValue;
    }

    public static SegmentAction fromWireValue(String value) {
        if (value == null) {
            return UNKNOWN;
        }
        for (SegmentAction action : values()) {
            if (!action.wireValue.isEmpty() && action.wireValue.equals(value)) {
                return action;
            }
        }
        return UNKNOWN;
    }
}
