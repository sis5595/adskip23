package org.adskip.probe;

import android.annotation.SuppressLint;
import android.content.Context;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import org.adskip.core.QueryState;
import org.adskip.core.EntryTimerHealth;
import org.adskip.core.PlaybackCycleGate;
import org.adskip.core.BiliIdentity;
import org.adskip.core.BiliIdentityResolver;
import org.adskip.core.BiliVideoKey;
import org.adskip.core.CidEvidence;
import org.adskip.core.LocalAuditLog;
import org.adskip.core.PageCatalog;
import org.adskip.core.ReadOnlyQueryTiming;
import org.adskip.core.ReadOnlyTransportTiming;
import org.adskip.core.SegmentRepository;
import org.adskip.core.SegmentRequest;
import org.adskip.core.SegmentRule;
import org.adskip.core.ScopedReadRetryGate;
import org.adskip.core.SessionGeneration;
import org.adskip.core.SinglePageCidResolver;
import org.adskip.core.SkipEngine;
import org.adskip.core.UndoStateMachine;
import org.adskip.core.UndoNotificationLease;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Production single-P path. Network work is serial; only fresh exact evidence can reach SkipEngine. */
final class ProductionSkipCoordinator {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService NETWORK = Executors.newSingleThreadExecutor();
    private static final LocalAuditLog AUDIT = new LocalAuditLog(32);
    private static final EntryTimerHealth ENTRY_HEALTH = new EntryTimerHealth();
    private static final PlaybackCycleGate CYCLE_GATE = new PlaybackCycleGate();
    private static final SkipEngine.Policy POLICY = new SkipEngine.Policy(1_500L, 0L, 1_500L);
    private static final SessionAdapter.Observer OBSERVER = ProductionSkipCoordinator::onAdapterChanged;
    private static final Runnable TIMER = ProductionSkipCoordinator::onTimer;
    private static final Runnable RETRY = ProductionSkipCoordinator::onAdapterChanged;
    // Drawer-first UX needs time for a hand swipe, notification search, and action tap.
    // Session/identity/readback gates remain unchanged throughout this short window.
    private static final long UNDO_WINDOW_MS = 15_000L;
    private static final UndoNotificationLease UNDO_NOTICE = new UndoNotificationLease();
    private static final Runnable UNDO_EXPIRE = ProductionSkipCoordinator::cancelUndoNotification;
    private static final Runnable UNDO_POST_RETRY = ProductionSkipCoordinator::showUndoNotification;

    // Process-scoped coordinator retains only Application context; never an Activity/Service.
    @SuppressLint("StaticFieldLeak")
    private static Context context;
    private static boolean observing;
    private static long requestEpoch;
    private static Future<?> inFlight;
    private static ReadOnlyHttp.Cancellation cancellation;
    private static Runnable queryTimeout;
    private static SessionAdapter.SessionGeneration queriedGeneration;
    private static SessionAdapter.SessionGeneration completedGeneration;
    private static SkipEngine engine;
    private static List<SegmentRule> rules = Collections.emptyList();
    private static BiliVideoKey exactVideo;
    private static long resolvedDurationMs;
    private static MediaController controller;
    private static MediaSession.Token token;
    private static String sessionScope;
    private static SkipEngine.TimerPlan timer;
    private static final ScopedReadRetryGate RETRY_GATE = new ScopedReadRetryGate();
    private static final Runnable REFRESH = ProductionSkipCoordinator::expireRules;
    private static final Runnable PREFETCH = ProductionSkipCoordinator::prefetchRules;
    private static final long PREFETCH_LEAD_MS = 10_000L;
    private static long rulesExpireAtMs;
    private static QueryState queryState = QueryState.IDLE;
    private static String cidState = "unavailable";
    private static String lastResult = "尚未查询";
    private static ReadOnlyQueryTiming lastTiming;
    private static ReadOnlyTransportTiming lastBsbTransportTiming;
    private static String recentPagelistStatus = "not_queried";
    private static String recentBsbStatus = "not_queried";
    private static long lastRuleLeadMs = Long.MIN_VALUE;

    private ProductionSkipCoordinator() { }

    static void start(Context source) {
        if (!BuildConfig.PRODUCTION_READ_ONLY_ENABLED) return;
        MAIN.post(() -> {
            context = source.getApplicationContext();
            if (!observing) {
                observing = true;
                SessionAdapter.addObserver(OBSERVER);
            }
            onAdapterChanged();
        });
    }

    static void stop() {
        if (!BuildConfig.PRODUCTION_READ_ONLY_ENABLED) return;
        MAIN.post(() -> {
            reset("listener-disconnected");
            if (observing) SessionAdapter.removeObserver(OBSERVER);
            observing = false;
            context = null;
        });
    }

    static void settingsChanged(Context source) {
        if (!BuildConfig.PRODUCTION_READ_ONLY_ENABLED) return;
        MAIN.post(() -> {
            context = source.getApplicationContext();
            reset("settings-changed");
            onAdapterChanged();
        });
    }

    static void notificationSettingsChanged(Context source) {
        if (!BuildConfig.PRODUCTION_READ_ONLY_ENABLED) return;
        MAIN.post(() -> {
            context = source.getApplicationContext();
            cancelUndoNotification();
        });
    }

    static QueryState queryState() { return queryState; }

    static void refreshRules(Context source) {
        if (!BuildConfig.PRODUCTION_READ_ONLY_ENABLED) return;
        MAIN.post(() -> {
            context = source.getApplicationContext();
            BsbHashSegmentDataSource.clearCache();
            cancelQuery();
            RETRY_GATE.clear();
            MAIN.removeCallbacks(RETRY);
            expireRules();
        });
    }

    private static void expireRules() {
        traceScheduling("rules-expired");
        MAIN.removeCallbacks(REFRESH);
        MAIN.removeCallbacks(PREFETCH);
        rulesExpireAtMs = 0L;
        completedGeneration = null;
        rules = Collections.emptyList();
        if (inFlight != null) queryState = QueryState.QUERYING;
        if (engine != null) drive(); // passive readback may finish; no new entry is authorized.
        onAdapterChanged();
    }

    private static void leaseRules(long untilMs) {
        rulesExpireAtMs = untilMs;
        traceScheduling("rules-leased");
        MAIN.removeCallbacks(REFRESH);
        MAIN.removeCallbacks(PREFETCH);
        MAIN.postDelayed(REFRESH, Math.max(1L, untilMs - SystemClock.elapsedRealtime()));
        if (!rules.isEmpty() && untilMs > SystemClock.elapsedRealtime()) {
            MAIN.postDelayed(PREFETCH, Math.max(1L,
                    untilMs - SystemClock.elapsedRealtime() - PREFETCH_LEAD_MS));
        }
    }

    private static void prefetchRules() {
        if (context == null || !ProductionSettings.enabled(context) || inFlight != null
                || rules.isEmpty() || SystemClock.elapsedRealtime() >= rulesExpireAtMs
                || !readyForSeek()) return;
        SessionAdapter.CandidateSelection selection = SessionAdapter.candidateSelection();
        startQuery(selection.generation, selection.controller, true);
    }

    static String cidState() { return cidState; }
    static String lastResult() { return lastResult; }
    static String timingReport() {
        if (lastTiming == null) return "暂无（仅本进程、最近一次有效查询）";
        return "总计=" + lastTiming.totalMs() + "ms；排队=" + lastTiming.queueMs()
                + "ms；pagelist=" + lastTiming.catalogMs() + "ms；CID 解析="
                + lastTiming.resolverMs() + "ms；BSB+规则="
                + (lastTiming.dataRequested() ? lastTiming.dataMs() + "ms" : "未请求")
                + "；主线程交付=" + lastTiming.mainHandoffMs() + "ms；最早规则提前量="
                + (lastRuleLeadMs == Long.MIN_VALUE ? "无规则/位置未知"
                : lastRuleLeadMs + "ms（负值=入口已过）");
    }
    static String bsbTransportTimingReport() {
        return lastBsbTransportTiming == null ? "暂无 BSB 请求" : lastBsbTransportTiming.report();
    }
    static String recentPagelistStatus() { return recentPagelistStatus; }
    static String recentBsbStatus() { return recentBsbStatus; }
    static List<LocalAuditLog.Entry> auditSnapshot() { return AUDIT.entries(); }
    static String latestAudit() {
        List<LocalAuditLog.Entry> entries = AUDIT.entries();
        if (entries.isEmpty()) return "暂无";
        LocalAuditLog.Entry last = entries.get(entries.size() - 1);
        return last.getStatus() + "；BVID=" + last.getBvid().orElse("-")
                + "；CID=" + last.getCid().orElse("-")
                + "；segment=" + last.getSegmentId().orElse("-")
                + "；category=" + last.getCategory().orElse("-")
                + "；range=" + last.getStartMs() + "-" + last.getEndMs() + "ms"
                + "；source=" + last.getSource().orElse("-")
                + "/" + last.getVersion().orElse("-")
                + "；members=" + last.getRules().size()
                + "；requested-distance=" + last.getSkippedDurationMs() + "ms"
                + "；reason=" + last.getFailClosedReason().orElse("-");
    }
    /** Read-only UI projection source; the audit remains bounded and process-local. */
    static LocalAuditLog.Entry recentUserEvent() {
        List<LocalAuditLog.Entry> entries = AUDIT.entries();
        for (int index = entries.size() - 1; index >= 0; index--) {
            LocalAuditLog.Entry entry = entries.get(index);
            if (entry.getStatus() == LocalAuditLog.Status.CONFIRMED
                    || entry.getStatus() == LocalAuditLog.Status.UNDO
                    || entry.getStatus() == LocalAuditLog.Status.FAILED) return entry;
        }
        return null;
    }
    static int ruleCount() { return rules.size(); }
    static long recentEntryDelayMs() { return ENTRY_HEALTH.recentDelayMs(SystemClock.elapsedRealtime()); }
    static String nextSegmentId() { return timer == null ? null : timer.getSegmentId(); }
    static boolean undoAvailable() {
        return engine != null && engine.isUndoAvailable(snapshot());
    }

    static void requestUndo() {
        MAIN.post(() -> {
            SkipEngine.PlaybackSnapshot current = snapshot();
            if (engine == null || current == null || !readyForSeek()) {
                lastResult = "Undo 拒绝：会话或证据已失效";
                return;
            }
            apply(engine.requestUndo(current));
        });
    }

    static void requestUndoFromNotification(Context source, String tokenValue) {
        if (!BuildConfig.PRODUCTION_READ_ONLY_ENABLED) return;
        Runnable action = () -> {
            if (!UNDO_NOTICE.consume(tokenValue, SystemClock.elapsedRealtime())) {
                noticeDiagnostic("action-token-rejected");
                return;
            }
            Context appContext = source.getApplicationContext();
            UndoNotificationController.cancel(appContext);
            MAIN.removeCallbacks(UNDO_EXPIRE);
            SkipEngine.PlaybackSnapshot current = snapshot();
            if (context == null || !ProductionSettings.enabled(context) || engine == null
                    || current == null || !readyForSeek() || !engine.isUndoAvailable(current)) {
                lastResult = "通知 Undo 拒绝：会话、身份或窗口已失效";
                noticeDiagnostic("action-scope-rejected");
                return;
            }
            apply(engine.requestUndo(current));
        };
        if (Looper.myLooper() == Looper.getMainLooper()) action.run();
        else MAIN.post(action);
    }

    private static void onAdapterChanged() {
        if (context == null || !ProductionSettings.enabled(context)) {
            if (inFlight != null || engine != null) reset("disabled");
            return;
        }
        SessionAdapter.CandidateSelection selection = SessionAdapter.candidateSelection();
        if (selection.state != SessionAdapter.CandidateState.READY
                || selection.controller == null || selection.generation == null
                || !selection.generation.stillCurrent()
                || !SessionProbe.BILIBILI_PACKAGE.equals(selection.controller.getPackageName())) {
            cancelUndoNotification();
            if (inFlight != null) cancelQuery();
            if (engine != null && (timer == null
                    || timer.getKind() == SkipEngine.TimerKind.ENTRY)) {
                apply(engine.suspend());
            }
            return;
        }
        SessionAdapter.SessionGeneration current = selection.generation;
        if (engine != null && !sameLoadedIdentity(current, selection.controller)) {
            reset("video-or-session-changed");
        }
        if (engine != null) {
            if (SystemClock.elapsedRealtime() >= rulesExpireAtMs) rules = Collections.emptyList();
            drive();
            SkipEngine.PlaybackSnapshot undoSnapshot = snapshot();
            if (UNDO_NOTICE.active(SystemClock.elapsedRealtime())
                    && (undoSnapshot == null || !engine.isUndoAvailable(undoSnapshot)
                    || !readyForSeek())) {
                cancelUndoNotification();
            } else if (!UNDO_NOTICE.active(SystemClock.elapsedRealtime())
                    && engine.getUndoState() == UndoStateMachine.State.AVAILABLE
                    && ProductionSettings.undoNotificationEnabled(context)
                    && undoSnapshot != null && engine.isUndoAvailable(undoSnapshot)) {
                showUndoNotification();
            }
            if (SystemClock.elapsedRealtime() < rulesExpireAtMs) return;
        }
        if (current.equals(queriedGeneration)) return;
        if (current.equals(completedGeneration) && SystemClock.elapsedRealtime() < rulesExpireAtMs) return;
        if (RETRY_GATE.selectScope(current)) MAIN.removeCallbacks(RETRY);
        if (!RETRY_GATE.canAttempt(SystemClock.elapsedRealtime())) return;
        cancelQuery();
        startQuery(current, selection.controller, false);
    }

    private static void startQuery(SessionAdapter.SessionGeneration generation,
            MediaController candidate, boolean prefetch) {
        queriedGeneration = generation;
        long epoch = ++requestEpoch;
        ReadOnlyHttp.Cancellation handle = new ReadOnlyHttp.Cancellation();
        cancellation = handle;
        String origin = ProductionSettings.serverOrigin(context);
        Context appContext = context;
        String bvid = generation.identity.getBvid();
        long duration = generation.identity.getDurationMs();
        if (!prefetch) {
            queryState = QueryState.QUERYING;
            cidState = "checking single-P";
            lastResult = "查询中（只读；单并发）";
        }
        lastTiming = null;
        lastBsbTransportTiming = null;
        lastRuleLeadMs = Long.MIN_VALUE;
        long queuedAtMs = SystemClock.elapsedRealtime();
        inFlight = NETWORK.submit(() -> {
            try {
                long workerAtMs = SystemClock.elapsedRealtime();
                PageCatalog catalog = new BiliSinglePageCatalogSource().fetch(bvid, handle);
                long catalogAtMs = SystemClock.elapsedRealtime();
                Optional<CidEvidence> evidence = SinglePageCidResolver.resolve(catalog, bvid, duration);
                long resolvedAtMs = SystemClock.elapsedRealtime();
                SegmentRepository.Result result = null;
                ReadOnlyTransportTiming bsbTiming = null;
                boolean dataRequested = false;
                long validUntilMs = 0L;
                if (evidence.isPresent() && !handle.isCancelled() && generation.stillCurrent()) {
                    dataRequested = true;
                    BiliVideoKey key = evidence.get().getExactVideo();
                    SessionGeneration scope = new SessionGeneration(scopeOf(generation), key,
                            generation.generationNo);
                    BsbHashSegmentDataSource source = new BsbHashSegmentDataSource(origin, handle, prefetch);
                    SegmentRepository repository = new SegmentRepository(source);
                    result = repository.loadAutomaticSkips(new SegmentRequest(scope), evidence.get(),
                            duration, ProductionSettings.categoryPolicy(appContext),
                            ignored -> generation.stillCurrent() && !handle.isCancelled());
                    bsbTiming = source.timing();
                    validUntilMs = source.validUntilMs();
                }
                long dataAtMs = dataRequested ? SystemClock.elapsedRealtime() : resolvedAtMs;
                SegmentRepository.Result finalResult = result;
                ReadOnlyTransportTiming finalBsbTiming = bsbTiming;
                boolean finalDataRequested = dataRequested;
                long finalValidUntilMs = validUntilMs;
                MAIN.post(() -> finishQuery(epoch, generation, candidate, catalog,
                        evidence, finalResult, finalBsbTiming, handle, queuedAtMs, workerAtMs, catalogAtMs,
                        resolvedAtMs, dataAtMs, finalDataRequested, finalValidUntilMs));
            } catch (RuntimeException exception) {
                MAIN.post(() -> failQuery(epoch, generation, "transport-exception"));
            } finally {
                handle.close();
            }
        });
        queryTimeout = () -> failQuery(epoch, generation, "query-deadline");
        MAIN.postDelayed(queryTimeout, ReadOnlyHttp.QUERY_DEADLINE_MS);
    }

    private static void failQuery(long epoch, SessionAdapter.SessionGeneration generation, String reason) {
        if (epoch != requestEpoch || context == null) return;
        cancelQuery();
        recentBsbStatus = reason;
        scheduleRetry(generation, reason);
    }

    private static void finishQuery(long epoch, SessionAdapter.SessionGeneration generation,
            MediaController candidate, PageCatalog catalog, Optional<CidEvidence> evidence,
            SegmentRepository.Result result, ReadOnlyTransportTiming bsbTiming,
            ReadOnlyHttp.Cancellation handle,
            long queuedAtMs, long workerAtMs, long catalogAtMs, long resolvedAtMs,
            long dataAtMs, boolean dataRequested, long validUntilMs) {
        if (epoch != requestEpoch || (handle.isCancelled() && !handle.isTimedOut()) || context == null
                || !ProductionSettings.enabled(context) || !generation.stillCurrent()) {
            return;
        }
        if (handle.isTimedOut()) {
            failQuery(epoch, generation, "query-deadline");
            return;
        }
        inFlight = null;
        if (queryTimeout != null) MAIN.removeCallbacks(queryTimeout);
        queryTimeout = null;
        cancellation = null;
        queriedGeneration = null;
        lastTiming = new ReadOnlyQueryTiming(queuedAtMs, workerAtMs, catalogAtMs,
                resolvedAtMs, dataAtMs, SystemClock.elapsedRealtime(), dataRequested);
        lastBsbTransportTiming = bsbTiming;
        recentPagelistStatus = catalog.getState().name();
        recentBsbStatus = bsbTiming == null ? "not_requested"
                : bsbTiming.getPhase().name() + "_" + bsbTiming.getFailure().name();
        if (catalog.getState() == PageCatalog.State.TIMEOUT
                || catalog.getState() == PageCatalog.State.NETWORK_ERROR) {
            cidState = "unavailable";
            scheduleRetry(generation, "pagelist-" + catalog.getState());
            return;
        }
        if (evidence.isPresent() && result != null
                && (result.getStatus() == SegmentRepository.Status.TIMEOUT
                || result.getStatus() == SegmentRepository.Status.NETWORK_ERROR)) {
            cidState = "exact single-P";
            scheduleRetry(generation, "BSB-" + result.getStatus());
            return;
        }
        completedGeneration = generation;
        RETRY_GATE.clear();
        MAIN.removeCallbacks(RETRY);
        if (!evidence.isPresent()) {
            withdrawRulesForTerminalResult();
            queryState = catalog.getState() == PageCatalog.State.SUCCESS
                    ? QueryState.IDENTITY_REJECTED : QueryState.SERVICE_ERROR;
            cidState = catalog.getState() == PageCatalog.State.SUCCESS
                    && catalog.getCandidates().size() > 1 ? "multi-P unknown" : "unavailable";
            lastResult = "安全拒绝：pagelist=" + catalog.getState()
                    + (catalog.getState() == PageCatalog.State.HTTP_ERROR
                    ? "(" + BiliSinglePageCatalogSource.lastHttpStatus() + ")" : "")
                    + "，候选页=" + catalog.getCandidates().size();
            return;
        }
        cidState = "exact single-P";
        queryState = QueryState.from(result);
        if (queryState == QueryState.SERVICE_ERROR) {
            withdrawRulesForTerminalResult();
            lastResult = "片段查询失败：" + (result == null ? "stale/cancelled"
                    : result.getStatus() + "/" + result.getReason());
            return;
        }
        if (validUntilMs > 0L && validUntilMs <= SystemClock.elapsedRealtime()) {
            // A cache hit can expire while its result is queued for the main thread.
            // Never turn its exhausted lease into a fresh 20/60 second authorization.
            rules = Collections.emptyList();
            rulesExpireAtMs = 0L;
            completedGeneration = null;
            MAIN.removeCallbacks(REFRESH);
            MAIN.removeCallbacks(PREFETCH);
            scheduleRetry(generation, "rules-expired-before-delivery");
            drive();
            return;
        }
        BiliVideoKey resolvedVideo = evidence.get().getExactVideo();
        boolean retainCycle = engine != null && resolvedVideo.equals(exactVideo)
                && sameLoadedIdentity(generation, candidate);
        exactVideo = resolvedVideo;
        resolvedDurationMs = generation.identity.getDurationMs();
        controller = candidate;
        token = candidate.getSessionToken();
        sessionScope = scopeOf(generation);
        rules = result.getRules();
        leaseRules(validUntilMs > SystemClock.elapsedRealtime() ? validUntilMs
                : SystemClock.elapsedRealtime() + (rules.isEmpty() ? 20_000L : 60_000L));
        SkipEngine.PlaybackSnapshot publicationSnapshot = snapshot();
        if (publicationSnapshot != null) {
            lastRuleLeadMs = ReadOnlyQueryTiming.earliestRuleLeadMs(
                    publicationSnapshot.getPositionMs(), rules);
        }
        if (!retainCycle) CYCLE_GATE.clear();
        if (!retainCycle) engine = new SkipEngine(POLICY,
                new UndoStateMachine(new UndoStateMachine.Policy(UNDO_WINDOW_MS, 1_500L, 2_000L)),
                AUDIT);
        lastResult = rules.isEmpty() ? "查询成功；没有启用且可执行的片段"
                : "规则已发布：" + rules.size() + " 条（single-P exact CID）";
        drive();
    }

    private static void drive() {
        SkipEngine.PlaybackSnapshot current = snapshot();
        if (engine != null && current != null) {
            if (CYCLE_GATE.observe(current, timer != null && timer.getKind() != SkipEngine.TimerKind.ENTRY)) {
                cancelUndoNotification();
                apply(engine.resetPlaybackCycle(current.getSessionScope(), current.getVideoKey()));
            }
            apply(engine.onPlayback(current, rules));
        }
    }

    private static void withdrawRulesForTerminalResult() {
        rules = Collections.emptyList();
        leaseRules(SystemClock.elapsedRealtime() + 60_000L);
        drive();
    }

    private static void onTimer() {
        traceScheduling("timer-fired");
        SkipEngine.TimerPlan fired = timer;
        timer = null;
        if (engine == null || fired == null) return;
        if (fired.getKind() == SkipEngine.TimerKind.ENTRY) {
            ENTRY_HEALTH.record(fired.getWakeAtElapsedMs(), SystemClock.elapsedRealtime());
        }
        if (fired.getKind() == SkipEngine.TimerKind.ENTRY
                && SystemClock.elapsedRealtime() >= rulesExpireAtMs) {
            expireRules();
            return;
        }
        if (fired.getKind() == SkipEngine.TimerKind.ENTRY && !readyForSeek()) {
            apply(engine.suspend());
            return;
        }
        SkipEngine.PlaybackSnapshot current = snapshot();
        if (current == null) {
            apply(engine.suspend());
            return;
        }
        traceScheduling("timer-position=" + current.getPositionMs());
        apply(engine.onTimer(current, rules, fired.getId()));
    }

    private static SkipEngine.PlaybackSnapshot snapshot() {
        if (controller == null || token == null || exactVideo == null || sessionScope == null
                || !token.equals(controller.getSessionToken())
                || !SessionProbe.BILIBILI_PACKAGE.equals(controller.getPackageName())) return null;
        MediaMetadata metadata = controller.getMetadata();
        PlaybackState playback = controller.getPlaybackState();
        if (metadata == null || playback == null) return null;
        Optional<BiliIdentity> identity = BiliIdentityResolver.resolve(
                metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID),
                metadata.getString(MediaMetadata.METADATA_KEY_TITLE),
                metadata.getLong(MediaMetadata.METADATA_KEY_DURATION));
        if (!identity.isPresent() || !exactVideo.getBvid().equals(identity.get().getBvid())
                || identity.get().getDurationMs() != resolvedDurationMs
                || playback.getPosition() < 0L) return null;
        Optional<SessionAdapter.Diagnostics> diagnostics = SessionAdapter.diagnostics(token);
        if (!diagnostics.isPresent()) return null;
        long positionMs = SessionProbe.estimatedPosition(playback);
        long durationMs = identity.get().getDurationMs();
        float speed = playback.getPlaybackSpeed();
        boolean playing = playback.getState() == PlaybackState.STATE_PLAYING;
        if (positionMs > durationMs || !Float.isFinite(speed) || speed < 0F
                || (playing && speed <= 0F)) return null;
        PlaybackState publisher = SessionAdapter.publisherPlayback(token, identity.get());
        boolean publisherMatches = publisher != null && publisher.getState() == playback.getState()
                && Float.compare(publisher.getPlaybackSpeed(), speed) == 0
                && publisher.getPosition() >= 0L && publisher.getLastPositionUpdateTime() > 0L;
        return new SkipEngine.PlaybackSnapshot(sessionScope, exactVideo,
                diagnostics.get().generationNo, positionMs,
                durationMs, speed, playing,
                (playback.getActions() & PlaybackState.ACTION_SEEK_TO) != 0L,
                SystemClock.elapsedRealtime(), publisherMatches ? publisher.getPosition() : -1L,
                publisherMatches ? publisher.getLastPositionUpdateTime() : -1L);
    }

    private static boolean readyForSeek() {
        SessionAdapter.CandidateSelection selection = SessionAdapter.candidateSelection();
        return selection.state == SessionAdapter.CandidateState.READY
                && selection.generation != null && selection.generation.stillCurrent()
                && selection.controller != null && sameLoadedIdentity(
                        selection.generation, selection.controller);
    }

    private static boolean sameLoadedIdentity(SessionAdapter.SessionGeneration generation,
            MediaController candidate) {
        return token != null && token.equals(candidate.getSessionToken())
                && sessionScope != null && sessionScope.equals(scopeOf(generation))
                && exactVideo != null && exactVideo.getBvid().equals(generation.identity.getBvid())
                && resolvedDurationMs == generation.identity.getDurationMs();
    }

    private static String scopeOf(SessionAdapter.SessionGeneration generation) {
        return generation.connectionEpoch + ":" + generation.tokenFingerprint + ":"
                + generation.identity.getBvid();
    }

    private static void apply(SkipEngine.Directive directive) {
        traceScheduling("apply=" + directive.getOutcome() + ":" + directive.getReason());
        if (!directive.shouldRetainCurrentTimer()) {
            MAIN.removeCallbacks(TIMER);
            timer = null;
        }
        if (directive.getTimerPlan() != null) {
            timer = directive.getTimerPlan();
            traceScheduling("timer-scheduled");
            MAIN.removeCallbacks(TIMER);
            MAIN.postDelayed(TIMER, Math.max(0L,
                    timer.getWakeAtElapsedMs() - SystemClock.elapsedRealtime()));
        }
        switch (directive.getOutcome()) {
            case SEEK:
            case UNDO_SEEK:
                cancelUndoNotification();
                if (!readyForSeek() || (directive.getOutcome() == SkipEngine.Outcome.SEEK
                        && SystemClock.elapsedRealtime() >= rulesExpireAtMs)) {
                    dispatchFailed(directive, "session-not-ready-at-dispatch");
                    return;
                }
                try {
                    controller.getTransportControls().seekTo(directive.getSeekTargetMs());
                    lastResult = directive.getOutcome() == SkipEngine.Outcome.SEEK
                            ? "已请求跳过；等待回读确认" : "已请求 Undo；等待回读确认";
                } catch (RuntimeException exception) {
                    dispatchFailed(directive, "transport-dispatch-failed");
                }
                break;
            case CONFIRMED:
                lastResult = "skipped：回读确认";
                showUndoNotification();
                break;
            case FAILED:
                lastResult = "failed：seek 回读未确认";
                break;
            case UNDO_CONFIRMED:
                cancelUndoNotification();
                lastResult = "Undo：回读确认，本轮 suppress";
                break;
            case UNDO_FAILED:
                cancelUndoNotification();
                lastResult = "Undo failed：回读未确认";
                break;
            case MISSED:
                lastResult = "安全拒绝：错过入口窗口";
                break;
            default:
                break;
        }
    }

    private static void dispatchFailed(SkipEngine.Directive directive, String reason) {
        SkipEngine.PlaybackSnapshot current = readyForSeek() ? snapshot() : null;
        List<SegmentRule> authorized = SystemClock.elapsedRealtime() < rulesExpireAtMs
                ? rules : Collections.emptyList();
        apply(directive.getOutcome() == SkipEngine.Outcome.SEEK
                ? engine.onSeekDispatchFailed(directive.getAttemptId(), reason, current, authorized)
                : engine.onUndoDispatchFailed(reason, current, authorized));
    }

    /** Debug-only scheduling evidence; no titles, notification contents, or external IDs. */
    private static void traceScheduling(String event) {
        if (!BuildConfig.DEBUG) return;
        Log.d("AdSkipScheduling", "elapsed=" + SystemClock.elapsedRealtime()
                + " event=" + event + " rules=" + rules.size() + " expires=" + rulesExpireAtMs
                + " timer=" + (timer == null ? "none" : timer.getKind() + ":" + timer.getWakeAtElapsedMs())
                + " candidate=" + SessionAdapter.candidateSelection().state);
    }

    private static void cancelQuery() {
        requestEpoch++;
        if (queryTimeout != null) MAIN.removeCallbacks(queryTimeout);
        queryTimeout = null;
        if (cancellation != null) cancellation.cancel();
        if (inFlight != null) inFlight.cancel(true);
        cancellation = null;
        inFlight = null;
        queriedGeneration = null;
    }

    private static void scheduleRetry(SessionAdapter.SessionGeneration generation, String reason) {
        if (!rules.isEmpty() && SystemClock.elapsedRealtime() < rulesExpireAtMs
                && sameLoadedIdentity(generation, controller)) {
            // A failed proactive read must neither renew nor prematurely revoke the existing lease.
            queryState = QueryState.READY;
            cidState = "exact single-P";
            lastResult = "提前刷新失败（" + reason + "）；旧规则仅使用至原期限";
            return;
        }
        long delay = RETRY_GATE.failed(generation, SystemClock.elapsedRealtime());
        queryState = QueryState.SERVICE_ERROR;
        lastResult = "只读查询失败（" + reason + "）；" + delay / 1_000L + " 秒后重试";
        MAIN.removeCallbacks(RETRY);
        MAIN.postDelayed(RETRY, delay);
    }

    private static void reset(String reason) {
        cancelUndoNotification();
        cancelQuery();
        MAIN.removeCallbacks(TIMER);
        MAIN.removeCallbacks(RETRY);
        RETRY_GATE.clear();
        MAIN.removeCallbacks(REFRESH);
        MAIN.removeCallbacks(PREFETCH);
        rulesExpireAtMs = 0L;
        queryState = QueryState.IDLE;
        timer = null;
        engine = null;
        CYCLE_GATE.clear();
        rules = Collections.emptyList();
        exactVideo = null;
        resolvedDurationMs = 0L;
        controller = null;
        token = null;
        sessionScope = null;
        completedGeneration = null;
        cidState = "unavailable";
        lastResult = "已停止：" + reason;
        lastTiming = null;
        lastBsbTransportTiming = null;
        lastRuleLeadMs = Long.MIN_VALUE;
    }

    private static void showUndoNotification() {
        cancelUndoNotification();
        if (context == null || engine == null) {
            noticeDiagnostic("no-context-or-engine");
            return;
        }
        if (!ProductionSettings.undoNotificationEnabled(context)) {
            noticeDiagnostic("setting-off");
            return;
        }
        if (!UndoNotificationController.notificationsAvailable(context)) {
            noticeDiagnostic("permission-or-system-disabled");
            return;
        }
        UndoStateMachine.Receipt receipt = engine.getUndoReceipt();
        if (receipt == null || engine.getUndoState() != UndoStateMachine.State.AVAILABLE) {
            noticeDiagnostic("receipt-not-available");
            return;
        }
        long nowMs = SystemClock.elapsedRealtime();
        long remainingMs = UNDO_WINDOW_MS - (nowMs - receipt.getConfirmedAtElapsedMs());
        if (remainingMs <= 0L) {
            noticeDiagnostic("window-expired");
            return;
        }
        SkipEngine.PlaybackSnapshot current = snapshot();
        if (current == null || !engine.isUndoAvailable(current) || !readyForSeek()) {
            noticeDiagnostic("waiting-for-fresh-scope");
            if (remainingMs > 250L) MAIN.postDelayed(UNDO_POST_RETRY, 250L);
            return;
        }
        long durationMs = receipt.getSkippedToPositionMs() - receipt.getOriginalPositionMs();
        if (durationMs <= 0L) {
            noticeDiagnostic("segment-not-found");
            return;
        }
        String noticeToken = UNDO_NOTICE.arm(nowMs, remainingMs);
        if (!UndoNotificationController.show(context, noticeToken, remainingMs, durationMs,
                ProductionSettings.undoHeadsUpEnabled(context))) {
            UNDO_NOTICE.invalidate();
            noticeDiagnostic("notification-manager-rejected");
            return;
        }
        noticeDiagnostic("posted");
        MAIN.postDelayed(UNDO_EXPIRE, remainingMs);
    }

    private static void noticeDiagnostic(String reason) {
        if (BuildConfig.DEBUG) Log.d("AdSkipUndoNotice", reason);
    }

    private static void cancelUndoNotification() {
        MAIN.removeCallbacks(UNDO_EXPIRE);
        MAIN.removeCallbacks(UNDO_POST_RETRY);
        UNDO_NOTICE.invalidate();
        if (context != null) UndoNotificationController.cancel(context);
    }
}
