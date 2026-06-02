package com.example.sms;

import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.provider.Telephony;
import android.telephony.SmsMessage;
import android.text.TextUtils;

public class OtpSmsReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Telephony.Sms.Intents.SMS_RECEIVED_ACTION.equals(intent.getAction())) {
            return;
        }

        StringBuilder body = new StringBuilder();
        SmsMessage[] messages = Telephony.Sms.Intents.getMessagesFromIntent(intent);
        if (messages != null) {
            for (SmsMessage message : messages) {
                if (message != null && !TextUtils.isEmpty(message.getMessageBody())) {
                    body.append(message.getMessageBody()).append(' ');
                }
            }
        }

        String smsBody = body.toString().trim();
        String code = CodeStore.extractCode(context, smsBody);

        // 保存到 SharedPreferences
        CodeStore.saveSmsReceipt(context, smsBody, code);

        // 如果提取到验证码，复制到剪贴板
        if (!TextUtils.isEmpty(code)) {
            try {
                ClipboardManager clipboard = (ClipboardManager)
                    context.getSystemService(Context.CLIPBOARD_SERVICE);
                if (clipboard != null) {
                    ClipData clip = ClipData.newPlainText(
                        "CodeDelayLSP:" + System.currentTimeMillis(),
                        code
                    );
                    clipboard.setPrimaryClip(clip);
                }
            } catch (Throwable ignored) {
            }
        }

        // 通知 UI 刷新
        Intent updateIntent = new Intent(Actions.ACTION_SMS_STATUS_UPDATED);
        updateIntent.setPackage(context.getPackageName());
        context.sendBroadcast(updateIntent);
    }
}
