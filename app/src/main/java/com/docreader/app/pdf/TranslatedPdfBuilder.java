package com.docreader.app.pdf;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Paint.FontMetrics;
import android.graphics.RectF;
import android.graphics.pdf.PdfDocument;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextDirectionHeuristics;
import android.text.TextPaint;
import android.text.TextUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Locale;

/**
 * يبني ملف PDF لناتج "ترجمة الملف بالكامل" - بمراعاة طلبين صريحين من
 * المستخدم: (1) الحفاظ على تصميم/هيكل كل صفحة أصلية بدل استبدالها بصفحة
 * نص عادي فاضية، و(2) *استبدال* كل سطر نص أصلي بترجمته في نفس مكانه
 * بالضبط بدل رسم الترجمة في لوحة منفصلة مجمّعة فوق كل شيء.
 *
 * آلية الاستبدال لكل سطر (انظر addPage):
 *  1) ترسم صورة الصفحة الأصلية بالكامل كخلفية (نفس الصورة اللي PdfRenderer
 *     بيعرضها في شاشة القراءة العادية) - فالتصميم والصور والألوان والتخطيط
 *     الأصلي يفضلوا زي ما هم تمامًا.
 *  2) لكل سطر نص استخرجه PdfLineExtractor (مع صندوق إحاطة بمكانه بالضبط):
 *     - تُمسح منطقة السطر الأصلي بلون *مأخوذ من خلفية الصفحة نفسها* حول
 *       السطر (بدل تعبئة بيضاء ثابتة تتعارض مع صفحات ذات خلفية داكنة).
 *     - يُرسم النص المترجم في نفس المكان، بلون قراءة واضح محسوب تلقائيًا
 *       حسب سطوع الخلفية المأخوذة (أبيض فوق خلفية داكنة، أسود فوق فاتحة).
 *     - حجم الخط يتقلّص تلقائيًا حتى يتّسع عرض السطر المترجم (غالبًا أطول
 *       من الأصل عند الترجمة للعربية مثلًا) داخل نفس عرض الصندوق الأصلي.
 *  3) لو فشل استخراج/ترجمة سطر معيّن (مفقود من translatedLines، أو مطابق
 *     للنص الأصلي حرفيًا) - يُترك هذا السطر تحديدًا زي ما هو بدون أي مسح
 *     أو رسم فوقه، بدل استبداله بترجمة غير مؤكدة.
 *
 * ملاحظة مهمة وصادقة عن حدود الحل: صندوق كل سطر تقريبي (من PdfBox، مش
 * قياس حبر حقيقي)، ولا توجد إعادة تدفّق (reflow) لباقي الصفحة لو طالت
 * الترجمة كثيرًا - فوق حد أدنى لحجم الخط بيُختصر النص المترجم لهذا السطر
 * تحديدًا (…) بدل ما يفيض فوق أسطر/عناصر تانية في نفس الصفحة. استبدال
 * حروف النص الأجنبي بمحاذاة/تكسير أسطر مطابقة تمامًا لمحرّك تنضيد الصفحة
 * الأصلي (مش مجرد صندوقه) يحتاج مكتبة تحرير PDF على مستوى عناصر النص نفسها
 * (مش متوفرة هنا) - فهذا أقرب تقريب عملي متاح لاستبدال حقيقي في المكان.
 *
 * تصميم تدفقي (streaming) مقصود: الكلاس ده بيتفتح مرة واحدة وبتتضاف الصفحات
 * وحدة وحدة عبر addPage() بدل ما يستقبل List فيها كل صور خلفيات الصفحات
 * محمّلة في الذاكرة مرة واحدة - ملف من 100+ صفحة زي كتب العلاج الطبيعي
 * الشائعة كان ممكن يستهلك مئات الميجابايت لو اتجمّعوا كلهم قبل الكتابة.
 * هنا كل صورة خلفية بتتحمّل وترسم وتتحرّر (recycle) فورًا بعد ما تخلص
 * صفحتها - صورة وحدة بس في الذاكرة في أي لحظة، بغض النظر عن عدد الصفحات.
 */
final class TranslatedPdfBuilder implements AutoCloseable {

    private static final int FALLBACK_WIDTH = 595;   // A4 عند 72dpi - يُستخدم فقط لو تعذّر قراءة أبعاد الصفحة الأصلية
    private static final int FALLBACK_HEIGHT = 842;
    private static final int MARGIN = 28;
    /** هامش أمان حول صندوق كل سطر (حتى يغطي المسح أي جزء من الحرف تجاوز
     *  تقدير PdfBox لصندوق الإحاطة - ذيول الحروف والتشكيل مثلًا). */
    private static final float LINE_PAD_PT = 1.6f;
    private static final float MIN_REPLACE_FONT_PT = 5.5f;
    private static final float FONT_STEP_PT = 0.5f;

    private final PdfDocument doc = new PdfDocument();
    private final boolean rtl;

    /** يفتح البناء ويرسم صفحة غلاف فورًا (عنوان + بيانات الترجمة). */
    TranslatedPdfBuilder(String sourceTitle, String targetLangLabel, int totalPages, boolean rtl) {
        this.rtl = rtl;
        drawCoverPage(sourceTitle, targetLangLabel, totalPages);
    }

    /**
     * يضيف صفحة واحدة للملف الناتج ويحرّر صورة الخلفية فورًا بعد رسمها -
     * لازم يُستدعى بترتيب أرقام الصفحات.
     *
     * @param background       صورة الصفحة الأصلية (من PdfRenderer) أو null لو تعذّر رسمها.
     * @param lines            أسطر الصفحة مع صناديق إحاطتها (PdfLineExtractor.extractLines)،
     *                         أو قائمة فاضية لو تعذّر استخراج النص (صورة ممسوحة ضوئيًا مثلًا).
     * @param translatedLines  ترجمة كل سطر بنفس ترتيب lines تمامًا، أو null لو فشلت الترجمة
     *                         بالكامل لهذه الصفحة - في الحالتين تُترك الصفحة بخلفيتها الأصلية
     *                         فقط بدون أي إضافة لو معندناش ترجمة مؤكدة المحاذاة.
     */
    void addPage(int pageNumber, Bitmap background, float pageWidthPt, float pageHeightPt,
                 List<PdfLineExtractor.Line> lines, List<String> translatedLines) {
        int pageW = pageWidthPt > 0 ? Math.round(pageWidthPt) : FALLBACK_WIDTH;
        int pageH = pageHeightPt > 0 ? Math.round(pageHeightPt) : FALLBACK_HEIGHT;

        PdfDocument.Page page = startPage(pageW, pageH);
        Canvas canvas = page.getCanvas();
        canvas.drawColor(Color.WHITE);
        if (background != null) {
            canvas.drawBitmap(background, null, new RectF(0, 0, pageW, pageH), new Paint(Paint.FILTER_BITMAP_FLAG));
        }

        if (lines != null && translatedLines != null) {
            int n = Math.min(lines.size(), translatedLines.size());
            for (int i = 0; i < n; i++) {
                PdfLineExtractor.Line line = lines.get(i);
                String translated = translatedLines.get(i);
                if (translated == null) continue;
                String t = translated.trim();
                if (t.isEmpty()) continue;
                // نفس النص الأصلي بالحرف (رقم صفحة، رمز، اسم علم لم تُترجم خدمة
                // الترجمة له... إلخ) - مفيش داعي نمسح ونعيد رسم نفس الشيء.
                if (t.equalsIgnoreCase(line.text.trim())) continue;
                drawReplacedLine(canvas, background, pageW, pageH, line.box, t);
            }
        }

        doc.finishPage(page);
        // بعد finishPage() محتوى الصفحة (بما فيها صورة الخلفية) بقى محفوظ
        // جوه بنية الملف الناتج نفسها - الصورة الخام مش محتاجة تفضل في
        // الذاكرة بعد كده، فبنحرّرها فورًا بدل ما تتراكم مع كل صفحة جديدة.
        if (background != null && !background.isRecycled()) {
            background.recycle();
        }
    }

    /** يكتب الملف النهائي إلى outFile - يُستدعى مرة واحدة بعد إضافة كل الصفحات. */
    void writeTo(File outFile) throws IOException {
        try (FileOutputStream out = new FileOutputStream(outFile)) {
            doc.writeTo(out);
        }
    }

    @Override
    public void close() {
        doc.close();
    }

    private PdfDocument.Page startPage(int w, int h) {
        PdfDocument.PageInfo info = new PdfDocument.PageInfo.Builder(Math.max(1, w), Math.max(1, h), 1).create();
        return doc.startPage(info);
    }

    /** يمسح منطقة سطر النص الأصلي ويرسم ترجمته مكانه بالضبط - هذا هو
     *  "الاستبدال في المكان" المطلوب بدل لوحة منفصلة. */
    private void drawReplacedLine(Canvas canvas, Bitmap background, int pageW, int pageH,
                                   RectF originalBox, String translated) {
        RectF box = new RectF(
                originalBox.left - LINE_PAD_PT,
                originalBox.top - LINE_PAD_PT,
                originalBox.right + LINE_PAD_PT,
                originalBox.bottom + LINE_PAD_PT);
        box.left = Math.max(0, box.left);
        box.top = Math.max(0, box.top);
        box.right = Math.min(pageW, box.right);
        box.bottom = Math.min(pageH, box.bottom);
        if (box.width() <= 2f || box.height() <= 2f) return;

        int fillColor = sampleBackgroundColor(background, pageW, pageH, box);
        int textColor = readableTextColorOn(fillColor);

        Paint erase = new Paint(Paint.ANTI_ALIAS_FLAG);
        erase.setColor(fillColor);
        canvas.drawRect(box, erase);

        // نصوص RTL مختلطة بأرقام/كلمات لاتينية (مدى صفحات "146-149" مثلًا)
        // بترسم بترتيب مقلوب لو اتبعتت مباشرة لـ Canvas.drawText - نفس
        // المشكلة اللي BidiText.fix() مصمّم لحلها أصلًا لعرض TextView،
        // وبتنطبق هنا بالظبط لأن نفس محرّك النص (Minikin) بيرسم الاثنين.
        String bidiSafe = rtl ? BidiText.fix(translated) : translated;

        TextPaint tp = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        tp.setColor(textColor);
        float fontSize = Math.max(MIN_REPLACE_FONT_PT, box.height() * 0.72f);
        tp.setTextSize(fontSize);
        // النص المترجم غالبًا أطول من الأصل (خصوصًا عند الترجمة للعربية) -
        // نقلّل حجم الخط تدريجيًا حتى يتّسع عرضه داخل نفس عرض صندوق السطر
        // الأصلي على سطر واحد، بدل ما يفيض خارج مكانه.
        while (fontSize > MIN_REPLACE_FONT_PT && tp.measureText(bidiSafe) > box.width()) {
            fontSize -= FONT_STEP_PT;
            tp.setTextSize(fontSize);
        }

        String toDraw = bidiSafe;
        if (tp.measureText(toDraw) > box.width()) {
            CharSequence ellipsized = TextUtils.ellipsize(toDraw, tp, box.width(), TextUtils.TruncateAt.END);
            toDraw = ellipsized.toString();
        }

        tp.setTextAlign(rtl ? Paint.Align.RIGHT : Paint.Align.LEFT);
        FontMetrics fm = tp.getFontMetrics();
        float baseline = box.centerY() - (fm.ascent + fm.descent) / 2f;
        float x = rtl ? box.right : box.left;
        canvas.drawText(toDraw, x, baseline, tp);
    }

    /** يعاين لون بكسل من صورة خلفية الصفحة عند زاوية صندوق السطر (منطقة
     *  خلفية صافية غالبًا، غير مغطاة بالحرف نفسه) - بيدّي لون مسح أقرب
     *  لحقيقة الصفحة من افتراض أبيض ثابت (مهم لصفحات ذات خلفية داكنة زي
     *  شرائح بعض المحاضرات). background بإحداثيات بكسل مختلفة عن box
     *  (بوحدة نقطة PDF) لكن بنفس نسبة الأبعاد دائمًا، فبنحوّل بمقياس بسيط. */
    private static int sampleBackgroundColor(Bitmap background, int pageW, int pageH, RectF box) {
        if (background == null || background.isRecycled()) return Color.WHITE;
        int bw = background.getWidth();
        int bh = background.getHeight();
        if (bw <= 0 || bh <= 0 || pageW <= 0 || pageH <= 0) return Color.WHITE;
        float sx = bw / (float) pageW;
        float sy = bh / (float) pageH;
        int sampleX = clamp(Math.round(box.left * sx), 0, bw - 1);
        int sampleY = clamp(Math.round((box.top - 2f) * sy), 0, bh - 1);
        try {
            return background.getPixel(sampleX, sampleY);
        } catch (Exception e) {
            return Color.WHITE;
        }
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private static int readableTextColorOn(int bgColor) {
        double luminance = (0.299 * Color.red(bgColor) + 0.587 * Color.green(bgColor) + 0.114 * Color.blue(bgColor)) / 255.0;
        return luminance > 0.55 ? Color.BLACK : Color.WHITE;
    }

    private void drawCoverPage(String sourceTitle, String targetLangLabel, int pageCount) {
        PdfDocument.Page page = startPage(FALLBACK_WIDTH, FALLBACK_HEIGHT);
        Canvas canvas = page.getCanvas();
        canvas.drawColor(Color.WHITE);

        TextPaint titlePaint = paint(20f, true, Color.BLACK);
        TextPaint metaPaint = paint(11.5f, false, Color.rgb(110, 110, 110));

        String title = sourceTitle == null || sourceTitle.trim().isEmpty() ? "ترجمة ملف PDF" : sourceTitle.trim();
        String meta = String.format(Locale.US, "ترجمة تلقائية إلى %s · %d صفحة - كل صفحة تحافظ على تصميمها الأصلي، وكل سطر نص يُستبدل بترجمته في مكانه",
                targetLangLabel, pageCount);

        float y = MARGIN + 20;
        y = drawWrapped(canvas, title, titlePaint, MARGIN, y, FALLBACK_WIDTH - 2 * MARGIN) + 10;
        drawWrapped(canvas, meta, metaPaint, MARGIN, y, FALLBACK_WIDTH - 2 * MARGIN);

        doc.finishPage(page);
    }

    private float drawWrapped(Canvas canvas, String text, TextPaint paint, float left, float top, int width) {
        StaticLayout layout = StaticLayout.Builder
                .obtain(text, 0, text.length(), paint, width)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setTextDirection(rtl ? TextDirectionHeuristics.RTL : TextDirectionHeuristics.LTR)
                .setLineSpacing(4f, 1f)
                .setIncludePad(false)
                .build();
        canvas.save();
        canvas.translate(left, top);
        layout.draw(canvas);
        canvas.restore();
        return top + layout.getHeight();
    }

    private static TextPaint paint(float sizePt, boolean bold, int color) {
        TextPaint p = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        p.setTextSize(sizePt);
        p.setColor(color);
        p.setFakeBoldText(bold);
        return p;
    }
}
