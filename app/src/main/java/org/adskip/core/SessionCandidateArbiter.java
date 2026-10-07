package org.adskip.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Deterministic fail-closed arbitration of otherwise eligible media-session candidates. */
public final class SessionCandidateArbiter {
    private SessionCandidateArbiter() {
    }

    /**
     * A candidate is publishable only when exactly one candidate has independently passed its
     * stability gate. Ordering never breaks a tie: two stable sessions are ambiguous.
     */
    public static <T> Decision<T> choose(List<Candidate<T>> supplied) {
        if (supplied == null || supplied.isEmpty()) {
            return Decision.none("no-candidates");
        }
        List<Candidate<T>> stable = new ArrayList<>();
        for (Candidate<T> candidate : supplied) {
            if (candidate != null && candidate.isStable()) {
                stable.add(candidate);
            }
        }
        if (stable.isEmpty()) {
            return Decision.none("no-stable-candidate");
        }
        stable.sort(Comparator.comparing(Candidate::getSortKey));
        if (stable.size() > 1) {
            return Decision.ambiguous(stable.size());
        }
        return Decision.ready(stable.get(0));
    }

    public enum State {
        NONE,
        READY,
        AMBIGUOUS
    }

    public static final class Candidate<T> {
        private final T value;
        private final String sortKey;
        private final BiliIdentity identity;
        private final long generationNo;
        private final boolean stable;

        public Candidate(
                T value,
                String sortKey,
                BiliIdentity identity,
                long generationNo,
                boolean stable) {
            this.value = Objects.requireNonNull(value, "value");
            if (sortKey == null || sortKey.trim().isEmpty() || !sortKey.equals(sortKey.trim())) {
                throw new IllegalArgumentException("sortKey must be nonblank and unpadded");
            }
            this.sortKey = sortKey;
            this.identity = Objects.requireNonNull(identity, "identity");
            if (generationNo < 0L) {
                throw new IllegalArgumentException("generationNo must not be negative");
            }
            this.generationNo = generationNo;
            this.stable = stable;
        }

        public T getValue() {
            return value;
        }

        public String getSortKey() {
            return sortKey;
        }

        public BiliIdentity getIdentity() {
            return identity;
        }

        public long getGenerationNo() {
            return generationNo;
        }

        public boolean isStable() {
            return stable;
        }
    }

    public static final class Decision<T> {
        private final State state;
        private final Candidate<T> candidate;
        private final int stableCandidateCount;
        private final String detail;

        private Decision(
                State state, Candidate<T> candidate, int stableCandidateCount, String detail) {
            this.state = state;
            this.candidate = candidate;
            this.stableCandidateCount = stableCandidateCount;
            this.detail = detail;
        }

        private static <T> Decision<T> none(String detail) {
            return new Decision<>(State.NONE, null, 0, detail);
        }

        private static <T> Decision<T> ready(Candidate<T> candidate) {
            return new Decision<>(State.READY, candidate, 1, "stable-candidate");
        }

        private static <T> Decision<T> ambiguous(int count) {
            return new Decision<>(State.AMBIGUOUS, null, count, "ambiguous-session");
        }

        public State getState() {
            return state;
        }

        public Candidate<T> getCandidate() {
            return candidate;
        }

        public int getStableCandidateCount() {
            return stableCandidateCount;
        }

        public String getDetail() {
            return detail;
        }
    }
}
