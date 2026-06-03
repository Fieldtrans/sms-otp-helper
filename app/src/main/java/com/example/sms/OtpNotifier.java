package com.example.sms;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.text.TextUtils;

public final class OtpNotifier {
    private static final String CHANNEL_ID = "otp_ready";
    private static final int NOTIFICATION_ID = 1001;
    private static final long DUPLICATE_WINDOW_MS = 5_000L;

    private static String lastNotifiedCode = "";
    private static long lastNotifiedAtMs = 0L;

    private OtpNotifier() {
    }

    public static void notifyReady(Context context, String code) {
        if (context == null || TextUtils.isEmpty(code) || !hasNotificationPermission(context)) {
            return;
        }
        long now = System.currentTimeMillis();
        synchronized (OtpNotifier.class) {
            if (TextUtils.equals(lastNotifiedCode, code) && now - lastNotifiedAtMs < DUPLICATE_WINDOW_MS) {
                return;
            }
            lastNotifiedCode = code;
            lastNotifiedAtMs = now;
        }
        try {
            NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager == null) {
                return;
            }
            ensureChannel(manager);

            Intent intent = new Intent(context, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent pendingIntent = PendingIntent.getActivity(
                    context,
                    0,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            );

            Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(context, CHANNEL_ID)
                    : new Notification.Builder(context);
            Notification notification = builder
                    .setSmallIcon(R.mipmap.ic_launcher)
                    .setContentTitle("验证码已就绪")
                    .setContentText("验证码 " + code + " 已复制")
                    .setContentIntent(pendingIntent)
                    .setAutoCancel(true)
                    .setShowWhen(true)
                    .setWhen(now)
                    .build();
            manager.notify(NOTIFICATION_ID, notification);
        } catch (Throwable ignored) {
        }
    }

    private static boolean hasNotificationPermission(Context context) {
        return Build.VERSION.SDK_INT < 33
                || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
    }

    private static void ensureChannel(NotificationManager manager) {
        if (Build.VERSION.SDK_INT < 26) {
            return;
        }
        NotificationChannel existing = manager.getNotificationChannel(CHANNEL_ID);
        if (existing != null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "验证码通知",
                NotificationManager.IMPORTANCE_HIGH
        );
        channel.setDescription("收到验证码时显示复制状态");
        manager.createNotificationChannel(channel);
    }
}
