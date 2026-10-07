package org.adskip.probe;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;

/** Ephemeral, identity-free system UI for a confirmed production skip. */
final class UndoNotificationController {
    private static final String CHANNEL_DRAWER = "adskip_undo_drawer_v1";
    // Channel settings are immutable after creation; v2 restores the system alert sound so
    // HIGH can request a heads-up presentation on ROMs that suppress silent channels.
    private static final String CHANNEL_HEADS_UP = "adskip_undo_heads_up_v2";
    private static final String TAG = "adskip_confirmed_undo";
    private static final int ID = 1;
    // Explicit receiver is always addressed inside this APK; the action follows the final
    // applicationId without renaming Java classes or changing the research namespace.
    static final String ACTION_UNDO = BuildConfig.APPLICATION_ID + ".action.UNDO_CONFIRMED_SKIP";
    static final String EXTRA_TOKEN = "undo_token";

    private UndoNotificationController() { }

    static boolean permissionGranted(Context context) {
        return Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(
                Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
    }

    static boolean notificationsAvailable(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        return permissionGranted(context) && manager != null && manager.areNotificationsEnabled();
    }

    static boolean show(Context context, String token, long remainingMs, long segmentDurationMs,
            boolean headsUp) {
        if (!BuildConfig.PRODUCTION_READ_ONLY_ENABLED || !notificationsAvailable(context)
                || token == null || remainingMs <= 0L) return false;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) return false;
        String channelId = headsUp ? CHANNEL_HEADS_UP : CHANNEL_DRAWER;
        NotificationChannel channel = new NotificationChannel(channelId,
                context.getString(headsUp ? R.string.undo_channel_heads_up : R.string.undo_channel_drawer),
                headsUp ? NotificationManager.IMPORTANCE_HIGH : NotificationManager.IMPORTANCE_DEFAULT);
        channel.setDescription(context.getString(R.string.undo_channel_description));
        if (!headsUp) channel.setSound(null, null);
        channel.enableVibration(false);
        manager.createNotificationChannel(channel);
        NotificationChannel effective = manager.getNotificationChannel(channelId);
        if (effective == null || effective.getImportance() == NotificationManager.IMPORTANCE_NONE) {
            return false;
        }

        Intent undo = new Intent(context, UndoActionReceiver.class)
                .setAction(ACTION_UNDO)
                .setData(Uri.parse("adskip-undo://action/" + token))
                .putExtra(EXTRA_TOKEN, token);
        PendingIntent action = PendingIntent.getBroadcast(context, 0, undo,
                PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_CANCEL_CURRENT);
        long seconds = Math.max(1L, Math.round(segmentDurationMs / 1_000.0));
        Notification notification = new Notification.Builder(context, channelId)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(context.getString(R.string.undo_notification_title, seconds))
                .setContentText(context.getString(R.string.undo_notification_text))
                .setPriority(headsUp ? Notification.PRIORITY_HIGH : Notification.PRIORITY_DEFAULT)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setLocalOnly(true)
                .setTimeoutAfter(remainingMs)
                .addAction(R.drawable.ic_launcher_foreground,
                        context.getString(R.string.undo_notification_action), action)
                .build();
        try {
            manager.notify(TAG, ID, notification);
            return true;
        } catch (SecurityException exception) {
            return false;
        }
    }

    static void cancel(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager != null) manager.cancel(TAG, ID);
    }
}
