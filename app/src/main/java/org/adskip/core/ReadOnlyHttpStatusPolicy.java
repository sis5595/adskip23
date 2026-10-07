package org.adskip.core;

/** HTTP response classification for read-only catalogue and segment requests. */
public final class ReadOnlyHttpStatusPolicy {
    public enum Outcome { SUCCESS, NOT_FOUND, RETRYABLE, REJECTED }

    private ReadOnlyHttpStatusPolicy() { }

    public static Outcome classify(int status) {
        if (status == 200) return Outcome.SUCCESS;
        if (status == 404) return Outcome.NOT_FOUND;
        if (status == 408 || status == 425 || status == 429
                || (status >= 500 && status <= 599)) return Outcome.RETRYABLE;
        return Outcome.REJECTED;
    }
}
