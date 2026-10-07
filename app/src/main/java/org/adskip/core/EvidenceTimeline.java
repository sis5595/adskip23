package org.adskip.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

/** Bounded in-memory research timeline. Stale generation observations are rejected at append. */
public final class EvidenceTimeline {
    public enum AppendResult { ACCEPTED, STALE_SCOPE }

    private final int capacity;
    private final Deque<CurrentPartEvidence> events = new ArrayDeque<>();

    public EvidenceTimeline(int capacity) {
        if (capacity <= 0) throw new IllegalArgumentException("capacity must be positive");
        this.capacity = capacity;
    }

    public AppendResult append(CurrentPartEvidence evidence, EvidenceScope currentScope) {
        Objects.requireNonNull(evidence, "evidence");
        Objects.requireNonNull(currentScope, "currentScope");
        if (!evidence.getScope().equals(currentScope)) {
            return AppendResult.STALE_SCOPE;
        }
        if (events.size() == capacity) events.removeFirst();
        events.addLast(evidence);
        return AppendResult.ACCEPTED;
    }

    public List<CurrentPartEvidence> events() {
        return immutableSorted(new ArrayList<>(events));
    }

    public List<CurrentPartEvidence> eventsFor(EvidenceScope scope) {
        Objects.requireNonNull(scope, "scope");
        List<CurrentPartEvidence> matching = new ArrayList<>();
        for (CurrentPartEvidence evidence : events) {
            if (scope.equals(evidence.getScope())) matching.add(evidence);
        }
        return immutableSorted(matching);
    }

    private static List<CurrentPartEvidence> immutableSorted(List<CurrentPartEvidence> values) {
        values.sort(Comparator.comparingLong(CurrentPartEvidence::getObservedAtElapsedMs)
                .thenComparing(value -> value.getSource().name()));
        return Collections.unmodifiableList(values);
    }
}
