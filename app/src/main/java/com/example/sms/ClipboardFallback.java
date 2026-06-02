package com.example.sms;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;

public final class ClipboardFallback {
    private ClipboardFallback() {
    }

    public static void write(Context context, String code) {
        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null || code == null || code.isEmpty()) {
            return;
        }
        clipboard.setPrimaryClip(ClipData.newPlainText("CodeDelayLSP:" + System.currentTimeMillis(), code));
    }
}
