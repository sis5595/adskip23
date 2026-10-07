package org.adskip.core;

/** Typed query result; localized status text must never drive behavior. */
public enum QueryState {
    IDLE, QUERYING, READY, EMPTY, FILTERED, IDENTITY_REJECTED, SERVICE_ERROR;

    public static QueryState from(SegmentRepository.Result result) {
        if (result == null) return SERVICE_ERROR;
        switch (result.getStatus()) {
            case PUBLISHED: return result.getRules().isEmpty() ? FILTERED : READY;
            case EMPTY:
            case NOT_FOUND: return EMPTY;
            default: return SERVICE_ERROR;
        }
    }
}
