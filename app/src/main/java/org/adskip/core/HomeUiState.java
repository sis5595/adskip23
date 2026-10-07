package org.adskip.core;

/** UI-only projection. No identity evidence or playback authorization is derived here. */
public final class HomeUiState {
    public enum Tone { NORMAL, WAITING, ATTENTION, OFF }

    public final Tone tone;
    public final String headline;
    public final String detail;
    public final String video;

    private HomeUiState(Tone tone, String headline, String detail, String video) {
        this.tone = tone;
        this.headline = headline;
        this.detail = detail;
        this.video = video;
    }

    public static HomeUiState from(boolean enabled, boolean authorized,
            BackgroundCompatibilityHealth.State connection, boolean sessionReady,
            String cidState, int ruleCount, boolean serviceUnavailable) {
        return from(enabled, authorized, connection, sessionReady, cidState, ruleCount,
                serviceUnavailable ? QueryState.SERVICE_ERROR : QueryState.IDLE);
    }

    public static HomeUiState from(boolean enabled, boolean authorized,
            BackgroundCompatibilityHealth.State connection, boolean sessionReady,
            String cidState, int ruleCount, QueryState queryState) {
        String video = videoLabel(sessionReady, cidState, ruleCount);
        if (!enabled) return new HomeUiState(Tone.OFF, "自动跳过已关闭",
                "打开后，B站播放时将自动跳过已启用类别的片段", video);
        if (!authorized) return new HomeUiState(Tone.ATTENTION, "需要开启通知使用权",
                "授权后才能识别B站播放状态", video);
        if (connection == BackgroundCompatibilityHealth.State.SCHEDULING_DELAY) {
            return new HomeUiState(Tone.ATTENTION, "检测到跳过调度延迟",
                    "最近的跳过检查未能按时执行，可能漏跳；请检查后台运行和电池限制", video);
        }
        if (connection == BackgroundCompatibilityHealth.State.CHECK_SETTINGS) {
            return new HomeUiState(Tone.ATTENTION, "后台连接异常",
                    "系统暂未连接 23adskip，可到设置中检查", video);
        }
        if (connection == BackgroundCompatibilityHealth.State.WAITING_SYSTEM
                || connection == BackgroundCompatibilityHealth.State.RECOVERING) {
            return new HomeUiState(Tone.WAITING, "正在等待系统连接",
                    "系统恢复窗口内，暂不需要调整后台设置", video);
        }
        if (!sessionReady) return new HomeUiState(Tone.NORMAL, "正常工作中",
                "等待B站播放；有可跳过片段时会自动处理", video);
        if (queryState == QueryState.SERVICE_ERROR) return new HomeUiState(Tone.ATTENTION, "服务暂时不可用",
                "当前无法取得片段数据，可稍后重试或检查数据服务设置", "片段查询失败");
        if (queryState == QueryState.QUERYING) return new HomeUiState(Tone.WAITING, "正在查询片段",
                "正在获取当前视频的可用数据", "查询中");
        if (queryState == QueryState.EMPTY) return new HomeUiState(Tone.NORMAL, "当前视频暂无片段数据",
                "数据服务未返回标注，稍后会重新检查", "暂无标注");
        if (queryState == QueryState.FILTERED) return new HomeUiState(Tone.NORMAL, "当前没有可执行片段",
                "返回的数据没有通过当前类别或规则校验", "没有符合设置的片段");
        if ("multi-P unknown".equals(cidState)) {
            return new HomeUiState(Tone.WAITING, "当前视频暂不可安全识别",
                    "多 P 视频目前不会自动跳过", video);
        }
        if ("unavailable".equals(cidState)) {
            return new HomeUiState(Tone.WAITING, "当前视频暂不可安全识别",
                    "身份未确认时不会自动跳过", video);
        }
        return new HomeUiState(Tone.NORMAL, "正常工作中",
                "B站播放时将自动跳过已启用类别的片段", video);
    }

    private static String videoLabel(boolean sessionReady, String cidState, int count) {
        if (!sessionReady) return "等待 B站播放";
        if ("multi-P unknown".equals(cidState)) return "多 P 视频暂不支持自动跳过";
        if ("exact single-P".equals(cidState)) {
            return count > 0 ? "已识别 · " + count + " 条可跳过片段" : "没有可跳过片段";
        }
        if ("checking single-P".equals(cidState)) return "正在检查当前视频";
        return "当前视频暂不可安全识别";
    }
}
