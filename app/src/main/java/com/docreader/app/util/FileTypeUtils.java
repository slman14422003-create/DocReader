package com.docreader.app.util;

import android.content.Context;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.database.Cursor;

import com.docreader.app.R;

public class FileTypeUtils {

    public enum DocType { PDF, DOCX, XLSX, PPTX, TXT, UNKNOWN }

    public static DocType detect(String fileName) {
        if (fileName == null) return DocType.UNKNOWN;
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".pdf")) return DocType.PDF;
        if (lower.endsWith(".docx") || lower.endsWith(".doc")) return DocType.DOCX;
        if (lower.endsWith(".xlsx") || lower.endsWith(".xls") || lower.endsWith(".csv")) return DocType.XLSX;
        if (lower.endsWith(".pptx") || lower.endsWith(".ppt")) return DocType.PPTX;
        if (lower.endsWith(".txt") || lower.endsWith(".md")) return DocType.TXT;
        return DocType.UNKNOWN;
    }

    public static String queryDisplayName(Context context, Uri uri) {
        String result = uri.getLastPathSegment();
        try (Cursor cursor = context.getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) {
                    String name = cursor.getString(idx);
                    if (name != null) result = name;
                }
            }
        } catch (Exception ignored) {
        }
        return result;
    }

    /** أيقونة موحّدة على شكل مستند؛ اللون هو ما يميّز نوع الملف (انظر colorResFor). */
    public static int iconResFor(DocType type) {
        return R.drawable.ic_file;
    }

    public static int colorResFor(DocType type) {
        switch (type) {
            case PDF: return R.color.type_pdf;
            case DOCX: return R.color.type_docx;
            case XLSX: return R.color.type_xlsx;
            case PPTX: return R.color.type_pptx;
            default: return R.color.text_tertiary;
        }
    }
}
