package com.docreader.app.pdf;

import android.content.Context;

/**
 * واجهة التهيئة العامة لقارئ PDF (المنقول من تطبيق الفيزيو).
 * تُستدعى مرة واحدة من Application.onCreate: تحمّل قاموس التشكيل في الخلفية
 * حتى تكون القراءة الصوتية جاهزة فور فتح أول ملف.
 */
public final class PdfReaderModule {
    private PdfReaderModule() {}

    public static void init(Context context) {
        final Context app = context.getApplicationContext();
        new Thread(() -> TashkeelDict.load(app), "pdf-tashkeel-warmup").start();
    }
}
