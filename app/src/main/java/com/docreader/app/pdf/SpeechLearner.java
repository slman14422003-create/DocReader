package com.docreader.app.pdf;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * تعلّم ذاتي محلي لتحسين النطق تلقائيًا (بلا إنترنت وبلا نموذج خارجي): ذاكرة تتكيّف مع المستخدم والملفات.
 *
 * ليس ذكاءً اصطناعيًا توليديًا؛ هو تعلّم إحصائي خفيف من ثلاثة مصادر:
 *
 *  1) الملف نفسه: لو ظهرت كلمة مشكولة في الـ PDF (عَضَلَة) تُحفظ بتشكيلها، وتُطبَّق على ظهورها بلا تشكيل في أي
 *     صفحة أو ملف لاحق. (كلما قرأتَ أكثر صار النطق أدق.)
 *  2) تصحيحات المستخدم: teach("كلمة", "نطقها") تُحفظ دائمًا وتتقدّم على كل ما عداها (عدا قاموس المستخدم اليدوي).
 *  3) سلوك الاستماع: رجوع المستخدم إلى المقطع = إشارة أن نطقه لم يكن واضحًا. الكلمات التي عدّلها التدقيق في ذلك
 *     المقطع تُتّهم؛ فإذا تكرّر الاتهام تُنطق بشكلها الأصلي بلا تدخلنا (ربما كان تشكيلنا هو الخطأ)، وإذا سُمع المقطع
 *     كاملًا بلا رجوع يخفّ الاتهام. وتتكيّف السرعة المقترحة: كثرة الرجوع تُبطّئ القراءة قليلًا (حتى 8%).
 *
 * 4) فهم الحروف: كل كلمة مشكولة تُتعلَّم (من الملف أو من تعليم المستخدم) تُغذّي أيضًا LetterModel (طبقة المستخدم)، فيتكيّف
 *    نموذج الحروف مع أسلوب المستخدم وملفاته: الكلمة الجديدة المشابهة تُشكَّل بنفس الأنماط حرفًا حرفًا.
 *
 * كل شيء محفوظ في ملف نصي صغير داخل مجلد التطبيق، بحدّ أقصى للحجم، وأولوية النطق: قاموس المستخدم > التصحيحات >
 * الاتهام (الأصل بلا تدخل) > التشكيل المتعلَّم > الافتراضي. لا يلمس النص المعروض.
 */
final class SpeechLearner {

    private SpeechLearner() {
    }

    private static final String FILE_NAME = "speech_learner.txt";
    private static final int MAX_TAUGHT = 3000;
    private static final int MAX_VOWELED = 6000;
    private static final int MAX_BLAME = 3000;
    private static final int MAX_RECENT = 800;
    private static final long SAVE_EVERY_MS = 20_000L;
    /** اتهام بهذا الحدّ فأكثر = نعيد الكلمة لشكلها الأصلي. */
    private static final float BLAME_LIMIT = 2f;

    private static final Object LOCK = new Object();

    private static File file;
    private static boolean loaded;
    private static boolean dirty;
    private static long lastSave;

    private static final Map<String, String> taught = lru(MAX_TAUGHT);
    /** مفتاح: الكلمة بلا تشكيل. القيمة: تشكيل + عدد مرات رؤيته (ت: التشكيل\tالعدد). */
    private static final Map<String, String[]> voweled = lruArr(MAX_VOWELED);
    private static final Map<String, Float> blame = lruF(MAX_BLAME);
    /** كلمات عدّلها التدقيق مؤخرًا (لتُتَّهم عند الرجوع). */
    private static final Map<String, Boolean> recentChanged = lruB(MAX_RECENT);

    private static float rewinds;
    private static float heard;

    // ------------------------------------------------------------------ بدء وحفظ

    /** يُستدعى مرة عند بدء التطبيق/القارئ. يمكن تكراره. */
    static void init(File dir) {
        synchronized (LOCK) {
            if (loaded || dir == null) return;
            try {
                file = new File(dir, FILE_NAME);
                load();
            } catch (Throwable ignored) {
            }
            loaded = true;
        }
    }

    static void flush() {
        synchronized (LOCK) {
            save(true);
        }
    }

    private static void touch() {
        dirty = true;
        long now = System.currentTimeMillis();
        if (now - lastSave >= SAVE_EVERY_MS) save(false);
    }

    private static void save(boolean force) {
        if (file == null || (!dirty && !force)) return;
        lastSave = System.currentTimeMillis();
        File tmp = new File(file.getParentFile(), FILE_NAME + ".tmp");
        try (BufferedWriter w = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(tmp), "UTF-8"))) {
            for (Map.Entry<String, String> e : taught.entrySet()) {
                w.write("T\t" + e.getKey() + "\t" + e.getValue() + "\n");
            }
            for (Map.Entry<String, String[]> e : voweled.entrySet()) {
                w.write("V\t" + e.getKey() + "\t" + e.getValue()[0] + "\t" + e.getValue()[1] + "\n");
            }
            for (Map.Entry<String, Float> e : blame.entrySet()) {
                if (e.getValue() > 0.05f) w.write("B\t" + e.getKey() + "\t" + e.getValue() + "\n");
            }
            w.write("S\t" + rewinds + "\t" + heard + "\n");
        } catch (IOException e) {
            tmp.delete();
            return;
        }
        if (!tmp.renameTo(file)) {
            file.delete();
            tmp.renameTo(file);
        }
        dirty = false;
    }

    private static void load() throws IOException {
        if (file == null || !file.exists()) return;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(file), "UTF-8"))) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] p = line.split("\t", -1);
                try {
                    if (p[0].equals("T") && p.length >= 3) {
                        taught.put(p[1], p[2]);
                        feedLetterModel(p[2], 6); // تعليم مباشر: وزن أعلى
                    } else if (p[0].equals("V") && p.length >= 4) {
                        voweled.put(p[1], new String[]{p[2], p[3]});
                        feedLetterModel(p[2], 1 + Math.min(4, parseInt(p[3])));
                    }
                    else if (p[0].equals("B") && p.length >= 3) blame.put(p[1], Float.parseFloat(p[2]));
                    else if (p[0].equals("S") && p.length >= 3) {
                        rewinds = Float.parseFloat(p[1]);
                        heard = Float.parseFloat(p[2]);
                    }
                } catch (RuntimeException ignored) {
                    // سطر تالف: نتجاوزه ونكمل
                }
            }
        }
    }

    // ------------------------------------------------------------------ مفاتيح

    private static boolean isMark(char c) {
        return ArabicPhonetics.isMark(c) || c == '\u0640';
    }

    /** الكلمة بلا تشكيل ولا تطويل، بأشكال موحّدة (ك/ي) وحروف صغيرة للاتينية - مفتاح البحث. */
    static String key(String word) {
        if (word == null || word.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(word.length());
        for (int i = 0; i < word.length(); i++) {
            char c = SpeechAuditor.unifyLetter(word.charAt(i));
            if (isMark(c)) continue;
            if (!Character.isLetterOrDigit(c)) continue;
            sb.append(Character.toLowerCase(c));
        }
        return sb.toString();
    }

    private static String bare(String s) {
        int a = 0, b = s.length();
        while (a < b && !isWordChar(s.charAt(a))) a++;
        while (b > a && !isWordChar(s.charAt(b - 1))) b--;
        return s.substring(a, b);
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || Character.getType(c) == Character.NON_SPACING_MARK;
    }

    private static String trail(String s) {
        int b = s.length();
        while (b > 0 && !isWordChar(s.charAt(b - 1))) b--;
        return s.substring(b);
    }

    private static String lead(String s) {
        int a = 0;
        while (a < s.length() && !isWordChar(s.charAt(a))) a++;
        return s.substring(0, a);
    }

    private static boolean isArabicWord(String w) {
        boolean any = false;
        for (int i = 0; i < w.length(); i++) {
            char c = w.charAt(i);
            char u = SpeechAuditor.unifyLetter(c);
            if (u >= 0x0621 && u <= 0x064A) any = true;
            else if (!isMark(c)) return false;
        }
        return any;
    }

    private static int markCount(String w) {
        int n = 0;
        for (int i = 0; i < w.length(); i++) if (ArabicPhonetics.isMark(w.charAt(i))) n++;
        return n;
    }

    private static String clean1(String s) {
        return s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim();
    }

    // ------------------------------------------------------------------ 1) التعلّم من الملف

    /** يمرّ على نص مقطع/صفحة: الكلمات العربية المشكولة تُحفظ لتطبيقها على ظهورها بلا تشكيل. */
    static void learnFromText(String text) {
        if (text == null || text.length() < 3) return;
        List<String[]> found = null;
        int n = text.length();
        int i = 0;
        while (i < n) {
            while (i < n && Character.isWhitespace(text.charAt(i))) i++;
            int s = i;
            while (i < n && !Character.isWhitespace(text.charAt(i))) i++;
            if (i <= s) continue;
            String w = bare(text.substring(s, i));
            if (w.length() < 4 || markCount(w) < 2 || !isArabicWord(w)) continue;
            String k = key(w);
            if (k.length() < 3) continue;
            String form = ArabicPhonetics.pausal(w); // بلا حركة إعراب الآخر: تصلح لأي موضع
            if (found == null) found = new ArrayList<>();
            found.add(new String[]{k, form});
        }
        if (found == null) return;
        synchronized (LOCK) {
            for (String[] f : found) {
                String[] cur = voweled.get(f[0]);
                if (cur == null) {
                    voweled.put(f[0], new String[]{f[1], "1"});
                    feedLetterModel(f[1], 1); // كلمة جديدة: يتعلّم منها نموذج الحروف مرة واحدة
                } else if (cur[0].equals(f[1])) {
                    cur[1] = String.valueOf(Math.min(1000, parseInt(cur[1]) + 1));
                } else {
                    int c = parseInt(cur[1]) - 1; // شكل مختلف: نضعف القديم حتى يحلّ الجديد
                    if (c <= 0) {
                        voweled.put(f[0], new String[]{f[1], "1"});
                        feedLetterModel(f[1], 1);
                    } else {
                        cur[1] = String.valueOf(c);
                    }
                }
            }
            touch();
        }
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * يغذّي نموذج الحروف (LetterModel) بكلمة مشكولة واحدة. يتجاهل العبارات والكلمات غير المشكولة أو غير العربية،
     * فما يُغذَّى به هو كلمة عربية بحروفها وعلاماتها فقط.
     */
    private static void feedLetterModel(String form, int weight) {
        if (form == null) return;
        String w = bare(form.trim());
        if (w.length() < 4 || w.indexOf(' ') >= 0 || markCount(w) < 2 || !isArabicWord(w)) return;
        try {
            LetterModel.learnUser(w, weight);
        } catch (RuntimeException ignored) {
        }
    }

    // ------------------------------------------------------------------ 2) تصحيحات المستخدم

    /** المستخدم يعلّم النطق الصحيح لكلمة (تُحفظ دائمًا). ينظّف الاتهام السابق للكلمة. */
    static void teach(String word, String spoken) {
        String k = key(bare(word == null ? "" : word));
        String v = clean1(spoken == null ? "" : spoken);
        if (k.isEmpty() || v.isEmpty()) return;
        synchronized (LOCK) {
            taught.put(k, v);
            blame.remove(k);
            touch();
        }
        // تعليم المستخدم أقوى إشارة: إن كان نطقًا مشكولًا لكلمة واحدة بنفس حروفها فنموذج الحروف يتعلّم منه بوزن عالٍ
        if (key(bare(v)).equals(k)) feedLetterModel(v, 6);
    }

    static void forget(String word) {
        String k = key(bare(word == null ? "" : word));
        synchronized (LOCK) {
            taught.remove(k);
            voweled.remove(k);
            blame.remove(k);
            touch();
        }
    }

    /** كل ما تعلّمه المستخدم بصيغة "كلمة=نطق" (لنسخها إلى قاموس المستخدم لو أراد). */
    static String exportTaught() {
        StringBuilder sb = new StringBuilder();
        synchronized (LOCK) {
            for (Map.Entry<String, String> e : taught.entrySet()) {
                sb.append(e.getKey()).append('=').append(e.getValue()).append('\n');
            }
        }
        return sb.toString();
    }

    static void resetAll() {
        synchronized (LOCK) {
            taught.clear();
            voweled.clear();
            blame.clear();
            recentChanged.clear();
            rewinds = 0f;
            heard = 0f;
            dirty = true;
            save(true);
        }
        LetterModel.resetUser();
    }

    // ------------------------------------------------------------------ التطبيق على الكلمة

    /**
     * ct = الرمز بعد التنظيف. sp = النطق الحالي المحسوب. يُعيد النطق النهائي.
     * لا يتدخل في كلمات المستخدم اليدوية (يتحقق منها SpeechPrep قبل الاستدعاء).
     */
    static String apply(String ct, String sp) {
        if (ct == null || sp == null || sp.isEmpty()) return sp;
        String core = bare(ct);
        if (core.isEmpty()) return sp;
        String k = key(core);
        if (k.isEmpty()) return sp;
        String pre = lead(ct);
        String post = trail(ct);
        String punct = punctOf(post);
        synchronized (LOCK) {
            String t = taught.get(k);
            if (t != null) return t + punct;
            if (!isArabicWord(core)) return sp;
            Float b = blame.get(k);
            if (b != null && b >= BLAME_LIMIT) {
                String safe = SpeechAuditor.safeForm(ct);
                if (!safe.isEmpty()) return safe;
            }
            // تشكيل متعلَّم: فقط لو كانت الكلمة بلا تشكيل ولم يشكّلها التدقيق، ولم يتغيّر هيكل حروفها
            if (markCount(core) == 0 && markCount(sp) == 0) {
                String[] v = voweled.get(k);
                if (v != null && parseInt(v[1]) >= 1) return v[0] + punct;
            }
        }
        return sp;
    }

    /**
     * تشكيل متعلَّم لكلمة عربية مجرّدة (بصيغة الوقف: بلا حركة إعراب الآخر)، أو null.
     * للقراءة فقط: يستعمله WordVerifier ليستفيد من تشكيل الملف نفسه عند تركيب السوابق واللواحق
     * (تعلّمنا "عَضَلَة" من الملف فنشكّل "عضلتها" و"بالعضلة" و"للعضلة" دون أن ترد في أي قاموس).
     * الكلمة المُتَّهمة (رجع المستخدم عندها مرتين) لا تُعاد.
     */
    static String learnedForm(String plain) {
        if (plain == null || plain.length() < 3) return null;
        String k = key(plain);
        if (k.isEmpty()) return null;
        synchronized (LOCK) {
            if (!loaded) return null;
            Float b = blame.get(k);
            if (b != null && b >= BLAME_LIMIT) return null;
            String[] v = voweled.get(k);
            if (v != null && parseInt(v[1]) >= 1) return v[0];
        }
        return null;
    }

    private static String punctOf(String post) {
        for (int i = post.length() - 1; i >= 0; i--) {
            char c = post.charAt(i);
            if (".,;:!?\u060C\u061B\u061F".indexOf(c) >= 0) return String.valueOf(c);
            if (c == '\u2026') return ".";
        }
        return "";
    }

    /** SpeechPrep يبلّغ أن التدقيق عدّل هذه الكلمة (لتُتَّهم لو رجع المستخدم). */
    static void noteChanged(String ct, String sp) {
        if (ct == null || sp == null) return;
        String k = key(bare(ct));
        if (k.length() < 2) return;
        String core = bare(ct);
        if (!isArabicWord(core)) return;
        boolean changed = markCount(sp) > markCount(core) || !key(bare(sp)).equals(k);
        if (!changed) return;
        synchronized (LOCK) {
            recentChanged.put(k, Boolean.TRUE);
        }
    }

    // ------------------------------------------------------------------ 3) سلوك الاستماع

    /** المستخدم رجع لهذا المقطع: كلماته المعدَّلة مؤخرًا تُتَّهم. */
    static void noteRewind(String rawChunk) {
        if (rawChunk == null || rawChunk.isEmpty()) return;
        synchronized (LOCK) {
            rewinds = decay(rewinds) + 1f;
            heard = decay(heard);
            List<String> cand = new ArrayList<>();
            for (String w : rawChunk.split("\\s+")) {
                String k = key(bare(w));
                if (k.length() < 2 || !recentChanged.containsKey(k) || taught.containsKey(k)) continue;
                cand.add(k);
            }
            // نوزّع الاتهام: مقطع فيه كلمات كثيرة معدَّلة لا نحمّل كل واحدة كامل الذنب
            float wgt = cand.isEmpty() ? 0f : Math.min(1f, 3f / cand.size());
            for (String k : cand) {
                Float b = blame.get(k);
                blame.put(k, (b == null ? 0f : b) + wgt);
            }
            touch();
        }
    }

    /** المقطع سُمع كاملًا بلا رجوع: يخفّ الاتهام عن كلماته وتُحسب حالة استماع ناجحة. */
    static void noteHeard(String rawChunk) {
        if (rawChunk == null || rawChunk.isEmpty()) return;
        synchronized (LOCK) {
            heard = decay(heard) + 1f;
            rewinds = decay(rewinds);
            if (!blame.isEmpty()) {
                for (String w : rawChunk.split("\\s+")) {
                    String k = key(bare(w));
                    Float b = blame.get(k);
                    if (b == null) continue;
                    float nb = b - 0.1f;
                    if (nb <= 0f) blame.remove(k);
                    else blame.put(k, nb);
                }
            }
            dirty = true;
        }
    }

    /** نُخفّف العدّادات القديمة كي تعكس السلوك الأخير لا التاريخ كله. */
    private static float decay(float v) {
        return v > 400f ? v * 0.5f : v;
    }

    /** تعديل سرعة الصوت السحابي (نسبة مئوية، 0 إلى -8): كثرة الرجوع تعني أن الكلام سريع أو غير واضح. */
    static int rateAdjustPct() {
        synchronized (LOCK) {
            float total = rewinds + heard;
            if (total < 20f) return 0;
            float ratio = rewinds / total;
            int adj = -Math.round(Math.min(8f, ratio * 40f));
            return adj;
        }
    }

    // ------------------------------------------------------------------ معلومات

    static String stats() {
        synchronized (LOCK) {
            int strict = 0;
            for (Float f : blame.values()) if (f >= BLAME_LIMIT) strict++;
            return String.format(Locale.ROOT,
                    "taught=%d | learnedVoweled=%d | blamed=%d (reverted=%d) | rewinds=%.0f | heard=%.0f | rateAdj=%d%%\n%s",
                    taught.size(), voweled.size(), blame.size(), strict, rewinds, heard, rateAdjustPct(),
                    LetterModel.stats());
        }
    }

    // ------------------------------------------------------------------ خرائط بحدّ أقصى (الأقدم استخدامًا يُحذف)

    private static <V> LinkedHashMap<String, V> lruGeneric(final int max) {
        return new LinkedHashMap<String, V>(64, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, V> eldest) {
                return size() > max;
            }
        };
    }

    private static Map<String, String> lru(int max) {
        return SpeechLearner.<String>lruGeneric(max);
    }

    private static Map<String, String[]> lruArr(int max) {
        return SpeechLearner.<String[]>lruGeneric(max);
    }

    private static Map<String, Float> lruF(int max) {
        return SpeechLearner.<Float>lruGeneric(max);
    }

    private static Map<String, Boolean> lruB(int max) {
        return SpeechLearner.<Boolean>lruGeneric(max);
    }
}
