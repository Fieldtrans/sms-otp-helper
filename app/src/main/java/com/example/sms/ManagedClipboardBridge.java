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
    private static final String SEMI_AUTO_MARK = "semi:";
    private static final String FULL_AUTO_MARK = "full";
    public static final long TTL_MS = 90_000L;

    private ManagedClipboardBridge() {
    }

    public static void write(Context context, String code) {
        if (context == null || code == null || code.isEmpty()) {
            return;
        }
        write(
                context,
                code,
                CodeStore.isSemiAutoEnabled(context),
                CodeStore.getSemiAutoKeepTailLength(context)
        );
    }

    public static void write(Context context, String code, boolean semiAutoEnabled, int keepTailLength) {
        if (context == null || code == null || code.isEmpty()) {
            return;
        }
        try {
            ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard == null) {
                return;
            }
            long createdAtMs = System.currentTimeMillis();
            clipboard.setPrimaryClip(ClipData.newPlainText(
                    buildLabel(createdAtMs, semiAutoEnabled, keepTailLength),
                    code
            ));
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

    public static String buildLabel(long createdAtMs, boolean semiAutoEnabled, int keepTailLength) {
        return LABEL_PREFIX + buildFillModeLabel(semiAutoEnabled, keepTailLength) + ":" + createdAtMs;
    }

    public static String buildFilledLabel(long createdAtMs) {
        return LABEL_PREFIX + FILLED_MARK + createdAtMs;
    }

    public static String buildFilledLabel(long createdAtMs, boolean semiAutoEnabled, int keepTailLength) {
        return LABEL_PREFIX + FILLED_MARK + buildFillModeLabel(semiAutoEnabled, keepTailLength) + ":" + createdAtMs;
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
        String metadata = isFilled ? payload.substring(FILLED_MARK.length()) : payload;
        int separator = payload.lastIndexOf(':');
        String createdAtText = separator < 0 ? payload : payload.substring(separator + 1);
        long createdAtMs = 0L;
        try {
            createdAtMs = Long.parseLong(createdAtText.trim());
        } catch (Throwable ignored) {
        }
        FillMode fillMode = parseFillMode(metadata);
        return new ClipMeta(
                true,
                isFilled,
                createdAtMs,
                fillMode.hasConfig,
                fillMode.semiAutoEnabled,
                fillMode.keepTailLength
        );
    }

    private static String buildFillModeLabel(boolean semiAutoEnabled, int keepTailLength) {
        if (!semiAutoEnabled) {
            return FULL_AUTO_MARK;
        }
        return SEMI_AUTO_MARK + clampKeepTailLength(keepTailLength);
    }

    private static FillMode parseFillMode(String metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return FillMode.EMPTY;
        }
        if (metadata.equals(FULL_AUTO_MARK) || metadata.startsWith(FULL_AUTO_MARK + ":")) {
            return new FillMode(true, false, 0);
        }
        if (metadata.startsWith(SEMI_AUTO_MARK)) {
            int valueStart = SEMI_AUTO_MARK.length();
            int valueEnd = metadata.indexOf(':', valueStart);
            String value = valueEnd < 0 ? metadata.substring(valueStart) : metadata.substring(valueStart, valueEnd);
            try {
                return new FillMode(true, true, clampKeepTailLength(Integer.parseInt(value.trim())));
            } catch (Throwable ignored) {
                return new FillMode(true, true, 2);
            }
        }
        return FillMode.EMPTY;
    }

    private static int clampKeepTailLength(int keepTailLength) {
        return Math.max(0, Math.min(8, keepTailLength));
    }

    public static final class ClipMeta {
        private static final ClipMeta EMPTY = new ClipMeta(false, false, 0L, false, false, 0);

        public final boolean isManaged;
        public final boolean isFilled;
        public final long createdAtMs;
        public final boolean hasFillConfig;
        public final boolean semiAutoEnabled;
        public final int keepTailLength;

        private ClipMeta(
                boolean isManaged,
                boolean isFilled,
                long createdAtMs,
                boolean hasFillConfig,
                boolean semiAutoEnabled,
                int keepTailLength
        ) {
            this.isManaged = isManaged;
            this.isFilled = isFilled;
            this.createdAtMs = createdAtMs;
            this.hasFillConfig = hasFillConfig;
            this.semiAutoEnabled = semiAutoEnabled;
            this.keepTailLength = keepTailLength;
        }
    }

    private static final class FillMode {
        private static final FillMode EMPTY = new FillMode(false, false, 0);

        private final boolean hasConfig;
        private final boolean semiAutoEnabled;
        private final int keepTailLength;

        private FillMode(boolean hasConfig, boolean semiAutoEnabled, int keepTailLength) {
            this.hasConfig = hasConfig;
            this.semiAutoEnabled = semiAutoEnabled;
            this.keepTailLength = keepTailLength;
        }
    }
}
