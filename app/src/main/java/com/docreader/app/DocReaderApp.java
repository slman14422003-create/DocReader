package com.docreader.app;

import android.app.Application;

import com.docreader.app.pdf.PdfReaderModule;
import com.docreader.app.util.ThemeUtils;

public class DocReaderApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        ThemeUtils.applySavedTheme(this);
        // تحميل قاموس التشكيل لقارئ PDF في الخلفية
        PdfReaderModule.init(this);
    }
}
