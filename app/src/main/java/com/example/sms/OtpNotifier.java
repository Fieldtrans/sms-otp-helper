package com.example.sms;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.widget.Toast;

public final class OtpNotifier {
    private static final long DUPLICATE_WINDOW_MS = 5_000L;

    private static String lastShownCode = "";
    private static long lastShownAtMs = 0L;
    private static Toast activeToast;

    private OtpNotifier() {
    }

    public static void notifyReady(Context context, String code) {
        if (context == null || TextUtils.isEmpty(code) || !CodeStore.isToastPromptEnabled(context)) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        synchronized (OtpNotifier.class) {
            if (TextUtils.equals(lastShownCode, code) && now - lastShownAtMs < DUPLICATE_WINDOW_MS) {
                return;
            }
            lastShownCode = code;
            lastShownAtMs = now;
        }
        Context appContext = context.getApplicationContext();
        int durationSeconds = CodeStore.getToastPromptDurationSeconds(appContext);
        new Handler(Looper.getMainLooper()).post(() -> showToast(appContext, code, durationSeconds));
    }

    private static void showToast(Context context, String code, int durationSeconds) {
        try {
            if (activeToast != null) {
                activeToast.cancel();
            }
            String text = "验证码 " + code + " 已复制";
            long endAtMs = SystemClock.elapsedRealtime() + Math.max(1, durationSeconds) * 1_000L;
            showToastOnce(context, text, endAtMs);
        } catch (Throwable ignored) {
        }
    }

    private static void showToastOnce(Context context, String text, long endAtMs) {
        try {
            activeToast = Toast.makeText(context, text, Toast.LENGTH_SHORT);
            activeToast.show();
            long remainingMs = endAtMs - SystemClock.elapsedRealtime();
            if (remainingMs > 2_000L) {
                new Handler(Looper.getMainLooper()).postDelayed(
                        () -> showToastOnce(context, text, endAtMs),
                        1_900L
                );
            }
        } catch (Throwable ignored) {
        }
    }
}
