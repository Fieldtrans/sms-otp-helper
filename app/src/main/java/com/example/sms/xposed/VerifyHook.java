package com.example.sms.xposed;

import android.app.Application;
import android.app.Notification;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.inputmethodservice.InputMethodService;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;

import com.example.sms.Actions;
import com.example.sms.CodeStore;
import com.example.sms.LspModuleState;

import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class VerifyHook implements IXposedHookLoadPackage {
    private static final String SELF_PACKAGE = "com.example.sms";
    private static final String CLIP_LABEL_PREFIX = "CodeDelayLSP:";
    private static final String CLIP_FILLED_MARK = "filled:";
    private static final Pattern OTP_PATTERN = Pattern.compile("(?<!\\d)(\\d{4,8})(?!\\d)");
    private static final long DEFAULT_DELAY_MS = 1200L;
    private static final long CLIPBOARD_CHANGE_DELAY_MS = 250L;
    private static final long DUPLICATE_WINDOW_MS = 12_000L;
    private static final long CLIP_TTL_MS = 90_000L;
    private static final Set<String> SMS_PACKAGES = new HashSet<>(Arrays.asList(
            "com.android.mms",
            "com.google.android.apps.messaging",
            "com.miui.smsextra",
            "com.miui.contentextension"
    ));

    private static volatile XSharedPreferences modulePrefs;
    private static WeakReference<Context> processContext = new WeakReference<>(null);
    private static final Map<InputMethodService, ClipboardManager.OnPrimaryClipChangedListener> imeClipboardListeners =
            new WeakHashMap<>();
    private static String lastCopiedCode = "";
    private static long lastCopiedAt = 0L;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (SELF_PACKAGE.equals(lpparam.packageName)) {
            hookSelfCheck();
            return;
        }

        hookApplicationAttach();

        if (isSmsPackage(lpparam.packageName)) {
            hookNotificationDispatch(lpparam);
            XposedBridge.log("SMS LSP sms scope active: " + lpparam.packageName);
            return;
        }

        hookInputMethodService(lpparam.packageName);
    }

    private void hookApplicationAttach() {
        XposedHelpers.findAndHookMethod(
                Application.class,
                "attach",
                Context.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Context context = (Context) param.args[0];
                        processContext = new WeakReference<>(context.getApplicationContext());
                        reloadPrefs();
                    }
                }
        );
    }

    private void hookSelfCheck() {
        try {
            XposedHelpers.findAndHookMethod(
                    LspModuleState.class,
                    "isModuleActive",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            param.setResult(true);
                        }
                    }
            );
        } catch (Throwable t) {
            XposedBridge.log("SMS LSP self check hook failed: " + t);
        }
    }

    private void hookInputMethodService(String packageName) {
        try {
            XposedHelpers.findAndHookMethod(
                    InputMethodService.class,
                    "onCreate",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            registerImeClipboardListener((InputMethodService) param.thisObject);
                        }
                    }
            );
            XposedHelpers.findAndHookMethod(
                    InputMethodService.class,
                    "onDestroy",
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            unregisterImeClipboardListener((InputMethodService) param.thisObject);
                        }
                    }
            );
            XposedHelpers.findAndHookMethod(
                    InputMethodService.class,
                    "onStartInputView",
                    EditorInfo.class,
                    boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            commitOtpFromClipboard((InputMethodService) param.thisObject, DEFAULT_DELAY_MS);
                        }
                    }
            );
            XposedHelpers.findAndHookMethod(
                    InputMethodService.class,
                    "onStartInput",
                    EditorInfo.class,
                    boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            commitOtpFromClipboard((InputMethodService) param.thisObject, DEFAULT_DELAY_MS);
                        }
                    }
            );
            XposedHelpers.findAndHookMethod(
                    InputMethodService.class,
                    "onWindowShown",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            commitOtpFromClipboard((InputMethodService) param.thisObject, DEFAULT_DELAY_MS);
                        }
                    }
            );
            XposedBridge.log("SMS LSP ime scope active: " + packageName);
        } catch (Throwable t) {
            XposedBridge.log("SMS LSP hook ime failed in " + packageName + ": " + t);
        }
    }

    private void registerImeClipboardListener(InputMethodService service) {
        if (service == null) {
            return;
        }
        synchronized (imeClipboardListeners) {
            if (imeClipboardListeners.containsKey(service)) {
                return;
            }
            Context context = service.getApplicationContext();
            if (context == null) {
                return;
            }
            ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard == null) {
                return;
            }
            ClipboardManager.OnPrimaryClipChangedListener listener = () -> {
                commitOtpFromClipboard(service, CLIPBOARD_CHANGE_DELAY_MS);
                commitOtpFromClipboard(service, DEFAULT_DELAY_MS);
            };
            try {
                clipboard.addPrimaryClipChangedListener(listener);
                imeClipboardListeners.put(service, listener);
                XposedBridge.log("SMS LSP ime clipboard listener registered");
            } catch (Throwable t) {
                XposedBridge.log("SMS LSP register ime clipboard listener failed: " + t);
            }
        }
    }

    private void unregisterImeClipboardListener(InputMethodService service) {
        if (service == null) {
            return;
        }
        synchronized (imeClipboardListeners) {
            ClipboardManager.OnPrimaryClipChangedListener listener = imeClipboardListeners.remove(service);
            if (listener == null) {
                return;
            }
            try {
                Context context = service.getApplicationContext();
                ClipboardManager clipboard = context == null
                        ? null
                        : (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
                if (clipboard != null) {
                    clipboard.removePrimaryClipChangedListener(listener);
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private void hookNotificationDispatch(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            Class<?> notificationManagerClass = XposedHelpers.findClass(
                    "android.app.NotificationManager",
                    lpparam.classLoader
            );
            XC_MethodHook notifyHook = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    Notification notification = findNotificationArg(param.args);
                    if (notification == null || notification.extras == null) {
                        return;
                    }
                    Context context = extractNotificationContext(param.thisObject);
                    if (context == null) {
                        return;
                    }
                    maybeCopyOtpFromNotification(context, notification);
                }
            };
            XposedBridge.hookAllMethods(notificationManagerClass, "notify", notifyHook);
            XposedBridge.hookAllMethods(notificationManagerClass, "notifyAsUser", notifyHook);
        } catch (Throwable t) {
            XposedBridge.log("SMS LSP hook notification failed: " + t);
        }
    }

    private void maybeCopyOtpFromNotification(Context context, Notification notification) {
        String text = collectNotificationText(notification.extras);
        String code = extractOtp(text);
        notifyReceiveDiagnostic(context, code, text);
        if (!isClipboardBridgeEnabled()) {
            return;
        }
        if (TextUtils.isEmpty(code) || isDuplicateCode(code)) {
            return;
        }
        try {
            ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard == null) {
                return;
            }
            String label = buildClipLabel(System.currentTimeMillis());
            clipboard.setPrimaryClip(ClipData.newPlainText(label, code));
            notifyModuleApp(context, code, text);
            lastCopiedCode = code;
            lastCopiedAt = SystemClock.elapsedRealtime();
            XposedBridge.log("SMS LSP copied otp to managed clipboard: " + code);
        } catch (Throwable t) {
            XposedBridge.log("SMS LSP copy clipboard failed: " + t);
        }
    }

    private void notifyReceiveDiagnostic(Context context, String code, String preview) {
        try {
            Intent intent = new Intent(Actions.ACTION_RECEIVE_DIAGNOSTIC);
            intent.setClassName(SELF_PACKAGE, "com.example.sms.CodeCapturedReceiver");
            intent.putExtra(Actions.EXTRA_CODE, code == null ? "" : code);
            intent.putExtra(Actions.EXTRA_SOURCE, "notification-hook");
            intent.putExtra(Actions.EXTRA_PREVIEW, preview == null ? "" : preview);
            intent.putExtra(Actions.EXTRA_PACKAGE, context.getPackageName());
            context.sendBroadcast(intent);
        } catch (Throwable t) {
            XposedBridge.log("SMS LSP notify receive diagnostic failed: " + t);
        }
    }

    private void notifyModuleApp(Context context, String code, String preview) {
        try {
            Intent intent = new Intent(Actions.ACTION_CODE_CAPTURED);
            intent.setClassName(SELF_PACKAGE, "com.example.sms.CodeCapturedReceiver");
            intent.putExtra(Actions.EXTRA_CODE, code);
            intent.putExtra(Actions.EXTRA_SOURCE, "notification");
            intent.putExtra(Actions.EXTRA_PREVIEW, preview == null ? "" : preview);
            intent.putExtra(Actions.EXTRA_PACKAGE, context.getPackageName());
            context.sendBroadcast(intent);
        } catch (Throwable t) {
            XposedBridge.log("SMS LSP notify module app failed: " + t);
        }
    }

    private void commitOtpFromClipboard(InputMethodService service, long delayMs) {
        if (service == null || !isClipboardBridgeEnabled()) {
            return;
        }
        Context context = service.getApplicationContext();
        if (context == null) {
            return;
        }
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            InputConnection inputConnection = service.getCurrentInputConnection();
            if (inputConnection == null) {
                return;
            }
            String clipboardCode = readOtpFromClipboard(context);
            if (TextUtils.isEmpty(clipboardCode)) {
                return;
            }
            if (inputConnection.commitText(clipboardCode, 1)) {
                markManagedClipboardFilled(context, clipboardCode);
                notifyCodeFilled(context, clipboardCode);
            }
        }, Math.max(0L, delayMs));
    }

    private void notifyCodeFilled(Context context, String code) {
        try {
            Intent intent = new Intent(Actions.ACTION_CODE_FILLED);
            intent.setClassName(SELF_PACKAGE, "com.example.sms.CodeCapturedReceiver");
            intent.putExtra(Actions.EXTRA_CODE, code == null ? "" : code);
            context.sendBroadcast(intent);
        } catch (Throwable t) {
            XposedBridge.log("SMS LSP notify code filled failed: " + t);
        }
    }

    private String readOtpFromClipboard(Context context) {
        try {
            ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard == null || !clipboard.hasPrimaryClip()) {
                return "";
            }
            ClipMeta meta = parseClipMeta(clipboard.getPrimaryClipDescription());
            if (meta.isFilled) {
                return "";
            }
            if (meta.isManaged
                    && meta.createdAtMs > 0L
                    && System.currentTimeMillis() - meta.createdAtMs > CLIP_TTL_MS) {
                clearManagedClipboard(context);
                return "";
            }
            ClipData data = clipboard.getPrimaryClip();
            if (data == null || data.getItemCount() == 0) {
                return "";
            }
            CharSequence text = data.getItemAt(0).coerceToText(context);
            return meta.isManaged ? extractOtp(text) : "";
        } catch (Throwable ignored) {
            return "";
        }
    }

    private void clearManagedClipboard(Context context) {
        try {
            ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard == null || !clipboard.hasPrimaryClip()) {
                return;
            }
            ClipMeta meta = parseClipMeta(clipboard.getPrimaryClipDescription());
            if (!meta.isManaged) {
                return;
            }
            clipboard.setPrimaryClip(ClipData.newPlainText("", ""));
        } catch (Throwable ignored) {
        }
    }

    private void markManagedClipboardFilled(Context context, String code) {
        try {
            ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard == null || !clipboard.hasPrimaryClip()) {
                return;
            }
            ClipMeta meta = parseClipMeta(clipboard.getPrimaryClipDescription());
            if (!meta.isManaged) {
                return;
            }
            long now = System.currentTimeMillis();
            clipboard.setPrimaryClip(ClipData.newPlainText(buildFilledClipLabel(now), code == null ? "" : code));
            new Handler(Looper.getMainLooper()).postDelayed(() -> clearManagedClipboard(context), CLIP_TTL_MS);
        } catch (Throwable ignored) {
        }
    }

    private String collectNotificationText(Bundle extras) {
        StringBuilder builder = new StringBuilder();
        append(builder, extras.getCharSequence(Notification.EXTRA_TITLE));
        append(builder, extras.getCharSequence(Notification.EXTRA_TEXT));
        append(builder, extras.getCharSequence(Notification.EXTRA_BIG_TEXT));
        CharSequence[] lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
        if (lines != null) {
            for (CharSequence line : lines) {
                append(builder, line);
            }
        }
        return builder.toString().trim();
    }

    private void append(StringBuilder builder, CharSequence value) {
        if (!TextUtils.isEmpty(value)) {
            builder.append(value).append(' ');
        }
    }

    private String extractOtp(CharSequence text) {
        if (TextUtils.isEmpty(text)) {
            return "";
        }
        Matcher matcher = OTP_PATTERN.matcher(text);
        if (!matcher.find()) {
            return "";
        }
        return matcher.groupCount() >= 1 ? matcher.group(1) : matcher.group();
    }

    private boolean isDuplicateCode(String code) {
        if (!TextUtils.equals(lastCopiedCode, code)) {
            return false;
        }
        return SystemClock.elapsedRealtime() - lastCopiedAt < DUPLICATE_WINDOW_MS;
    }

    private Notification findNotificationArg(Object[] args) {
        if (args == null) {
            return null;
        }
        for (Object arg : args) {
            if (arg instanceof Notification) {
                return (Notification) arg;
            }
        }
        return null;
    }

    private Context extractNotificationContext(Object manager) {
        if (manager == null) {
            return processContext.get();
        }
        try {
            Object context = XposedHelpers.getObjectField(manager, "mContext");
            if (context instanceof Context) {
                return ((Context) context).getApplicationContext();
            }
        } catch (Throwable ignored) {
        }
        return processContext.get();
    }

    private boolean isSmsPackage(String packageName) {
        if (TextUtils.isEmpty(packageName)) {
            return false;
        }
        if (SMS_PACKAGES.contains(packageName)) {
            return true;
        }
        return packageName.contains(".mms")
                || packageName.contains("messaging")
                || packageName.contains("message");
    }

    private void reloadPrefs() {
        try {
            if (modulePrefs == null) {
                modulePrefs = new XSharedPreferences(SELF_PACKAGE, CodeStore.PREFS);
                modulePrefs.makeWorldReadable();
            } else {
                modulePrefs.reload();
            }
        } catch (Throwable t) {
            XposedBridge.log("SMS LSP reload prefs failed: " + t);
        }
    }

    private boolean isClipboardBridgeEnabled() {
        reloadPrefs();
        return modulePrefs == null || modulePrefs.getBoolean("clipboard_bridge_enabled", true);
    }

    private String buildClipLabel(long createdAtMs) {
        return CLIP_LABEL_PREFIX + createdAtMs;
    }

    private String buildFilledClipLabel(long createdAtMs) {
        return CLIP_LABEL_PREFIX + CLIP_FILLED_MARK + createdAtMs;
    }

    private ClipMeta parseClipMeta(ClipDescription description) {
        if (description == null || description.getLabel() == null) {
            return ClipMeta.EMPTY;
        }
        String label = description.getLabel().toString();
        if (!label.startsWith(CLIP_LABEL_PREFIX)) {
            return ClipMeta.EMPTY;
        }
        String payload = label.substring(CLIP_LABEL_PREFIX.length());
        boolean isFilled = payload.startsWith(CLIP_FILLED_MARK);
        int separator = payload.lastIndexOf(':');
        String createdAtText = separator < 0 ? payload : payload.substring(separator + 1);
        long createdAtMs = 0L;
        try {
            createdAtMs = Long.parseLong(createdAtText.trim());
        } catch (Throwable ignored) {
        }
        return new ClipMeta(true, isFilled, createdAtMs);
    }

    private static final class ClipMeta {
        private static final ClipMeta EMPTY = new ClipMeta(false, false, 0L);

        private final boolean isManaged;
        private final boolean isFilled;
        private final long createdAtMs;

        private ClipMeta(boolean isManaged, boolean isFilled, long createdAtMs) {
            this.isManaged = isManaged;
            this.isFilled = isFilled;
            this.createdAtMs = createdAtMs;
        }
    }
}
