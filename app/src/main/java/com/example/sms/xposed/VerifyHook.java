package com.example.sms.xposed;

import android.app.Application;
import android.app.Notification;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.inputmethodservice.InputMethodService;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.text.TextUtils;
import android.view.inputmethod.InputMethod;
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
import java.util.List;

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
    private static final String CLIP_SEMI_AUTO_MARK = "semi:";
    private static final String CLIP_FULL_AUTO_MARK = "full";
    private static final long DEFAULT_DELAY_MS = 1200L;
    private static final long CLIPBOARD_CHANGE_DELAY_MS = 250L;
    private static final long DUPLICATE_WINDOW_MS = 12_000L;
    private static final long FILL_DUPLICATE_WINDOW_MS = 60_000L;
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
    private static final Map<InputMethodService, EditorInfo> imeEditorInfos = new WeakHashMap<>();
    private static final Set<String> hookedImePackages = new HashSet<>();
    private static String lastCopiedCode = "";
    private static long lastCopiedAt = 0L;
    private static String lastFilledKey = "";
    private static long lastFilledAt = 0L;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (SELF_PACKAGE.equals(lpparam.packageName)) {
            hookSelfCheck();
            return;
        }

        if (isSmsPackage(lpparam.packageName)) {
            hookApplicationAttach(lpparam.packageName, false);
            hookNotificationDispatch(lpparam);
            XposedBridge.log("SMS LSP sms scope active: " + lpparam.packageName);
            return;
        }

        hookApplicationAttach(lpparam.packageName, true);
    }

    private void hookApplicationAttach(String packageName, boolean hookImeAfterAttach) {
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
                        if (hookImeAfterAttach && isInputMethodPackage(context, packageName)) {
                            hookInputMethodService(packageName);
                        }
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
        synchronized (hookedImePackages) {
            if (!hookedImePackages.add(packageName)) {
                return;
            }
        }
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
                            InputMethodService service = (InputMethodService) param.thisObject;
                            unregisterImeClipboardListener(service);
                            synchronized (imeEditorInfos) {
                                imeEditorInfos.remove(service);
                            }
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
                            InputMethodService service = (InputMethodService) param.thisObject;
                            rememberImeEditorInfo(service, (EditorInfo) param.args[0]);
                            commitOtpFromClipboard(service, DEFAULT_DELAY_MS);
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
                            InputMethodService service = (InputMethodService) param.thisObject;
                            rememberImeEditorInfo(service, (EditorInfo) param.args[0]);
                            commitOtpFromClipboard(service, DEFAULT_DELAY_MS);
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

    private boolean isInputMethodPackage(Context context, String packageName) {
        if (context == null || TextUtils.isEmpty(packageName)) {
            return false;
        }
        try {
            Intent intent = new Intent(InputMethod.SERVICE_INTERFACE);
            intent.setPackage(packageName);
            List<ResolveInfo> services = context.getPackageManager().queryIntentServices(intent, 0);
            if (services == null || services.isEmpty()) {
                return false;
            }
            for (ResolveInfo service : services) {
                if (service != null
                        && service.serviceInfo != null
                        && "android.permission.BIND_INPUT_METHOD".equals(service.serviceInfo.permission)) {
                    XposedBridge.log("SMS LSP ime package detected: " + packageName);
                    return true;
                }
            }
        } catch (Throwable t) {
            XposedBridge.log("SMS LSP detect ime package failed in " + packageName + ": " + t);
        }
        return false;
    }

    private void rememberImeEditorInfo(InputMethodService service, EditorInfo editorInfo) {
        if (service == null || editorInfo == null) {
            return;
        }
        synchronized (imeEditorInfos) {
            imeEditorInfos.put(service, editorInfo);
        }
    }

    private boolean looksLikeOtpField(InputMethodService service) {
        EditorInfo editorInfo;
        synchronized (imeEditorInfos) {
            editorInfo = imeEditorInfos.get(service);
        }
        if (editorInfo == null) {
            return false;
        }
        int inputType = editorInfo.inputType;
        int inputClass = inputType & InputType.TYPE_MASK_CLASS;
        int variation = inputType & InputType.TYPE_MASK_VARIATION;
        boolean numeric = inputClass == InputType.TYPE_CLASS_NUMBER;
        boolean password = variation == InputType.TYPE_TEXT_VARIATION_PASSWORD
                || variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                || variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
                || variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD;
        return numeric
                || password
                || containsOtpKeyword(editorInfo.hintText)
                || containsOtpKeyword(editorInfo.fieldName)
                || containsOtpKeyword(editorInfo.privateImeOptions);
    }

    private boolean containsOtpKeyword(CharSequence value) {
        if (TextUtils.isEmpty(value)) {
            return false;
        }
        String text = value.toString().toLowerCase();
        return text.contains("验证码")
                || text.contains("校验码")
                || text.contains("动态码")
                || text.contains("短信码")
                || text.contains("确认码")
                || text.contains("安全码")
                || text.contains("otp")
                || text.contains("code")
                || text.contains("verify")
                || text.contains("verification");
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
            long createdAtMs = System.currentTimeMillis();
            FillMode fillMode = readFillModeFromPrefs();
            String label = buildClipLabel(createdAtMs, fillMode);
            clipboard.setPrimaryClip(ClipData.newPlainText(label, code));
            scheduleManagedClipboardClear(context, createdAtMs);
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
            if (!looksLikeOtpField(service)) {
                return;
            }
            InputConnection inputConnection = service.getCurrentInputConnection();
            if (inputConnection == null) {
                return;
            }
            ClipboardOtp clipboardOtp = readOtpFromClipboard(context);
            if (clipboardOtp.isEmpty() || isRecentlyFilled(clipboardOtp.key)) {
                return;
            }
            String fillCode = buildFillCode(clipboardOtp.code, clipboardOtp.fillMode);
            if (TextUtils.isEmpty(fillCode)) {
                return;
            }
            if (inputConnection.commitText(fillCode, 1)) {
                rememberFilled(clipboardOtp.key);
                markManagedClipboardFilled(context, clipboardOtp.code, clipboardOtp.fillMode);
                notifyCodeFilled(context, clipboardOtp.code);
            }
        }, Math.max(0L, delayMs));
    }

    private boolean isRecentlyFilled(String key) {
        if (TextUtils.isEmpty(key) || !TextUtils.equals(lastFilledKey, key)) {
            return false;
        }
        return SystemClock.elapsedRealtime() - lastFilledAt < FILL_DUPLICATE_WINDOW_MS;
    }

    private void rememberFilled(String key) {
        if (TextUtils.isEmpty(key)) {
            return;
        }
        lastFilledKey = key;
        lastFilledAt = SystemClock.elapsedRealtime();
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

    private ClipboardOtp readOtpFromClipboard(Context context) {
        try {
            ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard == null || !clipboard.hasPrimaryClip()) {
                return ClipboardOtp.EMPTY;
            }
            ClipMeta meta = parseClipMeta(clipboard.getPrimaryClipDescription());
            if (meta.isFilled) {
                return ClipboardOtp.EMPTY;
            }
            if (meta.isManaged
                    && meta.createdAtMs > 0L
                    && System.currentTimeMillis() - meta.createdAtMs > CLIP_TTL_MS) {
                clearManagedClipboardIfSame(context, meta.createdAtMs);
                return ClipboardOtp.EMPTY;
            }
            ClipData data = clipboard.getPrimaryClip();
            if (data == null || data.getItemCount() == 0) {
                return ClipboardOtp.EMPTY;
            }
            CharSequence text = data.getItemAt(0).coerceToText(context);
            String code = meta.isManaged ? CodeStore.extractToken(text) : "";
            if (TextUtils.isEmpty(code)) {
                return ClipboardOtp.EMPTY;
            }
            return new ClipboardOtp(code, meta.createdAtMs + ":" + code, meta.fillMode);
        } catch (Throwable ignored) {
            return ClipboardOtp.EMPTY;
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

    private void markManagedClipboardFilled(Context context, String code, FillMode fillMode) {
        try {
            ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard == null || !clipboard.hasPrimaryClip()) {
                return;
            }
            ClipMeta meta = parseClipMeta(clipboard.getPrimaryClipDescription());
            if (!meta.isManaged) {
                return;
            }
            long createdAtMs = meta.createdAtMs > 0L ? meta.createdAtMs : System.currentTimeMillis();
            long ageMs = System.currentTimeMillis() - createdAtMs;
            if (ageMs < 0L || ageMs > CLIP_TTL_MS) {
                clearManagedClipboardIfSame(context, createdAtMs);
                return;
            }
            FillMode safeFillMode = fillMode == null || !fillMode.hasConfig ? meta.fillMode : fillMode;
            clipboard.setPrimaryClip(ClipData.newPlainText(
                    buildFilledClipLabel(createdAtMs, safeFillMode),
                    code == null ? "" : code
            ));
            scheduleManagedClipboardClear(context, createdAtMs);
        } catch (Throwable ignored) {
        }
    }

    private void scheduleManagedClipboardClear(Context context, long createdAtMs) {
        if (context == null || createdAtMs <= 0L) {
            return;
        }
        long remainingMs = Math.max(0L, CLIP_TTL_MS - (System.currentTimeMillis() - createdAtMs));
        new Handler(Looper.getMainLooper()).postDelayed(
                () -> clearManagedClipboardIfSame(context, createdAtMs),
                remainingMs
        );
    }

    private void clearManagedClipboardIfSame(Context context, long createdAtMs) {
        try {
            ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard == null || !clipboard.hasPrimaryClip()) {
                return;
            }
            ClipMeta meta = parseClipMeta(clipboard.getPrimaryClipDescription());
            if (!meta.isManaged || meta.createdAtMs != createdAtMs) {
                return;
            }
            clipboard.setPrimaryClip(ClipData.newPlainText("", ""));
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
        return CodeStore.extractCode(text, null);
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
                modulePrefs = new XSharedPreferences(SELF_PACKAGE, CodeStore.PUBLIC_PREFS);
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
        return modulePrefs == null || modulePrefs.getBoolean(CodeStore.KEY_CLIPBOARD_BRIDGE_ENABLED, true);
    }

    private String buildFillCode(String code, FillMode fillMode) {
        if (fillMode != null && fillMode.hasConfig) {
            return CodeStore.applySemiAuto(code, fillMode.semiAutoEnabled, fillMode.keepTailLength);
        }
        FillMode prefsFillMode = readFillModeFromPrefs();
        return CodeStore.applySemiAuto(code, prefsFillMode.semiAutoEnabled, prefsFillMode.keepTailLength);
    }

    private FillMode readFillModeFromPrefs() {
        reloadPrefs();
        boolean semiAutoEnabled = modulePrefs != null
                && modulePrefs.getBoolean(CodeStore.KEY_SEMI_AUTO_ENABLED, false);
        int keepTailLength = modulePrefs == null
                ? 2
                : modulePrefs.getInt(CodeStore.KEY_SEMI_AUTO_KEEP_TAIL_LENGTH, 2);
        return new FillMode(true, semiAutoEnabled, clampKeepTailLength(keepTailLength));
    }

    private String buildClipLabel(long createdAtMs, FillMode fillMode) {
        return CLIP_LABEL_PREFIX + buildFillModeLabel(fillMode) + ":" + createdAtMs;
    }

    private String buildFilledClipLabel(long createdAtMs, FillMode fillMode) {
        return CLIP_LABEL_PREFIX + CLIP_FILLED_MARK + buildFillModeLabel(fillMode) + ":" + createdAtMs;
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
        String metadata = isFilled ? payload.substring(CLIP_FILLED_MARK.length()) : payload;
        int separator = payload.lastIndexOf(':');
        String createdAtText = separator < 0 ? payload : payload.substring(separator + 1);
        long createdAtMs = 0L;
        try {
            createdAtMs = Long.parseLong(createdAtText.trim());
        } catch (Throwable ignored) {
        }
        return new ClipMeta(true, isFilled, createdAtMs, parseFillMode(metadata));
    }

    private String buildFillModeLabel(FillMode fillMode) {
        if (fillMode == null || !fillMode.hasConfig || !fillMode.semiAutoEnabled) {
            return CLIP_FULL_AUTO_MARK;
        }
        return CLIP_SEMI_AUTO_MARK + clampKeepTailLength(fillMode.keepTailLength);
    }

    private FillMode parseFillMode(String metadata) {
        if (TextUtils.isEmpty(metadata)) {
            return FillMode.EMPTY;
        }
        if (CLIP_FULL_AUTO_MARK.equals(metadata) || metadata.startsWith(CLIP_FULL_AUTO_MARK + ":")) {
            return new FillMode(true, false, 0);
        }
        if (metadata.startsWith(CLIP_SEMI_AUTO_MARK)) {
            int valueStart = CLIP_SEMI_AUTO_MARK.length();
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

    private int clampKeepTailLength(int keepTailLength) {
        return Math.max(0, Math.min(8, keepTailLength));
    }

    private static final class ClipMeta {
        private static final ClipMeta EMPTY = new ClipMeta(false, false, 0L, FillMode.EMPTY);

        private final boolean isManaged;
        private final boolean isFilled;
        private final long createdAtMs;
        private final FillMode fillMode;

        private ClipMeta(boolean isManaged, boolean isFilled, long createdAtMs, FillMode fillMode) {
            this.isManaged = isManaged;
            this.isFilled = isFilled;
            this.createdAtMs = createdAtMs;
            this.fillMode = fillMode == null ? FillMode.EMPTY : fillMode;
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

    private static final class ClipboardOtp {
        private static final ClipboardOtp EMPTY = new ClipboardOtp("", "", FillMode.EMPTY);

        private final String code;
        private final String key;
        private final FillMode fillMode;

        private ClipboardOtp(String code, String key, FillMode fillMode) {
            this.code = code == null ? "" : code;
            this.key = key == null ? "" : key;
            this.fillMode = fillMode == null ? FillMode.EMPTY : fillMode;
        }

        private boolean isEmpty() {
            return TextUtils.isEmpty(code) || TextUtils.isEmpty(key);
        }
    }
}
