package com.docreader.app.pdf;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * عميل خفيف لخدمة ترجمة جوجل المجانية (نفس الواجهة غير الرسمية التي تعتمد
 * عليها إضافات المتصفح وأدوات الترجمة المجانية - translate_a/single بمعامل
 * client=gtx) - بدون أي مفتاح API أو اشتراك، تُستخدم من PdfViewerActivity
 * لترجمة نص صفحة PDF (النص مُستخرَج فعليًا عبر PdfBox، مش OCR).
 *
 * لأن هذه الواجهة غير رسمية وممكن تحظر عنوان IP مؤقتًا (خطأ 429) لو تكرر
 * النداء عليها بسرعة كبيرة كأنه بوت، هذا العميل "يلتف" حولها بأربع طبقات:
 *
 *  1) طابور مُسلسَل بخيط واحد فقط: أبدًا لا يوجد طلبان يخرجان بالتوازي،
 *     حتى لو المستخدم ضغط "ترجمة" على صفحتين بسرعة.
 *  2) فاصل زمني أدنى إلزامي بين أي طلبين متتاليين (حتى لو نجحا) - يحاكي
 *     سلوك متصفح عادي بدل قصف الخدمة.
 *  3) إعادة محاولة بتأخير متصاعد (exponential backoff + jitter) عند فشل
 *     مؤقت (429/5xx/انقطاع شبكة)، مع تبديل تلقائي بين مضيفين مختلفين
 *     لنفس الواجهة (translate.googleapis.com و translate.google.com)
 *     حتى لا يعتمد كل شيء على مضيف واحد قد يكون محظورًا مؤقتًا.
 *  4) ذاكرة تخزين مؤقت صغيرة (نفس نص الصفحة + نفس اللغة الهدف) حتى لا
 *     تُعاد ترجمة نفس الصفحة مرتين لو رجع المستخدم إليها.
 *  5) محرّك احتياطي مستقل (MyMemory، مجاني وبدون مفتاح) يُجرَّب تلقائيًا
 *     لو جوجل فشلت في كل محاولاتها على المضيفين الاتنين - "أكثر من محرّك
 *     ترجمة" بدل الاعتماد الكلي على جوجل (انظر translateWithMyMemory).
 */
final class GoogleTranslateClient {

    private GoogleTranslateClient() {
    }

    interface Callback {
        /** يُستدعى دائمًا على الخيط الرئيسي. عند الفشل: translated=null وerror != null. */
        void onDone(String translated, Exception error);
    }

    interface LinesCallback {
        /** يُستدعى دائمًا على الخيط الرئيسي. عند النجاح translatedLines بنفس
         *  عدد وترتيب الأسطر المُرسَلة تمامًا (انظر translateLinesAsync). */
        void onDone(List<String> translatedLines, Exception error);
    }

    private static final String[] HOSTS = {
            "https://translate.googleapis.com",
            "https://translate.google.com",
    };

    private static final int TIMEOUT_MS = 12000;
    private static final int MAX_CHUNK_CHARS = 1800;
    private static final int MAX_ATTEMPTS = 3; // لكل جزء نص
    private static final long MIN_INTERVAL_MS = 400; // فاصل أدنى بين أي طلبين
    private static final long BASE_BACKOFF_MS = 900;
    private static final int CACHE_MAX_ENTRIES = 40;

    /** خيط واحد فقط لكل طلبات الترجمة - يضمن التسلسل (لا تزامن) تلقائيًا. */
    private static final ExecutorService queue = Executors.newSingleThreadExecutor();
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());
    private static final Object throttleLock = new Object();
    private static volatile long lastRequestAtMs = 0L;

    private static final Object cacheLock = new Object();
    private static final LinkedHashMap<String, String> cache = new LinkedHashMap<String, String>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > CACHE_MAX_ENTRIES;
        }
    };

    /** يترجم نصًا (يُقسَّم تلقائيًا لو طويل) إلى لغة الهدف. غير حاجب - النتيجة
     *  ترجع عبر callback على الخيط الرئيسي. آمن للاستدعاء من الخيط الرئيسي مباشرة. */
    static void translateAsync(String text, String targetLangCode, Callback callback) {
        if (text == null || text.trim().isEmpty()) {
            postResult(callback, "", null);
            return;
        }
        final String trimmed = text.trim();
        queue.execute(() -> {
            try {
                String result = translateBlocking(trimmed, targetLangCode);
                postResult(callback, result, null);
            } catch (Exception e) {
                postResult(callback, null, e);
            }
        });
    }

    private static void postResult(Callback callback, String result, Exception error) {
        if (callback == null) return;
        mainHandler.post(() -> callback.onDone(result, error));
    }

    /**
     * يترجم قائمة أسطر مع الحفاظ على المحاذاة سطرًا بسطر (نفس عدد وترتيب
     * lines بالضبط في النتيجة) - يُستخدم من TranslatedPdfBuilder/
     * PdfViewerActivity حتى يُرسم كل سطر مترجم في مكان سطره الأصلي بالظبط،
     * بدل ترجمة نص الصفحة ككتلة واحدة مدموجة في لوحة منفصلة.
     *
     * الأسطر بتتبعت في نداء واحد (أو دفعات قليلة لو تجاوز مجموع طولها
     * الحد الأقصى) مفصولة بسطر جديد "\n" - هذه الواجهة غير الرسمية بترجع
     * كل سطر كعنصر منفصل في مصفوفة الجمل عند وجود "\n" بين المدخلات (نفس
     * الأسلوب المُستخدم في أدوات ترجمة ملفات الترجمة المصاحبة SRT)، فبنعتمد
     * عليه للحصول على محاذاة 1:1 في الحالة الشائعة (سريع، طلب واحد للدفعة
     * كلها). لو اختلف عدد العناصر الراجعة - بيحصل كتير في مستندات كثيفة
     * الأسطر القصيرة زي السير الذاتية - كل سطر من الدفعة دي بيتترجم لوحده
     * بطلب مستقل (انظر alignToBatch) بدل ما يترك بنصه الأجنبي الأصلي وسط
     * صفحة مترجمة.
     */
    static void translateLinesAsync(List<String> lines, String targetLangCode, LinesCallback callback) {
        if (lines == null || lines.isEmpty()) {
            postLinesResult(callback, new ArrayList<>(), null);
            return;
        }
        final List<String> copy = new ArrayList<>(lines);
        queue.execute(() -> {
            try {
                List<String> result = translateLinesBlocking(copy, targetLangCode);
                postLinesResult(callback, result, null);
            } catch (Exception e) {
                postLinesResult(callback, null, e);
            }
        });
    }

    private static void postLinesResult(LinesCallback callback, List<String> result, Exception error) {
        if (callback == null) return;
        mainHandler.post(() -> callback.onDone(result, error));
    }

    /** يشتغل فقط على خيط الطابور الداخلي (queue). لا يرمي أبدًا - أي سطر
     *  يتعذّر ترجمته بأي طريقة بيرجع بنصه الأصلي (انظر alignToBatch) بدل ما
     *  يوقف ترجمة باقي الصفحة. */
    private static List<String> translateLinesBlocking(List<String> lines, String targetLangCode) {
        List<String> result = new ArrayList<>(lines.size());
        for (List<String> batch : splitLinesIntoBatches(lines, MAX_CHUNK_CHARS)) {
            result.addAll(translateBatchWithRetry(batch, targetLangCode));
        }
        return result;
    }

    /** يجمّع الأسطر في دفعات تحت الحد الأقصى لطول الطلب، بدون تقطيع سطر
     *  واحد أبدًا بين دفعتين (لو سطر واحد أطول من الحد الأقصى، بيتبعت في
     *  دفعة لوحده كما هو - حالة نادرة جدًا لسطر PDF عادي). */
    private static List<List<String>> splitLinesIntoBatches(List<String> lines, int maxLen) {
        List<List<String>> batches = new ArrayList<>();
        List<String> current = new ArrayList<>();
        int currentLen = 0;
        for (String line : lines) {
            String safe = line == null ? "" : line;
            int addLen = safe.length() + 1;
            if (!current.isEmpty() && currentLen + addLen > maxLen) {
                batches.add(current);
                current = new ArrayList<>();
                currentLen = 0;
            }
            current.add(safe);
            currentLen += addLen;
        }
        if (!current.isEmpty()) batches.add(current);
        return batches;
    }

    /** لا يرمي أبدًا - أي فشل (جوجل، المحرّك الاحتياطي، أو محاذاة غير
     *  مؤكدة) بينتهي بترجمة فردية مضمونة لكل سطر فيه شك (انظر alignToBatch). */
    private static List<String> translateBatchWithRetry(List<String> batch, String targetLangCode) {
        String joined = joinLines(batch);
        List<String> sentences = null;
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            throttleBeforeRequest();
            String host = HOSTS[attempt % HOSTS.length];
            try {
                sentences = translateBatchOnce(host, joined, targetLangCode);
                break;
            } catch (IOException e) {
                if (attempt < MAX_ATTEMPTS - 1) sleepQuietly(backoffDelay(attempt));
            }
        }
        if (sentences == null) {
            // جوجل رفضت/فشلت في كل المحاولات على المضيفين الاتنين - نجرّب
            // محرّك احتياطي مستقل (MyMemory) على الدفعة كلها كمحاولة أخيرة
            // سريعة قبل اللجوء للترجمة الفردية الأبطأ تحت.
            try {
                throttleBeforeRequest();
                sentences = splitByNewline(translateWithMyMemory(joined, targetLangCode));
            } catch (IOException fallbackError) {
                sentences = new ArrayList<>();
            }
        }
        return alignToBatch(sentences, batch, targetLangCode);
    }

    /** يقسّم ردًا نصيًا واحدًا من محرّك احتياطي (ما بيرجعش مصفوفة جمل زي
     *  جوجل) على أسطر جديدة لمحاولة محاذاته مع عدد الأسطر الأصلي - محاولة
     *  سريعة أولى فقط؛ alignToBatch بعدها بتتأكد من المحاذاة وتترجم أي سطر
     *  مش متأكد منه لوحده بطلب مستقل بدل الاعتماد على هذا التقسيم وحده. */
    private static List<String> splitByNewline(String blob) {
        if (blob == null) return new ArrayList<>();
        String[] parts = blob.split("\n");
        List<String> out = new ArrayList<>(parts.length);
        for (String p : parts) out.add(p.trim());
        return out;
    }

    private static String joinLines(List<String> lines) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) sb.append('\n');
            sb.append(lines.get(i));
        }
        return sb.toString();
    }

    /**
     * لو عدد الجمل الراجعة من جوجل/المحرّك الاحتياطي طابق عدد الأسطر
     * المُرسَلة بالظبط - محاذاة 1:1 مباشرة ومضمونة (الحالة الشائعة السريعة
     * لصفحات فيها فقرات نص عادية طويلة). لو اختلف العدد - وده بيحصل كتير
     * في مستندات كثيفة الأسطر القصيرة زي السير الذاتية (كل سطر كلمة أو
     * اتنين: تاريخ، مسمّى وظيفي، مهارة...) لأن تقسيم جوجل الداخلي للجمل مش
     * مضمون يطابق كل سطر PDF لوحده - **مفيش تخمين محاذاة هنا خالص**: كل
     * سطر من الدفعة دي بيتترجم لوحده بطلب مستقل (translateBlocking، وعنده
     * نفس إعادة المحاولة + المحرّك الاحتياطي أصلًا) فتضمن محاذاة صحيحة
     * 100% مهما كان طول السطر. ده أبطأ من المحاذاة الجماعية، لكنه اللي
     * بيمنع فعليًا مشكلة "كلمات أجنبية متروكة وسط نص مترجم" اللي كانت
     * بتظهر قبل كده لما نستسلم ونرجّع النص الأصلي كما هو.
     */
    private static List<String> alignToBatch(List<String> sentences, List<String> originalBatch, String targetLangCode) {
        List<String> aligned = new ArrayList<>(originalBatch.size());
        boolean matched = sentences.size() == originalBatch.size();
        for (int i = 0; i < originalBatch.size(); i++) {
            String original = originalBatch.get(i);
            if (matched) {
                aligned.add(sentences.get(i));
                continue;
            }
            try {
                aligned.add(translateBlocking(original, targetLangCode));
            } catch (Exception e) {
                // فشلت حتى المحاولة الفردية (شبكة منقطعة تمامًا مثلًا) -
                // نرجّع النص الأصلي لهذا السطر تحديدًا بدل ما نوقف الصفحة
                // كلها؛ TranslatedPdfBuilder.addPage بتسيبه بخلفيته الأصلية
                // زي ما هو لما الترجمة تطابق الأصل حرفيًا.
                aligned.add(original);
            }
        }
        return aligned;
    }

    private static List<String> translateBatchOnce(String host, String joinedText, String targetLangCode) throws IOException {
        HttpURLConnection conn = null;
        try {
            String encoded = URLEncoder.encode(joinedText, "UTF-8");
            String url = host + "/translate_a/single?client=gtx&sl=auto&tl="
                    + targetLangCode + "&dt=t&q=" + encoded;
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124 Mobile Safari/537.36");

            int status = conn.getResponseCode();
            if (status == 429 || status >= 500) {
                throw new IOException("رفضت خدمة الترجمة الطلب مؤقتًا (كود " + status + ").");
            }
            if (status < 200 || status >= 300) {
                throw new IOException("طلب ترجمة غير ناجح (كود " + status + ").");
            }

            String body = readStream(conn.getInputStream());
            return parseTranslatedSentences(body);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** نفس شكل رد translate_a/single الموضّح أعلى parseTranslatedText، لكن
     *  هنا بنرجّع كل "جملة" كعنصر منفصل في القائمة بدل دمجهم في نص واحد -
     *  هو ده اللي بيدّينا محاذاة سطر-لسطر مع المدخلات المفصولة بـ"\n". */
    private static List<String> parseTranslatedSentences(String json) throws IOException {
        try {
            JSONArray root = new JSONArray(json);
            JSONArray sentences = root.getJSONArray(0);
            List<String> out = new ArrayList<>();
            for (int i = 0; i < sentences.length(); i++) {
                JSONArray sentence = sentences.optJSONArray(i);
                if (sentence == null || sentence.isNull(0)) continue;
                out.add(sentence.getString(0).replace("\n", "").trim());
            }
            return out;
        } catch (Exception e) {
            throw new IOException("رد غير متوقع من خدمة الترجمة.", e);
        }
    }

    /** يشتغل فقط على خيط الطابور الداخلي (queue) - لا يُستدعى مباشرة من الخارج. */
    private static String translateBlocking(String text, String targetLangCode) throws IOException {
        String cacheKey = targetLangCode + "\u0001" + text;
        synchronized (cacheLock) {
            String hit = cache.get(cacheKey);
            if (hit != null) return hit;
        }

        List<String> chunks = splitIntoChunks(text, MAX_CHUNK_CHARS);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < chunks.size(); i++) {
            if (i > 0 && needsSeparator(out)) out.append("\n\n");
            out.append(translateChunkWithRetry(chunks.get(i), targetLangCode));
        }
        String result = out.toString().trim();

        synchronized (cacheLock) {
            cache.put(cacheKey, result);
        }
        return result;
    }

    private static boolean needsSeparator(StringBuilder sb) {
        return sb.length() > 0 && sb.charAt(sb.length() - 1) != '\n';
    }

    private static String translateChunkWithRetry(String chunk, String targetLangCode) throws IOException {
        IOException lastError = null;
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            throttleBeforeRequest();
            String host = HOSTS[attempt % HOSTS.length];
            try {
                return translateChunkOnce(host, chunk, targetLangCode);
            } catch (IOException e) {
                lastError = e;
                if (attempt < MAX_ATTEMPTS - 1) {
                    sleepQuietly(backoffDelay(attempt));
                }
            }
        }
        try {
            throttleBeforeRequest();
            return translateWithMyMemory(chunk, targetLangCode);
        } catch (IOException fallbackError) {
            throw lastError != null ? lastError : fallbackError;
        }
    }

    /**
     * محرّك ترجمة احتياطي مستقل (MyMemory - https://mymemory.translated.net،
     * مجاني وبدون مفتاح API) يُستخدم فقط لو جوجل رفضت/فشلت الطلب في كل
     * محاولاته - "أكثر من محرّك ترجمة" بدل الاعتماد الكامل على جوجل واللي
     * ممكن يحظر IP مؤقتًا. ملاحظة صادقة: MyMemory (بخلاف واجهة جوجل غير
     * الرسمية) بيرجّع النص المترجم ككتلة واحدة مش كمصفوفة جمل، فمحاذاته
     * بالأسطر أضعف (splitByNewline أعلى - أفضل الجهد فقط)، وبيفترض أن لغة
     * المصدر إنجليزية (langpair=en|<هدف>) لأنه بيحتاج كود لغة مصدر صريح
     * (بعكس sl=auto في جوجل) - مناسب لمعظم كتب/شرائح المستخدم الإنجليزية،
     * لكن ممكن يترجم غلط لو المصدر لغة تانية. حد الطول ~500 حرف لكل طلب في
     * النسخة المجانية، فبنستخدمه هنا على نفس حجم الدفعة/الجزء المرسَل
     * لجوجل أصلًا (MAX_CHUNK_CHARS = 1800 أكبر من الحد - قد يُقتطع الرد لو
     * وصل الجزء لأقصى طول، حالة نادرة وأفضل من فشل كامل).
     */
    private static String translateWithMyMemory(String text, String targetLangCode) throws IOException {
        HttpURLConnection conn = null;
        try {
            String encoded = URLEncoder.encode(text, "UTF-8");
            String url = "https://api.mymemory.translated.net/get?q=" + encoded
                    + "&langpair=en|" + targetLangCode;
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124 Mobile Safari/537.36");

            int status = conn.getResponseCode();
            if (status == 429 || status >= 500) {
                throw new IOException("رفض المحرّك الاحتياطي الطلب مؤقتًا (كود " + status + ").");
            }
            if (status < 200 || status >= 300) {
                throw new IOException("طلب ترجمة احتياطي غير ناجح (كود " + status + ").");
            }
            String body = readStream(conn.getInputStream());
            return parseMyMemoryText(body);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** شكل الرد: {"responseData":{"translatedText":"..."}, "responseStatus":200, ...} */
    private static String parseMyMemoryText(String json) throws IOException {
        try {
            JSONObject root = new JSONObject(json);
            JSONObject data = root.optJSONObject("responseData");
            String text = data != null ? data.optString("translatedText", "") : "";
            if (text.isEmpty()) throw new IOException("رد فاضٍ من المحرّك الاحتياطي.");
            return text;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("رد غير متوقع من المحرّك الاحتياطي.", e);
        }
    }

    /** يضمن فاصلًا زمنيًا أدنى بين أي طلبين فعليين للخدمة - حتى لو من إعادة محاولة. */
    private static void throttleBeforeRequest() {
        synchronized (throttleLock) {
            long now = System.currentTimeMillis();
            long elapsed = now - lastRequestAtMs;
            if (elapsed < MIN_INTERVAL_MS) {
                sleepQuietly(MIN_INTERVAL_MS - elapsed);
            }
            lastRequestAtMs = System.currentTimeMillis();
        }
    }

    private static long backoffDelay(int attempt) {
        long base = BASE_BACKOFF_MS * (1L << attempt);
        long jitter = (long) (Math.random() * 250);
        return base + jitter;
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(Math.max(0, ms));
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private static String translateChunkOnce(String host, String chunk, String targetLangCode) throws IOException {
        HttpURLConnection conn = null;
        try {
            String encoded = URLEncoder.encode(chunk, "UTF-8");
            String url = host + "/translate_a/single?client=gtx&sl=auto&tl="
                    + targetLangCode + "&dt=t&q=" + encoded;
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            // بدون هذا الترويسة بعض المضيفين يرفضون الطلب فورًا (403) لأنه يبدو كبوت صريح.
            conn.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124 Mobile Safari/537.36");

            int status = conn.getResponseCode();
            if (status == 429 || status >= 500) {
                throw new IOException("رفضت خدمة الترجمة الطلب مؤقتًا (كود " + status + ").");
            }
            if (status < 200 || status >= 300) {
                throw new IOException("طلب ترجمة غير ناجح (كود " + status + ").");
            }

            String body = readStream(conn.getInputStream());
            return parseTranslatedText(body);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** شكل الرد: [[["مترجم1","أصلي1",...], ["مترجم2","أصلي2",...], ...], null, "en"] */
    private static String parseTranslatedText(String json) throws IOException {
        try {
            JSONArray root = new JSONArray(json);
            JSONArray sentences = root.getJSONArray(0);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < sentences.length(); i++) {
                JSONArray sentence = sentences.optJSONArray(i);
                if (sentence == null || sentence.isNull(0)) continue;
                sb.append(sentence.getString(0));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IOException("رد غير متوقع من خدمة الترجمة.", e);
        }
    }

    /** يقسّم نصًا طويلًا لأجزاء تحت الحد الأقصى، محاولًا القطع عند فقرة/سطر
     *  كامل بدل تقطيع كلمة أو جملة في المنتصف، مع قطع صارم كحل أخير. */
    private static List<String> splitIntoChunks(String text, int maxLen) {
        List<String> chunks = new ArrayList<>();
        if (text.length() <= maxLen) {
            chunks.add(text);
            return chunks;
        }
        String[] paragraphs = text.split("\n+");
        StringBuilder current = new StringBuilder();
        for (String para : paragraphs) {
            if (para.trim().isEmpty()) continue;
            if (current.length() > 0 && current.length() + para.length() + 1 > maxLen) {
                chunks.add(current.toString());
                current.setLength(0);
            }
            if (para.length() > maxLen) {
                if (current.length() > 0) {
                    chunks.add(current.toString());
                    current.setLength(0);
                }
                for (int i = 0; i < para.length(); i += maxLen) {
                    chunks.add(para.substring(i, Math.min(para.length(), i + maxLen)));
                }
            } else {
                if (current.length() > 0) current.append('\n');
                current.append(para);
            }
        }
        if (current.length() > 0) chunks.add(current.toString());
        return chunks.isEmpty() ? java.util.Collections.singletonList(text.substring(0, Math.min(text.length(), maxLen))) : chunks;
    }

    private static String readStream(InputStream is) throws IOException {
        if (is == null) return "";
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            return sb.toString();
        }
    }
}
