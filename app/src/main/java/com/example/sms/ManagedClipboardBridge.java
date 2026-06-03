package com.example.sms;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

public final class ManagedClipboardBridge {
    public static final String LABEL_PREFIX = "CodeDelayLSP:";
    public static final String FILLED_MARK = "filled:";
    public static final long TTL_MS = 90_000L;

    private ManagedClipboardBridge() {
    }

    public static void write(Context context, String code) {
        if (context == null || code == null || code.isEmpty()) {
            return;
        }
        try {
            ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard == null) {
                return;
            }
            long createdAtMs = System.currentTimeMillis();
            clipboard.setPrimaryClip(ClipData.newPlainText(buildLabel(createdAtMs), code));
            scheduleClear(context.getApplicationContext(), createdAtMs);
        } catch (Throwable ignored) {
        }
    }

    public static void scheduleClear(Context context, long createdAtMs) {
        if (context == null || createdAtMs <= 0L) {
            return;
        }
        long remainingMs = Math.max(0L, TTL_MS - (System.currentTimeMillis() - createdAtMs));
        new Handler(Looper.getMainLooper()).postDelayed(
                () -> clearIfSame(context.getApplicationContext(), createdAtMs),
                remainingMs
        );
    }

    public static void clearIfSame(Context context, long createdAtMs) {
        try {
            ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard == null || !clipboard.hasPrimaryClip()) {
                return;
            }
            ClipMeta meta = parse(clipboard.getPrimaryClipDescription());
            if (meta.isManaged && meta.createdAtMs == createdAtMs) {
                clipboard.setPrimaryClip(ClipData.newPlainText("", ""));
            }
        } catch (Throwable ignored) {
        }
    }

    public static String buildLabel(long createdAtMs) {
        return LABEL_PREFIX + createdAtMs;
    }

    public static String buildFilledLabel(long createdAtMs) {
        return LABEL_PREFIX + FILLED_MARK + createdAtMs;
    }

    public static ClipMeta parse(ClipDescription description) {
        if (description == null || description.getLabel() == null) {
            return ClipMeta.EMPTY;
        }
        String label = description.getLabel().toString();
        if (!label.startsWith(LABEL_PREFIX)) {
            return ClipMeta.EMPTY;
        }
        String payload = label.substring(LABEL_PREFIX.length());
        boolean isFilled = payload.startsWith(FILLED_MARK);
        int separator = payload.lastIndexOf(':');
        String createdAtText = separator < 0 ? payload : payload.substring(separator + 1);
        long createdAtMs = 0L;
        try {
            createdAtMs = Long.parseLong(createdAtText.trim());
        } catch (Throwable ignored) {
        }
        return new ClipMeta(true, isFilled, createdAtMs);
    }

    public static final class ClipMeta {
        private static final ClipMeta EMPTY = new ClipMeta(false, false, 0L);

        public final boolean isManaged;
        public final boolean isFilled;
        public final long createdAtMs;

        private ClipMeta(boolean isManaged, boolean isFilled, long createdAtMs) {
            this.isManaged = isManaged;
            this.isFilled = isFilled;
            this.createdAtMs = createdAtMs;
        }
    }
}
