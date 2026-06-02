package com.example.sms;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.text.TextUtils;

public class CodeCapturedReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !Actions.ACTION_CODE_CAPTURED.equals(intent.getAction())) {
            return;
        }
        String code = intent.getStringExtra(Actions.EXTRA_CODE);
        if (TextUtils.isEmpty(code)) {
            return;
        }
        String source = intent.getStringExtra(Actions.EXTRA_SOURCE);
        String preview = intent.getStringExtra(Actions.EXTRA_PREVIEW);
        CodeStore.saveCapturedCode(context, code, source == null ? "notification" : source, preview == null ? "" : preview);

        Intent updateIntent = new Intent(Actions.ACTION_SMS_STATUS_UPDATED);
        updateIntent.setPackage(context.getPackageName());
        context.sendBroadcast(updateIntent);
    }
}
