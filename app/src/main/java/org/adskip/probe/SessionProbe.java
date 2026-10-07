package org.adskip.probe;

import android.content.Context;
import android.media.MediaDescription;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;

import org.adskip.core.BiliIdentity;
import org.adskip.core.AccessibilityEvidenceMapper;
import org.adskip.core.AccessibilitySelectionSignal;
import org.adskip.core.BiliIdentityResolver;
import org.adskip.core.BiliVideoKey;
import org.adskip.core.CategoryPolicy;
import org.adskip.core.CandidatePart;
import org.adskip.core.CandidatePageMap;
import org.adskip.core.CidEvidence;
import org.adskip.core.CurrentPartEvidence;
import org.adskip.core.CurrentPartResolver;
import org.adskip.core.DisplayedPartMarker;
import org.adskip.core.EvidenceFingerprint;
import org.adskip.core.EvidenceScope;
import org.adskip.core.EvidenceSource;
import org.adskip.core.EvidenceTimeline;
import org.adskip.core.FixtureSegmentDataSource;
import org.adskip.core.LocalAuditLog;
import org.adskip.core.SegmentRepository;
import org.adskip.core.SegmentRequest;
import org.adskip.core.SegmentRule;
import org.adskip.core.SessionGeneration;
import org.adskip.core.SessionTransitionEvent;
import org.adskip.core.SkipEngine;
import org.adskip.core.ShareLinkObservation;
import org.adskip.core.UndoStateMachine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/** In-memory media-session diagnostics and the explicitly armed fixed-video experiment. */
public final class SessionProbe {
    /** Production targets the official B站 package; harness uses a separately installed helper. */
    public static final String BILIBILI_PACKAGE = BuildConfig.TARGET_MEDIA_PACKAGE;
    public static final long EXPERIMENT_AID = 117_008_263_877_045L;
    public static final String EXPERIMENT_BVID = "BV1Jo346uEYC";
    public static final String EXPERIMENT_CID = "40439909150";
    public static final String EXPERIMENT_MEDIA_ID = "1170082638770450";
    public static final long EXPERIMENT_DURATION_MS = 317_450L;
    public static final long EXPERIMENT_PREPARE_MS = 105_000L;
    public static final long EXPERIMENT_START_MS = 116_648L;
    public static final long EXPERIMENT_END_MS = 150_265L;

    private static final BiliVideoKey EXPERIMENT_VIDEO =
            new BiliVideoKey(EXPERIMENT_BVID, EXPERIMENT_CID);
    private static final SegmentRepository EXPERIMENT_REPOSITORY =
            new SegmentRepository(FixtureSegmentDataSource.experimentFixture());
    private static final LocalAuditLog PRODUCT_AUDIT = new LocalAuditLog(32);
    private static final EvidenceTimeline EVIDENCE_TIMELINE = new EvidenceTimeline(64);

    /*
     * These values retain the already proven experiment's exact endpoint (epsilon = 0). They are
     * deliberately injected rather than becoming global production defaults: real B站 calibration
     * in later stages decides the endpoint epsilon and entry tolerance.
     */
    private static final SkipEngine.Policy EXPERIMENT_POLICY =
            new SkipEngine.Policy(1_500L, 0L, 1_500L);
    private static final Handler EXPERIMENT_HANDLER = new Handler(Looper.getMainLooper());
    private static final Runnable COMPLETE_ARM = SessionProbe::completeArm;
    private static final Runnable ENGINE_TIMER = SessionProbe::fireEngineTimer;
    private static final SessionAdapter.Observer EXPERIMENT_OBSERVER =
            SessionProbe::onAdapterStateChanged;

    private static boolean experimentArmed;
    private static boolean observerRegistered;
    private static String experimentReport = "P4 实验：未武装";
    private static long experimentPrepareDeadlineMs;
    private static SkipEngine experimentEngine;
    private static List<SegmentRule> experimentRules = Collections.emptyList();
    private static MediaController experimentController;
    private static MediaSession.Token experimentToken;
    private static SkipEngine.TimerPlan experimentTimer;
    private static String experimentAuditReport = "本地审计：暂无自动跳过事件";

    private SessionProbe() {
    }

    public static List<MediaController> activeSessions(Context context) {
        return SessionAdapter.currentControllers();
    }

    public static void rescan(Context context) {
        SessionAdapter.requestRescan();
    }

    public static void clear() {
        cancelExperiment();
        SessionAdapter.disconnect("probe-clear");
    }

    public static boolean isExperimentEligible(MediaController controller) {
        if (controller == null || !BILIBILI_PACKAGE.equals(controller.getPackageName())) {
            return false;
        }
        MediaMetadata metadata = controller.getMetadata();
        if (metadata == null) {
            return false;
        }
        Optional<BiliIdentity> identity = BiliIdentityResolver.resolve(
                metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID),
                metadata.getString(MediaMetadata.METADATA_KEY_TITLE),
                metadata.getLong(MediaMetadata.METADATA_KEY_DURATION));
        return identity.isPresent()
                && identity.get().getAid() == EXPERIMENT_AID
                && EXPERIMENT_BVID.equals(identity.get().getBvid())
                && EXPERIMENT_DURATION_MS == identity.get().getDurationMs();
    }

    /** Starts only the user-triggered P4 experiment; this is not a general automatic mode. */
    public static boolean armExperiment(Context context) {
        if (!BuildConfig.DEBUG) return false;
        SessionAdapter.CandidateSelection selection = SessionAdapter.candidateSelection();
        MediaController controller = selection.controller;
        if (selection.state != SessionAdapter.CandidateState.READY
                || controller == null
                || !isExperimentEligible(controller)) {
            experimentReport = "P4 实验：没有唯一且稳定的目标视频会话，拒绝武装";
            return false;
        }
        PlaybackState state = controller.getPlaybackState();
        if (state == null || (state.getActions() & PlaybackState.ACTION_SEEK_TO) == 0L) {
            experimentReport = "P4 实验：目标视频会话当前不可精确跳转";
            return false;
        }

        clearExperimentState();
        experimentReport = "P4 实验：准备中；请求跳到 " + EXPERIMENT_PREPARE_MS + " ms";
        experimentPrepareDeadlineMs = SystemClock.elapsedRealtime() + 5_000L;
        controller.getTransportControls().seekTo(EXPERIMENT_PREPARE_MS);
        EXPERIMENT_HANDLER.postDelayed(COMPLETE_ARM, 1_500L);
        return true;
    }

    public static void cancelExperiment() {
        clearExperimentState();
        experimentReport = "P4 实验：已取消";
    }

    public static String experimentReport() {
        return experimentReport;
    }

    public static String experimentAuditReport() {
        return experimentAuditReport;
    }

    /**
     * Adds one explicitly requested, sanitized MediaSession observation to the research timeline.
     * It never creates CidEvidence and has no path to SegmentRuleSelector or SkipEngine.
     */
    public static String captureCurrentEvidence() {
        SessionAdapter.CandidateSelection selection = SessionAdapter.candidateSelection();
        if (selection.state != SessionAdapter.CandidateState.READY
                || selection.controller == null || selection.generation == null) {
            return "EvidenceTimeline：未追加（没有唯一稳定 generation）";
        }
        SessionAdapter.SessionGeneration generation = selection.generation;
        if (!generation.stillCurrent()) {
            return "EvidenceTimeline：未追加（generation 已过期）";
        }
        MediaController controller = selection.controller;
        MediaMetadata metadata = controller.getMetadata();
        PlaybackState playback = controller.getPlaybackState();
        if (metadata == null || playback == null) {
            return "EvidenceTimeline：未追加（metadata/playback 缺失）";
        }
        long observedAt = SystemClock.elapsedRealtime();
        EvidenceScope scope = evidenceScope(generation);
        CurrentPartEvidence.Builder builder = CurrentPartEvidence.builder(
                        scope, EvidenceSource.MEDIA_SESSION, observedAt)
                .freshness(CurrentPartEvidence.Freshness.FRESH)
                .durationMs(generation.identity.getDurationMs())
                .positionMs(estimatedPosition(playback))
                .rawFingerprint(EvidenceFingerprint.sha256(publicSurfaceFingerprint(controller)));

        CharSequence partLabel = partLabelCandidate(metadata);
        if (partLabel != null) {
            builder.labelFingerprint(EvidenceFingerprint.sha256(partLabel.toString()));
        }
        Optional<DisplayedPartMarker> marker = displayedPartMarker(metadata);
        if (marker.isPresent()) {
            builder.page(marker.get().getPage()).totalParts(marker.get().getTotalParts());
        }
        CurrentPartEvidence evidence = builder.build();
        if (!generation.stillCurrent()) {
            return "EvidenceTimeline：未追加（采样期间 generation 已变化）";
        }
        EvidenceTimeline.AppendResult result = EVIDENCE_TIMELINE.append(evidence, scope);
        if (result != EvidenceTimeline.AppendResult.ACCEPTED) {
            return "EvidenceTimeline：未追加（stale scope）";
        }
        String part = marker.isPresent()
                ? marker.get().getPage() + "/" + marker.get().getTotalParts() : "unknown";
        String fingerprint = evidence.getRawFingerprint().orElse("<none>");
        return "EvidenceTimeline：已追加 MEDIA_SESSION；bvid=" + scope.getBvid()
                + "；generation=" + scope.getGeneration()
                + "；displayedPart=" + part
                + "；duration=" + evidence.getDurationMs().getAsLong()
                + "ms；position=" + evidence.getPositionMs().getAsLong()
                + "ms；surfaceDigest=" + fingerprint.substring(0, 12)
                + "；本 scope events=" + EVIDENCE_TIMELINE.eventsFor(scope).size()
                + "（research only，不授权 CID/seek）";
    }

    /** Appends one sanitized explicit share observation; short-link expansion is outside this APK. */
    public static String captureShareEvidence(ShareLinkObservation observation) {
        if (observation == null || observation.getState() != ShareLinkObservation.State.DIRECT) {
            return "分享链接证据：未追加（不是已解析 direct link）";
        }
        SessionAdapter.CandidateSelection selection = SessionAdapter.candidateSelection();
        if (selection.state != SessionAdapter.CandidateState.READY
                || selection.generation == null || !selection.generation.stillCurrent()) {
            return "分享链接证据：未追加（没有唯一稳定 generation）";
        }
        SessionAdapter.SessionGeneration generation = selection.generation;
        EvidenceScope scope = evidenceScope(generation);
        Optional<CurrentPartEvidence> converted = observation.toEvidence(
                scope, SystemClock.elapsedRealtime());
        if (!converted.isPresent()) {
            return "分享链接证据：拒绝（分享 BVID 与当前 generation 不一致）";
        }
        if (!generation.stillCurrent()) {
            return "分享链接证据：未追加（绑定期间 generation 已变化）";
        }
        EvidenceTimeline.AppendResult result = EVIDENCE_TIMELINE.append(converted.get(), scope);
        if (result != EvidenceTimeline.AppendResult.ACCEPTED) {
            return "分享链接证据：未追加（stale scope）";
        }
        String page = converted.get().getPage().isPresent()
                ? Long.toString(converted.get().getPage().getAsLong()) : "unknown";
        String position = converted.get().getPositionMs().isPresent()
                ? converted.get().getPositionMs().getAsLong() + "ms" : "unknown";
        return "分享链接证据：已追加 SHARE_LINK；bvid=" + scope.getBvid()
                + "；generation=" + scope.getGeneration() + "；page=" + page
                + "；sharePosition=" + position + "；本 scope events="
                + EVIDENCE_TIMELINE.eventsFor(scope).size()
                + "（research only，不授权 CID/seek）";
    }

    /** Returns the current immutable research scope, never a controller or account-bearing value. */
    public static Optional<EvidenceScope> currentEvidenceScope() {
        SessionAdapter.CandidateSelection selection = SessionAdapter.candidateSelection();
        if (selection.state != SessionAdapter.CandidateState.READY
                || selection.generation == null || !selection.generation.stillCurrent()) {
            return Optional.empty();
        }
        return Optional.of(evidenceScope(selection.generation));
    }

    public static boolean isEvidenceScopeCurrent(EvidenceScope expected) {
        return expected != null && currentEvidenceScope().map(expected::equals).orElse(false);
    }

    /** Research-only selected-state timeline bridge; it cannot create CID authorization. */
    public static void captureAccessibilityEvidence(EvidenceScope scope, long observedAtElapsedMs,
            List<AccessibilitySelectionSignal> signals) {
        if (!BuildConfig.RESEARCH_NETWORK_ENABLED || scope == null || signals == null
                || !isEvidenceScopeCurrent(scope)) return;
        for (AccessibilitySelectionSignal signal : signals) {
            Optional<CurrentPartEvidence> mapped = AccessibilityEvidenceMapper.map(signal,
                    scope, observedAtElapsedMs, 0L, CurrentPartEvidence.Freshness.FRESH);
            if (mapped.isPresent() && isEvidenceScopeCurrent(scope)) {
                EVIDENCE_TIMELINE.append(mapped.get(), scope);
            }
        }
    }

    /** Adds a pagelist candidate to the research timeline; this cannot authorize a seek. */
    public static String captureNativeHttpCandidate(
            EvidenceScope scope, CandidatePart candidate, long observedAtElapsedMs,
            long latencyMs, String lineageFingerprint) {
        if (scope == null || candidate == null || !isEvidenceScopeCurrent(scope)
                || !scope.getBvid().equals(candidate.getVideoKey().getBvid())) {
            return "NATIVE_HTTP：未追加（stale 或 BVID 不一致）";
        }
        CurrentPartEvidence evidence = CurrentPartEvidence.builder(
                        scope, EvidenceSource.NATIVE_HTTP, observedAtElapsedMs)
                .freshness(CurrentPartEvidence.Freshness.FRESH)
                .latencyMs(latencyMs)
                .lineageFingerprint(lineageFingerprint)
                .page(candidate.getPage())
                .totalParts(candidate.getTotalParts())
                .cid(candidate.getVideoKey().getCidString())
                .durationMs(candidate.getDurationMs())
                .labelFingerprint(candidate.getLabelFingerprint().orElse(null))
                .build();
        EvidenceTimeline.AppendResult result = EVIDENCE_TIMELINE.append(evidence, scope);
        if (result != EvidenceTimeline.AppendResult.ACCEPTED) {
            return "NATIVE_HTTP：未追加（stale scope）";
        }
        return "NATIVE_HTTP：已追加 page=" + candidate.getPage()
                + "/" + candidate.getTotalParts() + " → CID="
                + candidate.getVideoKey().getCidString()
                + "（research unique，不授权 CID/seek）";
    }

    /** Resolves a candidate map against this exact scope's timeline, for diagnostics only. */
    public static String resolveResearchPageMap(CandidatePageMap pageMap) {
        if (pageMap == null || pageMap.getState() != CandidatePageMap.State.SUCCESS
                || !isEvidenceScopeCurrent(pageMap.getScope())) {
            return "resolver：未运行（pagelist 非成功或 scope stale）";
        }
        CurrentPartResolver.Result result = CurrentPartResolver.resolve(
                pageMap.getScope(), pageMap.getCandidates(),
                EVIDENCE_TIMELINE.eventsFor(pageMap.getScope()), 0L);
        String candidate = result.getCandidates().size() == 1
                ? result.getCandidates().get(0).getVideoKey().getCidString() : "none";
        return "resolver=" + result.getState() + "；survivors="
                + result.getCandidates().size() + "；candidateCid=" + candidate
                + "；steps=" + result.getSteps().size()
                + "（research only，不授权 CidEvidence/seek）";
    }

    private static EvidenceScope evidenceScope(SessionAdapter.SessionGeneration generation) {
        return new EvidenceScope(
                "epoch-" + generation.connectionEpoch + "-token-" + generation.tokenFingerprint,
                generation.identity.getBvid(), generation.generationNo);
    }

    public static int experimentRuleCount() {
        return experimentRules.size();
    }

    public static String nextExperimentSegmentId() {
        return experimentTimer == null ? null : experimentTimer.getSegmentId();
    }

    public static boolean isExperimentUndoAvailable() {
        return BuildConfig.DEBUG && experimentArmed && experimentEngine != null
                && experimentEngine.isUndoAvailable(currentExperimentSnapshot());
    }

    /** UI bridge for the pure undo state machine; completion still requires a playback readback. */
    public static void requestExperimentUndo() {
        if (!BuildConfig.DEBUG) return;
        if (!experimentArmed || experimentEngine == null) {
            experimentReport = "Undo：没有当前已确认的受控跳过";
            return;
        }
        SkipEngine.PlaybackSnapshot snapshot = currentExperimentSnapshot();
        if (snapshot == null) {
            experimentReport = "Undo：当前会话/身份已失效，拒绝";
            return;
        }
        applyDirective(experimentEngine.requestUndo(snapshot));
    }

    public static long estimatedPosition(PlaybackState state) {
        long position = Math.max(0L, state.getPosition());
        if (state.getState() != PlaybackState.STATE_PLAYING
                || state.getLastPositionUpdateTime() <= 0L
                || !Float.isFinite(state.getPlaybackSpeed())
                || state.getPlaybackSpeed() <= 0F) {
            return position;
        }
        long elapsed = Math.max(0L, SystemClock.elapsedRealtime() - state.getLastPositionUpdateTime());
        long advance = Math.round(elapsed * (double) state.getPlaybackSpeed());
        return advance > Long.MAX_VALUE - position ? Long.MAX_VALUE : position + advance;
    }

    private static void completeArm() {
        SessionAdapter.requestRescan();
        SessionAdapter.CandidateSelection selection = SessionAdapter.candidateSelection();
        MediaController controller = selection.state == SessionAdapter.CandidateState.READY
                ? selection.controller : null;
        if (controller != null && isExperimentEligible(controller)) {
            PlaybackState state = controller.getPlaybackState();
            if (state == null) {
                experimentReport = "P4 实验：准备后 PlaybackState 为空";
                return;
            }
            long position = estimatedPosition(state);
            if (position >= EXPERIMENT_END_MS) {
                experimentReport = "P4 实验：准备跳转未生效，拒绝武装";
                return;
            }
            Optional<SessionAdapter.Diagnostics> diagnostics =
                    SessionAdapter.diagnostics(controller.getSessionToken());
            if (!diagnostics.isPresent()) {
                experimentReport = "P4 实验：准备后缺少会话 generation，拒绝武装";
                PRODUCT_AUDIT.recordRejected(SystemClock.elapsedRealtime(), EXPERIMENT_BVID,
                        "missing-session-generation", "fixture", "p4-contract-v1");
                updateAuditReport();
                return;
            }
            SessionGeneration queryGeneration = new SessionGeneration(
                    sessionScope(controller.getSessionToken()), EXPERIMENT_VIDEO,
                    diagnostics.get().generationNo);
            SegmentRepository.Result fixtureResult = EXPERIMENT_REPOSITORY.loadAutomaticSkips(
                    new SegmentRequest(queryGeneration), CidEvidence.exactSinglePage(EXPERIMENT_VIDEO),
                    EXPERIMENT_DURATION_MS, CategoryPolicy.defaults(), value -> value.equals(queryGeneration));
            if (fixtureResult.getStatus() != SegmentRepository.Status.PUBLISHED
                    || fixtureResult.getRules().size() != 1) {
                experimentReport = "P4 实验：fixture 规则未能经数据链发布（"
                        + fixtureResult.getStatus() + " / " + fixtureResult.getReason() + "）";
                PRODUCT_AUDIT.recordRejected(SystemClock.elapsedRealtime(), EXPERIMENT_BVID,
                        fixtureResult.getStatus() + ":" + fixtureResult.getReason(),
                        "fixture", "p4-contract-v1");
                updateAuditReport();
                return;
            }
            experimentEngine = new SkipEngine(EXPERIMENT_POLICY,
                    new UndoStateMachine(new UndoStateMachine.Policy(8_000L, 1_500L, 2_000L)),
                    PRODUCT_AUDIT);
            experimentRules = fixtureResult.getRules();
            experimentController = controller;
            experimentToken = controller.getSessionToken();
            experimentArmed = true;
            observerRegistered = true;
            SessionAdapter.addObserver(EXPERIMENT_OBSERVER);
            experimentReport = "P4 实验：已武装；准备后位置 " + position
                    + " ms；由下一个入口 timer 等待 " + EXPERIMENT_START_MS + " ms";
            driveEngine("armed");
            return;
        }
        if (SystemClock.elapsedRealtime() < experimentPrepareDeadlineMs) {
            experimentReport = "P4 实验：准备 seek 后等待稳定性重新握手";
            EXPERIMENT_HANDLER.postDelayed(COMPLETE_ARM, 500L);
            return;
        }
        experimentReport = "P4 实验：准备后 5 秒内未恢复唯一稳定身份，拒绝武装";
    }

    private static void onAdapterStateChanged() {
        if (experimentArmed) {
            driveEngine("session-callback");
        }
    }

    private static void fireEngineTimer() {
        SkipEngine.TimerPlan timer = experimentTimer;
        experimentTimer = null;
        if (!experimentArmed || experimentEngine == null || timer == null) {
            return;
        }
        SkipEngine.PlaybackSnapshot snapshot = currentExperimentSnapshot();
        if (snapshot == null) {
            stopExperiment("P4 实验：timer 到期时目标会话已失效，已停止");
            return;
        }
        applyDirective(experimentEngine.onTimer(snapshot, experimentRules, timer.getId()));
    }

    private static void driveEngine(String source) {
        if (!experimentArmed || experimentEngine == null) {
            return;
        }
        SkipEngine.PlaybackSnapshot snapshot = currentExperimentSnapshot();
        if (snapshot == null) {
            stopExperiment("P4 实验：" + source + " 时目标会话已失效，已停止");
            return;
        }
        applyDirective(experimentEngine.onPlayback(snapshot, experimentRules));
    }

    private static SkipEngine.PlaybackSnapshot currentExperimentSnapshot() {
        if (experimentController == null || experimentToken == null
                || !experimentToken.equals(experimentController.getSessionToken())
                || !isExperimentEligible(experimentController)) {
            return null;
        }
        Optional<SessionAdapter.Diagnostics> diagnostics =
                SessionAdapter.diagnostics(experimentToken);
        if (!diagnostics.isPresent()) {
            return null;
        }
        PlaybackState state = experimentController.getPlaybackState();
        if (state == null) {
            return null;
        }
        long position = estimatedPosition(state);
        if (position > EXPERIMENT_DURATION_MS) {
            return null;
        }
        boolean playing = state.getState() == PlaybackState.STATE_PLAYING;
        boolean seekable = (state.getActions() & PlaybackState.ACTION_SEEK_TO) != 0L;
        String scope = sessionScope(experimentToken);
        return new SkipEngine.PlaybackSnapshot(
                scope,
                EXPERIMENT_VIDEO,
                diagnostics.get().generationNo,
                position,
                EXPERIMENT_DURATION_MS,
                state.getPlaybackSpeed(),
                playing,
                seekable,
                SystemClock.elapsedRealtime(), state.getPosition(),
                state.getLastPositionUpdateTime() > 0L ? state.getLastPositionUpdateTime() : -1L);
    }

    private static void applyDirective(SkipEngine.Directive directive) {
        if (!directive.shouldRetainCurrentTimer()) {
            EXPERIMENT_HANDLER.removeCallbacks(ENGINE_TIMER);
            experimentTimer = null;
        }
        if (directive.getTimerPlan() != null) {
            experimentTimer = directive.getTimerPlan();
            long delay = Math.max(0L,
                    directive.getTimerPlan().getWakeAtElapsedMs() - SystemClock.elapsedRealtime());
            EXPERIMENT_HANDLER.postDelayed(ENGINE_TIMER, delay);
        }
        switch (directive.getOutcome()) {
            case SEEK:
                if (experimentController == null || !isExperimentEligible(experimentController)) {
                    stopExperiment("P4 实验：触发前目标会话失效，已停止");
                    return;
                }
                try {
                    experimentController.getTransportControls().seekTo(directive.getSeekTargetMs());
                    experimentReport = "P4 实验：入口命中；请求 " + directive.getSeekTargetMs()
                            + " ms，等待回读确认";
                } catch (RuntimeException exception) {
                    applyDirective(experimentEngine.onSeekDispatchFailed(
                            directive.getAttemptId(), "seek-dispatch-"
                                    + exception.getClass().getSimpleName()));
                }
                return;
            case CONFIRMED:
                experimentReport = "P4 实验：跳过已回读确认（位置越过 "
                        + EXPERIMENT_END_MS + " ms）；短窗口内可 Undo";
                updateAuditReport();
                return;
            case FAILED:
                stopExperiment("P4 实验：seek 未通过回读确认，已停止本片段");
                return;
            case UNDO_SEEK:
                if (experimentController == null || !isExperimentEligible(experimentController)) {
                    stopExperiment("Undo：发送前目标会话失效，已停止");
                    return;
                }
                try {
                    experimentController.getTransportControls().seekTo(directive.getSeekTargetMs());
                    experimentReport = "Undo：请求回到 " + directive.getSeekTargetMs() + " ms，等待回读确认";
                } catch (RuntimeException exception) {
                    applyDirective(experimentEngine.onUndoDispatchFailed("undo-seek-dispatch-"
                            + exception.getClass().getSimpleName()));
                }
                return;
            case UNDO_CONFIRMED:
                experimentReport = "Undo：已回读确认；本播放轮次不再自动跳过该片段";
                updateAuditReport();
                return;
            case UNDO_FAILED:
                experimentReport = "Undo：seek 未通过回读确认，保持 fail-closed";
                updateAuditReport();
                return;
            case MISSED:
                stopExperiment("P4 实验：timer 未在入口保护窗内到达，拒绝跳过");
                return;
            case IGNORED:
                experimentReport = "P4 实验：过期 timer 或会话变更，已忽略";
                return;
            case IDLE:
            default:
                if (directive.getTimerPlan() != null) {
                    experimentReport = "P4 实验：已武装；等待下一入口 timer";
                } else if ("not-playing".equals(directive.getReason())) {
                    experimentReport = "P4 实验：播放已暂停；入口 timer 已取消，恢复播放后重排";
                } else if ("not-seekable".equals(directive.getReason())) {
                    experimentReport = "P4 实验：当前不可 seek；入口 timer 已取消，能力恢复后重排";
                } else if (directive.getReason().endsWith(":no-future-segment")) {
                    stopExperiment("P4 实验：没有可安全触发的后续片段，已停止等待");
                    return;
                }
        }
    }

    private static void stopExperiment(String report) {
        clearExperimentState();
        experimentReport = report;
    }

    private static void clearExperimentState() {
        experimentArmed = false;
        EXPERIMENT_HANDLER.removeCallbacks(COMPLETE_ARM);
        EXPERIMENT_HANDLER.removeCallbacks(ENGINE_TIMER);
        if (observerRegistered) {
            SessionAdapter.removeObserver(EXPERIMENT_OBSERVER);
        }
        observerRegistered = false;
        experimentEngine = null;
        experimentRules = Collections.emptyList();
        experimentController = null;
        experimentToken = null;
        experimentTimer = null;
    }

    private static String sessionScope(MediaSession.Token token) {
        return Integer.toHexString(token.hashCode()) + ":" + EXPERIMENT_BVID;
    }

    private static void updateAuditReport() {
        if (PRODUCT_AUDIT.entries().isEmpty()) {
            return;
        }
        List<LocalAuditLog.Entry> entries = PRODUCT_AUDIT.entries();
        LocalAuditLog.Entry last = entries.get(entries.size() - 1);
        String segment = last.getSegmentId().orElse("<none>");
        experimentAuditReport = "本地审计（内存、上限 32）：" + last.getStatus()
                + "；segment=" + segment
                + "；reason=" + last.getFailClosedReason().orElse("<none>")
                + "；source=" + last.getSource().orElse("<none>");
    }

    static Optional<DisplayedPartMarker> displayedPartMarker(MediaMetadata metadata) {
        return displayedPartObservation(metadata).map(observation -> observation.marker);
    }

    static Optional<PartMarkerObservation> displayedPartObservation(MediaMetadata metadata) {
        List<CharSequence> values = new ArrayList<>();
        List<SessionTransitionEvent.MarkerSource> sources = new ArrayList<>();
        values.add(metadata.getString(MediaMetadata.METADATA_KEY_TITLE));
        sources.add(SessionTransitionEvent.MarkerSource.METADATA_TITLE);
        values.add(metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE));
        sources.add(SessionTransitionEvent.MarkerSource.METADATA_DISPLAY_TITLE);
        values.add(metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE));
        sources.add(SessionTransitionEvent.MarkerSource.METADATA_DISPLAY_SUBTITLE);
        values.add(metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION));
        sources.add(SessionTransitionEvent.MarkerSource.METADATA_DISPLAY_DESCRIPTION);
        MediaDescription description = metadata.getDescription();
        if (description != null) {
            values.add(description.getTitle());
            sources.add(SessionTransitionEvent.MarkerSource.DESCRIPTION_TITLE_DERIVED);
            values.add(description.getSubtitle());
            sources.add(SessionTransitionEvent.MarkerSource.DESCRIPTION_SUBTITLE_DERIVED);
            values.add(description.getDescription());
            sources.add(SessionTransitionEvent.MarkerSource.DESCRIPTION_TEXT_DERIVED);
        }
        for (int index = 0; index < values.size(); index++) {
            CharSequence value = values.get(index);
            Optional<DisplayedPartMarker> marker = DisplayedPartMarker.parse(value);
            if (marker.isPresent()) {
                return Optional.of(new PartMarkerObservation(marker.get(), sources.get(index)));
            }
        }
        return Optional.empty();
    }

    static final class PartMarkerObservation {
        final DisplayedPartMarker marker;
        final SessionTransitionEvent.MarkerSource source;

        PartMarkerObservation(DisplayedPartMarker marker,
                SessionTransitionEvent.MarkerSource source) {
            this.marker = marker;
            this.source = source;
        }
    }

    /** The public description title is the observed per-part label on Bilibili 9.0.0. */
    private static CharSequence partLabelCandidate(MediaMetadata metadata) {
        MediaDescription description = metadata.getDescription();
        if (description != null && !TextUtils.isEmpty(description.getTitle())) {
            return description.getTitle();
        }
        String displayTitle = metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
        if (!TextUtils.isEmpty(displayTitle)) return displayTitle;
        String title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE);
        return TextUtils.isEmpty(title) ? null : title;
    }

    private static String publicSurfaceFingerprint(MediaController controller) {
        StringBuilder material = new StringBuilder();
        MediaMetadata metadata = controller.getMetadata();
        if (metadata != null) {
            List<String> keys = new ArrayList<>(metadata.keySet());
            Collections.sort(keys);
            material.append("metadataKeys=").append(keys);
            appendFingerprintText(material, "title",
                    metadata.getString(MediaMetadata.METADATA_KEY_TITLE));
            appendFingerprintText(material, "displayTitle",
                    metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE));
            appendFingerprintText(material, "displaySubtitle",
                    metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE));
            appendFingerprintText(material, "displayDescription",
                    metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_DESCRIPTION));
            MediaDescription description = metadata.getDescription();
            if (description != null) {
                appendFingerprintText(material, "descriptionTitle", description.getTitle());
                appendFingerprintText(material, "descriptionSubtitle", description.getSubtitle());
                appendFingerprintText(material, "descriptionText", description.getDescription());
            }
        }
        if (controller.getExtras() != null) {
            List<String> keys = new ArrayList<>(controller.getExtras().keySet());
            Collections.sort(keys);
            material.append(";controllerExtraKeys=").append(keys);
        }
        PlaybackState state = controller.getPlaybackState();
        if (state != null) {
            material.append(";state=").append(state.getState())
                    .append(";actions=").append(state.getActions())
                    .append(";activeQueueId=").append(state.getActiveQueueItemId());
            if (state.getExtras() != null) {
                List<String> keys = new ArrayList<>(state.getExtras().keySet());
                Collections.sort(keys);
                material.append(";playbackExtraKeys=").append(keys);
            }
            List<PlaybackState.CustomAction> actions = state.getCustomActions();
            if (actions != null) {
                for (PlaybackState.CustomAction action : actions) {
                    material.append(";customAction=")
                            .append(action == null ? "<null>" : action.getAction());
                }
            }
        }
        List<MediaSession.QueueItem> queue = controller.getQueue();
        material.append(";queueSize=").append(queue == null ? -1 : queue.size());
        return material.toString();
    }

    private static void appendFingerprintText(
            StringBuilder material, String field, CharSequence value) {
        if (!TextUtils.isEmpty(value)) {
            material.append(';').append(field).append("Digest=")
                    .append(EvidenceFingerprint.sha256(value.toString()));
        }
    }
}
