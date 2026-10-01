package com.docreader.app.pdf;

import android.content.Context;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * قاموس تشكيل محلي بالكامل (بدون إنترنت ولا ذكاء اصطناعي ولا حدود استخدام).
 *
 * يُبنى مرة واحدة على الكمبيوتر من مدوّنة عربية مشكولة عبر tools/build_tashkeel_dict.py ويوضع في
 * app/src/main/assets/tashkeel_dict.txt (كل سطر: كلمة_بلا_تشكيل TAB كلمة_مشكولة).
 *
 * هذا الصنف مخزن بيانات فقط؛ منطق المطابقة (توحيد الهمزات، السوابق، الضمائر، التدقيق) في WordVerifier.
 * SpeechPrep.diacritize ينادي lookup() بعد القاموس اليدوي (D) وقاموس المصطلحات الطبية (ArabicPhonetics.lookup).
 *
 * تحسينات هذه النسخة:
 *  - فهرس ثانٍ موحَّد (الهمزات وغيرها) لمطابقة "الالم" مع "الألم"؛ والكلمات الملتبسة (تشترك في المفتاح الموحّد) تُعلَّم
 *    ملتبسة فلا تُطابَق إلا حرفيًا.
 *  - تنظيف الأسطر عند التحميل (حروف مختلفة بعد إزالة التشكيل، تنوين في وسط الكلمة = كلمتان ملتصقتان، حركتان على
 *    حرف، كلمة بلا أي علامة).
 *  - awaitReady(): يمكن للقارئ انتظار اكتمال التحميل بدل أن تُنطق الجملة الأولى بلا قاموس.
 *  - الفهارس تُبنى مرة واحدة ثم تُنشر دفعة واحدة (لا قراءة لنصف محمَّل).
 *
 * مكتبات الكلمات (DictionaryPacks): لم يعد القاموس ملفًا واحدًا؛ يُدمج فيه كل ما فعّله المستخدم من مكتبات (الأساسية +
 * مدمجة إضافية + مستورَدة). عند تعارض كلمة بين مكتبتين تفوز الأعلى أولوية. بعد الدمج يتدرّب LetterModel (الذكاء المحلي
 * الذي يفهم القاموس حرفًا حرفًا) على كل الكلمات المشكولة. reload()/reloadAsync() يعيدان الدمج بعد تغيير اختيارات المستخدم.
 */
final class TashkeelDict {

    /** قيمة في الفهرس الموحّد تعني: أكثر من كلمة تشترك في هذا المفتاح (لا نخمّن). */
    private static final String AMBIGUOUS = "\u0000";
    private static final Object LOCK = new Object();
    private static final CountDownLatch READY = new CountDownLatch(1);

    private static volatile Map<String, String> map = null;   // الكلمة بلا تشكيل -> المشكولة
    private static volatile Map<String, String> norm = null;  // المفتاح الموحّد -> المشكولة (أو AMBIGUOUS)
    private static volatile int rejected = 0;

    private TashkeelDict() {
    }

    /** يحمّل القاموس ومكتبات المستخدم المفعّلة (مرة واحدة). يمكن استدعاؤه من أكثر من خيط؛ اللاحق ينتظر السابق. */
    static void load(Context ctx) {
        if (map != null) return;
        synchronized (LOCK) {
            if (map != null) return;
            build(ctx);
        }
        trainLetterModel();
    }

    /** يعيد بناء القاموس من المكتبات المفعّلة الآن (بعد تغيير المستخدم لاختياراته). القاموس القديم يبقى صالحًا حتى يجهز الجديد. */
    static void reload(Context ctx) {
        synchronized (LOCK) {
            build(ctx);
        }
        trainLetterModel();
    }

    /** reload في خيط خلفي؛ done يُنادى (من الخيط الخلفي) بعد انتهاء الدمج والتدريب. */
    static void reloadAsync(final Context ctx, final Runnable done) {
        final Context app = ctx.getApplicationContext();
        new Thread(() -> {
            try {
                reload(app);
            } catch (Throwable ignored) {
            }
            if (done != null) done.run();
        }, "tashkeel-dict-reload").start();
    }

    private static void trainLetterModel() {
        try {
            Map<String, String> m = map;
            if (m == null) return;
            // التنبؤ اختياري ومتوقف افتراضيًا: لا نُدرّب (ولا نستهلك ذاكرة) إلا لو فعّله المستخدم
            if (LetterModel.isEnabled()) LetterModel.train(m.values());
            else LetterModel.releaseBase();
        } catch (Throwable ignored) {
            // فشل التدريب (ذاكرة مثلًا): يبقى القاموس يعمل بالبحث المباشر كما كان
        }
    }

    /** الدمج الفعلي. يُنادى داخل LOCK فقط. المكتبات تُقرأ من الأقل أولوية إلى الأعلى فيتغلّب الأعلى عند التعارض. */
    private static void build(Context ctx) {
        try {
            DictionaryPacks.applyPrefs(ctx);
        } catch (Throwable ignored) {
        }
        Map<String, String> m = new HashMap<>(1 << 18);
        Map<String, String> nm = new HashMap<>(1 << 17);
        final int[] bad = {0};
        java.util.List<DictionaryPacks.Pack> packs;
        try {
            packs = DictionaryPacks.enabledInMergeOrder(ctx);
        } catch (Throwable t) {
            packs = java.util.Collections.emptyList();
        }
        for (DictionaryPacks.Pack pack : packs) {
            final int[] st = new int[4]; // أسطر، مقبولة، مرفوضة، تجاوزات
            final Map<String, String> fm = m;
            final Map<String, String> fnm = nm;
            try {
                DictionaryPacks.read(ctx, pack, (plain, shaped) -> {
                    st[0]++;
                    if (!WordVerifier.acceptEntry(plain, shaped)) {
                        st[2]++;
                        bad[0]++;
                        return;
                    }
                    String before = fm.put(plain, shaped);
                    st[1]++;
                    String nk = WordVerifier.normKey(plain);
                    boolean replaced = before != null && !before.equals(shaped);
                    if (replaced) st[3]++;
                    if (!nk.equals(plain)) {
                        String cur = fnm.get(nk);
                        if (replaced && cur != null && cur.equals(before)) {
                            fnm.put(nk, shaped); // نفس الكلمة بتشكيل مكتبة أعلى أولوية: استبدال لا التباس
                        } else {
                            String old = fnm.put(nk, shaped);
                            if (old != null && !old.equals(shaped)) fnm.put(nk, AMBIGUOUS);
                        }
                    }
                });
            } catch (Throwable ignored) {
                // مكتبة غير موجودة أو تالفة: نتجاوزها ويبقى ما حُمِّل من غيرها
            }
            DictionaryPacks.recordStats(pack.id, st[0], st[1], st[2], st[3]);
        }
        rejected = bad[0];
        norm = nm;
        map = m;
        READY.countDown();
    }

    /** هل اكتمل التحميل (ولو كان القاموس فارغًا لغياب الملف)؟ */
    static boolean isReady() {
        return map != null;
    }

    /** ينتظر اكتمال التحميل حتى المهلة المعطاة (بالمللي ثانية). لا تستدعه من خيط الواجهة. */
    static boolean awaitReady(long millis) {
        if (map != null) return true;
        try {
            return READY.await(millis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return map != null;
        }
    }

    static int size() {
        Map<String, String> m = map;
        return m == null ? 0 : m.size();
    }

    /** عدد الأسطر التي رُفضت عند التحميل لفسادها (تشخيص). */
    static int rejectedCount() {
        return rejected;
    }

    /** كل كلمات القاموس بلا تشكيل (للتشخيص والاقتراحات فقط؛ لا تعدّلها). */
    static Collection<String> keys() {
        Map<String, String> m = map;
        return m == null ? Collections.<String>emptySet() : Collections.unmodifiableCollection(m.keySet());
    }

    /** مطابقة حرفية فقط. null لو غير موجودة أو القاموس غير محمَّل بعد. */
    static String exact(String plain) {
        Map<String, String> m = map;
        if (m == null || plain == null) return null;
        return m.get(plain);
    }

    /**
     * مطابقة بالمفتاح الموحّد (WordVerifier.normKey) لكلمة غير موجودة حرفيًا.
     * null لو لا يوجد، أو لو ملتبسة (كلمتان مختلفتان بنفس المفتاح)، فلا نخمّن.
     */
    static String normalized(String normKey) {
        Map<String, String> nm = norm;
        Map<String, String> m = map;
        if (nm == null || m == null || normKey == null) return null;
        String viaNorm = nm.get(normKey);
        String direct = m.get(normKey); // كلمة بلا علامات همزة مطابقة للمفتاح نفسه
        if (viaNorm == null) return direct;
        if (AMBIGUOUS.equals(viaNorm)) return null;
        if (direct != null && !direct.equals(viaNorm)) return null; // كلمتان مختلفتان: ملتبس
        return viaNorm;
    }

    /**
     * تشكيل كلمة عربية مجرّدة (3 أحرف فأكثر) أو null لو لا يوجد ما يوثق به.
     * يعمل عبر WordVerifier: تشكيل متعلَّم، ثم حرفي، ثم موحَّد الهمزات، ثم بسوابق ولواحق - وكلها مدقَّقة الحروف.
     */
    static String lookup(String plain) {
        return WordVerifier.shape(plain);
    }
}
