package org.adskip.probe;

import android.content.ComponentName;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

/**
 * The system grants this service media-session visibility only after the user
 * enables notification access in Android settings. It reads no notification
 * contents. The production single-P coordinator is independently guarded by the user's
 * persisted master switch and exact CID evidence; research/harness remain isolated.
 */
public final class BiliNotificationListener extends NotificationListenerService {
    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        SessionAdapter.connect(this);
        ProductionSkipCoordinator.start(this);
        if (BuildConfig.RESEARCH_NETWORK_ENABLED) {
            try {
                StatusBarNotification[] active = getActiveNotifications();
                if (active != null) {
                    for (StatusBarNotification notification : active) {
                        NotificationSurfaceLog.record(notification, false);
                    }
                }
            } catch (RuntimeException ignored) {
                // Session monitoring remains available; notification-shape research fails closed.
            }
        }
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        NotificationSurfaceLog.record(sbn, false);
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        NotificationSurfaceLog.record(sbn, true);
    }

    @Override
    public void onListenerDisconnected() {
        ProductionSkipCoordinator.stop();
        SessionProbe.cancelExperiment();
        SessionAdapter.disconnect("listener-disconnected");
        requestRebind(new ComponentName(this, BiliNotificationListener.class));
        super.onListenerDisconnected();
    }

    @Override
    public void onDestroy() {
        ProductionSkipCoordinator.stop();
        SessionProbe.cancelExperiment();
        SessionAdapter.disconnect("listener-service-destroyed");
        super.onDestroy();
    }
}
