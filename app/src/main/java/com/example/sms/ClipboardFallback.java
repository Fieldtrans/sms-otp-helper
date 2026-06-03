package com.example.sms;

import android.content.Context;

public final class ClipboardFallback {
    private ClipboardFallback() {
    }

    public static void write(Context context, String code) {
        ManagedClipboardBridge.write(context, code);
    }
}
