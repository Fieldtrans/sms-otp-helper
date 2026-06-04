package com.example.sms;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.text.TextUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public final class OtpPcExport {
    public static final String EXPORT_DIR = "Download/SMS";
    public static final String LATEST_FILE_NAME = "latest_otp.txt";

    private OtpPcExport() {
    }

    public static void writeLatest(Context context, String code) {
        if (context == null || TextUtils.isEmpty(code) || !CodeStore.isPcExportEnabled(context)) {
            return;
        }
        String content = code + "\n" + System.currentTimeMillis() + "\n";
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                writeWithMediaStore(context, content);
            } else {
                writeWithFileApi(content);
            }
        } catch (Throwable ignored) {
        }
    }

    private static void writeWithMediaStore(Context context, String content) throws Exception {
        ContentResolver resolver = context.getContentResolver();
        Uri collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        String relativePath = Environment.DIRECTORY_DOWNLOADS + "/SMS/";
        try {
            resolver.delete(
                    collection,
                    MediaStore.MediaColumns.DISPLAY_NAME + "=? AND " + MediaStore.MediaColumns.RELATIVE_PATH + "=?",
                    new String[]{LATEST_FILE_NAME, relativePath}
            );
        } catch (Throwable ignored) {
        }

        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, LATEST_FILE_NAME);
        values.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath);
        Uri item = resolver.insert(collection, values);
        if (item == null) {
            return;
        }
        try (OutputStream stream = resolver.openOutputStream(item, "w")) {
            if (stream != null) {
                stream.write(content.getBytes(StandardCharsets.UTF_8));
            }
        }
    }

    private static void writeWithFileApi(String content) throws Exception {
        File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "SMS");
        if (!dir.exists() && !dir.mkdirs()) {
            return;
        }
        File file = new File(dir, LATEST_FILE_NAME);
        try (FileOutputStream stream = new FileOutputStream(file, false)) {
            stream.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }
}
