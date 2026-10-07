package org.adskip.probe;

import android.app.Activity;
import android.app.AlertDialog;
import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.net.Uri;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.text.TextUtils;
import android.text.util.Linkify;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.util.Log;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import org.adskip.core.BiliIdentity;
import org.adskip.core.BackgroundCompatibilityHealth;
import org.adskip.core.CompatibilityReport;
import org.adskip.core.OemCompatibilityGuide;
import org.adskip.core.OemCompatibilityRegistry;
import org.adskip.core.BsbEndpointPolicy;
import org.adskip.core.BiliIdentityResolver;
import org.adskip.core.AnalysisRequestRepository;
import org.adskip.core.AnalysisRequestLedger;
import org.adskip.core.AnalysisRequestResolver;
import org.adskip.core.AnalysisRequestResult;
import org.adskip.core.AnalysisRequestTarget;
import org.adskip.core.AnalysisRequestStateStore;
import org.adskip.core.CandidatePageMap;
import org.adskip.core.CandidatePart;
import org.adskip.core.CategoryPolicy;
import org.adskip.core.EvidenceScope;
import org.adskip.core.FixtureAnalysisRequestDataSource;
import org.adskip.core.HomeUiState;
import org.adskip.core.LocalAuditLog;
import org.adskip.core.PageCatalog;
import org.adskip.core.PageListMapper;
import org.adskip.core.ShareLinkEvidenceParser;
import org.adskip.core.ShareLinkObservation;
import org.adskip.core.UiThemeChoice;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Three-level product UI. All playback authorization remains in the existing coordinator. */
public final class MainActivity extends Activity {
    private static final String STATE_SCREEN = "screen";
    private static final String STATE_ACCESS_ATTEMPTED = "access_attempted";
    private static final BackgroundCompatibilityHealth COMPATIBILITY_HEALTH =
            new BackgroundCompatibilityHealth();
    private static final int REQUEST_UNDO_NOTIFICATION_PERMISSION = 410;
    private static final AnalysisRequestStateStore REQUEST_ANALYSIS_STORE =
            new AnalysisRequestStateStore();
    private static final AnalysisRequestLedger REQUEST_LEDGER =
            new AnalysisRequestLedger(32, 10L * 60L * 1_000L);
    private enum Screen { HOME, SETTINGS, DIAGNOSTICS }
    private final Handler handler = new Handler(Looper.getMainLooper());
    private UiAppearance appearance;
    private Screen screen = Screen.HOME;
    private ScrollView homePage;
    private ScrollView settingsPage;
    private ScrollView diagnosticsPage;
    private LinearLayout homeStatusCard;
    private TextView homeStatusHeadline;
    private TextView homeStatusDetail;
    private TextView homeVideo;
    private TextView homeRecent;
    private TextView homeCategorySummary;
    private Button homeRecoveryButton;
    private Switch homeMasterSwitch;
    private Switch settingsMasterSwitch;
    private TextView compatibilityStatus;
    private Button compatibilitySettingsButton;
    private AppUpdater appUpdater;
    private Button restrictedSettingsButton;
    private TextView report;
    private Button seekButton;
    private Button armButton;
    private Button undoButton;
    private Switch undoNotificationSwitch;
    private Switch undoHeadsUpSwitch;
    private TextView undoNotificationStatus;
    private Button requestAnalysisButton;
    private LinearLayout diagnosticsPanel;
    private boolean accessAttempted;
    private boolean automaticSkipEnabled;
    private CategoryPolicy categoryPolicy = CategoryPolicy.defaults();
    private String serverStatus = "";
    private String seekResult = "P2 seek：尚未执行";
    private String surfaceCapture = "P5A 公开会话表面采集：尚未手动执行";
    private String transitionCapture = "P5T callback 时序：等待真实 callback";
    private String notificationCapture = "P5N notification shape：等待 research 事件";
    private String accessibilityCapture = "P5U Accessibility：尚未启用/采集";
    private String shareCapture = "分享链接证据：尚未收到 ACTION_SEND";
    private String analysisCapture = "请求分析：尚未收到明确分享";
    private AnalysisRequestTarget requestAnalysisTarget;
    private ShareLinkObservation pendingShare;
    private long pendingShareDeadlineMs;
    private ShareLinkObservation pendingResearchPageObservation;
    private PageCatalog pendingAnalysisCatalog;
    private long pendingAnalysisEvidenceDeadlineMs;
    private long appliedRequestRevision;
    private final Runnable refreshTask = new Runnable() {
        @Override
        public void run() {
            refresh();
            handler.postDelayed(this, 1_000L);
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        appearance = UiAppearance.apply(this);
        super.onCreate(state);
        if (state != null) {
            try { screen = Screen.valueOf(state.getString(STATE_SCREEN, Screen.HOME.name())); }
            catch (IllegalArgumentException ignored) { screen = Screen.HOME; }
        }
        accessAttempted = state != null && state.getBoolean(STATE_ACCESS_ATTEMPTED, false);
        automaticSkipEnabled = ProductionSettings.enabled(this);
        categoryPolicy = ProductionSettings.categoryPolicy(this);
        setContentView(createContent(this));
        handleShareIntent(getIntent());
        if ("com.sis5595.adskip23".equals(BuildConfig.APPLICATION_ID)) appUpdater = new AppUpdater(this);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        outState.putString(STATE_SCREEN, screen.name());
        outState.putBoolean(STATE_ACCESS_ATTEMPTED, accessAttempted);
        super.onSaveInstanceState(outState);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleShareIntent(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        ProductionSkipCoordinator.start(this);
        applyLatestRequestAnalysis();
        requestListenerRescanOrRebind();
        handler.removeCallbacks(refreshTask);
        handler.post(refreshTask);
        if (appUpdater != null) appUpdater.resume();
    }

    @Override
    protected void onPause() {
        if (appUpdater != null) appUpdater.pause();
        handler.removeCallbacks(refreshTask);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (appUpdater != null) appUpdater.close();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (screen == Screen.DIAGNOSTICS) {
            showScreen(Screen.SETTINGS);
        } else if (screen == Screen.SETTINGS) {
            showScreen(Screen.HOME);
        } else {
            super.onBackPressed();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
            int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_UNDO_NOTIFICATION_PERMISSION) return;
        boolean granted = grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        ProductionSettings.setUndoNotificationEnabled(this, granted);
        ProductionSkipCoordinator.notificationSettingsChanged(this);
        if (undoNotificationSwitch != null) undoNotificationSwitch.setChecked(granted);
        refresh();
    }

    private View createContent(Context context) {
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(appearance.background);

        LinearLayout home = pageColumn(context);
        addTopBar(home, getString(R.string.app_name), false, true);
        homeStatusCard = card(home);
        homeStatusHeadline = text(homeStatusCard, "", 26, appearance.text, true);
        homeStatusDetail = text(homeStatusCard, "", 15, appearance.muted, false);
        homeRecoveryButton = new Button(context);
        homeRecoveryButton.setOnClickListener(view -> {
            if (!isNotificationAccessEnabled()) openNotificationAccessSettings();
            else showScreen(Screen.SETTINGS);
        });
        homeStatusCard.addView(homeRecoveryButton);

        LinearLayout autoCard = card(home);
        text(autoCard, "自动跳过", 18, appearance.text, true);
        homeMasterSwitch = new Switch(context);
        homeMasterSwitch.setText("自动跳过已启用类别");
        homeMasterSwitch.setEnabled(BuildConfig.PRODUCTION_READ_ONLY_ENABLED);
        homeMasterSwitch.setChecked(automaticSkipEnabled);
        homeMasterSwitch.setOnCheckedChangeListener((button, checked) -> setAutomaticSkip(checked));
        autoCard.addView(homeMasterSwitch);
        homeCategorySummary = text(autoCard, "", 14, appearance.muted, false);

        LinearLayout recentCard = card(home);
        text(recentCard, "最近一次", 18, appearance.text, true);
        homeRecent = text(recentCard, "", 15, appearance.muted, false);
        undoButton = new Button(context);
        undoButton.setText("撤销本次跳过");
        undoButton.setEnabled(false);
        undoButton.setOnClickListener(view -> {
            if (BuildConfig.PRODUCTION_READ_ONLY_ENABLED
                    && ProductionSkipCoordinator.undoAvailable()) {
                ProductionSkipCoordinator.requestUndo();
            } else if (BuildConfig.DEBUG && SessionProbe.isExperimentUndoAvailable()) {
                SessionProbe.requestExperimentUndo();
            }
            refresh();
        });
        recentCard.addView(undoButton);

        LinearLayout videoCard = card(home);
        text(videoCard, "当前视频", 18, appearance.text, true);
        homeVideo = text(videoCard, "", 15, appearance.muted, false);

        LinearLayout column = pageColumn(context);
        addTopBar(column, "设置", true, false);
        LinearLayout skipCard = card(column);
        text(skipCard, "跳过设置", 18, appearance.text, true);
        settingsMasterSwitch = new Switch(context);
        settingsMasterSwitch.setText("自动跳过");
        settingsMasterSwitch.setEnabled(BuildConfig.PRODUCTION_READ_ONLY_ENABLED);
        settingsMasterSwitch.setChecked(automaticSkipEnabled);
        settingsMasterSwitch.setOnCheckedChangeListener((button, checked) -> setAutomaticSkip(checked));
        skipCard.addView(settingsMasterSwitch);
        text(skipCard, "仅对可安全识别的单 P 视频生效", 13, appearance.muted, false);

        LinearLayout undoCard = card(column);
        text(undoCard, "撤销", 18, appearance.text, true);

        LinearLayout appearanceCard = card(column);
        text(appearanceCard, "外观", 18, appearance.text, true);
        Button themeButton = new Button(context);
        themeButton.setText(getString(R.string.ui_theme_current,
                themeLabel(UiAppearance.choice(this))));
        themeButton.setOnClickListener(view -> showThemeChoice());
        appearanceCard.addView(themeButton);
        text(appearanceCard, "默认跟随系统；选择后立即应用", 13, appearance.muted, false);

        LinearLayout compatibilityCard = card(column);
        text(compatibilityCard, "兼容性", 18, appearance.text, true);
        Button accessButton = new Button(context);
        accessButton.setText(R.string.notification_access);
        accessButton.setOnClickListener(view -> openNotificationAccessSettings());
        compatibilityCard.addView(accessButton);
        compatibilityStatus = text(compatibilityCard, "", 14, appearance.muted, false);
        compatibilitySettingsButton = new Button(context);
        compatibilitySettingsButton.setText("本机后台设置说明");
        compatibilitySettingsButton.setOnClickListener(view -> showCompatibilityGuide());
        compatibilityCard.addView(compatibilitySettingsButton);
        restrictedSettingsButton = new Button(context);
        restrictedSettingsButton.setText(R.string.restricted_settings_help);
        restrictedSettingsButton.setOnClickListener(view -> showRestrictedSettingsHelp());
        compatibilityCard.addView(restrictedSettingsButton);

        LinearLayout advancedCard = card(column);
        text(advancedCard, "高级", 18, appearance.text, true);

        diagnosticsPanel = pageColumn(context);
        addTopBar(diagnosticsPanel, "诊断", true, false);
        text(diagnosticsPanel, "连接、身份与数据服务的详细状态", 14,
                appearance.muted, false);
        LinearLayout diagnosticActions = card(diagnosticsPanel);
        Button refreshButton = new Button(context);
        refreshButton.setText(R.string.listener_rebind);
        refreshButton.setOnClickListener(view -> {
            requestListenerRescanOrRebind();
            ProductionSkipCoordinator.refreshRules(this);
            refresh();
        });
        diagnosticActions.addView(refreshButton);
        Button copyCompatibilityReport = new Button(context);
        copyCompatibilityReport.setText(R.string.compatibility_copy_report);
        copyCompatibilityReport.setOnClickListener(view -> copyCompatibilityReport());
        diagnosticActions.addView(copyCompatibilityReport);

        if (BuildConfig.PRODUCTION_READ_ONLY_ENABLED) {
            undoNotificationSwitch = new Switch(context);
            undoNotificationSwitch.setText(R.string.undo_notification_switch);
            undoNotificationSwitch.setChecked(ProductionSettings.undoNotificationEnabled(this));
            undoNotificationSwitch.setOnCheckedChangeListener((button, checked) -> {
                if (checked && !UndoNotificationController.permissionGranted(this)) {
                    undoNotificationSwitch.setChecked(false);
                    boolean openSettings = ProductionSettings.undoPermissionRequested(this)
                            && !shouldShowRequestPermissionRationale(
                                    Manifest.permission.POST_NOTIFICATIONS);
                    new AlertDialog.Builder(this)
                            .setTitle(R.string.undo_permission_title)
                            .setMessage(openSettings ? R.string.undo_permission_settings_explanation
                                    : R.string.undo_permission_explanation)
                            .setNegativeButton(android.R.string.cancel, null)
                            .setPositiveButton(openSettings ? R.string.undo_permission_settings
                                    : R.string.undo_permission_accept,
                                    (dialog, which) -> {
                                        if (openSettings) {
                                            openOwnNotificationSettings();
                                        } else if (Build.VERSION.SDK_INT >= 33) {
                                            ProductionSettings.markUndoPermissionRequested(this);
                                            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                                                    REQUEST_UNDO_NOTIFICATION_PERMISSION);
                                        }
                                    })
                            .show();
                    refresh();
                    return;
                }
                ProductionSettings.setUndoNotificationEnabled(this, checked);
                ProductionSkipCoordinator.notificationSettingsChanged(this);
                refresh();
            });
            undoCard.addView(undoNotificationSwitch);

            undoHeadsUpSwitch = new Switch(context);
            undoHeadsUpSwitch.setText(R.string.undo_heads_up_switch);
            undoHeadsUpSwitch.setChecked(ProductionSettings.undoHeadsUpEnabled(this));
            undoHeadsUpSwitch.setEnabled(ProductionSettings.undoNotificationEnabled(this));
            undoHeadsUpSwitch.setOnCheckedChangeListener((button, checked) -> {
                ProductionSettings.setUndoHeadsUpEnabled(this, checked);
                ProductionSkipCoordinator.notificationSettingsChanged(this);
                refresh();
            });
            undoCard.addView(undoHeadsUpSwitch);

            undoNotificationStatus = new TextView(context);
            undoNotificationStatus.setText(R.string.undo_permission_explanation);
            undoNotificationStatus.setPadding(0, dp(4), 0, dp(12));
            undoNotificationStatus.setTextColor(appearance.muted);
            undoNotificationStatus.setTextSize(13);
            undoCard.addView(undoNotificationStatus);
            Button undoHelp = new Button(context);
            undoHelp.setText("了解两种通知权限");
            undoHelp.setOnClickListener(view -> new AlertDialog.Builder(this)
                    .setTitle("撤销通知")
                    .setMessage(R.string.undo_permission_explanation)
                    .setPositiveButton(android.R.string.ok, null)
                    .show());
            undoCard.addView(undoHelp);
        }

        if (BuildConfig.PRODUCTION_READ_ONLY_ENABLED) {
            text(advancedCard, "数据服务", 15, appearance.text, true);
            TextView attribution = new TextView(context);
            attribution.setText(R.string.bsb_attribution);
            attribution.setPadding(0, dp(10), 0, dp(8));
            attribution.setOnClickListener(view -> startActivity(new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://github.com/hanydd/BilibiliSponsorBlock"))));
            attribution.setTextColor(appearance.primary);
            advancedCard.addView(attribution);

            EditText serverInput = new EditText(context);
            serverInput.setSingleLine(true);
            serverInput.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                    | android.text.InputType.TYPE_TEXT_VARIATION_URI);
            serverInput.setText(ProductionSettings.serverOrigin(this));
            serverInput.setHint(R.string.server_hint);
            advancedCard.addView(serverInput);
            Button saveServer = new Button(context);
            saveServer.setText(R.string.server_save);
            saveServer.setOnClickListener(view -> {
                try {
                    String origin = BsbEndpointPolicy.normalizeOrigin(
                            serverInput.getText().toString());
                    ProductionSettings.setServerOrigin(this, origin);
                    ProductionSkipCoordinator.settingsChanged(this);
                    serverStatus = "服务端已保存：" + origin;
                } catch (IllegalArgumentException exception) {
                    serverStatus = "服务端地址无效：只允许 HTTPS origin，不含路径/账号/参数";
                }
                refresh();
            });
            advancedCard.addView(saveServer);
        }
        Button diagnosticsButton = new Button(context);
        diagnosticsButton.setText("诊断与兼容报告");
        diagnosticsButton.setOnClickListener(view -> showScreen(Screen.DIAGNOSTICS));
        advancedCard.addView(diagnosticsButton);
        Button aboutButton = new Button(context);
        aboutButton.setText(R.string.ui_about_button);
        aboutButton.setOnClickListener(view -> showAbout());
        advancedCard.addView(aboutButton);

        Button categoryButton = new Button(context);
        categoryButton.setText(R.string.ui_category_expand);
        skipCard.addView(categoryButton);
        LinearLayout categoryOptions = new LinearLayout(context);
        categoryOptions.setOrientation(LinearLayout.VERTICAL);
        categoryOptions.setVisibility(View.GONE);
        skipCard.addView(categoryOptions);
        categoryButton.setOnClickListener(view -> {
            boolean open = categoryOptions.getVisibility() != View.VISIBLE;
            categoryOptions.setVisibility(open ? View.VISIBLE : View.GONE);
            categoryButton.setText(open ? R.string.ui_category_collapse
                    : R.string.ui_category_expand);
        });
        addCategoryToggle(categoryOptions, context, CategoryPolicy.Category.SPONSOR, "赞助内容", true);
        addCategoryToggle(categoryOptions, context, CategoryPolicy.Category.SELFPROMO, "自我推广", false);
        addCategoryToggle(categoryOptions, context, CategoryPolicy.Category.INTERACTION, "互动提醒", false);
        addCategoryToggle(categoryOptions, context, CategoryPolicy.Category.INTRO, "片头", false);
        addCategoryToggle(categoryOptions, context, CategoryPolicy.Category.OUTRO, "片尾", false);
        addCategoryToggle(categoryOptions, context, CategoryPolicy.Category.PREVIEW, "预告", false);
        addCategoryToggle(categoryOptions, context, CategoryPolicy.Category.PADDING, "填充内容", false);
        addCategoryToggle(categoryOptions, context, CategoryPolicy.Category.FILLER, "可跳过内容（默认关闭）", false);
        addCategoryToggle(categoryOptions, context, CategoryPolicy.Category.MUSIC_OFFTOPIC, "无关音乐", false);

        requestAnalysisButton = new Button(context);
        requestAnalysisButton.setText(R.string.request_analysis);
        requestAnalysisButton.setEnabled(false);
        requestAnalysisButton.setOnClickListener(view -> {
            AnalysisRequestTarget target = requestAnalysisTarget;
            if (target == null) return;
            long now = SystemClock.elapsedRealtime();
            AnalysisRequestLedger.Decision decision = REQUEST_LEDGER.assess(
                    target.getVideoKey(), now);
            if (decision == AnalysisRequestLedger.Decision.DUPLICATE_RECENT) {
                analysisCapture = "请求分析：本地去重（近期已处理；未再次 submit）；BVID="
                        + target.getVideoKey().getBvid() + "；CID="
                        + target.getVideoKey().getCidString();
                refresh();
                return;
            }
            AnalysisRequestRepository repository = new AnalysisRequestRepository(
                    new FixtureAnalysisRequestDataSource(AnalysisRequestResult.State.RECEIVED),
                    "adskip-android", BuildConfig.VERSION_NAME);
            AnalysisRequestResult result = repository.submit(target);
            AnalysisRequestLedger.Outcome outcome =
                    result.getState() == AnalysisRequestResult.State.RECEIVED
                            ? AnalysisRequestLedger.Outcome.RECEIVED
                            : result.getState() == AnalysisRequestResult.State.ALREADY_AVAILABLE
                            ? AnalysisRequestLedger.Outcome.ALREADY_AVAILABLE
                            : AnalysisRequestLedger.Outcome.FAILED;
            REQUEST_LEDGER.record(target.getVideoKey(), now, outcome);
            analysisCapture = "请求分析：" + result.getState()
                    + "（local fixture；" + decision + "；未连接 production API）；BVID="
                    + target.getVideoKey().getBvid() + "；CID="
                    + target.getVideoKey().getCidString();
            refresh();
        });
        if (BuildConfig.DEBUG && !BuildConfig.PRODUCTION_READ_ONLY_ENABLED)
            diagnosticsPanel.addView(requestAnalysisButton);

        Button clearRequestButton = new Button(context);
        clearRequestButton.setText(R.string.clear_request_state);
        clearRequestButton.setOnClickListener(view -> {
            REQUEST_LEDGER.clearAll();
            REQUEST_ANALYSIS_STORE.clear();
            appliedRequestRevision = 0L;
            analysisCapture = "请求分析：本地状态已清除；分享解析目标仍可重新确认";
            refresh();
        });
        if (BuildConfig.DEBUG && !BuildConfig.PRODUCTION_READ_ONLY_ENABLED)
            diagnosticsPanel.addView(clearRequestButton);

        Button captureButton = new Button(context);
        captureButton.setText(R.string.diagnostics_surface);
        captureButton.setOnClickListener(view -> {
            surfaceCapture = PublicSessionSurfaceCapture.capture(SessionProbe.activeSessions(this))
                    + "\n" + SessionProbe.captureCurrentEvidence();
            refresh();
        });
        if (BuildConfig.DEBUG && !BuildConfig.PRODUCTION_READ_ONLY_ENABLED)
            diagnosticsPanel.addView(captureButton);

        Button transitionButton = new Button(context);
        transitionButton.setText(R.string.diagnostics_transition);
        transitionButton.setOnClickListener(view -> {
            transitionCapture = SessionTransitionReport.render(SessionAdapter.transitionEvents());
            refresh();
        });
        if (BuildConfig.DEBUG && !BuildConfig.PRODUCTION_READ_ONLY_ENABLED)
            diagnosticsPanel.addView(transitionButton);

        Button clearTransitionButton = new Button(context);
        clearTransitionButton.setText(R.string.diagnostics_transition_clear);
        clearTransitionButton.setOnClickListener(view -> {
            SessionAdapter.clearTransitionEvents();
            transitionCapture = "P5T callback 时序：已清除；等待新 callback";
            refresh();
        });
        if (BuildConfig.DEBUG && !BuildConfig.PRODUCTION_READ_ONLY_ENABLED)
            diagnosticsPanel.addView(clearTransitionButton);

        if (BuildConfig.RESEARCH_NETWORK_ENABLED) {
            Switch enhancedPartSwitch = new Switch(context);
            enhancedPartSwitch.setText(R.string.research_enhanced_part);
            enhancedPartSwitch.setChecked(ResearchAccessibilityBridge.isModeEnabled(context));
            enhancedPartSwitch.setOnCheckedChangeListener((button, checked) -> {
                ResearchAccessibilityBridge.setModeEnabled(this, checked);
                refresh();
            });
            if (BuildConfig.DEBUG && !BuildConfig.PRODUCTION_READ_ONLY_ENABLED)
                diagnosticsPanel.addView(enhancedPartSwitch);

            Button notificationButton = new Button(context);
            notificationButton.setText(R.string.diagnostics_notification);
            notificationButton.setOnClickListener(view -> {
                notificationCapture = NotificationSurfaceReport.render(
                        NotificationSurfaceLog.snapshot());
                refresh();
            });
            if (BuildConfig.DEBUG && !BuildConfig.PRODUCTION_READ_ONLY_ENABLED)
                diagnosticsPanel.addView(notificationButton);

            Button clearNotificationButton = new Button(context);
            clearNotificationButton.setText(R.string.diagnostics_notification_clear);
            clearNotificationButton.setOnClickListener(view -> {
                NotificationSurfaceLog.clear();
                notificationCapture = "P5N notification shape：已清除；等待新事件";
                refresh();
            });
            if (BuildConfig.DEBUG && !BuildConfig.PRODUCTION_READ_ONLY_ENABLED)
                diagnosticsPanel.addView(clearNotificationButton);

            Button accessibilitySettingsButton = new Button(context);
            accessibilitySettingsButton.setText(R.string.diagnostics_accessibility_settings);
            accessibilitySettingsButton.setOnClickListener(view ->
                    startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
            if (BuildConfig.DEBUG && !BuildConfig.PRODUCTION_READ_ONLY_ENABLED)
                diagnosticsPanel.addView(accessibilitySettingsButton);

            Button accessibilityButton = new Button(context);
            accessibilityButton.setText(R.string.diagnostics_accessibility);
            accessibilityButton.setOnClickListener(view -> {
                accessibilityCapture = ResearchAccessibilityBridge.render();
                refresh();
            });
            if (BuildConfig.DEBUG && !BuildConfig.PRODUCTION_READ_ONLY_ENABLED)
                diagnosticsPanel.addView(accessibilityButton);

            Button clearAccessibilityButton = new Button(context);
            clearAccessibilityButton.setText(R.string.diagnostics_accessibility_clear);
            clearAccessibilityButton.setOnClickListener(view -> {
                ResearchAccessibilityBridge.clear();
                accessibilityCapture = "P5U Accessibility：已清除；等待 B站 selected-state 事件";
                refresh();
            });
            if (BuildConfig.DEBUG && !BuildConfig.PRODUCTION_READ_ONLY_ENABLED)
                diagnosticsPanel.addView(clearAccessibilityButton);
        }

        if (BuildConfig.RESEARCH_NETWORK_ENABLED) {
            Button webViewButton = new Button(context);
            webViewButton.setText(R.string.diagnostics_webview);
            webViewButton.setOnClickListener(view -> {
                if (!ResearchShareBridge.startWebViewMatrix(this)) {
                    shareCapture = "P5D WebView：research 组件启动失败";
                    refresh();
                }
            });
            if (BuildConfig.DEBUG && !BuildConfig.PRODUCTION_READ_ONLY_ENABLED)
                diagnosticsPanel.addView(webViewButton);
        }

        seekButton = new Button(context);
        seekButton.setText(R.string.diagnostics_seek);
        seekButton.setEnabled(false);
        seekButton.setOnClickListener(view -> seekForwardTenSeconds());
        if (BuildConfig.DEBUG && !BuildConfig.PRODUCTION_READ_ONLY_ENABLED)
            diagnosticsPanel.addView(seekButton);

        armButton = new Button(context);
        armButton.setText(R.string.diagnostics_arm);
        armButton.setEnabled(false);
        armButton.setOnClickListener(view -> {
            if (!BuildConfig.DEBUG) return;
            SessionProbe.armExperiment(this);
            refresh();
        });
        if (BuildConfig.DEBUG && !BuildConfig.PRODUCTION_READ_ONLY_ENABLED)
            diagnosticsPanel.addView(armButton);

        Button cancelButton = new Button(context);
        cancelButton.setText(R.string.diagnostics_cancel);
        cancelButton.setOnClickListener(view -> {
            SessionProbe.cancelExperiment();
            refresh();
        });
        if (BuildConfig.DEBUG && !BuildConfig.PRODUCTION_READ_ONLY_ENABLED)
            diagnosticsPanel.addView(cancelButton);

        report = new TextView(context);
        report.setTextSize(14);
        report.setTextColor(appearance.text);
        report.setTextIsSelectable(true);
        report.setGravity(Gravity.START);
        report.setPadding(0, dp(12), 0, 0);
        diagnosticsPanel.addView(report);

        homePage = wrap(home);
        settingsPage = wrap(column);
        diagnosticsPage = wrap(diagnosticsPanel);
        root.addView(homePage);
        root.addView(settingsPage);
        root.addView(diagnosticsPage);
        styleButtons(root);
        stylePrimaryButton(homeRecoveryButton);
        showScreen(screen);
        return root;
    }

    private LinearLayout pageColumn(Context context) {
        LinearLayout page = new LinearLayout(context);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(18), dp(12), dp(18), dp(24));
        return page;
    }

    private ScrollView wrap(LinearLayout content) {
        ScrollView page = new ScrollView(this);
        page.setFillViewport(true);
        page.setBackgroundColor(appearance.background);
        page.addView(content);
        page.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        return page;
    }

    private void addTopBar(LinearLayout page, String title, boolean back, boolean settings) {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(0, 0, 0, dp(18));
        if (back) {
            Button backButton = new Button(this);
            backButton.setText("‹ 返回");
            backButton.setContentDescription("返回上一级");
            backButton.setOnClickListener(view -> onBackPressed());
            LinearLayout.LayoutParams backLayout = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            backLayout.rightMargin = dp(12);
            bar.addView(backButton, backLayout);
        }
        TextView label = new TextView(this);
        label.setText(title);
        label.setTextSize(23);
        label.setTextColor(appearance.text);
        label.setTypeface(null, android.graphics.Typeface.BOLD);
        bar.addView(label, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        if (settings) {
            ImageButton button = new ImageButton(this);
            button.setImageResource(android.R.drawable.ic_menu_preferences);
            button.setColorFilter(appearance.primary);
            button.setBackgroundColor(android.graphics.Color.TRANSPARENT);
            button.setContentDescription("设置");
            button.setOnClickListener(view -> showScreen(Screen.SETTINGS));
            bar.addView(button, new LinearLayout.LayoutParams(dp(48), dp(48)));
        }
        page.addView(bar);
    }

    private LinearLayout card(LinearLayout page) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(16), dp(18), dp(16));
        card.setBackground(rounded(appearance.surface, appearance.outline));
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        layout.bottomMargin = dp(12);
        page.addView(card, layout);
        return card;
    }

    private GradientDrawable rounded(int fill, int outline) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(18));
        drawable.setStroke(dp(1), outline);
        return drawable;
    }

    private void styleButtons(View view) {
        if (view instanceof Button && !(view instanceof CompoundButton)) {
            Button button = (Button) view;
            button.setAllCaps(false);
            button.setTextColor(appearance.primary);
            button.setMinHeight(dp(48));
            button.setPadding(dp(14), dp(8), dp(14), dp(8));
            button.setElevation(0);
            button.setStateListAnimator(null);
            button.setBackground(new RippleDrawable(
                    ColorStateList.valueOf(appearance.outline),
                    rounded(appearance.primaryContainer, appearance.primaryContainer),
                    rounded(android.graphics.Color.WHITE, android.graphics.Color.WHITE)));
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                styleButtons(group.getChildAt(index));
            }
        }
    }

    private void stylePrimaryButton(Button button) {
        int onPrimary = appearance.dark ? 0xFF152B48 : android.graphics.Color.WHITE;
        button.setTextColor(onPrimary);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(appearance.outline),
                rounded(appearance.primary, appearance.primary),
                rounded(android.graphics.Color.WHITE, android.graphics.Color.WHITE)));
    }

    private TextView text(LinearLayout parent, String value, int size, int color,
            boolean strong) {
        TextView label = new TextView(this);
        label.setText(value);
        label.setTextSize(size);
        label.setTextColor(color);
        label.setPadding(0, dp(4), 0, dp(4));
        if (strong) label.setTypeface(null, android.graphics.Typeface.BOLD);
        parent.addView(label);
        return label;
    }

    private void showScreen(Screen target) {
        screen = target;
        if (homePage == null) return;
        homePage.setVisibility(target == Screen.HOME ? View.VISIBLE : View.GONE);
        settingsPage.setVisibility(target == Screen.SETTINGS ? View.VISIBLE : View.GONE);
        diagnosticsPage.setVisibility(target == Screen.DIAGNOSTICS ? View.VISIBLE : View.GONE);
        if (target == Screen.DIAGNOSTICS) refresh();
    }

    private void setAutomaticSkip(boolean checked) {
        if (automaticSkipEnabled == checked) return;
        automaticSkipEnabled = checked;
        if (BuildConfig.PRODUCTION_READ_ONLY_ENABLED) {
            if (checked) SessionProbe.cancelExperiment();
            ProductionSettings.setEnabled(this, checked);
            ProductionSkipCoordinator.settingsChanged(this);
        }
        if (homeMasterSwitch.isChecked() != checked) homeMasterSwitch.setChecked(checked);
        if (settingsMasterSwitch.isChecked() != checked) settingsMasterSwitch.setChecked(checked);
        refresh();
    }

    private static String themeLabel(UiThemeChoice choice) {
        switch (choice) {
            case LIGHT: return "浅色";
            case DARK: return "深色";
            default: return "跟随系统";
        }
    }

    private void showThemeChoice() {
        UiThemeChoice[] choices = UiThemeChoice.values();
        String[] labels = {"跟随系统", "浅色", "深色"};
        new AlertDialog.Builder(this)
                .setTitle("主题")
                .setSingleChoiceItems(labels, UiAppearance.choice(this).ordinal(),
                        (dialog, which) -> {
                            UiThemeChoice selected = choices[which];
                            dialog.dismiss();
                            if (selected != UiAppearance.choice(this)) {
                                UiAppearance.save(this, selected);
                                recreate();
                            }
                        })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showAbout() {
        HelpUi.show(this, appUpdater, this::buildCompatibilityReport, this::copyCompatibilityReport);
    }

    private static String recentUserText(LocalAuditLog.Entry event) {
        if (event == null) return "还没有自动跳过记录";
        if (event.getStatus() == LocalAuditLog.Status.UNDO) return "已撤销上次自动跳过";
        if (event.getStatus() == LocalAuditLog.Status.FAILED) return "最近一次跳过未完成";
        long seconds = (event.getSkippedDurationMs() + 500L) / 1_000L;
        String category = event.getCategory().orElse("");
        for (org.adskip.core.SegmentRule member : event.getRules()) {
            if (!category.equals(member.getCategory())) { category = ""; break; }
        }
        String label = "sponsor".equals(category) ? "赞助内容"
                : "selfpromo".equals(category) ? "自我推广"
                : "interaction".equals(category) ? "互动内容"
                : "intro".equals(category) ? "片头"
                : "outro".equals(category) ? "片尾" : "片段";
        return "已跳过 " + seconds + " 秒" + label;
    }

    private void refresh() {
        if (undoHeadsUpSwitch != null) {
            undoHeadsUpSwitch.setEnabled(ProductionSettings.undoNotificationEnabled(this));
        }
        if (undoNotificationStatus != null) {
            undoNotificationStatus.setText(getString(R.string.undo_permission_status_short,
                    getString(UndoNotificationController.permissionGranted(this)
                            ? R.string.undo_status_allowed : R.string.undo_status_not_allowed),
                    getString(UndoNotificationController.notificationsAvailable(this)
                            ? R.string.undo_status_displayable : R.string.undo_status_blocked)));
        }
        List<MediaController> active = SessionProbe.activeSessions(this);
        List<MediaController> bili = new ArrayList<>(active);
        SessionAdapter.CandidateSelection candidate = SessionAdapter.candidateSelection();
        consumePendingShare(candidate);
        consumePendingAnalysisCatalog(candidate);
        seekButton.setEnabled(candidate.state == SessionAdapter.CandidateState.READY
                && !automaticSkipEnabled && BuildConfig.DEBUG);
        armButton.setEnabled(candidate.state == SessionAdapter.CandidateState.READY
                && candidate.controller != null
                && SessionProbe.isExperimentEligible(candidate.controller)
                && !automaticSkipEnabled && BuildConfig.DEBUG);
        boolean canUndo = ProductionSkipCoordinator.undoAvailable()
                || (BuildConfig.DEBUG && !BuildConfig.PRODUCTION_READ_ONLY_ENABLED
                && SessionProbe.isExperimentUndoAvailable());
        undoButton.setEnabled(canUndo);
        undoButton.setVisibility(canUndo ? View.VISIBLE : View.GONE);
        requestAnalysisButton.setEnabled(requestAnalysisTarget != null);

        SessionAdapter.Status adapterStatus = SessionAdapter.status();
        boolean accessAuthorized = isNotificationAccessEnabled();
        long recentEntryDelayMs = ProductionSkipCoordinator.recentEntryDelayMs();
        BackgroundCompatibilityHealth.State compatibilityState = COMPATIBILITY_HEALTH.update(
                accessAuthorized, adapterStatus.listenerConnected, SystemClock.elapsedRealtime(),
                SessionAdapter.recentListenerLosses(), adapterStatus.connectionEpoch, recentEntryDelayMs > 0L);
        compatibilitySettingsButton.setText(
                compatibilityState == BackgroundCompatibilityHealth.State.CHECK_SETTINGS
                        || compatibilityState == BackgroundCompatibilityHealth.State.SCHEDULING_DELAY
                        ? "查看后台恢复建议" : "本机后台设置说明");
        restrictedSettingsButton.setVisibility(Build.VERSION.SDK_INT >= 33
                && accessAttempted && !accessAuthorized ? View.VISIBLE : View.GONE);
        switch (compatibilityState) {
            case UNAUTHORIZED:
                compatibilityStatus.setText(R.string.compatibility_unauthed);
                break;
            case CONNECTED:
                compatibilityStatus.setText(R.string.compatibility_connected);
                break;
            case SCHEDULING_DELAY:
                compatibilityStatus.setText(R.string.compatibility_scheduling_delayed);
                break;
            case WAITING_SYSTEM:
                compatibilityStatus.setText(R.string.compatibility_waiting);
                break;
            case RECOVERING:
                compatibilityStatus.setText(R.string.compatibility_recovering);
                break;
            default:
                compatibilityStatus.setText(R.string.compatibility_suspected);
        }
        boolean sessionReady = candidate.state == SessionAdapter.CandidateState.READY;
        String cidState = BuildConfig.PRODUCTION_READ_ONLY_ENABLED
                ? ProductionSkipCoordinator.cidState() : "unavailable";
        int ruleCount = BuildConfig.PRODUCTION_READ_ONLY_ENABLED
                ? ProductionSkipCoordinator.ruleCount() : SessionProbe.experimentRuleCount();
        boolean serviceUnavailable = BuildConfig.PRODUCTION_READ_ONLY_ENABLED
                && ProductionSkipCoordinator.queryState() == org.adskip.core.QueryState.SERVICE_ERROR;
        HomeUiState homeState = HomeUiState.from(automaticSkipEnabled, accessAuthorized,
                compatibilityState, sessionReady, cidState, ruleCount,
                BuildConfig.PRODUCTION_READ_ONLY_ENABLED ? ProductionSkipCoordinator.queryState()
                        : org.adskip.core.QueryState.IDLE);
        homeStatusHeadline.setText(homeState.headline);
        homeStatusDetail.setText(homeState.detail);
        homeVideo.setText(homeState.video);
        int statusFill = homeState.tone == HomeUiState.Tone.NORMAL
                ? appearance.successContainer : homeState.tone == HomeUiState.Tone.ATTENTION
                ? appearance.errorContainer : homeState.tone == HomeUiState.Tone.WAITING
                ? appearance.warningContainer : appearance.primaryContainer;
        int statusText = homeState.tone == HomeUiState.Tone.NORMAL
                ? appearance.successText : homeState.tone == HomeUiState.Tone.ATTENTION
                ? appearance.errorText : homeState.tone == HomeUiState.Tone.WAITING
                ? appearance.warningText : appearance.primary;
        homeStatusCard.setBackground(rounded(statusFill, statusFill));
        homeStatusHeadline.setTextColor(statusText);
        homeStatusDetail.setTextColor(appearance.text);
        homeRecoveryButton.setVisibility(!accessAuthorized && automaticSkipEnabled
                || compatibilityState == BackgroundCompatibilityHealth.State.CHECK_SETTINGS
                && automaticSkipEnabled || compatibilityState == BackgroundCompatibilityHealth.State.SCHEDULING_DELAY
                && automaticSkipEnabled || serviceUnavailable && automaticSkipEnabled
                ? View.VISIBLE : View.GONE);
        homeRecoveryButton.setText(!accessAuthorized ? "开启通知使用权"
                : serviceUnavailable ? "查看数据服务设置" : "查看兼容性设置");
        int enabledCategories = 0;
        for (CategoryPolicy.Category category : categoryPolicy.categories()) {
            if (categoryPolicy.isEnabled(category)) enabledCategories++;
        }
        homeCategorySummary.setText(getString(R.string.ui_category_count, enabledCategories));
        LocalAuditLog.Entry recent = BuildConfig.PRODUCTION_READ_ONLY_ENABLED
                ? ProductionSkipCoordinator.recentUserEvent() : null;
        homeRecent.setText(recentUserText(recent));

        StringBuilder output = new StringBuilder();
        output.append("总自动跳过：").append(automaticSkipEnabled ? "开启（单 P exact）" : "关闭").append('\n');
        output.append("通知使用权：").append(isNotificationAccessEnabled() ? "已启用" : "未启用或系统尚未刷新").append('\n');
        output.append("监听器实际连接：").append(adapterStatus.listenerConnected).append('\n');
        appendProductIdentity(output, candidate);
        output.append("当前规则数量：").append(BuildConfig.PRODUCTION_READ_ONLY_ENABLED
                ? ProductionSkipCoordinator.ruleCount() : SessionProbe.experimentRuleCount()).append('\n');
        String nextSegment = BuildConfig.PRODUCTION_READ_ONLY_ENABLED
                ? ProductionSkipCoordinator.nextSegmentId() : SessionProbe.nextExperimentSegmentId();
        output.append("下一条可执行片段：").append(nextSegment == null ? "无" : nextSegment)
                .append("（仅 exact CID evidence + 启用类别时可发布）\n");
        if (BuildConfig.PRODUCTION_READ_ONLY_ENABLED) {
            output.append("只读数据源：BilibiliSponsorBlock；服务端=")
                    .append(ProductionSettings.serverOrigin(this)).append('\n');
            output.append("最近一次正式链：").append(ProductionSkipCoordinator.lastResult()).append('\n');
            output.append("pagelist state：")
                    .append(ProductionSkipCoordinator.recentPagelistStatus()).append('\n');
            output.append("BSB state：")
                    .append(ProductionSkipCoordinator.recentBsbStatus()).append('\n');
            output.append("只读查询耗时（单调时钟）：")
                    .append(ProductionSkipCoordinator.timingReport()).append('\n');
            output.append("BSB 传输阶段（仅本进程）：")
                    .append(ProductionSkipCoordinator.bsbTransportTimingReport()).append('\n');
            output.append("最近一次本地审计（内存上限 32）：")
                    .append(ProductionSkipCoordinator.latestAudit()).append('\n');
            if (!serverStatus.isEmpty()) output.append(serverStatus).append('\n');
        }
        output.append("会话监控：").append(adapterStatus.connectionState)
                .append("；registered=").append(adapterStatus.activeListenerRegistered)
                .append("；detail=").append(adapterStatus.detail)
                .append("；epoch=").append(adapterStatus.connectionEpoch).append('\n');
        output.append("后台兼容状态：").append(compatibilityState).append('\n');
        output.append("最近十分钟入口定时回调延迟：").append(recentEntryDelayMs).append("ms（不推断具体原因）\n");
        output.append("候选：").append(adapterStatus.candidateState)
                .append("；detail=").append(adapterStatus.candidateDetail).append('\n');
        output.append("跟踪的活跃 B 站会话：").append(bili.size()).append("\n\n");
        if (accessAuthorized && !adapterStatus.listenerConnected) {
            output.append(compatibilityState == BackgroundCompatibilityHealth.State.CHECK_SETTINGS
                    ? "恢复建议：监听器持续未连接。若“重扫/重连”无效，可在通知使用权中重新启用，并按需检查后台设置。\n\n"
                    : "恢复观察：系统仍在连接窗口内，暂不提示后台设置。\n\n");
        }
        if (bili.isEmpty()) {
            output.append("请先在设置中允许 23adskip 使用通知，再让 B 站开始播放普通视频。\n");
        }
        for (MediaController controller : bili) {
            appendController(output, controller);
        }
        if (BuildConfig.DEBUG && !BuildConfig.PRODUCTION_READ_ONLY_ENABLED) {
            output.append(seekResult).append("\n");
            output.append(SessionProbe.experimentReport()).append("\n");
            output.append(SessionProbe.experimentAuditReport()).append("\n");
            output.append('\n').append(surfaceCapture).append("\n");
            output.append('\n').append(transitionCapture).append("\n");
        }
        if (BuildConfig.RESEARCH_NETWORK_ENABLED) {
            output.append("增强多P识别：")
                    .append(ResearchAccessibilityBridge.isModeEnabled(this) ? "研究模式开启" : "基础模式")
                    .append("；状态=").append(ResearchAccessibilityBridge.modeStatus())
                    .append("（尚不授权自动 seek）\n");
            output.append('\n').append(notificationCapture).append("\n");
            output.append('\n').append(accessibilityCapture).append("\n");
        }
        if (BuildConfig.DEBUG && !BuildConfig.PRODUCTION_READ_ONLY_ENABLED) {
            output.append('\n').append(shareCapture).append("\n");
            output.append('\n').append(analysisCapture).append("\n");
            output.append("本地请求 ledger：").append(REQUEST_LEDGER.snapshot().size())
                    .append(" 条（bounded/process-local；进程退出即清空）\n");
            output.append("\n解释：raw position 是客户端直接给出的数值；只有它能随播放变化且 seek 结果可回读，才会进入 P2。\n");
        }
        report.setText(output);
    }

    private void handleShareIntent(Intent intent) {
        if (intent == null || !Intent.ACTION_SEND.equals(intent.getAction())
                || (intent.getType() != null && !"text/plain".equals(intent.getType()))) {
            return;
        }
        if (BuildConfig.RESEARCH_NETWORK_ENABLED && diagnosticsPanel != null) {
            showScreen(Screen.DIAGNOSTICS);
        }
        String text = extractSharedText(intent);
        ShareLinkObservation observation = ShareLinkEvidenceParser.parse(text);
        intent.removeExtra(Intent.EXTRA_TEXT);
        intent.setClipData(null);
        pendingShare = null;
        pendingResearchPageObservation = null;
        pendingAnalysisCatalog = null;
        requestAnalysisTarget = null;
        analysisCapture = "请求分析：解析中（不等待 MediaSession）";
        shareCapture = "分享链接证据：state=" + observation.getState()
                + "；recognizedUrls=" + observation.getRecognizedUrlCount()
                + "；inputDigest=" + observation.getInputFingerprint().substring(0, 12);
        if (observation.getState() == ShareLinkObservation.State.DIRECT) {
            pendingShare = observation;
            pendingShareDeadlineMs = SystemClock.elapsedRealtime() + 5_000L;
            shareCapture += "；等待绑定稳定 session/generation（research only）";
            SessionAdapter.requestRescan();
        } else if (observation.getState()
                == ShareLinkObservation.State.SHORT_LINK_NEEDS_EXPANSION) {
            if (BuildConfig.RESEARCH_NETWORK_ENABLED) {
                shareCapture += "；request flow 立即展开；automatic evidence 仍等待稳定 session";
            } else {
                shareCapture += "；短链需独立 transport 展开，本版不联网、不猜测";
            }
        } else {
            shareCapture += "；未产生 evidence";
        }
        if (BuildConfig.RESEARCH_NETWORK_ENABLED
                && (observation.getState() == ShareLinkObservation.State.DIRECT
                || observation.getState() == ShareLinkObservation.State.SHORT_LINK_NEEDS_EXPANSION)) {
            long requestEpoch = REQUEST_ANALYSIS_STORE.begin();
            logRequestEpoch(REQUEST_ANALYSIS_STORE.latest());
            startRequestAnalysis(text, requestEpoch);
        } else if (!BuildConfig.RESEARCH_NETWORK_ENABLED) {
            analysisCapture = "请求分析：当前变体无网络 transport";
        }
    }

    private void startRequestAnalysis(String sharedText, long requestEpoch) {
        if (!ResearchShareBridge.startAnalysis(this, sharedText,
                (summary, expanded, catalog, resolution) -> {
                    if (!REQUEST_ANALYSIS_STORE.complete(
                            requestEpoch, summary, expanded, catalog, resolution)) return;
                    logRequestEpoch(REQUEST_ANALYSIS_STORE.latest());
                    runOnUiThread(() -> {
                        if (isDestroyed()) return;
                        applyLatestRequestAnalysis();
                        refresh();
                    });
                })) {
            REQUEST_ANALYSIS_STORE.complete(requestEpoch,
                    "请求分析：research transport 不可用", null, null, null);
            logRequestEpoch(REQUEST_ANALYSIS_STORE.latest());
            applyLatestRequestAnalysis();
        }
    }

    private static void logRequestEpoch(AnalysisRequestStateStore.Snapshot snapshot) {
        if (!BuildConfig.RESEARCH_NETWORK_ENABLED || snapshot == null) return;
        Log.i("AdSkipRequestEpoch", "epoch=" + snapshot.getEpoch()
                + " revision=" + snapshot.getRevision()
                + " state=" + snapshot.getState());
    }

    /** Applies at most once per Activity, allowing an older revealed instance to adopt new data. */
    private void applyLatestRequestAnalysis() {
        if (!BuildConfig.RESEARCH_NETWORK_ENABLED) return;
        AnalysisRequestStateStore.Snapshot snapshot = REQUEST_ANALYSIS_STORE.latest();
        if (snapshot == null || snapshot.getRevision() == appliedRequestRevision) return;
        appliedRequestRevision = snapshot.getRevision();
        analysisCapture = snapshot.getSummary()
                + "；requestEpoch=" + snapshot.getEpoch()
                + "；revision=" + snapshot.getRevision();
        requestAnalysisTarget = null;
        if (snapshot.getState() != AnalysisRequestStateStore.State.COMPLETE) return;
        if (snapshot.getResolution() != null
                && snapshot.getResolution().getState() == AnalysisRequestResolver.State.READY) {
            requestAnalysisTarget = snapshot.getResolution().getTarget().orElse(null);
            if (requestAnalysisTarget != null) {
                analysisCapture += "；请求目标 BVID="
                        + requestAnalysisTarget.getVideoKey().getBvid()
                        + "；page=" + requestAnalysisTarget.getPage()
                        + "；CID=" + requestAnalysisTarget.getVideoKey().getCidString()
                        + "；等待用户确认";
            }
        }
        if (snapshot.getObservation() != null && snapshot.getCatalog() != null
                && snapshot.getObservation().getState() == ShareLinkObservation.State.DIRECT) {
            pendingShare = snapshot.getObservation();
            pendingShareDeadlineMs = SystemClock.elapsedRealtime() + 5_000L;
            pendingAnalysisCatalog = snapshot.getCatalog();
            pendingResearchPageObservation = snapshot.getObservation();
            pendingAnalysisEvidenceDeadlineMs = SystemClock.elapsedRealtime() + 5_000L;
            SessionAdapter.requestRescan();
        }
    }

    /** Optionally projects sessionless research data into the strict generation-scoped timeline. */
    private void consumePendingAnalysisCatalog(SessionAdapter.CandidateSelection candidate) {
        if (pendingAnalysisCatalog == null || pendingResearchPageObservation == null) return;
        Optional<EvidenceScope> scope = candidate.state == SessionAdapter.CandidateState.READY
                ? SessionProbe.currentEvidenceScope() : Optional.empty();
        if (!scope.isPresent()) {
            if (SystemClock.elapsedRealtime() >= pendingAnalysisEvidenceDeadlineMs) {
                shareCapture += "；automatic evidence 安全拒绝：5 秒内无稳定 generation";
                pendingAnalysisCatalog = null;
                pendingResearchPageObservation = null;
            }
            return;
        }
        if (!scope.get().getBvid().equals(pendingAnalysisCatalog.getBvid())) {
            shareCapture += "；automatic evidence 拒绝：当前 session BVID 冲突";
            pendingAnalysisCatalog = null;
            pendingResearchPageObservation = null;
            return;
        }
        long catalogObservedAt = pendingAnalysisCatalog.getObservedAtElapsedMs();
        CandidatePageMap scoped = PageListMapper.bind(scope.get(), pendingAnalysisCatalog);
        pendingAnalysisCatalog = null;
        ShareLinkObservation observation = pendingResearchPageObservation;
        pendingResearchPageObservation = null;
        if (!observation.getPage().isPresent()) {
            shareCapture += "；automatic evidence 拒绝：分享缺少显式 page";
            return;
        }
        Optional<CandidatePart> part = scoped.candidateForPage(observation.getPage().getAsLong());
        if (!part.isPresent()) {
            shareCapture += "；automatic evidence 拒绝：page 未唯一映射";
            return;
        }
        shareCapture += "；" + SessionProbe.captureNativeHttpCandidate(
                scoped.getScope(), part.get(), catalogObservedAt,
                Math.max(0L, SystemClock.elapsedRealtime() - catalogObservedAt),
                observation.getInputFingerprint());
        shareCapture += "；" + SessionProbe.resolveResearchPageMap(scoped);
    }

    private void consumePendingShare(SessionAdapter.CandidateSelection candidate) {
        if (pendingShare == null) return;
        if (candidate.state == SessionAdapter.CandidateState.READY) {
            shareCapture += "；" + SessionProbe.captureShareEvidence(pendingShare);
            pendingShare = null;
        } else if (SystemClock.elapsedRealtime() >= pendingShareDeadlineMs) {
            shareCapture += "；超时，未追加（没有唯一稳定 generation）";
            pendingShare = null;
        }
    }

    private String extractSharedText(Intent intent) {
        StringBuilder text = new StringBuilder();
        CharSequence extra = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
        if (extra != null) text.append(extra);
        ClipData clip = intent.getClipData();
        if (clip != null) {
            for (int index = 0; index < clip.getItemCount() && text.length() < 8_192; index++) {
                ClipData.Item item = clip.getItemAt(index);
                CharSequence itemText = item.getText();
                String value = itemText != null ? itemText.toString()
                        : item.getUri() != null ? item.getUri().toString() : null;
                if (value == null || value.isEmpty() || text.indexOf(value) >= 0) continue;
                if (text.length() > 0) text.append(' ');
                text.append(value);
            }
        }
        if (text.length() > 8_192) text.setLength(8_192);
        return text.toString();
    }

    private void addCategoryToggle(LinearLayout column, Context context, CategoryPolicy.Category category,
            String label, boolean checked) {
        CheckBox toggle = new CheckBox(context);
        toggle.setText(label);
        toggle.setChecked(categoryPolicy.isEnabled(category));
        toggle.setOnCheckedChangeListener((button, enabled) -> {
            categoryPolicy = categoryPolicy.withAutomaticSkipEnabled(category, enabled);
            if (BuildConfig.PRODUCTION_READ_ONLY_ENABLED) {
                ProductionSettings.setCategory(this, category, enabled);
                ProductionSkipCoordinator.settingsChanged(this);
            }
            refresh();
        });
        column.addView(toggle);
    }

    private void appendProductIdentity(StringBuilder output, SessionAdapter.CandidateSelection candidate) {
        if (candidate.state != SessionAdapter.CandidateState.READY || candidate.controller == null
                || candidate.controller.getMetadata() == null) {
            output.append("当前 BVID：unavailable\nCID 状态：unavailable\n");
            return;
        }
        MediaMetadata metadata = candidate.controller.getMetadata();
        Optional<BiliIdentity> identity = BiliIdentityResolver.resolve(
                metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID),
                metadata.getString(MediaMetadata.METADATA_KEY_TITLE),
                metadata.getLong(MediaMetadata.METADATA_KEY_DURATION));
        output.append("当前 BVID：").append(identity.isPresent() ? identity.get().getBvid() : "unavailable")
                .append('\n');
        if (BuildConfig.PRODUCTION_READ_ONLY_ENABLED) {
            output.append("CID 状态：").append(ProductionSkipCoordinator.cidState()).append('\n');
        } else if (SessionProbe.isExperimentEligible(candidate.controller)) {
            output.append("CID 状态：exact（本地 P4 fixture，受控实验）\n");
        } else if (identity.isPresent()) {
            output.append("CID 状态：multi-P unknown / unavailable（不自动升级）\n");
        } else {
            output.append("CID 状态：unavailable\n");
        }
        output.append("已启用类别：");
        boolean first = true;
        for (CategoryPolicy.Category category : categoryPolicy.categories()) {
            if (!categoryPolicy.isEnabled(category)) continue;
            if (!first) output.append(", ");
            output.append(category.getWireName());
            first = false;
        }
        output.append('\n');
    }

    private void appendController(StringBuilder output, MediaController controller) {
        PlaybackState state = controller.getPlaybackState();
        MediaMetadata metadata = controller.getMetadata();
        output.append("package: ").append(controller.getPackageName()).append('\n');
        output.append("session fingerprint: ").append(Integer.toHexString(controller.getSessionToken().hashCode())).append('\n');
        if (state == null) {
            output.append("playback state: <null>\n");
        } else {
            long actions = state.getActions();
            output.append("playback state: ").append(stateName(state.getState())).append('\n');
            output.append("raw position: ").append(state.getPosition()).append(" ms\n");
            output.append("estimated now: ").append(SessionProbe.estimatedPosition(state)).append(" ms\n");
            output.append("speed: ").append(state.getPlaybackSpeed()).append("; updated elapsed: ").append(state.getLastPositionUpdateTime()).append(" ms\n");
            output.append("actions: ").append(actions).append("; ACTION_SEEK_TO: ")
                    .append((actions & PlaybackState.ACTION_SEEK_TO) != 0L).append('\n');
        }
        if (metadata == null) {
            output.append("metadata: <null>\n");
        } else {
            String title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE);
            String mediaId = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID);
            Set<String> keys = metadata.keySet();
            List<String> sortedKeys = new ArrayList<>(keys);
            Collections.sort(sortedKeys);
            output.append("title: ").append(TextUtils.isEmpty(title) ? "<none>" : title).append('\n');
            output.append("media id: ").append(TextUtils.isEmpty(mediaId) ? "<none>" : mediaId).append('\n');
            output.append("duration: ").append(metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)).append(" ms\n");
            Optional<BiliIdentity> identity = BiliIdentityResolver.resolve(
                    mediaId,
                    title,
                    metadata.getLong(MediaMetadata.METADATA_KEY_DURATION));
            if (identity.isPresent()) {
                output.append("resolved identity: aid=").append(identity.get().getAid())
                        .append("; bvid=").append(identity.get().getBvid()).append('\n');
            } else {
                output.append("resolved identity: <rejected>\n");
            }
            output.append("metadata keys: ").append(sortedKeys).append('\n');
        }
        SessionAdapter.diagnostics(controller.getSessionToken()).ifPresent(diagnostics -> {
            output.append("adapter fingerprint: ").append(diagnostics.fingerprint).append('\n');
            output.append("callbacks: playback=").append(diagnostics.playbackCallbacks)
                    .append(", metadata=").append(diagnostics.metadataCallbacks)
                    .append(", queue=").append(diagnostics.queueCallbacks)
                    .append(", extras=").append(diagnostics.extrasCallbacks).append('\n');
            output.append("stability: ").append(diagnostics.gateState)
                    .append("；reason=").append(diagnostics.gateReason)
                    .append("；generation=").append(diagnostics.generationNo).append('\n');
        });
        output.append('\n');
    }

    private void seekForwardTenSeconds() {
        if (!BuildConfig.DEBUG) return;
        SessionAdapter.CandidateSelection selection = SessionAdapter.candidateSelection();
        MediaController controller = selection.controller;
        if (selection.state == SessionAdapter.CandidateState.READY && controller != null) {
            PlaybackState before = controller.getPlaybackState();
            if (before == null || (before.getActions() & PlaybackState.ACTION_SEEK_TO) == 0L) {
                seekResult = "P2 seek：稳定候选当前不支持 ACTION_SEEK_TO";
                refresh();
                return;
            }
            long from = SessionProbe.estimatedPosition(before);
            long target = from + 10_000L;
            seekResult = "P2 seek：已请求 " + from + " → " + target + " ms，等待回读…";
            controller.getTransportControls().seekTo(target);
            handler.postDelayed(() -> verifySeek(controller, target), 1_500L);
            refresh();
            return;
        }
        seekResult = "P2 seek：没有唯一且稳定的 B 站候选会话";
        refresh();
    }

    private void verifySeek(MediaController controller, long target) {
        PlaybackState after = controller.getPlaybackState();
        if (after == null) {
            seekResult = "P2 seek：请求后 PlaybackState 为空";
        } else {
            long actual = SessionProbe.estimatedPosition(after);
            long error = actual - target;
            seekResult = "P2 seek：目标 " + target + " ms；回读 " + actual
                    + " ms；偏差 " + error + " ms；状态 " + stateName(after.getState());
        }
        refresh();
    }

    private boolean isNotificationAccessEnabled() {
        String enabled = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        if (enabled == null) {
            return false;
        }
        ComponentName expected = new ComponentName(this, BiliNotificationListener.class);
        for (String flattened : enabled.split(":")) {
            if (expected.equals(ComponentName.unflattenFromString(flattened))) {
                return true;
            }
        }
        return false;
    }

    private void openNotificationAccessSettings() {
        accessAttempted = true;
        if (Build.VERSION.SDK_INT >= 30) {
            Intent detail = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS);
            detail.putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                    new ComponentName(this, BiliNotificationListener.class).flattenToString());
            try {
                startActivity(detail);
                return;
            } catch (ActivityNotFoundException ignored) {
                // OEM without this settings Activity: fall back to the system list.
            }
        }
        startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
    }

    private void showCompatibilityGuide() {
        OemCompatibilityGuide.Brand brand = OemCompatibilityGuide.classify(
                Build.MANUFACTURER, Build.BRAND);
        OemCompatibilityRegistry.Profile profile = OemCompatibilityRegistry.forBrand(brand);
        boolean hasOemDestination = !profile.settingsDestinations.isEmpty();
        AlertDialog guideDialog = new AlertDialog.Builder(this)
                .setTitle("后台设置说明 · " + profile.label)
                .setMessage(OemCompatibilityGuide.text(brand))
                .setPositiveButton(hasOemDestination ? profile.settingsLabel : "应用电池设置",
                        (dialog, which) -> OemSettingsNavigator.openSuggestedSettings(this, brand))
                .setNeutralButton(hasOemDestination ? "应用电池设置" : "电池优化列表",
                        (dialog, which) -> {
                            if (hasOemDestination) OemSettingsNavigator.openBattery(this);
                            else OemSettingsNavigator.openBatteryOptimizationList(this);
                        })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
        TextView message = guideDialog.findViewById(android.R.id.message);
        if (message != null) Linkify.addLinks(message, Linkify.WEB_URLS);
    }

    private void showRestrictedSettingsHelp() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.restricted_settings_help)
                .setMessage(R.string.restricted_settings_steps)
                .setPositiveButton(R.string.restricted_settings_app_info,
                        (dialog, which) -> OemSettingsNavigator.openAppDetails(this))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private String buildCompatibilityReport() {
        SessionAdapter.Status status = SessionAdapter.status();
        boolean biliInstalled;
        try {
            getPackageManager().getPackageInfo("tv.danmaku.bili", 0);
            biliInstalled = true;
        } catch (PackageManager.NameNotFoundException exception) {
            biliInstalled = false;
        }
        String postNotifications = Build.VERSION.SDK_INT < 33 ? "not_required"
                : checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED ? "granted" : "denied";
        return CompatibilityReport.build(BuildConfig.VERSION_NAME,
                BuildConfig.VERSION_CODE, Build.MANUFACTURER, Build.BRAND, Build.MODEL,
                Build.VERSION.SDK_INT, isNotificationAccessEnabled(), status.listenerConnected,
                biliInstalled, status.candidateState == SessionAdapter.CandidateState.READY,
                ProductionSkipCoordinator.recentPagelistStatus(),
                ProductionSkipCoordinator.recentBsbStatus(), postNotifications);
    }

    private void copyCompatibilityReport() {
        String text = buildCompatibilityReport();
        ClipboardManager clipboard = getSystemService(ClipboardManager.class);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("23adskip compatibility report", text));
            new AlertDialog.Builder(this).setMessage(R.string.compatibility_copied)
                    .setPositiveButton(android.R.string.ok, null).show();
        }
    }

    private void openOwnNotificationSettings() {
        Intent detail = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
        try {
            startActivity(detail);
        } catch (ActivityNotFoundException ignored) {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName())));
        }
    }

    /**
     * A process death can bypass {@link BiliNotificationListener#onListenerDisconnected()}, so
     * the listener's own rebind callback is not guaranteed to run. While foreground and while
     * the user-granted setting remains enabled, ask the framework once to rebind; otherwise a
     * rescan is sufficient. Android requires callers to wait for onListenerConnected before
     * touching listener data, and this method never changes that rule.
     */
    private void requestListenerRescanOrRebind() {
        SessionAdapter.Status status = SessionAdapter.status();
        if (status.listenerConnected) {
            SessionAdapter.requestRescan();
        } else if (isNotificationAccessEnabled()) {
            SessionAdapter.noteRebindRequested();
            NotificationListenerService.requestRebind(
                    new ComponentName(this, BiliNotificationListener.class));
        }
    }

    private static String stateName(int state) {
        switch (state) {
            case PlaybackState.STATE_PLAYING:
                return "PLAYING";
            case PlaybackState.STATE_PAUSED:
                return "PAUSED";
            case PlaybackState.STATE_BUFFERING:
                return "BUFFERING";
            case PlaybackState.STATE_CONNECTING:
                return "CONNECTING";
            case PlaybackState.STATE_STOPPED:
                return "STOPPED";
            case PlaybackState.STATE_ERROR:
                return "ERROR";
            default:
                return "STATE_" + state;
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
