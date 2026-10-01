package com.docreader.app.pdf;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * نموذج الحروف المحلي: "الذكاء المحلي" الذي يفهم القاموس حرفًا حرفًا وكيف يُنطق كل حرف في سياقه.
 *
 * بدل الاكتفاء بالبحث عن الكلمة كاملة في القاموس (فإن لم توجد نُطقت بلا تشكيل)، يتعلّم هذا الصنف من كل كلمة مشكولة
 * في القاموس (بعد دمج المكتبات التي اختارها المستخدم) ما الذي يتبع كل حرف: فتحة، ضمة، كسرة، سكون، شدّة، تنوين...
 * ويتذكّر ذلك حسب جيران الحرف (حرفان بعده وحرف قبله)، وحسب ما نُطق به الحرف السابق (تركيب المقاطع: ساكن بعد متحرك...)،
 * وأول الكلمة وآخرها. فإذا ظهرت كلمة غير موجودة في أي مكتبة (مصطلح جديد مثلًا) فإنه يتنبأ بتشكيلها من أنماط آلاف الكلمات
 * المشابهة، بحثًا شعاعيًا على مستوى الكلمة كلها لا حرفًا مستقلًا.
 *
 * الدقة (قِيست على القاموس الحقيقي بحجب 10% من كلماته عن التدريب): الحروف وحدها لا تحسم تشكيل كل كلمة، خصوصًا الأفعال
 * (أَبْصَرُوا/أَبْصِرُوا، أُحْكِمَت/أَحْكَمَت: يحسمها السياق لا الحروف). عند ثقة 0.80 تُشكَّل نحو 14% من الكلمات المجهولة بدقة
 * نحو 87%، وعند 0.90 نحو 4% بدقة نحو 90%. لذلك التنبؤ اختياري ومتوقف افتراضيًا (يفعّله المستخدم من إعدادات مكتبات الكلمات)،
 * وما يتعلمه المستخدم بنفسه (طبقة المستخدم) يتقدّم على إحصاء القاموس.
 *
 * طبقات الأمان (لأن التخمين الخاطئ أسوأ من ترك الكلمة للمحرك):
 *  1) لا يقبل كلمة إلا لو كان احتمال أفضل مسار لها (جداء احتمالات أصناف حروفها) فوق العتبة، وعلى دعم كافٍ من الأمثلة في كل
 *     حرف؛ وإلا تُترك الكلمة كلها (لا تشكيل جزئي).
 *  2) قواعد نطق تُرفض بها أي نتيجة مستحيلة صوتيًا: كلمة تبدأ بساكن، ساكنان متتاليان، ألف مدّ بلا فتحة قبلها،
 *     واو/ياء مدّ بلا ضمة/كسرة قبلها، إلخ. و"ال" تُطبَّق عليها قاعدة الشمسية/القمرية (ArabicLetters.isSun) بالكود.
 *  3) لا يُشكَّل آخر الكلمة أبدًا (حركة الإعراب تتغيّر بالموضع): تبقى بصيغة الوقف كما يفعل SpeechLearner.
 *
 * يتكيّف مع المستخدم: SpeechLearner يغذّيه بما يتعلمه من الملفات المشكولة وبما يعلّمه المستخدم (طبقة مستخدم منفصلة
 * بوزن أعلى تُضاف فوق طبقة القاموس ولا تُمحى عند إعادة بناء القاموس).
 *
 * كل شيء محلي (بلا إنترنت)، وآمن من أي خيط: جداول القاموس تُبنى مرة وتُنشر دفعة واحدة، وطبقة المستخدم متزامنة.
 */
final class LetterModel {

    private LetterModel() {
    }

    // ------------------------------------------------------------------ أصناف ما يتبع الحرف

    static final int NCLASS = 12;
    static final int C_NONE = 0, C_FATHA = 1, C_DAMMA = 2, C_KASRA = 3, C_SUKUN = 4,
            C_SHADDA = 5, C_SH_FATHA = 6, C_SH_DAMMA = 7, C_SH_KASRA = 8,
            C_TAN_F = 9, C_TAN_D = 10, C_TAN_K = 11;

    private static final char SHADDA = '\u0651';
    private static final char SUKUN = '\u0652';
    private static final char FATHA = '\u064E';
    private static final char DAMMA = '\u064F';
    private static final char KASRA = '\u0650';
    private static final char FATHATAN = '\u064B';
    private static final char DAMMATAN = '\u064C';
    private static final char KASRATAN = '\u064D';
    private static final char ALEF = '\u0627';
    private static final char LAM = '\u0644';
    private static final char WAW = '\u0648';
    private static final char YAA = '\u064A';

    /** العلامات التي يكتبها الصنف لكل صنف (بترتيب يقبله المحركات: الشدّة ثم الحركة). */
    private static final String[] MARKS = {
            "", "" + FATHA, "" + DAMMA, "" + KASRA, "" + SUKUN,
            "" + SHADDA, "" + SHADDA + FATHA, "" + SHADDA + DAMMA, "" + SHADDA + KASRA,
            "" + FATHATAN, "" + DAMMATAN, "" + KASRATAN
    };

    // ------------------------------------------------------------------ حدود الثقة (تُضبط بالاختبار على القاموس)

    /** أقل عدد أمثلة لكل سياق في سلسلة التراجع (من الأخص إلى الأعم). */
    private static final int[] MIN_SUPPORT = {3, 3, 4, 5, 10, 40};
    /** ثقة الكلمة كلها: احتمال أفضل مسار من مسارات البحث الشعاعي (بعد تطبيعه على مجموع المسارات). */
    static volatile float MIN_WORD_CONF = 0.80f;
    private static final int BEAM = 6;
    private static final int MIN_LEN = 3;
    private static final int MAX_LEN = 13;
    /** وزن ما يتعلمه المستخدم أمام طبقة القاموس. */
    private static final int USER_WEIGHT = 6;
    private static final int MAX_USER_CONTEXTS = 60000;
    /** صنف "بداية الكلمة" عند غياب صنف سابق. */
    private static final int C_START = 12;

    // ------------------------------------------------------------------ حالة

    /** التنبؤ بكلمات غير موجودة اختياري ومتوقف افتراضيًا (انظر الدقة المقاسة في وصف الصنف)؛ يفعّله المستخدم من الإعدادات. */
    private static volatile boolean enabled = false;
    private static volatile Tables base = null;
    private static volatile int trainedWords = 0;
    private static final Object USER_LOCK = new Object();
    private static final Map<Integer, int[]> user = new HashMap<>();
    private static int userWords = 0;
    private static volatile int predictions = 0;
    private static volatile int rejectedByPhonotactics = 0;

    static void setEnabled(boolean on) {
        enabled = on;
    }

    static boolean isEnabled() {
        return enabled;
    }

    /** يحرّر جداول القاموس من الذاكرة (لما يكون التنبؤ متوقفًا). طبقة المستخدم تبقى. */
    static void releaseBase() {
        base = null;
        trainedWords = 0;
    }

    static boolean isTrained() {
        return base != null;
    }

    // ------------------------------------------------------------------ ترميز الحروف

    /** رقم الحرف 1..42 (الهمزات على الألف تبقى مميّزة)، 0 لحدّ الكلمة، -1 لغير العربي. */
    private static int idx(char c) {
        c = SpeechAuditor.unifyLetter(c);
        if (c == '\u0671') c = ALEF;
        if (c >= 0x0621 && c <= 0x064A) return c - 0x0620;
        return -1;
    }

    private static char letterOf(int i) {
        return (char) (0x0620 + i);
    }

    private static int classOf(boolean shadda, char vowel) {
        int v;
        switch (vowel) {
            case FATHA:
                v = 1;
                break;
            case DAMMA:
                v = 2;
                break;
            case KASRA:
                v = 3;
                break;
            case SUKUN:
                return shadda ? C_SHADDA : C_SUKUN;
            case FATHATAN:
                return C_TAN_F;
            case DAMMATAN:
                return C_TAN_D;
            case KASRATAN:
                return C_TAN_K;
            default:
                return shadda ? C_SHADDA : C_NONE;
        }
        return shadda ? 5 + v : v;
    }

    private static boolean hasVowelClass(int c) {
        return c == C_FATHA || c == C_DAMMA || c == C_KASRA || c == C_SH_FATHA || c == C_SH_DAMMA
                || c == C_SH_KASRA || c >= C_TAN_F;
    }

    /** الصنف يُنطق معه الحرف متحركًا أو مشدّدًا (أي ليس ساكنًا ولا عاريًا). */
    private static boolean isVoweled(int c) {
        return hasVowelClass(c) || c == C_SHADDA;
    }

    private static boolean isMadd(int letter) {
        char c = letterOf(letter);
        return c == ALEF || c == WAW || c == YAA || c == '\u0649';
    }

    // ------------------------------------------------------------------ عيّنة: كلمة = أحرف + أصناف

    private static final class Sample {
        int[] l;
        int[] c;
        int n;
    }

    /**
     * يفكّك كلمة مشكولة إلى أحرف وأصناف. null لو فسد الشكل (علامة في أولها، حرف غير عربي، ...).
     * stripArticle: "ال" التعريف تُنزع ويُدرَّب الجذع وحده (تُطبَّق قاعدتها بالكود لا بالإحصاء).
     */
    private static Sample parse(String shaped, boolean stripArticle) {
        if (shaped == null || shaped.length() < 3) return null;
        int cap = shaped.length();
        int[] l = new int[cap];
        boolean[] sh = new boolean[cap];
        char[] vw = new char[cap];
        int n = -1;
        for (int i = 0; i < shaped.length(); i++) {
            char ch = shaped.charAt(i);
            if (ArabicPhonetics.isMark(ch)) {
                if (n < 0) return null;
                if (ch == SHADDA) sh[n] = true;
                else if (ch == FATHA || ch == DAMMA || ch == KASRA || ch == SUKUN
                        || (ch >= FATHATAN && ch <= KASRATAN)) vw[n] = ch;
                continue;
            }
            if (ch == '\u0640') continue;
            int k = idx(ch);
            if (k <= 0) return null;
            l[++n] = k;
        }
        n++;
        int from = 0;
        if (stripArticle && n >= 5 && letterOf(l[0]) == ALEF && letterOf(l[1]) == LAM) {
            from = 2;
            // الشمسية: الشدّة على أول حرف الجذع نتيجة الإدغام لا من الجذر
            if (ArabicLetters.isSun(letterOf(l[2])) && sh[2]) sh[2] = false;
        }
        Sample s = new Sample();
        s.n = n - from;
        if (s.n < MIN_LEN) return null;
        s.l = new int[s.n];
        s.c = new int[s.n];
        for (int i = 0; i < s.n; i++) {
            s.l[i] = l[from + i];
            s.c[i] = classOf(sh[from + i], vw[from + i]);
        }
        return s;
    }

    // ------------------------------------------------------------------ مفاتيح السياق

    private static int at(int[] l, int i) {
        return i < 0 || i >= l.length ? 0 : l[i];
    }

    /** يضغط سياقًا (عدة حقول) إلى مفتاح int غير صفري، مع وسم رتبة السياق في أعلى 4 بت. */
    private static int hash(long x, int tag) {
        x = (x + tag) * 0x9E3779B97F4A7C15L;
        x ^= x >>> 29;
        x *= 0xBF58476D1CE4E5B9L;
        x ^= x >>> 32;
        int h = (int) x & 0x0FFFFFFF;
        h |= (tag & 0xF) << 28;
        return h == 0 ? 1 : h;
    }

    /** عدد السياقات في سلسلة التراجع. */
    private static final int NCTX = 6;

    /**
     * مفاتيح سلسلة التراجع للحرف i من الأخص إلى الأعم. c1/c2 = صنفا الحرفين السابقين (C_START قبل بداية الكلمة).
     * الأخص: الحرف وجيرانه الأربعة + ما نُطق به الحرف السابق (تركيب المقطع الصوتي).
     */
    private static void keys(int[] l, int n, int i, int c1, int c2, int[] out) {
        long p2 = at(l, i - 2), p1 = at(l, i - 1), cu = l[i], n1 = at(l, i + 1), n2 = at(l, i + 2);
        long pos = (i == 0 ? 1 : 0) | (i == n - 2 ? 2 : 0);
        out[0] = hash(((((c1 * 16L + c2) * 64 + p1) * 64 + cu) * 64 + n1) * 64 + n2, 1);
        out[1] = hash((((c1 * 64L + p1) * 64 + cu) * 64 + n1) * 64 + n2 + (pos << 40), 2);
        out[2] = hash(((p1 * 64L + cu) * 64 + n1) * 64 + n2, 3);
        out[3] = hash(((c1 * 64L + p1) * 64 + cu) * 64 + n1 + (pos << 40), 4);
        out[4] = hash((p1 * 64L + cu) * 64 + n1 + (pos << 40), 5);
        out[5] = hash((cu * 64L + n1) + (pos << 40), 6);
        if (p2 < 0) out[0] = out[1]; // لا يحدث؛ يُبقي p2 مستخدمًا لمن يوسّع السلسلة
    }

    // ------------------------------------------------------------------ جدول التجزئة المضغوط (طبقة القاموس)

    /**
     * جدول تجزئة بعنونة مفتوحة: المفتاح int، والقيمة long = ثلاث خانات (صنف 4 بت + عدّ 12 بت) + المجموع الكلي 16 بت.
     * الخانات الثلاث تحفظ أكثر ثلاثة أصناف تكرارًا في السياق (يكفي للحكم، وتوفّر الذاكرة على الجوال).
     */
    private static final class Table {
        int[] keys;
        long[] vals;
        int used;

        Table(int cap) {
            keys = new int[cap];
            vals = new long[cap];
        }

        private static int mix(int k) {
            k *= 0x9E3779B1;
            return k ^ (k >>> 15);
        }

        int find(int key) {
            int mask = keys.length - 1;
            int p = mix(key) & mask;
            while (keys[p] != 0) {
                if (keys[p] == key) return p;
                p = (p + 1) & mask;
            }
            return -1;
        }

        long get(int key) {
            int p = find(key);
            return p < 0 ? 0L : vals[p];
        }

        void add(int key, int cls) {
            if (used * 10 >= keys.length * 7) grow();
            int mask = keys.length - 1;
            int p = mix(key) & mask;
            while (keys[p] != 0 && keys[p] != key) p = (p + 1) & mask;
            if (keys[p] == 0) {
                keys[p] = key;
                used++;
            }
            vals[p] = bump(vals[p], cls);
        }

        private void grow() {
            int[] ok = keys;
            long[] ov = vals;
            keys = new int[ok.length * 2];
            vals = new long[ok.length * 2];
            used = 0;
            int mask = keys.length - 1;
            for (int i = 0; i < ok.length; i++) {
                if (ok[i] == 0) continue;
                int p = mix(ok[i]) & mask;
                while (keys[p] != 0) p = (p + 1) & mask;
                keys[p] = ok[i];
                vals[p] = ov[i];
                used++;
            }
        }
    }

    private static int slotCls(long v, int s) {
        return (int) ((v >>> (s * 16)) & 0xF);
    }

    private static int slotCnt(long v, int s) {
        return (int) ((v >>> (s * 16 + 4)) & 0xFFF);
    }

    private static int total(long v) {
        return (int) ((v >>> 48) & 0xFFFF);
    }

    private static long setSlot(long v, int s, int cls, int cnt) {
        long mask = 0xFFFFL << (s * 16);
        return (v & ~mask) | (((long) (cls & 0xF) | ((long) (cnt & 0xFFF) << 4)) << (s * 16));
    }

    /** يضيف مشاهدة واحدة للصنف (space-saving: لو امتلأت الخانات يُستبدل الأقل عدًّا). */
    private static long bump(long v, int cls) {
        int tot = total(v);
        int slot = -1;
        int minSlot = 0;
        int minCnt = Integer.MAX_VALUE;
        for (int s = 0; s < 3; s++) {
            int cnt = slotCnt(v, s);
            if (cnt > 0 && slotCls(v, s) == cls) {
                slot = s;
                break;
            }
            if (cnt < minCnt) {
                minCnt = cnt;
                minSlot = s;
            }
        }
        if (slot >= 0) {
            v = setSlot(v, slot, cls, slotCnt(v, slot) + 1);
        } else {
            v = setSlot(v, minSlot, cls, minCnt + 1);
        }
        tot++;
        boolean big = tot >= 0xFFF0;
        for (int s = 0; s < 3 && !big; s++) if (slotCnt(v, s) >= 0xFF0) big = true;
        if (big) { // تنصيف كل العدّادات للحفاظ على النسب بلا فيضان
            for (int s = 0; s < 3; s++) v = setSlot(v, s, slotCls(v, s), slotCnt(v, s) / 2);
            tot /= 2;
        }
        return (v & 0x0000FFFFFFFFFFFFL) | ((long) tot << 48);
    }

    private static final class Tables {
        final Table t = new Table(1 << 20);
    }

    // ------------------------------------------------------------------ التدريب من القاموس

    private static void addSample(Table t, Sample s) {
        int[] ks = new int[NCTX];
        int c1 = C_START;
        int c2 = C_START;
        // آخر حرف لا يُدرَّب عليه (حركة الإعراب تتغير بموضع الكلمة)
        for (int i = 0; i < s.n - 1; i++) {
            keys(s.l, s.n, i, c1, c2, ks);
            for (int k = 0; k < NCTX; k++) t.add(ks[k], s.c[i]);
            c2 = c1;
            c1 = s.c[i];
        }
    }

    /**
     * يدرّب طبقة القاموس من كل الكلمات المشكولة (يُنادى بعد دمج المكتبات). يُنشر الناتج دفعة واحدة.
     * يُستدعى من خيط خلفي. آمن لو أُعيد استدعاؤه (يعيد البناء من الصفر).
     */
    static void train(Iterable<String> shapedWords) {
        if (shapedWords == null) return;
        Tables t = new Tables();
        int words = 0;
        for (String w : shapedWords) {
            Sample s = parse(w, true);
            if (s == null || s.n > MAX_LEN + 2) continue;
            addSample(t.t, s);
            words++;
        }
        trainedWords = words;
        base = t;
    }

    // ------------------------------------------------------------------ طبقة المستخدم (تكيّف)

    /** يتعلّم من كلمة مشكولة رآها في ملف أو علّمها المستخدم. weight 1 = ظهور عابر، أكثر = تعليم مباشر. */
    static void learnUser(String shaped, int weight) {
        Sample s = parse(shaped, true);
        if (s == null || s.n > MAX_LEN + 2) return;
        int w = Math.max(1, Math.min(20, weight));
        int[] ks = new int[NCTX];
        synchronized (USER_LOCK) {
            int c1 = C_START;
            int c2 = C_START;
            for (int i = 0; i < s.n - 1; i++) {
                keys(s.l, s.n, i, c1, c2, ks);
                for (int k = 0; k < NCTX; k++) addUser(ks[k], s.c[i], w);
                c2 = c1;
                c1 = s.c[i];
            }
            userWords++;
        }
    }

    private static void addUser(int key, int cls, int w) {
        int[] a = user.get(key);
        if (a == null) {
            if (user.size() >= MAX_USER_CONTEXTS) return;
            a = new int[NCLASS];
            user.put(key, a);
        }
        a[cls] += w;
    }

    static void resetUser() {
        synchronized (USER_LOCK) {
            user.clear();
            userWords = 0;
        }
    }

    // ------------------------------------------------------------------ توزيع أصناف حرف واحد

    /** احتمالات الأصناف لحرف واحد، والسياق الذي حُكم به (0 = لا دعم كافٍ). */
    private static final class Dist {
        final float[] p = new float[NCLASS];
        int ctx = -1;
        int support = 0;
    }

    /** يملأ out من أخص سياق له دعم كافٍ (طبقة القاموس + طبقة المستخدم). false لو لا دعم في أي سياق. */
    private static boolean dist(Tables t, int[] ks, Dist out) {
        int[] hist = new int[NCLASS];
        for (int j = 0; j < NCTX; j++) {
            java.util.Arrays.fill(hist, 0);
            int tot = 0;
            long v = t.t.get(ks[j]);
            if (v != 0) {
                for (int s = 0; s < 3; s++) {
                    int cnt = slotCnt(v, s);
                    if (cnt > 0) hist[slotCls(v, s)] += cnt;
                }
                tot = total(v);
            }
            synchronized (USER_LOCK) {
                int[] u = user.get(ks[j]);
                if (u != null) {
                    for (int c = 0; c < NCLASS; c++) {
                        hist[c] += u[c] * USER_WEIGHT;
                        tot += u[c] * USER_WEIGHT;
                    }
                }
            }
            if (tot < MIN_SUPPORT[j]) continue; // دعم قليل: جرّب سياقًا أعم
            int seen = 0;
            for (int c = 0; c < NCLASS; c++) seen += hist[c];
            float rest = Math.max(0, tot - seen); // كتلة الأصناف خارج الخانات الثلاث
            for (int c = 0; c < NCLASS; c++) out.p[c] = (hist[c] + rest / NCLASS + 0.02f) / (tot + 0.24f + rest);
            out.ctx = j;
            out.support = tot;
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ بحث شعاعي على مستوى الكلمة

    private static final class Path {
        int[] c;
        double lp; // لوغاريتم الاحتمال

        Path(int n) {
            c = new int[n];
        }
    }

    /** أفضل مسارات الأصناف لكلمة (أحرفها l) بعد تطبيق قواعد النطق، مرتبة تنازليًا. null لو لا دعم. */
    private static List<Path> search(Tables t, int[] l, int m) {
        List<Path> beam = new ArrayList<>();
        Path start = new Path(m);
        start.lp = 0;
        beam.add(start);
        int[] ks = new int[NCTX];
        Dist d = new Dist();
        for (int i = 0; i < m - 1; i++) {
            List<Path> next = new ArrayList<>();
            for (Path p : beam) {
                int c1 = i >= 1 ? p.c[i - 1] : C_START;
                int c2 = i >= 2 ? p.c[i - 2] : C_START;
                keys(l, m, i, c1, c2, ks);
                if (!dist(t, ks, d)) continue;
                for (int c = 0; c < NCLASS; c++) {
                    if (d.p[c] < 0.04f) continue;
                    Path q = new Path(m);
                    System.arraycopy(p.c, 0, q.c, 0, i);
                    q.c[i] = c;
                    q.lp = p.lp + Math.log(d.p[c]);
                    next.add(q);
                }
            }
            if (next.isEmpty()) return null;
            Collections.sort(next, (x, y) -> Double.compare(y.lp, x.lp));
            beam = next.size() > BEAM ? new ArrayList<>(next.subList(0, BEAM)) : next;
        }
        return beam;
    }

    // ------------------------------------------------------------------ قواعد النطق (رفض المستحيل)

    /** هل تتابع الأصناف ممكن نطقًا؟ (كلمة بلا "ال"؛ الحرف الأول متحرك.) */
    private static boolean phonotacticsOk(int[] l, int[] c, int n) {
        if (!isVoweled(c[0]) && letterOf(l[0]) != ALEF) return false; // لا ابتداء بساكن
        for (int i = 0; i < n; i++) {
            char ch = letterOf(l[i]);
            boolean madd = ch == ALEF || ch == WAW || ch == YAA || ch == '\u0649';
            if (ch == ALEF && c[i] != C_NONE) return false; // الألف العارية لا تحمل حركة
            if (i > 0) {
                int p = c[i - 1];
                boolean pSilent = p == C_SUKUN || (p == C_NONE && !isMadd(l[i - 1]));
                boolean cSilent = c[i] == C_SUKUN || (c[i] == C_NONE && !madd);
                if (pSilent && cSilent && i < n - 1) return false; // ساكنان متتاليان في وسط الكلمة
                // مدّ بلا حركة مناسبة قبله
                if (c[i] == C_NONE && i < n - 1) {
                    if (ch == ALEF && !(p == C_FATHA || p == C_SH_FATHA || p == C_TAN_F)) return false;
                    if (ch == WAW && madd && i > 0 && !(p == C_DAMMA || p == C_SH_DAMMA)) {
                        // واو عارية: مدّ يستلزم ضمة قبلها، وإلا رفضنا (لا نخمّن واوًا صامتة)
                        return false;
                    }
                    if (ch == YAA && i > 0 && !(p == C_KASRA || p == C_SH_KASRA)) return false;
                }
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ التنبؤ

    /** نتيجة تنبؤ: الصيغة المشكولة وثقتها (0..1). */
    static final class Prediction {
        final String shaped;
        final float confidence;

        Prediction(String shaped, float confidence) {
            this.shaped = shaped;
            this.confidence = confidence;
        }
    }

    /**
     * يتنبأ بتشكيل كلمة عربية مجرّدة غير موجودة في أي مكتبة، أو null لو لم يكن واثقًا.
     * يضمن: الحروف هي نفسها حرفًا بحرف (تُضاف علامات فقط)، وبنية التشكيل سليمة.
     */
    static String predict(String plain) {
        Prediction p = predictScored(plain, MIN_WORD_CONF);
        return p == null ? null : p.shaped;
    }

    /** مثل predict مع الثقة وحدّ أدنى مخصّص (للاختبار وضبط العتبة). */
    static Prediction predictScored(String plain, float minConf) {
        if (!enabled || plain == null) return null;
        Tables t = base;
        if (t == null) return null;
        // الكلمة المجرّدة: حروف فقط (تُجاهَل العلامات والتطويل)
        int cap = plain.length();
        int[] all = new int[cap];
        char[] raw = new char[cap];
        int n = 0;
        for (int i = 0; i < plain.length(); i++) {
            char ch = plain.charAt(i);
            if (ArabicPhonetics.isMark(ch) || ch == '\u0640') return null; // ليست مجرّدة
            int k = idx(ch);
            if (k <= 0) return null;
            raw[n] = ch;
            all[n++] = k;
        }
        if (n < MIN_LEN || n > MAX_LEN + 2) return null;

        int from = 0;
        boolean article = false;
        if (n >= 5 && letterOf(all[0]) == ALEF && letterOf(all[1]) == LAM) {
            from = 2; // "ال" + جذع: قاعدتها بالكود
            article = true;
        }
        int m = n - from;
        if (m < MIN_LEN) return null;
        int[] l = new int[m];
        System.arraycopy(all, from, l, 0, m);

        List<Path> beam = search(t, l, m);
        if (beam == null || beam.isEmpty()) return null;

        // ثقة المسار = احتماله المطلق (جداء احتمالات أصناف حروفه)؛ أصدق من حصته بين مسارات الشعاع لأن الشعاع قد يحوي مسارًا واحدًا
        double best = beam.get(0).lp;
        double mass = 1.0;

        int[] cls = null;
        float conf = 0f;
        for (int pi = 0; pi < beam.size(); pi++) {
            Path p = beam.get(pi);
            float c = (float) (Math.exp(p.lp) / mass);
            if (pi == 0 && c < minConf) return null; // أفضل مسار نفسه غير مقنع
            if (pi > 0 && c < minConf) break;
            int[] cc = p.c.clone();
            cc[m - 1] = C_NONE; // آخر الكلمة بصيغة الوقف
            if (phonotacticsOk(l, cc, m)) {
                cls = cc;
                conf = c;
                break;
            }
            rejectedByPhonotactics++;
        }
        if (cls == null) return null;

        StringBuilder sb = new StringBuilder(n * 3);
        if (article) {
            char first = letterOf(l[0]);
            boolean sun = ArabicLetters.isSun(first);
            // قاعدة "ال": القمرية لامها ساكنة، والشمسية لامها عارية وأول الجذر مشدّد
            sb.append(raw[0]).append(raw[1]);
            if (!sun) sb.append(SUKUN);
            if (sun) {
                if (cls[0] >= C_FATHA && cls[0] <= C_KASRA) cls[0] += 5; // حركة + شدّة الإدغام
                else if (!(cls[0] >= C_SH_FATHA && cls[0] <= C_SH_KASRA)) return null;
            }
        }
        for (int i = 0; i < m; i++) {
            sb.append(raw[from + i]).append(MARKS[cls[i]]);
        }
        String out = sb.toString();
        // وقاية أخيرة: الحروف مطابقة والبنية سليمة
        if (!WordVerifier.sameLetters(plain, out) || !WordVerifier.wellFormed(out)) return null;
        predictions++;
        return new Prediction(out, conf);
    }

    // ------------------------------------------------------------------ شرح حرفًا حرفًا (للتشخيص وشاشة القاموس)

    /**
     * يشرح كيف "يفهم" النموذج كلمة: لكل حرف اسمه وما يتوقعه بعده ونسبة ثقته فيه، ثم الثقة الكلية.
     * للعرض على المستخدم أو التشخيص.
     */
    static String describe(String plain) {
        Tables t = base;
        if (t == null) return "النموذج لم يُدرَّب بعد (القاموس يُحمَّل).";
        if (plain == null || plain.length() < 2) return "";
        int n = 0;
        int[] l = new int[plain.length()];
        char[] raw = new char[plain.length()];
        for (int i = 0; i < plain.length(); i++) {
            char ch = plain.charAt(i);
            if (ArabicPhonetics.isMark(ch) || ch == '\u0640') continue;
            int k = idx(ch);
            if (k <= 0) return "الكلمة تحوي حرفًا غير عربي.";
            raw[n] = ch;
            l[n++] = k;
        }
        if (n < 2) return "";
        int[] w = new int[n];
        System.arraycopy(l, 0, w, 0, n);
        List<Path> beam = n >= MIN_LEN ? search(t, w, n) : null;
        StringBuilder sb = new StringBuilder();
        int[] best = beam == null || beam.isEmpty() ? null : beam.get(0).c;
        int[] ks = new int[NCTX];
        Dist d = new Dist();
        for (int i = 0; i < n; i++) {
            sb.append(raw[i]).append("  ");
            String nm = ArabicLetters.spokenName(raw[i]);
            if (nm != null && !nm.isEmpty()) sb.append('(').append(nm).append(") ");
            if (i == n - 1) {
                sb.append("← آخر الكلمة: بصيغة الوقف\n");
                continue;
            }
            if (best == null) {
                sb.append("← لا أمثلة كافية\n");
                continue;
            }
            int c1 = i >= 1 ? best[i - 1] : C_START;
            int c2 = i >= 2 ? best[i - 2] : C_START;
            keys(w, n, i, c1, c2, ks);
            if (!dist(t, ks, d)) {
                sb.append("← لا أمثلة كافية\n");
                continue;
            }
            int bc = best[i];
            String mk = bc == C_NONE ? "بلا علامة" : MARKS[bc];
            sb.append(String.format(Locale.ROOT, "← %s  ثقة %.0f%% (%d مثالًا)\n", mk, d.p[bc] * 100f, d.support));
        }
        return sb.toString().trim();
    }

    // ------------------------------------------------------------------ تشخيص

    static String stats() {
        Tables t = base;
        int uw;
        int uc;
        synchronized (USER_LOCK) {
            uw = userWords;
            uc = user.size();
        }
        if (t == null) return "letterModel: غير مدرَّب | مستخدم: " + uw + " كلمة";
        return String.format(Locale.ROOT,
                "letterModel: %d كلمة | سياقات %d | مستخدم: %d كلمة (%d سياقًا) | تنبؤات=%d مرفوضة صوتيًا=%d | %s",
                trainedWords, t.t.used, uw, uc, predictions, rejectedByPhonotactics,
                enabled ? "مفعّل" : "متوقف");
    }
}
