package org.adskip.probe;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Explicit, non-exported PendingIntent target; never starts an Activity. */
public final class UndoActionReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!BuildConfig.PRODUCTION_READ_ONLY_ENABLED || intent == null
                || !UndoNotificationController.ACTION_UNDO.equals(intent.getAction())) return;
        ProductionSkipCoordinator.requestUndoFromNotification(
                context, intent.getStringExtra(UndoNotificationController.EXTRA_TOKEN));
    }
}
