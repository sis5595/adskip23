package org.adskip.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

/** Bounded process-local callback corpus. It never persists or contains raw metadata text. */
public final class SessionTransitionLog {
    private final int capacity;
    private final Deque<SessionTransitionEvent> events = new ArrayDeque<>();

    public SessionTransitionLog(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("capacity must be positive");
        this.capacity = capacity;
    }

    public synchronized void append(SessionTransitionEvent event) {
        Objects.requireNonNull(event, "event");
        if (events.size() == capacity) events.removeFirst();
        events.addLast(event);
    }

    public synchronized List<SessionTransitionEvent> snapshot() {
        return Collections.unmodifiableList(new ArrayList<>(events));
    }

    public synchronized void clear() {
        events.clear();
    }

    public synchronized int size() { return events.size(); }
}
