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
    private static final String KEY_PENDING_CODE = "pending_code";
    private static final String KEY_PENDING_CODE_SAVED_AT_MS = "pending_code_saved_at_ms";
    private static final String KEY_DIAG_ENTRY = "diag_entry";
    private static final String KEY_DIAG_PACKAGE = "diag_package";
    private static final String KEY_DIAG_PREVIEW = "diag_preview";
    private static final String KEY_DIAG_CODE = "diag_code";
    private static final String KEY_DIAG_AT_MS = "diag_at_ms";

    private static final String LEGACY_DEFAULT_REGEX = "(?<!\\d)(\\d{4,8})(?!\\d)";
    private static final String DEFAULT_REGEX =
            "(?:验证码|校验码|动态码|短信码|确认码|安全码|verification\\s*code|verify\\s*code|otp|code)\\D{0,20}(\\d{4,8})"
                    + "|(\\d{4,8})\\D{0,20}(?:验证码|校验码|动态码|短信码|确认码|安全码)";
    private static final Pattern DEFAULT_CODE_PATTERN = Pattern.compile(DEFAULT_REGEX, Pattern.CASE_INSENSITIVE);
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
        editor.putString(KEY_PENDING_CODE, code)
                .putLong(KEY_PENDING_CODE_SAVED_AT_MS, now);
        editor.apply();
        appendHistory(context, code, source == null ? "" : source, now);
        saveReceiveDiagnostic(context, source == null ? "notification" : source, "", preview, code);
        ensurePrefsReadable(context);
    }

    public static void saveSmsReceipt(Context context, String messageBody, String extractedCode) {
        long now = System.currentTimeMillis();
        SharedPreferences.Editor editor = prefs(context).edit()
                .putString(KEY_LAST_SMS_BODY, buildPreview(messageBody))
                .putLong(KEY_LAST_SMS_RECEIVED_AT_MS, now);
        if (!TextUtils.isEmpty(extractedCode)) {
            editor.putString(KEY_LAST_CODE, extractedCode)
                    .putString(KEY_LAST_SOURCE, "sms")
                    .putLong(KEY_CODE_SAVED_AT_MS, now)
                    .putString(KEY_PENDING_CODE, extractedCode)
                    .putLong(KEY_PENDING_CODE_SAVED_AT_MS, now);
        } else {
            editor.remove(KEY_LAST_CODE)
                    .remove(KEY_LAST_SOURCE)
                    .remove(KEY_CODE_SAVED_AT_MS)
                    .remove(KEY_PENDING_CODE)
                    .remove(KEY_PENDING_CODE_SAVED_AT_MS);
        }
        editor.apply();
        if (!TextUtils.isEmpty(extractedCode)) {
            appendHistory(context, extractedCode, "sms", now);
        }
        saveReceiveDiagnostic(context, "sms", "system", messageBody, extractedCode);
        ensurePrefsReadable(context);
    }

    public static void saveReceiveDiagnostic(Context context, String entry, String packageName, String preview, String code) {
        prefs(context).edit()
                .putString(KEY_DIAG_ENTRY, entry == null ? "" : entry)
                .putString(KEY_DIAG_PACKAGE, packageName == null ? "" : packageName)
                .putString(KEY_DIAG_PREVIEW, buildPreview(preview))
                .putString(KEY_DIAG_CODE, code == null ? "" : code)
                .putLong(KEY_DIAG_AT_MS, System.currentTimeMillis())
                .apply();
        ensurePrefsReadable(context);
    }

    public static ReceiveDiagnostic getReceiveDiagnostic(Context context) {
        SharedPreferences preferences = prefs(context);
        return new ReceiveDiagnostic(
                preferences.getString(KEY_DIAG_ENTRY, ""),
                preferences.getString(KEY_DIAG_PACKAGE, ""),
                preferences.getString(KEY_DIAG_PREVIEW, ""),
                preferences.getString(KEY_DIAG_CODE, ""),
                preferences.getLong(KEY_DIAG_AT_MS, 0L)
        );
    }

    public static PendingCode getPendingCode(Context context) {
        SharedPreferences preferences = prefs(context);
        String code = preferences.getString(KEY_PENDING_CODE, "");
        long savedAtMs = preferences.getLong(KEY_PENDING_CODE_SAVED_AT_MS, 0L);
        if (TextUtils.isEmpty(code) || savedAtMs <= 0L || System.currentTimeMillis() - savedAtMs > 90_000L) {
            clearPendingCode(context);
            return PendingCode.EMPTY;
        }
        return new PendingCode(code, savedAtMs);
    }

    public static void clearPendingCode(Context context) {
        prefs(context).edit()
                .remove(KEY_PENDING_CODE)
                .remove(KEY_PENDING_CODE_SAVED_AT_MS)
                .apply();
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
        if (isDefaultRegex(regex)) {
            return extractDefaultCode(text);
        }
        try {
            Matcher matcher = Pattern.compile(regex).matcher(text);
            if (matcher.find()) {
                return firstMatchedGroup(matcher);
            }
        } catch (Throwable ignored) {
            return extractDefaultCode(text);
        }
        return "";
    }

    private static boolean isDefaultRegex(String regex) {
        return regex == null || regex.length() == 0
                || DEFAULT_REGEX.equals(regex)
                || LEGACY_DEFAULT_REGEX.equals(regex);
    }

    private static String extractDefaultCode(CharSequence text) {
        Matcher matcher = DEFAULT_CODE_PATTERN.matcher(text);
        if (!matcher.find()) {
            return "";
        }
        return firstMatchedGroup(matcher);
    }

    private static String firstMatchedGroup(Matcher matcher) {
        for (int i = 1; i <= matcher.groupCount(); i++) {
            String group = matcher.group(i);
            if (group != null && group.length() > 0) {
                return group;
            }
        }
        return matcher.group();
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

    public static final class PendingCode {
        private static final PendingCode EMPTY = new PendingCode("", 0L);

        private final String code;
        private final long savedAtMs;

        private PendingCode(String code, long savedAtMs) {
            this.code = code;
            this.savedAtMs = savedAtMs;
        }

        public String getCode() {
            return code;
        }

        public long getSavedAtMs() {
            return savedAtMs;
        }
    }

    public static final class ReceiveDiagnostic {
        private final String entry;
        private final String packageName;
        private final String preview;
        private final String code;
        private final long receivedAtMs;

        private ReceiveDiagnostic(String entry, String packageName, String preview, String code, long receivedAtMs) {
            this.entry = entry;
            this.packageName = packageName;
            this.preview = preview;
            this.code = code;
            this.receivedAtMs = receivedAtMs;
        }

        public String getEntry() {
            return entry;
        }

        public String getPackageName() {
            return packageName;
        }

        public String getPreview() {
            return preview;
        }

        public String getCode() {
            return code;
        }

        public long getReceivedAtMs() {
            return receivedAtMs;
        }
    }
}
