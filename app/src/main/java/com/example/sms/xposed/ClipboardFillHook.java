package com.example.sms.xposed;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.EditText;

import java.lang.ref.WeakReference;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class ClipboardFillHook implements IXposedHookLoadPackage {
    private static final String SELF_PACKAGE = "com.example.sms";
    private static final String MANAGED_CLIP_PREFIX = "CodeDelayLSP:";
    private static final String FILLED_MARK = "filled:";
    private static final long CLIP_TTL_MS = 90_000L;
    private static WeakReference<EditText> lastFocusedEditText = new WeakReference<>(null);

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        // 模块自身进程，标记 LSPosed 已生效
        if (SELF_PACKAGE.equals(lpparam.packageName)) {
            try {
                Class<?> stateClass = XposedHelpers.findClass(
                    "com.example.sms.LspModuleState",
                    lpparam.classLoader
                );
                stateClass.getDeclaredMethod("setActive").invoke(null);
            } catch (Throwable ignored) {
            }
            return;
        }

        // Hook 所有 App 的 EditText focus 事件
        hookEditTextFocus(lpparam);
    }

    private void hookEditTextFocus(XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(
                View.class,
                "onFocusChanged",
                boolean.class,
                int.class,
                Rect.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        boolean focused = (boolean) param.args[0];
                        if (!focused || !(param.thisObject instanceof EditText)) {
                            return;
                        }

                        EditText editText = (EditText) param.thisObject;
                        lastFocusedEditText = new WeakReference<>(editText);

                        // 检查剪贴板是否有受管验证码
                        new Handler(Looper.getMainLooper()).postDelayed(() -> {
                            tryFillFromClipboard(editText);
                        }, 300);
                    }
                }
            );
        } catch (Throwable ignored) {
        }
    }

    private void tryFillFromClipboard(EditText editText) {
        if (editText == null || !editText.isFocused() || !editText.isEnabled()) {
            return;
        }

        try {
            Context context = editText.getContext();
            ClipboardManager clipboard = (ClipboardManager)
                context.getSystemService(Context.CLIPBOARD_SERVICE);

            if (clipboard == null || !clipboard.hasPrimaryClip()) {
                return;
            }

            ClipData clip = clipboard.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) {
                return;
            }

            CharSequence label = clip.getDescription().getLabel();
            if (label == null || !label.toString().startsWith(MANAGED_CLIP_PREFIX)) {
                return;
            }

            // 检查是否过期
            try {
                String timestampStr = label.toString().substring(MANAGED_CLIP_PREFIX.length());
                long timestamp = Long.parseLong(timestampStr);
                long ageMs = System.currentTimeMillis() - timestamp;
                if (ageMs < 0 || ageMs > CLIP_TTL_MS) {
                    return; // 已过期
                }
            } catch (Throwable ignored) {
                return;
            }

            CharSequence code = clip.getItemAt(0).getText();
            if (TextUtils.isEmpty(code)) {
                return;
            }

            // 填入验证码
            editText.setText(code);
            editText.setSelection(code.length());

            // Mark as filled and keep the original TTL.
            String labelText = label.toString();
            long filledTimestamp = Long.parseLong(labelText.substring(labelText.lastIndexOf(':') + 1));
            clipboard.setPrimaryClip(ClipData.newPlainText(MANAGED_CLIP_PREFIX + FILLED_MARK + filledTimestamp, code));
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                try {
                    if (!clipboard.hasPrimaryClip()) {
                        return;
                    }
                    CharSequence currentLabel = clipboard.getPrimaryClipDescription().getLabel();
                    if (currentLabel != null && currentLabel.toString().endsWith(String.valueOf(filledTimestamp))) {
                        clipboard.setPrimaryClip(ClipData.newPlainText("", ""));
                    }
                } catch (Throwable ignored) {
                }
            }, Math.max(0L, CLIP_TTL_MS - (System.currentTimeMillis() - filledTimestamp)));
        } catch (Throwable ignored) {
        }
    }
}
