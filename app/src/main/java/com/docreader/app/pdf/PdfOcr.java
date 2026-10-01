package com.docreader.app.pdf;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.RectF;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;

import com.googlecode.tesseract.android.TessBaseAPI;
import com.tom_roush.pdfbox.cos.COSName;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.PDPage;
import com.tom_roush.pdfbox.pdmodel.PDResources;
import com.tom_roush.pdfbox.pdmodel.graphics.PDXObject;
import com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject;
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * التعرّف الضوئي على الحروف (OCR) للقراءة الصوتية من ملفات PDF الممسوحة ضوئيًا أو المصوَّرة
 * (صفحات صور بلا طبقة نص، أو بطبقة نص تالفة).
 *
 * المسار: عرض الصفحة كصورة (PdfRenderer) ← تجهيز الصورة (تدرّج رمادي + تمديد التباين، وعند ضعف
 * الثقة تمريرة ثانية بعتبة تكيّفية للصور المظلَّلة/غير المتجانسة الإضاءة، ونختار الأفضل) ←
 * Tesseract (عربي + إنجليزي معًا، نماذج LSTM الأدق) ← تحليل مخرجات hOCR إلى كلمات بمواضعها
 * (نسبة من أبعاد الصفحة) بنفس نموذج PdfSpeechText.Word، فيعمل التظليل والقراءة كما في الملفات العادية.
 *
 * نموذج اللغة يُنزَّل مرة واحدة فقط (من مستودع Tesseract الرسمي) ثم يعمل التعرّف بدون إنترنت.
 * نتيجة كل صفحة تُحفظ في الكاش فلا يُعاد التعرّف عليها، ونُجهّز الصفحتين التاليتين مسبقًا في الخلفية
 * أثناء القراءة حتى لا يحدث فراغ عند الانتقال.
 */
final class PdfOcr {

    private PdfOcr() {
    }

    // ------------------------------------------------------------------ الإعداد (تلقائي / دائمًا / متوقف)

    static final int MODE_AUTO = 0;
    static final int MODE_ALWAYS = 1;
    static final int MODE_OFF = 2;
    private static final String PREFS = "pdf_ocr";
    private static final String KEY_MODE = "mode";
    private static final String[] MODE_LABELS = {"تلقائي", "دائمًا", "متوقف"};

    static int getMode(Context ctx) {
        int m = prefs(ctx).getInt(KEY_MODE, MODE_AUTO);
        return m < 0 || m > 2 ? MODE_AUTO : m;
    }

    static void cycleMode(Context ctx) {
        prefs(ctx).edit().putInt(KEY_MODE, (getMode(ctx) + 1) % 3).apply();
    }

    static String modeLabel(Context ctx) {
        return MODE_LABELS[getMode(ctx)];
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ------------------------------------------------------------------ الحالة والأخطاء

    interface StatusSink {
        void onStatus(String message);
    }

    private static volatile StatusSink sink;
    private static String fatalError;

    static void setStatusSink(StatusSink s) {
        sink = s;
    }

    private static void report(String msg) {
        StatusSink k = sink;
        if (k != null) k.onStatus(msg);
    }

    private static synchronized void setFatal(String msg) {
        fatalError = msg;
    }

    /** خطأ يمنع التعرّف من الأساس (تعذّر تنزيل النموذج مثلًا) - يُرجَع مرة واحدة ثم يُمسح. */
    static synchronized String takeFatalError() {
        String m = fatalError;
        fatalError = null;
        return m;
    }

    // ------------------------------------------------------------------ كشف الصفحة الممسوحة

    private static final long BIG_IMAGE_PIXELS = 400_000L;

    /** هل في الصفحة صورة كبيرة (مسح ضوئي)؟ عند تعذّر الفحص نفترض نعم. */
    static boolean pageLooksScanned(PDDocument doc, int pageIndex) {
        try {
            PDPage page = doc.getPage(pageIndex);
            return hasBigImage(page.getResources(), 0);
        } catch (Throwable t) {
            return true;
        }
    }

    private static boolean hasBigImage(PDResources res, int depth) {
        if (res == null || depth > 2) return false;
        try {
            for (COSName name : res.getXObjectNames()) {
                PDXObject x = res.getXObject(name);
                if (x instanceof PDImageXObject) {
                    PDImageXObject img = (PDImageXObject) x;
                    if ((long) img.getWidth() * (long) img.getHeight() >= BIG_IMAGE_PIXELS) return true;
                } else if (x instanceof PDFormXObject) {
                    if (hasBigImage(((PDFormXObject) x).getResources(), depth + 1)) return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    // ------------------------------------------------------------------ نماذج اللغة (تنزيل مرة واحدة)

    private static final String[] LANGS = {"ara", "eng"};
    private static final String[] MODEL_URLS = {
            "https://raw.githubusercontent.com/tesseract-ocr/tessdata_best/main/%s.traineddata",
            "https://raw.githubusercontent.com/tesseract-ocr/tessdata_fast/main/%s.traineddata",
    };
    private static final long MIN_MODEL_BYTES = 800_000L;

    private static final OkHttpClient HTTP = new OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build();

    private static File dataDir(Context ctx) {
        return new File(ctx.getFilesDir(), "ocr");
    }

    private static File tessdataDir(Context ctx) {
        return new File(dataDir(ctx), "tessdata");
    }

    private static boolean modelsReady(Context ctx) {
        for (String l : LANGS) {
            File f = new File(tessdataDir(ctx), l + ".traineddata");
            if (!f.exists() || f.length() < MIN_MODEL_BYTES) return false;
        }
        return true;
    }

    private static boolean ensureModels(Context ctx, boolean foreground) {
        if (modelsReady(ctx)) return true;
        File dir = tessdataDir(ctx);
        if (!dir.exists() && !dir.mkdirs()) return false;
        for (int i = 0; i < LANGS.length; i++) {
            File target = new File(dir, LANGS[i] + ".traineddata");
            if (target.exists() && target.length() >= MIN_MODEL_BYTES) continue;
            boolean ok = false;
            for (String pattern : MODEL_URLS) {
                if (downloadModel(String.format(Locale.US, pattern, LANGS[i]), target, foreground, i + 1, LANGS.length)) {
                    ok = true;
                    break;
                }
            }
            if (!ok) return false;
        }
        return modelsReady(ctx);
    }

    private static boolean downloadModel(String url, File target, boolean foreground, int idx, int total) {
        File part = new File(target.getParentFile(), target.getName() + ".part");
        try {
            Request req = new Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").build();
            try (Response resp = HTTP.newCall(req).execute()) {
                if (!resp.isSuccessful() || resp.body() == null) return false;
                long len = resp.body().contentLength();
                long done = 0;
                int lastPct = -1;
                try (InputStream in = resp.body().byteStream(); FileOutputStream out = new FileOutputStream(part)) {
                    byte[] buf = new byte[32 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        done += n;
                        if (foreground) {
                            int pct = len > 0 ? (int) (done * 100 / len) : -1;
                            if (pct != lastPct && (pct < 0 || pct % 5 == 0)) {
                                lastPct = pct;
                                report("جارٍ تنزيل نموذج التعرّف على النص الممسوح (" + idx + "/" + total + ")"
                                        + (pct >= 0 ? " " + pct + "%" : "") + " - مرة واحدة فقط");
                            }
                        }
                    }
                }
            }
            if (part.length() < MIN_MODEL_BYTES) {
                part.delete();
                return false;
            }
            if (target.exists()) target.delete();
            return part.renameTo(target);
        } catch (Throwable t) {
            part.delete();
            return false;
        }
    }

    // ------------------------------------------------------------------ المحرك (نسخة واحدة، خيط واحد في كل مرة)

    private static final Object LOCK = new Object();
    private static TessBaseAPI engine;

    private static TessBaseAPI ensureEngine(Context ctx, boolean foreground) {
        if (engine != null) return engine;
        if (!ensureModels(ctx, foreground)) {
            setFatal("تعذّر تنزيل نموذج التعرّف على النص الممسوح. تأكد من اتصال الإنترنت ثم أعد المحاولة"
                    + " (يُنزَّل مرة واحدة فقط ويعمل بعدها بدون إنترنت).");
            return null;
        }
        try {
            TessBaseAPI api = new TessBaseAPI();
            if (!api.init(dataDir(ctx).getAbsolutePath(), "ara+eng")) {
                destroy(api);
                // ملف نموذج تالف: نحذفه ليُعاد تنزيله في المحاولة القادمة
                for (String l : LANGS) new File(tessdataDir(ctx), l + ".traineddata").delete();
                setFatal("تعذّر تشغيل محرك التعرّف على النص (ملف النموذج تالف). سيُعاد تنزيله عند المحاولة التالية.");
                return null;
            }
            api.setPageSegMode(3); // PSM_AUTO: تحليل تخطيط تلقائي (أعمدة/فقرات) بدون كشف اتجاه
            api.setVariable("preserve_interword_spaces", "1");
            engine = api;
            return api;
        } catch (Throwable t) {
            setFatal("تعذّر تشغيل محرك التعرّف على النص الممسوح.");
            return null;
        }
    }

    /** إغلاق المحرك وتحرير ذاكرته (على خيط خلفي كي لا ننتظر تمريرة جارية على الخيط الرئيسي). */
    static void release() {
        cancelWarm();
        new Thread(() -> {
            synchronized (LOCK) {
                if (engine != null) {
                    destroy(engine);
                    engine = null;
                }
            }
        }, "ocr-release").start();
    }

    /** اسم دالة التحرير اختلف بين إصدارات المكتبة (recycle / end) فنستدعيها بالانعكاس. */
    private static void destroy(TessBaseAPI api) {
        try {
            api.getClass().getMethod("recycle").invoke(api);
            return;
        } catch (Throwable ignored) {
        }
        try {
            api.getClass().getMethod("end").invoke(api);
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------ تجهيز الصفحة القادمة في الخلفية

    private static final ExecutorService WARM = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ocr-warm");
        t.setDaemon(true);
        return t;
    });
    private static volatile int warmGen = 0;

    static void warmAhead(Context ctx, File file, int from, int count, int pageCount) {
        final Context app = ctx.getApplicationContext();
        final int gen = warmGen;
        for (int i = 0; i < count; i++) {
            final int p = from + i;
            if (p >= pageCount) break;
            WARM.execute(() -> {
                if (gen != warmGen) return;
                recognizePage(app, file, p, false);
            });
        }
    }

    static void cancelWarm() {
        warmGen++;
    }

    // ------------------------------------------------------------------ التعرّف على صفحة

    private static final float TARGET_WIDTH = 2000f;
    private static final float MAX_PIXELS = 6_200_000f;
    private static final int RETRY_BELOW_CONF = 72;

    /**
     * يرجّع كلمات الصفحة (بترتيب القراءة ومواضعها كنسبة من أبعاد الصفحة)، أو null عند الفشل
     * (والسبب في takeFatalError لو كان عائقًا دائمًا). قائمة فاضية = صفحة بلا نص.
     */
    static List<PdfSpeechText.Word> recognizePage(Context ctx, File file, int pageIndex, boolean foreground) {
        final Context app = ctx.getApplicationContext();
        final File cache = cacheFile(app, file, pageIndex);
        List<PdfSpeechText.Word> cached = readCache(cache);
        if (cached != null) return cached;
        synchronized (LOCK) {
            cached = readCache(cache); // ربما أنهتها التمريرة الخلفية أثناء الانتظار
            if (cached != null) return cached;
            try {
                if (foreground) report("جارٍ التعرّف على نص الصفحة " + (pageIndex + 1) + " (صفحة ممسوحة ضوئيًا)...");
                TessBaseAPI api = ensureEngine(app, foreground);
                if (api == null) return null;
                Rendered r = renderPage(file, pageIndex);
                if (r == null) return null;
                List<PdfSpeechText.Word> words = ocr(api, r);
                r.bmp.recycle();
                writeCache(cache, words);
                return words;
            } catch (Throwable t) {
                return null;
            }
        }
    }

    private static final class Rendered {
        Bitmap bmp;
        int dpi;
    }

    private static Rendered renderPage(File file, int pageIndex) {
        float[] factors = {1f, 0.7f}; // لو نفدت الذاكرة نعيد العرض بدقة أقل
        for (float f : factors) {
            try (ParcelFileDescriptor pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
                 PdfRenderer renderer = new PdfRenderer(pfd)) {
                if (pageIndex < 0 || pageIndex >= renderer.getPageCount()) return null;
                try (PdfRenderer.Page page = renderer.openPage(pageIndex)) {
                    float pw = Math.max(1, page.getWidth());
                    float ph = Math.max(1, page.getHeight());
                    float scale = (TARGET_WIDTH * f) / pw;
                    float maxPx = MAX_PIXELS * f * f;
                    if (pw * scale * ph * scale > maxPx) scale = (float) Math.sqrt(maxPx / (pw * ph));
                    int w = Math.max(64, Math.round(pw * scale));
                    int h = Math.max(64, Math.round(ph * scale));
                    Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                    bmp.eraseColor(0xFFFFFFFF);
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                    Rendered r = new Rendered();
                    r.bmp = bmp;
                    r.dpi = Math.round(scale * 72f);
                    return r;
                }
            } catch (OutOfMemoryError oom) {
                continue;
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }

    private static final class Pass {
        List<RawWord> words = new ArrayList<>();
        float score;
        float meanConf;
    }

    private static List<PdfSpeechText.Word> ocr(TessBaseAPI api, Rendered r) {
        Bitmap bmp = r.bmp;
        int w = bmp.getWidth();
        int h = bmp.getHeight();
        api.setVariable("user_defined_dpi", String.valueOf(Math.max(70, r.dpi)));

        byte[] gray = grayStretch(bmp);
        int[] scratch = new int[w * h];
        fillBitmap(bmp, gray, scratch);
        Pass best = runPass(api, bmp);

        // صورة ضعيفة الثقة (ظلال/إضاءة غير متجانسة/جودة منخفضة): تمريرة ثانية بعتبة تكيّفية ونأخذ الأفضل
        if (best.meanConf < RETRY_BELOW_CONF) {
            try {
                byte[] bin = bradley(gray, w, h);
                fillBitmap(bmp, bin, scratch);
                Pass second = runPass(api, bmp);
                if (second.score > best.score) best = second;
            } catch (OutOfMemoryError ignored) {
            }
        }
        return toWords(best.words, w, h);
    }

    private static Pass runPass(TessBaseAPI api, Bitmap bmp) {
        Pass p = new Pass();
        try {
            api.setImage(bmp);
            String hocr = api.getHOCRText(0);
            p.words = parseHocr(hocr);
            float sum = 0f;
            for (RawWord rw : p.words) {
                sum += rw.conf;
                if (rw.conf >= 50) p.score += rw.conf;
            }
            p.meanConf = p.words.isEmpty() ? 0f : sum / p.words.size();
        } finally {
            try {
                api.clear();
            } catch (Throwable ignored) {
            }
        }
        return p;
    }

    // ------------------------------------------------------------------ تجهيز الصورة

    /** تدرّج رمادي + تمديد التباين (النقطة السوداء/البيضاء من المدرّج التكراري) لتوحيد الخلفية الرمادية/الصفراء. */
    private static byte[] grayStretch(Bitmap bmp) {
        int w = bmp.getWidth();
        int h = bmp.getHeight();
        int n = w * h;
        int[] px = new int[n];
        bmp.getPixels(px, 0, w, 0, 0, w, h);
        byte[] gray = new byte[n];
        int[] hist = new int[256];
        for (int i = 0; i < n; i++) {
            int p = px[i];
            int g = (77 * ((p >> 16) & 255) + 151 * ((p >> 8) & 255) + 28 * (p & 255)) >> 8;
            gray[i] = (byte) g;
            hist[g]++;
        }
        int lo = 0;
        int acc = 0;
        for (; lo < 255; lo++) {
            acc += hist[lo];
            if (acc > n * 0.005f) break;
        }
        int hi = 255;
        acc = 0;
        for (; hi > 0; hi--) {
            acc += hist[hi];
            if (acc > n * 0.01f) break;
        }
        if (hi - lo < 40) {
            lo = 0;
            hi = 255;
        }
        float k = 255f / (hi - lo);
        for (int i = 0; i < n; i++) {
            int v = Math.round(((gray[i] & 255) - lo) * k);
            gray[i] = (byte) (v < 0 ? 0 : Math.min(v, 255));
        }
        return gray;
    }

    private static void fillBitmap(Bitmap bmp, byte[] gray, int[] scratch) {
        int w = bmp.getWidth();
        int h = bmp.getHeight();
        for (int i = 0; i < gray.length; i++) {
            int v = gray[i] & 255;
            scratch[i] = 0xFF000000 | (v << 16) | (v << 8) | v;
        }
        bmp.setPixels(scratch, 0, w, 0, 0, w, h);
    }

    /** عتبة تكيّفية (Bradley) بالصورة التكاملية: كل بكسل يُقارن بمتوسط محيطه، فتتحمّل الظلال. */
    private static byte[] bradley(byte[] gray, int w, int h) {
        final int stride = w + 1;
        int[] integral = new int[stride * (h + 1)];
        for (int y = 0; y < h; y++) {
            int rowSum = 0;
            for (int x = 0; x < w; x++) {
                rowSum += gray[y * w + x] & 255;
                integral[(y + 1) * stride + (x + 1)] = integral[y * stride + (x + 1)] + rowSum;
            }
        }
        final int half = Math.max(15, w / 24);
        final int sensitivity = 14; // % تحت متوسط المحيط يُعدّ حبرًا
        byte[] out = new byte[w * h];
        for (int y = 0; y < h; y++) {
            int y1 = Math.max(0, y - half);
            int y2 = Math.min(h - 1, y + half);
            for (int x = 0; x < w; x++) {
                int x1 = Math.max(0, x - half);
                int x2 = Math.min(w - 1, x + half);
                int count = (x2 - x1 + 1) * (y2 - y1 + 1);
                int sum = integral[(y2 + 1) * stride + (x2 + 1)] - integral[y1 * stride + (x2 + 1)]
                        - integral[(y2 + 1) * stride + x1] + integral[y1 * stride + x1];
                int g = gray[y * w + x] & 255;
                out[y * w + x] = (byte) (((long) g * count * 100L < (long) sum * (100 - sensitivity)) ? 0 : 255);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ تحليل hOCR

    private static final class RawWord {
        String text;
        int x0, y0, x1, y1;
        int conf;
        int line;
    }

    private static final Pattern TAG = Pattern.compile(
            "<span\\s+class=['\"](ocr_line|ocr_header|ocr_caption|ocr_textfloat|ocrx_word)['\"][^>]*?title=['\"]([^'\"]*)['\"][^>]*>");
    private static final Pattern BBOX = Pattern.compile("bbox\\s+(-?\\d+)\\s+(-?\\d+)\\s+(-?\\d+)\\s+(-?\\d+)");
    private static final Pattern WCONF = Pattern.compile("x_wconf\\s+(\\d+)");
    private static final Pattern NUM_ENTITY = Pattern.compile("&#(\\d+);");

    private static List<RawWord> parseHocr(String hocr) {
        List<RawWord> out = new ArrayList<>();
        if (hocr == null || hocr.isEmpty()) return out;
        Matcher m = TAG.matcher(hocr);
        int line = -1;
        while (m.find()) {
            if (!"ocrx_word".equals(m.group(1))) {
                line++;
                continue;
            }
            if (line < 0) line = 0;
            int end = hocr.indexOf("</span>", m.end());
            if (end < 0) break;
            String title = m.group(2);
            Matcher bb = BBOX.matcher(title);
            if (!bb.find()) continue;
            String text = unescape(hocr.substring(m.end(), end).replaceAll("<[^>]*>", ""));
            text = cleanToken(text);
            if (text.isEmpty()) continue;
            RawWord rw = new RawWord();
            rw.text = text;
            rw.x0 = Integer.parseInt(bb.group(1));
            rw.y0 = Integer.parseInt(bb.group(2));
            rw.x1 = Integer.parseInt(bb.group(3));
            rw.y1 = Integer.parseInt(bb.group(4));
            Matcher wc = WCONF.matcher(title);
            rw.conf = wc.find() ? Integer.parseInt(wc.group(1)) : 60;
            rw.line = line;
            out.add(rw);
        }
        return out;
    }

    private static String unescape(String s) {
        if (s.indexOf('&') < 0) return s;
        s = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                .replace("&#39;", "'").replace("&apos;", "'");
        Matcher m = NUM_ENTITY.matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String rep;
            try {
                rep = new String(Character.toChars(Integer.parseInt(m.group(1))));
            } catch (Throwable t) {
                rep = "";
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(rep));
        }
        m.appendTail(sb);
        return sb.toString().replace("&amp;", "&");
    }

    /** يحذف علامات الاتجاه/الأحرف غير المنطوقة والتطويل التي تُربك النطق. */
    private static String cleanToken(String t) {
        StringBuilder sb = new StringBuilder(t.length());
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == '\u200E' || c == '\u200F' || (c >= '\u202A' && c <= '\u202E')
                    || (c >= '\u2066' && c <= '\u2069') || c == '\uFEFF' || c == '\u0640'
                    || c == '\u200B' || c == '\u200C' || c == '\u200D' || c == '\u00AD') continue;
            sb.append(c);
        }
        return sb.toString().trim();
    }

    private static boolean isSentencePunct(String t) {
        for (int i = 0; i < t.length(); i++) {
            if (".,;:!?\u060C\u061B\u061F\u2026".indexOf(t.charAt(i)) < 0) return false;
        }
        return !t.isEmpty();
    }

    private static int letterCount(String t) {
        int n = 0;
        for (int i = 0; i < t.length(); i++) if (Character.isLetter(t.charAt(i))) n++;
        return n;
    }

    /**
     * كلمات OCR ← كلمات القراءة: يحذف الشوائب (رموز منفردة/كلمات ضعيفة الثقة القصيرة)، ويلصق علامة الترقيم
     * المنفردة بالكلمة قبلها (كي تُنطق وقفة لا كلمة)، ويحوّل المواضع إلى نسبة من أبعاد الصفحة.
     */
    private static List<PdfSpeechText.Word> toWords(List<RawWord> raw, int w, int h) {
        List<RawWord> kept = new ArrayList<>();
        for (RawWord r : raw) {
            String t = r.text;
            int letters = letterCount(t);
            boolean hasDigit = false;
            for (int i = 0; i < t.length(); i++) if (Character.isDigit(t.charAt(i))) hasDigit = true;
            if (letters == 0 && !hasDigit) {
                // ترقيم منفرد: نلصقه بالكلمة السابقة في نفس السطر، وأي رمز آخر (| _ ~ •) نتجاهله
                if (isSentencePunct(t) && !kept.isEmpty() && kept.get(kept.size() - 1).line == r.line) {
                    RawWord prev = kept.get(kept.size() - 1);
                    prev.text = prev.text + t;
                    prev.x0 = Math.min(prev.x0, r.x0);
                    prev.y0 = Math.min(prev.y0, r.y0);
                    prev.x1 = Math.max(prev.x1, r.x1);
                    prev.y1 = Math.max(prev.y1, r.y1);
                }
                continue;
            }
            if (r.conf < 15) continue;
            if (r.conf < 30 && letters + (hasDigit ? 1 : 0) <= 2) continue;
            kept.add(r);
        }
        List<PdfSpeechText.Word> out = new ArrayList<>(kept.size());
        float fw = Math.max(1, w);
        float fh = Math.max(1, h);
        for (RawWord r : kept) {
            RectF box = new RectF(clamp01(r.x0 / fw), clamp01(r.y0 / fh), clamp01(r.x1 / fw), clamp01(r.y1 / fh));
            out.add(new PdfSpeechText.Word(r.text, box, r.line));
        }
        return out;
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : Math.min(v, 1f);
    }

    // ------------------------------------------------------------------ الكاش (نتيجة كل صفحة على القرص)

    private static final String CACHE_HEADER = "#ocr1";

    private static File cacheFile(Context ctx, File pdf, int page) {
        String sig = Integer.toHexString(pdf.getAbsolutePath().hashCode()) + "_" + pdf.length() + "_" + pdf.lastModified();
        return new File(new File(ctx.getCacheDir(), "ocr_v1/" + sig), "p" + page + ".tsv");
    }

    private static List<PdfSpeechText.Word> readCache(File f) {
        if (!f.exists()) return null;
        try (BufferedReader br = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String first = br.readLine();
            if (!CACHE_HEADER.equals(first)) return null;
            List<PdfSpeechText.Word> out = new ArrayList<>();
            String ln;
            while ((ln = br.readLine()) != null) {
                String[] p = ln.split("\t", 6);
                if (p.length < 6) continue;
                RectF box = new RectF(Float.parseFloat(p[1]), Float.parseFloat(p[2]),
                        Float.parseFloat(p[3]), Float.parseFloat(p[4]));
                out.add(new PdfSpeechText.Word(p[5], box, Integer.parseInt(p[0])));
            }
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    private static void writeCache(File f, List<PdfSpeechText.Word> words) {
        try {
            File dir = f.getParentFile();
            if (dir != null && !dir.exists() && !dir.mkdirs()) return;
            File tmp = new File(dir, f.getName() + ".tmp");
            try (BufferedWriter bw = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8))) {
                bw.write(CACHE_HEADER);
                bw.write('\n');
                for (PdfSpeechText.Word w : words) {
                    bw.write(String.format(Locale.US, "%d\t%.5f\t%.5f\t%.5f\t%.5f\t%s\n",
                            w.line, w.box.left, w.box.top, w.box.right, w.box.bottom,
                            w.text.replace('\t', ' ').replace('\n', ' ')));
                }
            }
            if (f.exists()) f.delete();
            tmp.renameTo(f);
        } catch (Throwable ignored) {
        }
    }
}
