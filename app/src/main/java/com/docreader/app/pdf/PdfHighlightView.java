package com.docreader.app.pdf;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * طبقة شفافة فوق صورة صفحة الـ PDF: تظلّل الجملة المقروءة حاليًا (لون خفيف)
 * والكلمة المنطوقة الآن (لون أوضح)، وتظلّل نتائج البحث داخل الملف (أصفر لكل النتائج،
 * وبرتقالي للنتيجة الحالية). كل الإحداثيات نسب (0..1) من أبعاد الصفحة،
 * فتبقى صحيحة مهما كان التكبير أو حجم الشاشة.
 */
public class PdfHighlightView extends View {

    private final Paint sentencePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint wordPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint searchPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint searchCurrentPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final List<RectF> sentenceRects = new ArrayList<>();
    private final List<RectF> searchRects = new ArrayList<>();
    private final List<RectF> searchCurrentRects = new ArrayList<>();
    private RectF wordRect = null;
    private final RectF tmp = new RectF();

    public PdfHighlightView(Context context) {
        this(context, null);
    }

    public PdfHighlightView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        sentencePaint.setStyle(Paint.Style.FILL);
        sentencePaint.setColor(0x33C96442); // برتقالي هوية التطبيق بشفافية خفيفة
        wordPaint.setStyle(Paint.Style.FILL);
        wordPaint.setColor(0x80E8A317);     // كهرماني أوضح للكلمة الحالية
        searchPaint.setStyle(Paint.Style.FILL);
        searchPaint.setColor(0x66FFD54F);   // أصفر شفاف لكل نتائج البحث
        searchCurrentPaint.setStyle(Paint.Style.FILL);
        searchCurrentPaint.setColor(0xB3FF8A3D); // برتقالي واضح للنتيجة الحالية
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    /** يضبط تظليل الجملة (قائمة مستطيلات، مستطيل لكل سطر) وتظليل الكلمة الحالية (قد يكون null). */
    public void setHighlight(@Nullable List<RectF> sentence, @Nullable RectF word) {
        sentenceRects.clear();
        if (sentence != null) sentenceRects.addAll(sentence);
        wordRect = word;
        invalidate();
    }

    public void clearHighlight() {
        if (sentenceRects.isEmpty() && wordRect == null) return;
        sentenceRects.clear();
        wordRect = null;
        invalidate();
    }

    /** نتائج البحث في هذه الصفحة: others = باقي النتائج، current = النتيجة المحددة الآن. */
    public void setSearchHits(@Nullable List<RectF> others, @Nullable List<RectF> current) {
        boolean had = !searchRects.isEmpty() || !searchCurrentRects.isEmpty();
        searchRects.clear();
        searchCurrentRects.clear();
        if (others != null) searchRects.addAll(others);
        if (current != null) searchCurrentRects.addAll(current);
        if (had || !searchRects.isEmpty() || !searchCurrentRects.isEmpty()) invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;
        float pad = Math.max(1.5f, w * 0.002f);
        for (RectF r : searchRects) {
            tmp.set(r.left * w - pad, r.top * h - pad, r.right * w + pad, r.bottom * h + pad);
            canvas.drawRoundRect(tmp, 4f, 4f, searchPaint);
        }
        for (RectF r : searchCurrentRects) {
            tmp.set(r.left * w - pad, r.top * h - pad, r.right * w + pad, r.bottom * h + pad);
            canvas.drawRoundRect(tmp, 4f, 4f, searchCurrentPaint);
        }
        for (RectF r : sentenceRects) {
            tmp.set(r.left * w - pad, r.top * h - pad, r.right * w + pad, r.bottom * h + pad);
            canvas.drawRoundRect(tmp, 4f, 4f, sentencePaint);
        }
        if (wordRect != null) {
            tmp.set(wordRect.left * w - pad, wordRect.top * h - pad, wordRect.right * w + pad, wordRect.bottom * h + pad);
            canvas.drawRoundRect(tmp, 5f, 5f, wordPaint);
        }
    }
}
