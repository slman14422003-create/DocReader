package com.docreader.app.pdf;

import android.content.Context;
import android.graphics.RectF;

import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.text.PDFTextStripper;
import com.tom_roush.pdfbox.text.TextPosition;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * يستخرج نص صفحة PDF واحدة مقسّمًا إلى "أسطر" (Line) مع صندوق إحاطة
 * (Bounding Box) بوحدة نقطة PDF لكل سطر - بخلاف DocumentTextExtractor
 * اللي بيرجّع نص الصفحة كتلة واحدة فاضية من أي معلومات عن الموضع.
 *
 * الهدف: تمكين TranslatedPdfBuilder من *مسح* منطقة كل سطر نص أصلي بالضبط
 * ورسم ترجمته في نفس مكانه (استبدال حقيقي في مكانه) بدل تجميع كل نص
 * الصفحة في لوحة منفصلة أسفلها فوق كل شيء.
 *
 * إصلاح مهم (سبب رئيسي للنص المتراكب/المتداخل في صفحات بتخطيط عمودين أو
 * جداول): PDFTextStripper.writeString(...) بيجمّع "سطر" واحد بناءً على
 * تقارب الـ Y بس، فلو عمودين مختلفين (أو خليتين متجاورتين في جدول) وقعوا
 * على نفس ارتفاع السطر تقريبًا، PdfBox بيدمجهم في سطر واحد عريض يمتد عبر
 * الصفحة كلها. صندوق إحاطة بهذا العرض كان بيتمسح بالكامل (يمسح عمودين مع
 * بعض) وتُرسم فيه ترجمة نص العمودين مدموجًا في مكان واحد - وهو بالظبط
 * الخلل الظاهر في لقطات الشاشة. الحل: نعيد تقسيم كل "سطر" راجع من PdfBox
 * كل ما فيه فجوة أفقية كبيرة (أكبر من ~7 أضعاف حجم الخط) بين حرفين
 * متتاليين - فجوة بهذا الاتساع مش مسافة كلمة عادية، غالبًا قفزة لعمود أو
 * خلية جدول تانية.
 *
 * إصلاح تاني: خطوط الأيقونات الرمزية (Icon Fonts) المستخدمة أحيانًا
 * لرسم أيقونات زي الهلال/اللمبة في بعض الكتب - PdfBox بيستخرجها كنص عادي
 * قصير غريب (زي "ti" أو "Ie")، فلو اتبعتت للترجمة وانمسح مكانها هتتحول
 * لنص عشوائي فوق مكان الأيقونة. نتجاهل أي "سطر" أقل من 3 حروف حقيقية
 * (Unicode Letter) وموش رقم/مدى صفحات، فيفضل مكانه زي ما هو في الخلفية.
 */
final class PdfLineExtractor {

    private PdfLineExtractor() {
    }

    static final class Line {
        /** بوحدة نقطة PDF، Y من أعلى الصفحة لأسفل - نفس نظام إحداثيات
         *  pagePointSize/TranslatedPdfBuilder، فتُستخدم مباشرة بلا تحويل. */
        final RectF box;
        final String text;

        Line(RectF box, String text) {
            this.box = box;
            this.text = text;
        }
    }

    private static volatile boolean initialized = false;

    private static synchronized void ensureInit(Context ctx) {
        if (!initialized) {
            PDFBoxResourceLoader.init(ctx.getApplicationContext());
            initialized = true;
        }
    }

    /** يرجّع أسطر صفحة واحدة (فهرسها 0-based) بترتيب القراءة، أو قائمة فاضية
     *  لو تعذّر الاستخراج أو كانت الصفحة بلا طبقة نص حقيقية (صورة ممسوحة
     *  ضوئيًا مثلًا) - أي خطأ بيُبتلع هنا عشان فشل صفحة واحدة ما يوقفش باقي
     *  ترجمة الملف. */
    static List<Line> extractLines(Context ctx, File file, int pageIndex) {
        List<Line> lines = new ArrayList<>();
        try {
            ensureInit(ctx);
            try (PDDocument doc = PDDocument.load(file)) {
                if (pageIndex < 0 || pageIndex >= doc.getNumberOfPages()) return lines;
                LineCollectingStripper stripper = new LineCollectingStripper(lines);
                stripper.setStartPage(pageIndex + 1);
                stripper.setEndPage(pageIndex + 1);
                stripper.setSortByPosition(true);
                stripper.getText(doc); // النص المرجّع مش محتاجينه؛ المهم الأسطر المجمّعة جانبيًا في writeString
            }
        } catch (Throwable t) {
            return new ArrayList<>();
        }
        return lines;
    }

    /** أقل عدد "حروف حقيقية" (Unicode Letter) حتى يُعتبر السطر نصًا فعليًا
     *  بدل رمز أيقونة قصير - انظر الشرح أعلى الكلاس. */
    private static final int MIN_REAL_LETTERS = 3;
    /** أكبر فجوة أفقية بين حرفين متتاليين (كمضاعف لحجم الخط) قبل ما نعتبرها
     *  قفزة لعمود/خلية تانية بدل مسافة كلمة عادية. */
    private static final float MAX_GAP_FONT_MULTIPLIER = 7f;

    private static final class LineCollectingStripper extends PDFTextStripper {
        private final List<Line> out;

        LineCollectingStripper(List<Line> out) throws IOException {
            super();
            this.out = out;
        }

        @Override
        protected void writeString(String text, List<TextPosition> textPositions) {
            if (textPositions == null || textPositions.isEmpty()) return;
            List<TextPosition> run = new ArrayList<>();
            float prevEndX = Float.NaN;
            float prevFontSize = 0f;
            for (TextPosition tp : textPositions) {
                float x = tp.getXDirAdj();
                float fontSize = Math.max(1f, tp.getFontSizeInPt());
                if (!Float.isNaN(prevEndX)) {
                    float gap = x - prevEndX;
                    if (gap > prevFontSize * MAX_GAP_FONT_MULTIPLIER) {
                        flushRun(run);
                        run = new ArrayList<>();
                    }
                }
                run.add(tp);
                float w = tp.getWidthDirAdj() > 0 ? tp.getWidthDirAdj() : tp.getWidth();
                prevEndX = x + w;
                prevFontSize = fontSize;
            }
            flushRun(run);
        }

        private void flushRun(List<TextPosition> run) {
            if (run.isEmpty()) return;
            StringBuilder sb = new StringBuilder();
            float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE;
            float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
            for (TextPosition tp : run) {
                String u = tp.getUnicode();
                if (u != null) sb.append(u);
                float x = tp.getXDirAdj();
                float y = tp.getYDirAdj();
                float w = tp.getWidthDirAdj() > 0 ? tp.getWidthDirAdj() : tp.getWidth();
                float h = tp.getHeightDir() > 0 ? tp.getHeightDir() : tp.getHeight();
                minX = Math.min(minX, x);
                maxX = Math.max(maxX, x + w);
                // y هنا خط أساس الحرف (baseline) - جسم الحرف يمتد لأعلى تقريبًا
                // بارتفاعه الكامل، ولأسفل بمقدار بسيط (الذيول/التشكيل).
                minY = Math.min(minY, y - h);
                maxY = Math.max(maxY, y + h * 0.25f);
            }
            if (minX == Float.MAX_VALUE) return;
            String text = sb.toString();
            if (!looksLikeRealText(text)) return;
            out.add(new Line(new RectF(minX, minY, maxX, maxY), text));
        }

        private static boolean looksLikeRealText(String s) {
            String t = s.trim();
            if (t.isEmpty()) return false;
            // أرقام/تواريخ/مدى صفحات لوحدها مقبولة حتى لو قصيرة (146، 149-146).
            if (t.matches("[0-9\\-\u2013.,:/%\\s]+")) return true;
            int letters = 0;
            for (int i = 0; i < t.length(); i++) {
                if (Character.isLetter(t.charAt(i))) letters++;
            }
            return letters >= MIN_REAL_LETTERS;
        }
    }
}
