package com.example.sms;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.text.TextUtils;

public class CodeCapturedReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) {
            return;
        }
        if (Actions.ACTION_CODE_FILLED.equals(intent.getAction())) {
            CodeStore.clearPendingCode(context);
            sendStatusUpdate(context);
            return;
        }
        if (Actions.ACTION_RECEIVE_DIAGNOSTIC.equals(intent.getAction())) {
            String source = intent.getStringExtra(Actions.EXTRA_SOURCE);
            String preview = intent.getStringExtra(Actions.EXTRA_PREVIEW);
            String packageName = intent.getStringExtra(Actions.EXTRA_PACKAGE);
            String code = intent.getStringExtra(Actions.EXTRA_CODE);
            CodeStore.saveReceiveDiagnostic(context, source == null ? "notification" : source, packageName == null ? "" : packageName, preview == null ? "" : preview, code == null ? "" : code);
            sendStatusUpdate(context);
            return;
        }
        if (!Actions.ACTION_CODE_CAPTURED.equals(intent.getAction())) {
            return;
        }
        String code = intent.getStringExtra(Actions.EXTRA_CODE);
        if (TextUtils.isEmpty(code)) {
            return;
        }
        String source = intent.getStringExtra(Actions.EXTRA_SOURCE);
        String preview = intent.getStringExtra(Actions.EXTRA_PREVIEW);
        String packageName = intent.getStringExtra(Actions.EXTRA_PACKAGE);
        CodeStore.saveCapturedCode(context, code, source == null ? "notification" : source, preview == null ? "" : preview);
        CodeStore.saveReceiveDiagnostic(context, source == null ? "notification" : source, packageName == null ? "" : packageName, preview == null ? "" : preview, code);

        sendStatusUpdate(context);
    }

    private void sendStatusUpdate(Context context) {
        Intent updateIntent = new Intent(Actions.ACTION_SMS_STATUS_UPDATED);
        updateIntent.setPackage(context.getPackageName());
        context.sendBroadcast(updateIntent);
    }
}
