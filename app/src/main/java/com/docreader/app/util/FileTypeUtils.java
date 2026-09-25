package com.docreader.app.util;

import android.content.Context;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.database.Cursor;

import com.docreader.app.R;

public class FileTypeUtils {

    public enum DocType {
        PDF, DOCX, XLSX, CSV, PPTX, TXT, RTF, HTML, IMAGE,
        DOC_LEGACY, XLS_LEGACY, PPT_LEGACY, UNKNOWN
    }

    public static DocType detect(String fileName) {
        if (fileName == null) return DocType.UNKNOWN;
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".pdf")) return DocType.PDF;
        if (lower.endsWith(".docx")) return DocType.DOCX;
        if (lower.endsWith(".doc")) return DocType.DOC_LEGACY;
        if (lower.endsWith(".xlsx")) return DocType.XLSX;
        if (lower.endsWith(".xls")) return DocType.XLS_LEGACY;
        if (lower.endsWith(".csv")) return DocType.CSV;
        if (lower.endsWith(".pptx")) return DocType.PPTX;
        if (lower.endsWith(".ppt")) return DocType.PPT_LEGACY;
        if (lower.endsWith(".rtf")) return DocType.RTF;
        if (lower.endsWith(".htm") || lower.endsWith(".html")) return DocType.HTML;
        if (lower.endsWith(".txt") || lower.endsWith(".md") || lower.endsWith(".log")) return DocType.TXT;
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png")
                || lower.endsWith(".webp") || lower.endsWith(".bmp") || lower.endsWith(".gif")) {
            return DocType.IMAGE;
        }
        return DocType.UNKNOWN;
    }

    public static boolean isLegacyBinary(DocType type) {
        return type == DocType.DOC_LEGACY || type == DocType.XLS_LEGACY || type == DocType.PPT_LEGACY;
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
        if (type == DocType.IMAGE) return R.drawable.ic_file;
        return R.drawable.ic_file;
    }

    public static int colorResFor(DocType type) {
        switch (type) {
            case PDF: return R.color.type_pdf;
            case DOCX:
            case DOC_LEGACY:
            case RTF:
                return R.color.type_docx;
            case XLSX:
            case CSV:
            case XLS_LEGACY:
                return R.color.type_xlsx;
            case PPTX:
            case PPT_LEGACY:
                return R.color.type_pptx;
            default: return R.color.text_tertiary;
        }
    }
}
