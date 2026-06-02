package com.example.sms;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.json.JSONArray;
import org.json.JSONObject;

public final class CodeStore {
    public static final String PREFS = "verify_config";

    private static final String KEY_LAST_CODE = "last_code";
    private static final String KEY_LAST_SOURCE = "last_source";
    private static final String KEY_REGEX = "regex";
    private static final String KEY_CODE_SAVED_AT_MS = "code_saved_at_ms";
    private static final String KEY_LAST_SMS_BODY = "last_sms_body";
    private static final String KEY_LAST_SMS_RECEIVED_AT_MS = "last_sms_received_at_ms";
    private static final String KEY_CODE_HISTORY = "code_history";
    private static final String KEY_CLIPBOARD_BRIDGE_ENABLED = "clipboard_bridge_enabled";

    private static final String DEFAULT_REGEX = "(?<!\\d)(\\d{4,8})(?!\\d)";
    private static final long CODE_TTL_MS = 10 * 60_000L; // 10分钟，方便调试
    private static final long HISTORY_TTL_MS = 10 * 60_000L;
    private static final int HISTORY_LIMIT = 20;

    private CodeStore() {
    }

    public static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static void saveCode(Context context, String code, String source) {
        saveCapturedCode(context, code, source, "");
    }

    public static void saveCapturedCode(Context context, String code, String source, String preview) {
        if (TextUtils.isEmpty(code)) {
            return;
        }
        long now = System.currentTimeMillis();
        SharedPreferences.Editor editor = prefs(context).edit()
                .putString(KEY_LAST_CODE, code)
                .putString(KEY_LAST_SOURCE, source == null ? "" : source)
                .putLong(KEY_CODE_SAVED_AT_MS, now)
                .putLong(KEY_LAST_SMS_RECEIVED_AT_MS, now);
        if (!TextUtils.isEmpty(preview)) {
            editor.putString(KEY_LAST_SMS_BODY, buildPreview(preview));
        }
        editor.apply();
        appendHistory(context, code, source == null ? "" : source, now);
        ensurePrefsReadable(context);
    }

    public static void saveSmsReceipt(Context context, String messageBody, String extractedCode) {
        SharedPreferences.Editor editor = prefs(context).edit()
                .putString(KEY_LAST_SMS_BODY, buildPreview(messageBody))
                .putLong(KEY_LAST_SMS_RECEIVED_AT_MS, System.currentTimeMillis());
        if (!TextUtils.isEmpty(extractedCode)) {
            editor.putString(KEY_LAST_CODE, extractedCode)
                    .putString(KEY_LAST_SOURCE, "sms")
                    .putLong(KEY_CODE_SAVED_AT_MS, System.currentTimeMillis());
        } else {
            editor.remove(KEY_LAST_CODE)
                    .remove(KEY_LAST_SOURCE)
                    .remove(KEY_CODE_SAVED_AT_MS);
        }
        editor.apply();
        if (!TextUtils.isEmpty(extractedCode)) {
            appendHistory(context, extractedCode, "sms", System.currentTimeMillis());
        }
        ensurePrefsReadable(context);
    }

    public static String getLastCode(Context context) {
        if (!isCodeFresh(context)) {
            clearLastCode(context);
            return "";
        }
        return prefs(context).getString(KEY_LAST_CODE, "");
    }

    public static String getLastSource(Context context) {
        if (!isCodeFresh(context)) {
            clearLastCode(context);
            return "";
        }
        return prefs(context).getString(KEY_LAST_SOURCE, "");
    }

    public static long getLastCodeSavedAtMs(Context context) {
        return prefs(context).getLong(KEY_CODE_SAVED_AT_MS, 0L);
    }

    public static boolean isCodeFresh(Context context) {
        long savedAt = prefs(context).getLong(KEY_CODE_SAVED_AT_MS, 0L);
        if (savedAt <= 0L) {
            return false;
        }
        long ageMs = System.currentTimeMillis() - savedAt;
        return ageMs >= 0L && ageMs < CODE_TTL_MS;
    }

    public static void clearLastCode(Context context) {
        prefs(context).edit()
                .remove(KEY_LAST_CODE)
                .remove(KEY_LAST_SOURCE)
                .remove(KEY_CODE_SAVED_AT_MS)
                .apply();
        ensurePrefsReadable(context);
    }

    public static String getLastSmsBody(Context context) {
        return prefs(context).getString(KEY_LAST_SMS_BODY, "");
    }

    public static long getLastSmsReceivedAtMs(Context context) {
        return prefs(context).getLong(KEY_LAST_SMS_RECEIVED_AT_MS, 0L);
    }

    public static List<CodeHistoryItem> getCodeHistory(Context context) {
        long now = System.currentTimeMillis();
        List<CodeHistoryItem> items = parseHistory(prefs(context).getString(KEY_CODE_HISTORY, "[]"), now);
        saveHistory(context, items);
        return items;
    }

    public static String getRegex(Context context) {
        String regex = prefs(context).getString(KEY_REGEX, DEFAULT_REGEX);
        return TextUtils.isEmpty(regex) ? DEFAULT_REGEX : regex;
    }

    public static void setRegex(Context context, String regex) {
        prefs(context).edit()
                .putString(KEY_REGEX, TextUtils.isEmpty(regex) ? DEFAULT_REGEX : regex)
                .apply();
        ensurePrefsReadable(context);
    }

    public static boolean isClipboardBridgeEnabled(Context context) {
        return prefs(context).getBoolean(KEY_CLIPBOARD_BRIDGE_ENABLED, true);
    }

    public static void setClipboardBridgeEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_CLIPBOARD_BRIDGE_ENABLED, enabled).apply();
        ensurePrefsReadable(context);
    }

    public static String extractCode(Context context, CharSequence text) {
        return extractCode(text, getRegex(context));
    }

    public static String extractCode(CharSequence text, String regex) {
        if (text == null || text.length() == 0) {
            return "";
        }
        try {
            Matcher matcher = Pattern.compile(regex).matcher(text);
            if (matcher.find()) {
                return matcher.groupCount() >= 1 ? matcher.group(1) : matcher.group();
            }
        } catch (Throwable ignored) {
            Matcher fallback = Pattern.compile(DEFAULT_REGEX).matcher(text);
            if (fallback.find()) {
                return fallback.group(1);
            }
        }
        return "";
    }

    public static void ensurePrefsReadable(Context context) {
        try {
            File prefsDir = new File(context.getApplicationInfo().dataDir, "shared_prefs");
            File prefsFile = new File(prefsDir, PREFS + ".xml");
            prefsDir.setReadable(true, false);
            prefsDir.setExecutable(true, false);
            prefsFile.setReadable(true, false);
        } catch (Throwable ignored) {
        }
    }

    private static void appendHistory(Context context, String code, String source, long savedAtMs) {
        List<CodeHistoryItem> items = parseHistory(prefs(context).getString(KEY_CODE_HISTORY, "[]"), savedAtMs);
        if (!items.isEmpty()) {
            CodeHistoryItem first = items.get(0);
            if (TextUtils.equals(first.code, code) && savedAtMs - first.savedAtMs < 5_000L) {
                saveHistory(context, items);
                return;
            }
        }
        items.add(0, new CodeHistoryItem(code, source, savedAtMs));
        while (items.size() > HISTORY_LIMIT) {
            items.remove(items.size() - 1);
        }
        saveHistory(context, items);
    }

    private static List<CodeHistoryItem> parseHistory(String json, long now) {
        List<CodeHistoryItem> items = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(TextUtils.isEmpty(json) ? "[]" : json);
            for (int i = 0; i < array.length() && items.size() < HISTORY_LIMIT; i++) {
                JSONObject object = array.optJSONObject(i);
                if (object == null) {
                    continue;
                }
                String code = object.optString("code", "");
                long savedAtMs = object.optLong("savedAtMs", 0L);
                if (TextUtils.isEmpty(code) || savedAtMs <= 0L || now - savedAtMs > HISTORY_TTL_MS) {
                    continue;
                }
                items.add(new CodeHistoryItem(
                        code,
                        object.optString("source", ""),
                        savedAtMs
                ));
            }
        } catch (Throwable ignored) {
        }
        return items;
    }

    private static void saveHistory(Context context, List<CodeHistoryItem> items) {
        JSONArray array = new JSONArray();
        try {
            for (CodeHistoryItem item : items) {
                JSONObject object = new JSONObject();
                object.put("code", item.code);
                object.put("source", item.source);
                object.put("savedAtMs", item.savedAtMs);
                array.put(object);
            }
        } catch (Throwable ignored) {
        }
        prefs(context).edit().putString(KEY_CODE_HISTORY, array.toString()).apply();
    }

    private static String buildPreview(String text) {
        if (TextUtils.isEmpty(text)) {
            return "";
        }
        String normalized = text.replace('\n', ' ').trim();
        return normalized.length() > 120 ? normalized.substring(0, 120) + "..." : normalized;
    }

    public static final class CodeHistoryItem {
        private final String code;
        private final String source;
        private final long savedAtMs;

        private CodeHistoryItem(String code, String source, long savedAtMs) {
            this.code = code;
            this.source = source;
            this.savedAtMs = savedAtMs;
        }

        public String getCode() {
            return code;
        }

        public String getSource() {
            return source;
        }

        public long getSavedAtMs() {
            return savedAtMs;
        }
    }
}
