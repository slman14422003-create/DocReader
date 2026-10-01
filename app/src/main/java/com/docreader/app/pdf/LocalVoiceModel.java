package com.docreader.app.pdf;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * نموذج صوتي محلي يتعلّم ذاتيًا على الجهاز (بلا إنترنت ولا مكتبات خارجية ولا حدود استخدام).
 *
 * هو ليس شبكة عصبية تولّد الصوت (هذا يحتاج نموذجًا بعشرات الميغابايت)، بل نموذج إحصائي تكيّفي يتعلّم من
 * المحرّكين أثناء القراءة الفعلية ويحسّن قرارات القارئ:
 *
 *  1) إيقاع النطق: يقيس سرعة الصوت العصبي (مللي ثانية لكل حرف منطوق، لكل لغة) من الصوت الحقيقي الذي
 *     يُنتجه الخادم، ويقيس سرعة محرّك الجهاز وصوته من مدة نطقه الفعلية. ثم يحسب معامل سرعة لصوت الجهاز
 *     يجعل إيقاعه مطابقًا للعصبي - فلا يتغيّر الإحساس بالسرعة عند الرجوع التلقائي بين المحرّكين.
 *  2) صحة الأصوات: يسجّل نجاح/فشل كل صوت جهاز (بحسب المحرك) ويقدّم الأصوات الأوثق عند الاختيار التلقائي.
 *  3) يتحسّن مع الاستعمال: متوسط متحرّك تكيّفي (يبدأ سريعًا ثم يستقر)، مع رفض القيم الشاذة (انقطاع مكالمة،
 *     مقطع قصير جدًا، نطق متقطّع)، ويُحفظ في ملف على الجهاز فيبقى التعلّم بين الجلسات.
 *
 * كل الدوال آمنة للاستدعاء من أي خيط ولا ترمي استثناءات للمستدعي.
 */
final class LocalVoiceModel {

    private static final String FILE_NAME = "local_voice_model.txt";
    private static final long SAVE_EVERY_MS = 20_000L;
    private static final int MIN_CHARS = 14;          // مقاطع أقصر من هذا تُهمَل (الكمون يطغى على القياس)
    private static final int CONFIDENT_SAMPLES = 6;   // أقل عدد عيّنات لكل طرف قبل أن نطبّق المعايرة
    private static final float MUL_MIN = 0.70f;
    private static final float MUL_MAX = 1.45f;
    private static final Object LOCK = new Object();

    /** إحصاء متحرّك: متوسط ms لكل حرف + عدد العيّنات. */
    private static final class Stat {
        float msPerChar;
        int n;
    }

    /** صحة صوت: نجاحات وإخفاقات. */
    private static final class Health {
        float ok;
        float fail;
    }

    private static File file;
    private static boolean loaded;
    private static boolean dirty;
    private static long lastSave;

    /** العصبي مُطبَّعًا على سرعة 0% (قبل نسبة الأسلوب)، مفتاحه اللغة. */
    private static final Map<String, Stat> neural = new HashMap<>();
    /** الجهاز مُطبَّعًا على سرعة النطق 1.0، مفتاحه: حزمة المحرك|اسم الصوت|اللغة. */
    private static final Map<String, Stat> device = new HashMap<>();
    private static final Map<String, Health> health = new HashMap<>();

    private LocalVoiceModel() {
    }

    // ------------------------------------------------------------------ تهيئة وحفظ

    static void init(File dir) {
        synchronized (LOCK) {
            if (loaded || dir == null) return;
            file = new File(dir, FILE_NAME);
            try {
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
        if (System.currentTimeMillis() - lastSave >= SAVE_EVERY_MS) save(false);
    }

    private static void save(boolean force) {
        if (file == null || (!dirty && !force)) return;
        lastSave = System.currentTimeMillis();
        dirty = false;
        File tmp = new File(file.getPath() + ".tmp");
        try (Writer w = new OutputStreamWriter(new FileOutputStream(tmp), "UTF-8")) {
            for (Map.Entry<String, Stat> e : neural.entrySet()) {
                w.write("N\t" + e.getKey() + "\t" + e.getValue().msPerChar + "\t" + e.getValue().n + "\n");
            }
            for (Map.Entry<String, Stat> e : device.entrySet()) {
                w.write("D\t" + e.getKey() + "\t" + e.getValue().msPerChar + "\t" + e.getValue().n + "\n");
            }
            for (Map.Entry<String, Health> e : health.entrySet()) {
                w.write("H\t" + e.getKey() + "\t" + e.getValue().ok + "\t" + e.getValue().fail + "\n");
            }
        } catch (IOException e) {
            return;
        }
        if (!tmp.renameTo(file)) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
            //noinspection ResultOfMethodCallIgnored
            tmp.renameTo(file);
        }
    }

    private static void load() throws IOException {
        if (file == null || !file.exists()) return;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(file), "UTF-8"))) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] p = line.split("\t", -1);
                try {
                    if (p.length < 4) continue;
                    if (p[0].equals("N") || p[0].equals("D")) {
                        Stat s = new Stat();
                        s.msPerChar = Float.parseFloat(p[2]);
                        s.n = Integer.parseInt(p[3]);
                        if (s.msPerChar > 5f && s.msPerChar < 600f && s.n > 0) {
                            (p[0].equals("N") ? neural : device).put(p[1], s);
                        }
                    } else if (p[0].equals("H")) {
                        Health h = new Health();
                        h.ok = Float.parseFloat(p[2]);
                        h.fail = Float.parseFloat(p[3]);
                        health.put(p[1], h);
                    }
                } catch (RuntimeException ignored) {
                    // سطر تالف: نتجاوزه
                }
            }
        }
    }

    // ------------------------------------------------------------------ التعلّم

    /** متوسط متحرّك: سريع في البداية (1/n) ثم مستقر (حدّ أدنى 0.08). الشاذّ (خارج 0.4x-2.5x) بعد الثقة يُرفض. */
    private static void feed(Map<String, Stat> m, String key, float ms) {
        Stat s = m.get(key);
        if (s == null) {
            s = new Stat();
            s.msPerChar = ms;
            s.n = 1;
            m.put(key, s);
            return;
        }
        if (s.n >= 4 && (ms < s.msPerChar * 0.4f || ms > s.msPerChar * 2.5f)) return;
        s.n = Math.min(s.n + 1, 100000);
        float a = Math.max(0.08f, 1f / s.n);
        s.msPerChar += (ms - s.msPerChar) * a;
    }

    /**
     * من صوت عصبي حقيقي جاهز: chars = طول النص المنطوق، audioMs = مدة الصوت الفعلية، stylePct = نسبة سرعة الأسلوب
     * المطبّقة على هذا الصوت (نُطبّعها لنقارن بشكل عادل بعد تغيير أسلوب النطق).
     */
    static void learnNeural(String lang, int chars, int audioMs, int stylePct) {
        try {
            if (lang == null || chars < MIN_CHARS || audioMs < 400) return;
            float factor = Math.max(0.5f, 1f + stylePct / 100f);
            float ms = (float) audioMs / chars * factor; // على سرعة 0%
            if (ms < 10f || ms > 500f) return;
            synchronized (LOCK) {
                feed(neural, lang, ms);
                touch();
            }
        } catch (Throwable ignored) {
        }
    }

    /** من نطق جهاز اكتمل كاملًا: durationMs من onStart إلى onDone، rateUsed = سرعة المحرك وقتها. */
    static void learnDevice(String engine, String voice, String lang, int chars, long durationMs, float rateUsed) {
        try {
            if (lang == null || chars < MIN_CHARS || durationMs < 400 || durationMs > 120_000 || rateUsed <= 0f) return;
            float ms = (float) durationMs / chars * rateUsed; // على سرعة 1.0
            if (ms < 10f || ms > 600f) return;
            synchronized (LOCK) {
                feed(device, devKey(engine, voice, lang), ms);
                touch();
            }
        } catch (Throwable ignored) {
        }
    }

    /** نجاح/فشل صوت جهاز (فشل = خطأ من المحرك لهذا المقطع). */
    static void noteVoice(String engine, String voice, boolean ok) {
        try {
            if (voice == null) return;
            synchronized (LOCK) {
                String k = engine + "|" + voice;
                Health h = health.get(k);
                if (h == null) {
                    h = new Health();
                    health.put(k, h);
                }
                // تقادم: الأحدث أهم - نقلّص التاريخ القديم لو كبر
                if (h.ok + h.fail > 200f) {
                    h.ok *= 0.5f;
                    h.fail *= 0.5f;
                }
                if (ok) h.ok += 1f;
                else h.fail += 1f;
                touch();
            }
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------ الاستفادة من التعلّم

    /**
     * معامل يُضرب في سرعة صوت الجهاز ليطابق إيقاع العصبي. 1.0 لو لم تتوفر عيّنات كافية من الطرفين.
     * سرعة الجهاز المطلوبة = سرعةالمستخدم × (msPerChar الجهاز عند 1.0 ÷ msPerChar العصبي المطلوب).
     */
    static float deviceRateMultiplier(String engine, String voice, String lang, int currentStylePct) {
        try {
            synchronized (LOCK) {
                Stat n = neural.get(lang);
                Stat d = device.get(devKey(engine, voice, lang));
                if (n == null || d == null || n.n < CONFIDENT_SAMPLES || d.n < CONFIDENT_SAMPLES) return 1f;
                float nTarget = n.msPerChar / Math.max(0.5f, 1f + currentStylePct / 100f);
                float mul = d.msPerChar / nTarget;
                return Math.max(MUL_MIN, Math.min(MUL_MAX, mul));
            }
        } catch (Throwable t) {
            return 1f;
        }
    }

    /** صحة صوت 0..1 (قبل أي بيانات = 0.67 حياديًا). */
    static float voiceHealth(String engine, String voice) {
        try {
            synchronized (LOCK) {
                Health h = health.get(engine + "|" + voice);
                if (h == null) return 0.67f;
                return (h.ok + 2f) / (h.ok + h.fail + 3f);
            }
        } catch (Throwable t) {
            return 0.67f;
        }
    }

    /** تعديل نقاط اختيار الصوت التلقائي (-4..+4): الأوثق يتقدّم، المتعثّر يتأخر. */
    static int voiceScoreBonus(String engine, String voice) {
        return Math.max(-4, Math.min(4, Math.round((voiceHealth(engine, voice) - 0.67f) * 12f)));
    }

    static void reset() {
        synchronized (LOCK) {
            neural.clear();
            device.clear();
            health.clear();
            dirty = true;
            save(true);
        }
    }

    static String stats() {
        synchronized (LOCK) {
            StringBuilder sb = new StringBuilder("النموذج الصوتي المحلي: ");
            if (neural.isEmpty() && device.isEmpty() && health.isEmpty()) return sb.append("لم يتعلّم بعد").toString();
            for (Map.Entry<String, Stat> e : neural.entrySet()) {
                sb.append(String.format(Locale.ROOT, "[عصبي %s: %.0fms/حرف ن=%d] ", e.getKey(), e.getValue().msPerChar, e.getValue().n));
            }
            int dn = 0;
            for (Stat s : device.values()) dn += s.n;
            sb.append("[جهاز: ").append(device.size()).append(" أصوات، ").append(dn).append(" عيّنة] ");
            sb.append("[أصوات مقيَّمة: ").append(health.size()).append("]");
            return sb.toString();
        }
    }

    private static String devKey(String engine, String voice, String lang) {
        return (engine == null ? "" : engine) + "|" + (voice == null ? "" : voice) + "|" + lang;
    }
}
