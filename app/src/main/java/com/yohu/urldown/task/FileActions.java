package com.yohu.urldown.task;

import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.webkit.MimeTypeMap;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.FileProvider;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 打开 / 分享已下载的文件，复制原链接。 */
public final class FileActions {

    private FileActions() {
    }

    /** 用系统默认应用打开。没有能处理的应用时返回 false。 */
    public static boolean open(@NonNull Context context, @NonNull File file) {
        Intent intent = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri(context, file), mimeOf(file))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(intent);
            return true;
        } catch (ActivityNotFoundException | IllegalArgumentException e) {
            return false;
        }
    }

    /** 系统分享面板；多个文件一起分享。 */
    public static boolean share(@NonNull Context context, @NonNull List<File> files,
                                @Nullable CharSequence title) {
        if (files.isEmpty()) {
            return false;
        }
        Intent send;
        if (files.size() == 1) {
            File f = files.get(0);
            send = new Intent(Intent.ACTION_SEND)
                    .setType(mimeOf(f))
                    .putExtra(Intent.EXTRA_STREAM, uri(context, f));
        } else {
            ArrayList<Uri> uris = new ArrayList<>();
            for (File f : files) {
                uris.add(uri(context, f));
            }
            send = new Intent(Intent.ACTION_SEND_MULTIPLE)
                    .setType(commonMime(files))
                    .putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
        }
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        Intent chooser = Intent.createChooser(send, title)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(chooser);
            return true;
        } catch (ActivityNotFoundException | IllegalArgumentException e) {
            return false;
        }
    }

    public static void copyText(@NonNull Context context, @NonNull String label, @NonNull String text) {
        ClipboardManager cm = context.getSystemService(ClipboardManager.class);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText(label, text));
        }
    }

    @NonNull
    public static String mimeOf(@NonNull File file) {
        String name = file.getName();
        int dot = name.lastIndexOf('.');
        String ext = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
        String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        return mime == null ? "*/*" : mime;
    }

    private static String commonMime(List<File> files) {
        String first = mimeOf(files.get(0));
        String major = first.contains("/") ? first.substring(0, first.indexOf('/')) : "*";
        for (File f : files) {
            String m = mimeOf(f);
            if (!m.startsWith(major + "/")) {
                return "*/*";
            }
        }
        return major + "/*";
    }

    private static Uri uri(Context context, File file) {
        return FileProvider.getUriForFile(context, context.getPackageName() + ".files", file);
    }
}
