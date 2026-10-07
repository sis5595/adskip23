package org.adskip.probe;

import android.content.ComponentName;
import android.content.Context;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.Bundle;
import android.util.Log;

import org.adskip.core.BiliIdentity;
import org.adskip.core.BiliIdentityResolver;
import org.adskip.core.EvidenceFingerprint;
import org.adskip.core.DisplayedPartMarker;
import org.adskip.core.SessionTransitionEvent;
import org.adskip.core.SessionTransitionLog;
import org.adskip.core.SessionCandidateArbiter;
import org.adskip.core.SessionStabilityGate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Process-local, token-keyed owner of Bilibili MediaControllers.
 *
 * <p>Only {@link BiliNotificationListener#onListenerConnected()} activates system access. An
 * enabled setting alone never marks this adapter connected.</p>
 */
public final class SessionAdapter {
    private static final Object LOCK = new Object();
    private static final Object SCHEDULE_TOKEN = new Object();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final long[] RETRY_DELAYS_MS = {250L, 500L, 1_000L, 2_000L, 4_000L};
    private static final long[] STARTUP_RESCAN_DELAYS_MS = {300L, 1_000L, 2_000L, 4_000L, 8_000L};
    private static final long STABILITY_RECHECK_DELAY_MS =
            SessionStabilityGate.DEFAULT_MIN_STABLE_MS + 100L;

    public enum ConnectionState {
        DISCONNECTED,
        REGISTERING,
        RETRY_WAIT,
        MONITORING,
        FAILED
    }

    public enum CandidateState {
        NONE,
        READY,
        AMBIGUOUS
    }

    private static final Map<MediaSession.Token, TrackedSession> TRACKED = new HashMap<>();
    private static final Set<Observer> OBSERVERS = new HashSet<>();
    private static final SessionTransitionLog TRANSITIONS = new SessionTransitionLog(256);
    private static MediaSessionManager manager;
    private static ComponentName listenerComponent;
    private static boolean listenerConnected;
    private static boolean activeListenerRegistered;
    private static long connectionEpoch;
    private static long listenerLossWindowStartMs = -1L;
    private static int listenerLossesInWindow;
    private static ConnectionState connectionState = ConnectionState.DISCONNECTED;
    private static String connectionDetail = "listener-not-connected";
    private static int retryAttempt;
    private static CandidateSelection selection = CandidateSelection.none("listener-not-connected");

    private static final MediaSessionManager.OnActiveSessionsChangedListener ACTIVE_SESSIONS_LISTENER =
            controllers -> onMain(() -> {
                synchronized (LOCK) {
                    if (!listenerConnected) {
                        return;
                    }
                    reconcileLocked(controllers == null ? Collections.emptyList() : controllers,
                            controllers == null ? "active-list-null" : "active-list-callback");
                }
            });

    private SessionAdapter() {
    }

    public static void connect(Context context) {
        Context applicationContext = context.getApplicationContext();
        onMain(() -> {
            synchronized (LOCK) {
                disconnectLocked("reconnecting");
                listenerConnected = true;
                connectionEpoch++;
                manager = applicationContext.getSystemService(MediaSessionManager.class);
                listenerComponent = new ComponentName(applicationContext, BiliNotificationListener.class);
                connectionState = ConnectionState.REGISTERING;
                connectionDetail = "listener-connected";
                retryAttempt = 0;
                long epoch = connectionEpoch;
                attemptRegisterAndScanLocked(epoch, 0);
            }
        });
    }

    public static void disconnect(String reason) {
        onMain(() -> {
            synchronized (LOCK) {
                disconnectLocked(reason == null ? "listener-disconnected" : reason);
            }
        });
    }

    public static void requestRescan() {
        onMain(() -> {
            synchronized (LOCK) {
                if (!listenerConnected || manager == null || listenerComponent == null) {
                    connectionDetail = "rescan-rejected-listener-disconnected";
                    return;
                }
                if (!activeListenerRegistered || connectionState != ConnectionState.MONITORING) {
                    attemptRegisterAndScanLocked(connectionEpoch, 0);
                    return;
                }
                try {
                    scanLocked("manual-rescan");
                } catch (SecurityException exception) {
                    handleSecurityFailureLocked(connectionEpoch, retryAttempt, "manual-rescan-security");
                }
            }
        });
    }

    /** Receives a coalescible main-thread hint after tracked session state has changed. */
    public interface Observer {
        void onAdapterStateChanged();
    }

    public static void addObserver(Observer observer) {
        if (observer == null) {
            throw new IllegalArgumentException("observer must not be null");
        }
        onMain(() -> {
            synchronized (LOCK) {
                OBSERVERS.add(observer);
            }
        });
    }

    public static void removeObserver(Observer observer) {
        if (observer == null) {
            return;
        }
        onMain(() -> {
            synchronized (LOCK) {
                OBSERVERS.remove(observer);
            }
        });
    }

    public static void noteRebindRequested() {
        onMain(() -> {
            synchronized (LOCK) {
                if (!listenerConnected) {
                    connectionDetail = "rebind-requested-waiting-for-onListenerConnected";
                }
            }
        });
    }

    public static Status status() {
        synchronized (LOCK) {
            return new Status(
                    listenerConnected,
                    activeListenerRegistered,
                    connectionState,
                    connectionDetail,
                    retryAttempt,
                    connectionEpoch,
                    TRACKED.size(),
                    selection.state,
                    selection.detail);
        }
    }

    /** Unexpected real disconnect callbacks in the last ten minutes; UI hint, never a safety gate. */
    public static int recentListenerLosses() {
        synchronized (LOCK) {
            return listenerLossWindowStartMs >= 0
                    && SystemClock.elapsedRealtime() - listenerLossWindowStartMs <= 10 * 60_000L
                    ? listenerLossesInWindow : 0;
        }
    }

    public static List<MediaController> currentControllers() {
        synchronized (LOCK) {
            List<MediaController> controllers = new ArrayList<>();
            for (TrackedSession tracked : TRACKED.values()) {
                controllers.add(tracked.controller);
            }
            return controllers;
        }
    }

    public static CandidateSelection candidateSelection() {
        synchronized (LOCK) {
            return selection;
        }
    }

    public static Optional<Diagnostics> diagnostics(MediaSession.Token token) {
        synchronized (LOCK) {
            TrackedSession tracked = TRACKED.get(token);
            return tracked == null ? Optional.empty() : Optional.of(tracked.diagnostics());
        }
    }

    /** Callback evidence only: Android may extrapolate both values returned by getPlaybackState(). */
    public static PlaybackState publisherPlayback(MediaSession.Token token, BiliIdentity identity) {
        synchronized (LOCK) {
            TrackedSession tracked = TRACKED.get(token);
            return tracked != null && identity != null && identity.equals(tracked.publisherIdentity)
                    ? tracked.publisherPlayback : null;
        }
    }

    public static List<SessionTransitionEvent> transitionEvents() {
        return TRANSITIONS.snapshot();
    }

    public static void clearTransitionEvents() {
        TRANSITIONS.clear();
    }

    private static void attemptRegisterAndScanLocked(long epoch, int attempt) {
        if (!listenerConnected || epoch != connectionEpoch || manager == null || listenerComponent == null) {
            return;
        }
        connectionState = ConnectionState.REGISTERING;
        connectionDetail = "register-attempt-" + (attempt + 1);
        retryAttempt = attempt;
        try {
            if (!activeListenerRegistered) {
                manager.addOnActiveSessionsChangedListener(
                        ACTIVE_SESSIONS_LISTENER, listenerComponent, MAIN);
                activeListenerRegistered = true;
            }
            scanLocked("register-success");
            connectionState = ConnectionState.MONITORING;
            connectionDetail = "monitoring";
            retryAttempt = 0;
            scheduleStartupRescansLocked(epoch);
        } catch (SecurityException exception) {
            handleSecurityFailureLocked(epoch, attempt, "register-or-scan-security");
        } catch (RuntimeException exception) {
            connectionState = ConnectionState.FAILED;
            connectionDetail = "register-runtime-" + exception.getClass().getSimpleName();
            clearSessionsLocked(connectionDetail);
        }
    }

    private static void handleSecurityFailureLocked(long epoch, int attempt, String detail) {
        MAIN.removeCallbacksAndMessages(SCHEDULE_TOKEN);
        clearSessionsLocked(detail);
        unregisterActiveListenerLocked();
        if (!listenerConnected || epoch != connectionEpoch) {
            return;
        }
        if (attempt >= RETRY_DELAYS_MS.length) {
            connectionState = ConnectionState.FAILED;
            connectionDetail = detail + "-retry-exhausted";
            selection = CandidateSelection.none(connectionDetail);
            return;
        }
        connectionState = ConnectionState.RETRY_WAIT;
        retryAttempt = attempt + 1;
        long delay = RETRY_DELAYS_MS[attempt];
        connectionDetail = detail + "-retry-in-" + delay + "ms";
        MAIN.postAtTime(
                () -> {
                    synchronized (LOCK) {
                        attemptRegisterAndScanLocked(epoch, attempt + 1);
                    }
                },
                SCHEDULE_TOKEN,
                SystemClock.uptimeMillis() + delay);
    }

    private static void scheduleStartupRescansLocked(long epoch) {
        for (long delay : STARTUP_RESCAN_DELAYS_MS) {
            MAIN.postAtTime(
                    () -> {
                        synchronized (LOCK) {
                            if (!listenerConnected || epoch != connectionEpoch
                                    || connectionState != ConnectionState.MONITORING) {
                                return;
                            }
                            try {
                                scanLocked("startup-rescan-" + delay + "ms");
                            } catch (SecurityException exception) {
                                handleSecurityFailureLocked(epoch, 0, "startup-rescan-security");
                            }
                        }
                    },
                    SCHEDULE_TOKEN,
                    SystemClock.uptimeMillis() + delay);
        }
    }

    private static void scanLocked(String reason) throws SecurityException {
        List<MediaController> controllers = manager.getActiveSessions(listenerComponent);
        reconcileLocked(controllers == null ? Collections.emptyList() : controllers, reason);
    }

    private static void reconcileLocked(List<MediaController> controllers, String reason) {
        Set<MediaSession.Token> seenTokens = new HashSet<>();
        for (MediaController controller : controllers) {
            if (controller == null || !SessionProbe.BILIBILI_PACKAGE.equals(controller.getPackageName())) {
                continue;
            }
            MediaSession.Token token = controller.getSessionToken();
            seenTokens.add(token);
            TrackedSession tracked = TRACKED.get(token);
            if (tracked == null) {
                tracked = new TrackedSession(controller);
                TRACKED.put(token, tracked);
            }
            tracked.refresh(reason);
        }

        List<MediaSession.Token> removed = new ArrayList<>();
        for (MediaSession.Token token : TRACKED.keySet()) {
            if (!seenTokens.contains(token)) {
                removed.add(token);
            }
        }
        for (MediaSession.Token token : removed) {
            TrackedSession tracked = TRACKED.remove(token);
            if (tracked != null) {
                tracked.recordTerminal(SessionTransitionEvent.Kind.SESSION_REMOVED, "not-active");
                tracked.dispose("not-active");
            }
        }
        updateSelectionLocked(reason);
    }

    private static void updateSelectionLocked(String reason) {
        if (!listenerConnected || !activeListenerRegistered) {
            selection = CandidateSelection.none("monitor-not-registered:" + reason);
            notifyObserversLocked();
            return;
        }
        List<SessionCandidateArbiter.Candidate<TrackedSession>> candidates = new ArrayList<>();
        for (TrackedSession tracked : TRACKED.values()) {
            Optional<BiliIdentity> identity = tracked.gate.getIdentity();
            if (identity.isPresent()) {
                candidates.add(new SessionCandidateArbiter.Candidate<>(
                        tracked,
                        tracked.fingerprint,
                        identity.get(),
                        tracked.gate.getGenerationNo(),
                        tracked.gate.getState() == SessionStabilityGate.State.STABLE));
            }
        }
        SessionCandidateArbiter.Decision<TrackedSession> decision =
                SessionCandidateArbiter.choose(candidates);
        if (decision.getState() == SessionCandidateArbiter.State.NONE) {
            selection = CandidateSelection.none("no-stable-candidate:" + reason);
        } else if (decision.getState() == SessionCandidateArbiter.State.AMBIGUOUS) {
            selection = CandidateSelection.ambiguous(decision.getStableCandidateCount(), reason);
        } else {
            TrackedSession tracked = decision.getCandidate().getValue();
            selection = CandidateSelection.ready(
                    tracked.controller,
                    new SessionGeneration(
                            connectionEpoch,
                            tracked.token,
                            tracked.fingerprint,
                            tracked.gate.getIdentity().get(),
                            tracked.gate.getGenerationNo()));
        }
        notifyObserversLocked();
    }

    private static void disconnectLocked(String reason) {
        if (listenerConnected && !"reconnecting".equals(reason)) {
            long nowMs = SystemClock.elapsedRealtime();
            if (listenerLossWindowStartMs < 0
                    || nowMs - listenerLossWindowStartMs > 10 * 60_000L) {
                listenerLossWindowStartMs = nowMs;
                listenerLossesInWindow = 0;
            }
            listenerLossesInWindow++;
        }
        MAIN.removeCallbacksAndMessages(SCHEDULE_TOKEN);
        listenerConnected = false;
        connectionEpoch++;
        unregisterActiveListenerLocked();
        clearSessionsLocked(reason);
        manager = null;
        listenerComponent = null;
        retryAttempt = 0;
        connectionState = ConnectionState.DISCONNECTED;
        connectionDetail = reason;
        selection = CandidateSelection.none(reason);
        notifyObserversLocked();
    }

    private static void unregisterActiveListenerLocked() {
        if (manager != null && activeListenerRegistered) {
            try {
                manager.removeOnActiveSessionsChangedListener(ACTIVE_SESSIONS_LISTENER);
            } catch (RuntimeException ignored) {
                // Local state still fails closed; a dead system registration cannot retain rules.
            }
        }
        activeListenerRegistered = false;
    }

    private static void clearSessionsLocked(String reason) {
        for (TrackedSession tracked : new ArrayList<>(TRACKED.values())) {
            tracked.dispose(reason);
        }
        TRACKED.clear();
        selection = CandidateSelection.none(reason);
        notifyObserversLocked();
    }

    private static void notifyObserversLocked() {
        if (OBSERVERS.isEmpty()) {
            return;
        }
        List<Observer> observers = new ArrayList<>(OBSERVERS);
        MAIN.post(() -> {
            for (Observer observer : observers) {
                observer.onAdapterStateChanged();
            }
        });
    }

    private static long estimatedPosition(PlaybackState state, long observedAtMs) {
        long position = Math.max(0L, state.getPosition());
        if (state.getState() != PlaybackState.STATE_PLAYING
                || state.getLastPositionUpdateTime() <= 0L
                || !Float.isFinite(state.getPlaybackSpeed())
                || state.getPlaybackSpeed() <= 0F) {
            return position;
        }
        long elapsed = Math.max(0L, observedAtMs - state.getLastPositionUpdateTime());
        long advance = Math.round(elapsed * (double) state.getPlaybackSpeed());
        if (advance > Long.MAX_VALUE - position) {
            return Long.MAX_VALUE;
        }
        return position + advance;
    }

    private static String fingerprint(MediaSession.Token token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(String.valueOf(token).getBytes(StandardCharsets.UTF_8));
            StringBuilder output = new StringBuilder();
            for (int index = 0; index < 6; index++) {
                output.append(String.format("%02x", digest[index]));
            }
            return output.toString();
        } catch (NoSuchAlgorithmException exception) {
            return Integer.toHexString(token.hashCode());
        }
    }

    private static void onMain(Runnable action) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action.run();
        } else {
            MAIN.post(action);
        }
    }

    private static SessionTransitionEvent.Kind kindFor(String reason) {
        if ("metadata-callback".equals(reason)) return SessionTransitionEvent.Kind.METADATA_CALLBACK;
        if ("playback-callback".equals(reason)) return SessionTransitionEvent.Kind.PLAYBACK_CALLBACK;
        if ("queue-callback".equals(reason)) return SessionTransitionEvent.Kind.QUEUE_CALLBACK;
        if ("extras-callback".equals(reason)) return SessionTransitionEvent.Kind.EXTRAS_CALLBACK;
        if ("stability-timer".equals(reason)) return SessionTransitionEvent.Kind.STABILITY_TIMER;
        return SessionTransitionEvent.Kind.SCAN_REFRESH;
    }

    private static List<String> safeMetadataKeys(MediaMetadata metadata) {
        if (metadata == null) return Collections.emptyList();
        try {
            List<String> keys = new ArrayList<>(metadata.keySet());
            Collections.sort(keys);
            return keys;
        } catch (RuntimeException | LinkageError exception) {
            return Collections.singletonList("<unreadable>");
        }
    }

    private static List<String> safeBundleKeys(Bundle bundle) {
        if (bundle == null) return Collections.emptyList();
        try {
            List<String> keys = new ArrayList<>(bundle.keySet());
            Collections.sort(keys);
            return keys;
        } catch (RuntimeException | LinkageError exception) {
            return Collections.singletonList("<unreadable>");
        }
    }

    private static String boundedReason(String reason) {
        return reason.length() <= 96 ? reason : reason.substring(0, 96);
    }

    private static final class TrackedSession {
        private final MediaController controller;
        private final MediaSession.Token token;
        private final String fingerprint;
        private final SessionStabilityGate gate = new SessionStabilityGate();
        private final MediaController.Callback callback;
        private final Runnable stabilityRecheck;
        private int playbackCallbacks;
        private int metadataCallbacks;
        private int queueCallbacks;
        private int extrasCallbacks;
        private long lastObservedElapsedMs;
        private String lastObservationReason = "created";
        private boolean stabilityRecheckScheduled;
        private boolean disposed;
        private PlaybackState publisherPlayback;
        private BiliIdentity publisherIdentity;

        private TrackedSession(MediaController controller) {
            this.controller = controller;
            this.token = controller.getSessionToken();
            this.fingerprint = fingerprint(token);
            this.stabilityRecheck = () -> {
                synchronized (LOCK) {
                    stabilityRecheckScheduled = false;
                    if (disposed || !listenerConnected || TRACKED.get(token) != this) {
                        return;
                    }
                    refresh("stability-timer");
                    updateSelectionLocked("stability-timer");
                }
            };
            this.callback = new MediaController.Callback() {
                @Override
                public void onPlaybackStateChanged(PlaybackState state) {
                    long callbackAt = SystemClock.elapsedRealtime();
                    synchronized (LOCK) {
                        playbackCallbacks++;
                        publisherPlayback = state;
                        MediaMetadata metadata = controller.getMetadata();
                        publisherIdentity = metadata == null ? null : BiliIdentityResolver.resolve(
                                metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID),
                                metadata.getString(MediaMetadata.METADATA_KEY_TITLE),
                                metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)).orElse(null);
                        refresh("playback-callback", callbackAt);
                        updateSelectionLocked("playback-callback");
                    }
                }

                @Override
                public void onMetadataChanged(MediaMetadata metadata) {
                    long callbackAt = SystemClock.elapsedRealtime();
                    synchronized (LOCK) {
                        metadataCallbacks++;
                        refresh("metadata-callback", callbackAt);
                        updateSelectionLocked("metadata-callback");
                    }
                }

                @Override
                public void onQueueChanged(List<MediaSession.QueueItem> queue) {
                    long callbackAt = SystemClock.elapsedRealtime();
                    synchronized (LOCK) {
                        queueCallbacks++;
                        refresh("queue-callback", callbackAt);
                        updateSelectionLocked("queue-callback");
                    }
                }

                @Override
                public void onExtrasChanged(Bundle extras) {
                    long callbackAt = SystemClock.elapsedRealtime();
                    synchronized (LOCK) {
                        extrasCallbacks++;
                        refresh("extras-callback", callbackAt);
                        updateSelectionLocked("extras-callback");
                    }
                }

                @Override
                public void onSessionDestroyed() {
                    synchronized (LOCK) {
                        TrackedSession removed = TRACKED.remove(token);
                        if (removed != null) {
                            removed.recordTerminal(
                                    SessionTransitionEvent.Kind.SESSION_DESTROYED,
                                    "session-destroyed");
                            removed.dispose("session-destroyed");
                        }
                        updateSelectionLocked("session-destroyed");
                    }
                }
            };
            controller.registerCallback(callback, MAIN);
            recordTerminal(SessionTransitionEvent.Kind.SESSION_ADDED, "session-added");
        }

        private void refresh(String reason) {
            refresh(reason, SystemClock.elapsedRealtime());
        }

        private void refresh(String reason, long callbackAtElapsedMs) {
            long generationBefore = gate.getGenerationNo();
            lastObservedElapsedMs = SystemClock.elapsedRealtime();
            lastObservationReason = reason;
            MediaMetadata metadata = controller.getMetadata();
            PlaybackState state = controller.getPlaybackState();
            if (metadata == null) {
                publisherPlayback = null;
                publisherIdentity = null;
                gate.reject("metadata-null");
                recordTransition(reason, callbackAtElapsedMs, generationBefore, null, state);
                return;
            }
            if (state == null) {
                gate.reject("playback-null");
                recordTransition(reason, callbackAtElapsedMs, generationBefore, metadata, null);
                return;
            }
            Optional<BiliIdentity> identity = BiliIdentityResolver.resolve(
                    metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID),
                    metadata.getString(MediaMetadata.METADATA_KEY_TITLE),
                    metadata.getLong(MediaMetadata.METADATA_KEY_DURATION));
            if (!identity.isPresent() || !identity.get().equals(publisherIdentity)) {
                publisherPlayback = null;
                publisherIdentity = null;
            }
            boolean playing = state.getState() == PlaybackState.STATE_PLAYING;
            boolean seekable = (state.getActions() & PlaybackState.ACTION_SEEK_TO) != 0L;
            SessionStabilityGate.State observed = gate.observe(
                    identity.orElse(null),
                    playing,
                    seekable,
                    estimatedPosition(state, lastObservedElapsedMs),
                    state.getPlaybackSpeed(),
                    lastObservedElapsedMs);
            if (observed == SessionStabilityGate.State.WAITING) {
                scheduleStabilityRecheck();
            } else {
                cancelStabilityRecheck();
            }
            recordTransition(reason, callbackAtElapsedMs, generationBefore, metadata, state);
        }

        private void recordTransition(String reason, long callbackAtElapsedMs,
                long generationBefore, MediaMetadata metadata, PlaybackState state) {
            long readAt = SystemClock.elapsedRealtime();
            List<MediaSession.QueueItem> queue = null;
            Bundle controllerExtras = null;
            try { queue = controller.getQueue(); } catch (RuntimeException ignored) { }
            try { controllerExtras = controller.getExtras(); } catch (RuntimeException ignored) { }
            Integer queueSize = queue == null ? -1 : queue.size();
            List<String> metadataKeyNames = safeMetadataKeys(metadata);
            List<String> controllerExtraNames = safeBundleKeys(controllerExtras);
            Integer metadataKeys = metadata == null ? null : metadataKeyNames.size();
            Integer extrasKeys = controllerExtras == null ? null : controllerExtraNames.size();
            SessionActivitySnapshot sessionActivity;
            try {
                sessionActivity = SessionActivitySnapshot.capture(
                        controller.getSessionActivity(), SessionProbe.BILIBILI_PACKAGE);
            } catch (RuntimeException | LinkageError exception) {
                sessionActivity = SessionActivitySnapshot.unreadable(
                        exception.getClass().getSimpleName());
            }
            Long sourceAge = null;
            if (state != null && state.getLastPositionUpdateTime() > 0L) {
                sourceAge = Math.max(0L, callbackAtElapsedMs - state.getLastPositionUpdateTime());
            }
            Optional<BiliIdentity> identity = gate.getIdentity();
            Optional<SessionProbe.PartMarkerObservation> markerObservation = metadata == null
                    ? Optional.empty() : SessionProbe.displayedPartObservation(metadata);
            Optional<DisplayedPartMarker> marker = markerObservation
                    .map(observation -> observation.marker);
            String shape = reason
                    + "|metadata=" + metadataKeyNames
                    + "|state=" + (state == null ? "null" : state.getState())
                    + "|actions=" + (state == null ? "null" : state.getActions())
                    + "|activeQueue=" + (state == null ? "null" : state.getActiveQueueItemId())
                    + "|playbackExtras=" + safeBundleKeys(
                            state == null ? null : state.getExtras())
                    + "|queue=" + queueSize
                    + "|controllerExtras=" + controllerExtraNames
                    + "|sessionActivity=" + sessionActivity.getState()
                    + '/' + sessionActivity.getFingerprint();
            appendTransition(new SessionTransitionEvent(
                    kindFor(reason), callbackAtElapsedMs, connectionEpoch, fingerprint,
                    generationBefore, gate.getGenerationNo(),
                    identity.map(BiliIdentity::getBvid).orElse(null),
                    EvidenceFingerprint.sha256(shape),
                    Math.max(0L, readAt - callbackAtElapsedMs), sourceAge,
                    state == null ? null : state.getState(), metadataKeys, queueSize, extrasKeys,
                    sessionActivity.getState().name(), sessionActivity.getFingerprint(),
                    marker.map(DisplayedPartMarker::getPage).orElse(null),
                    marker.map(DisplayedPartMarker::getTotalParts).orElse(null),
                    markerObservation.map(observation -> observation.source)
                            .orElse(SessionTransitionEvent.MarkerSource.NONE),
                    boundedReason(reason + "/" + gate.getReason())));
        }

        private void recordTerminal(SessionTransitionEvent.Kind kind, String reason) {
            long now = SystemClock.elapsedRealtime();
            SessionActivitySnapshot sessionActivity;
            try {
                sessionActivity = SessionActivitySnapshot.capture(
                        controller.getSessionActivity(), SessionProbe.BILIBILI_PACKAGE);
            } catch (RuntimeException | LinkageError exception) {
                sessionActivity = SessionActivitySnapshot.unreadable(
                        exception.getClass().getSimpleName());
            }
            appendTransition(new SessionTransitionEvent(
                    kind, now, connectionEpoch, fingerprint,
                    gate.getGenerationNo(), gate.getGenerationNo(),
                    gate.getIdentity().map(BiliIdentity::getBvid).orElse(null),
                    EvidenceFingerprint.sha256(reason + "|" + fingerprint),
                    0L, null, null, null, null, null,
                    sessionActivity.getState().name(), sessionActivity.getFingerprint(),
                    null, null, SessionTransitionEvent.MarkerSource.NONE,
                    boundedReason(reason)));
        }

        private void scheduleStabilityRecheck() {
            if (stabilityRecheckScheduled || disposed) {
                return;
            }
            stabilityRecheckScheduled = true;
            MAIN.postDelayed(stabilityRecheck, STABILITY_RECHECK_DELAY_MS);
        }

        private void cancelStabilityRecheck() {
            if (!stabilityRecheckScheduled) {
                return;
            }
            MAIN.removeCallbacks(stabilityRecheck);
            stabilityRecheckScheduled = false;
        }

        private Diagnostics diagnostics() {
            return new Diagnostics(
                    fingerprint,
                    playbackCallbacks,
                    metadataCallbacks,
                    queueCallbacks,
                    extrasCallbacks,
                    lastObservedElapsedMs,
                    lastObservationReason,
                    gate.getState(),
                    gate.getReason(),
                    gate.getGenerationNo());
        }

        private void dispose(String reason) {
            disposed = true;
            cancelStabilityRecheck();
            gate.reject(reason);
            try {
                controller.unregisterCallback(callback);
            } catch (RuntimeException ignored) {
                // The session can disappear between callback and cleanup.
            }
        }
    }

    private static void appendTransition(SessionTransitionEvent event) {
        TRANSITIONS.append(event);
        if (!BuildConfig.RESEARCH_NETWORK_ENABLED) return;
        Log.i("AdSkipSessionTransition",
                "elapsed=" + event.getObservedAtElapsedMs()
                        + " kind=" + event.getKind()
                        + " token=" + event.getTokenFingerprint().substring(0, 12)
                        + " epoch=" + event.getConnectionEpoch()
                        + " generation=" + event.getGenerationBefore() + '-'
                        + event.getGenerationAfter()
                        + " bvid=" + event.getBvid().orElse("-")
                        + " sessionActivity=" + event.getSessionActivityState() + '/'
                        + event.getSessionActivityFingerprint().substring(0, 12)
                        + " marker=" + (event.getDisplayedPage().isPresent()
                                ? event.getDisplayedPage().getAsLong() + "/"
                                        + event.getDisplayedTotal().getAsLong() : "-")
                        + " markerSource=" + event.getMarkerSource()
                        + " playback=" + (event.getPlaybackState().isPresent()
                                ? event.getPlaybackState().getAsInt() : -1)
                        + " sourceAgeMs=" + (event.getSourceAgeMs().isPresent()
                                ? event.getSourceAgeMs().getAsLong() : -1L)
                        + " gate=" + event.getReason()
                        + " payload=" + event.getPayloadFingerprint().substring(0, 12));
    }

    public static final class Status {
        public final boolean listenerConnected;
        public final boolean activeListenerRegistered;
        public final ConnectionState connectionState;
        public final String detail;
        public final int retryAttempt;
        public final long connectionEpoch;
        public final int trackedSessionCount;
        public final CandidateState candidateState;
        public final String candidateDetail;

        private Status(
                boolean listenerConnected,
                boolean activeListenerRegistered,
                ConnectionState connectionState,
                String detail,
                int retryAttempt,
                long connectionEpoch,
                int trackedSessionCount,
                CandidateState candidateState,
                String candidateDetail) {
            this.listenerConnected = listenerConnected;
            this.activeListenerRegistered = activeListenerRegistered;
            this.connectionState = connectionState;
            this.detail = detail;
            this.retryAttempt = retryAttempt;
            this.connectionEpoch = connectionEpoch;
            this.trackedSessionCount = trackedSessionCount;
            this.candidateState = candidateState;
            this.candidateDetail = candidateDetail;
        }
    }

    public static final class Diagnostics {
        public final String fingerprint;
        public final int playbackCallbacks;
        public final int metadataCallbacks;
        public final int queueCallbacks;
        public final int extrasCallbacks;
        public final long lastObservedElapsedMs;
        public final String lastObservationReason;
        public final SessionStabilityGate.State gateState;
        public final String gateReason;
        public final long generationNo;

        private Diagnostics(
                String fingerprint,
                int playbackCallbacks,
                int metadataCallbacks,
                int queueCallbacks,
                int extrasCallbacks,
                long lastObservedElapsedMs,
                String lastObservationReason,
                SessionStabilityGate.State gateState,
                String gateReason,
                long generationNo) {
            this.fingerprint = fingerprint;
            this.playbackCallbacks = playbackCallbacks;
            this.metadataCallbacks = metadataCallbacks;
            this.queueCallbacks = queueCallbacks;
            this.extrasCallbacks = extrasCallbacks;
            this.lastObservedElapsedMs = lastObservedElapsedMs;
            this.lastObservationReason = lastObservationReason;
            this.gateState = gateState;
            this.gateReason = gateReason;
            this.generationNo = generationNo;
        }
    }

    public static final class SessionGeneration {
        public final long connectionEpoch;
        private final MediaSession.Token token;
        public final String tokenFingerprint;
        public final BiliIdentity identity;
        public final long generationNo;

        private SessionGeneration(
                long connectionEpoch,
                MediaSession.Token token,
                String tokenFingerprint,
                BiliIdentity identity,
                long generationNo) {
            this.connectionEpoch = connectionEpoch;
            this.token = token;
            this.tokenFingerprint = tokenFingerprint;
            this.identity = identity;
            this.generationNo = generationNo;
        }

        public boolean stillCurrent() {
            synchronized (LOCK) {
                return selection.state == CandidateState.READY
                        && equals(selection.generation);
            }
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof SessionGeneration)) {
                return false;
            }
            SessionGeneration that = (SessionGeneration) other;
            return connectionEpoch == that.connectionEpoch
                    && generationNo == that.generationNo
                    && token.equals(that.token)
                    && tokenFingerprint.equals(that.tokenFingerprint)
                    && identity.equals(that.identity);
        }

        @Override
        public int hashCode() {
            int result = Long.hashCode(connectionEpoch);
            result = 31 * result + token.hashCode();
            result = 31 * result + tokenFingerprint.hashCode();
            result = 31 * result + identity.hashCode();
            result = 31 * result + Long.hashCode(generationNo);
            return result;
        }
    }

    public static final class CandidateSelection {
        public final CandidateState state;
        public final MediaController controller;
        public final SessionGeneration generation;
        public final int candidateCount;
        public final String detail;

        private CandidateSelection(
                CandidateState state,
                MediaController controller,
                SessionGeneration generation,
                int candidateCount,
                String detail) {
            this.state = state;
            this.controller = controller;
            this.generation = generation;
            this.candidateCount = candidateCount;
            this.detail = detail;
        }

        private static CandidateSelection none(String detail) {
            return new CandidateSelection(CandidateState.NONE, null, null, 0, detail);
        }

        private static CandidateSelection ambiguous(int count, String detail) {
            return new CandidateSelection(CandidateState.AMBIGUOUS, null, null, count,
                    "ambiguous-session:" + detail);
        }

        private static CandidateSelection ready(
                MediaController controller, SessionGeneration generation) {
            return new CandidateSelection(
                    CandidateState.READY, controller, generation, 1, "stable-candidate");
        }
    }
}
