package com.docreader.app.pdf;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * محلّل ومطابِق ومدقّق الكلمات العربية بين القاموس المحلي (TashkeelDict) والقارئ الصوتي (SpeechPrep / PdfSpeaker).
 *
 * المشكلة القديمة: القاموس (214 ألف كلمة) كان يُبحث فيه بالمطابقة الحرفية فقط، فأي اختلاف بسيط يُسقط الكلمة بلا تشكيل:
 *   - همزة مفقودة من الـ PDF:   "الالم"  بدل "الألم"،  "اسلام" بدل "إسلام"
 *   - سوابق غير موجودة بصيغتها:  "بالكهرباء"، "للعلاج"، "وللعضلات"، "فالتحفيز"
 *   - ضمائر متصلة:              "عضلتها"، "يستخدمها"، "مرضاهم"، "علاجنا"
 *   - ما تعلّمه SpeechLearner من تشكيل الملف نفسه لا يُستفاد منه في تركيب السوابق واللواحق.
 *
 * المسار الآن (الأول الذي ينجح يفوز):
 *   1) LEARNED    تشكيل متعلَّم من الملف نفسه (SpeechLearner) - أقرب لنيّة كاتب الملف.
 *   2) EXACT      مطابقة حرفية في القاموس.
 *   3) NORMALIZED مطابقة بعد توحيد مقاعد الهمزة على الألف (أ إ آ ٱ -> ا)، فقط لو كانت النتيجة وحيدة بلا لبس.
 *   4) DERIVED    اشتقاق مضبوط من كلمة موجودة: جمع المؤنث السالم (عضلة -> عضلات)، المثنى (عضلتين/عضلتان)،
 *                 والنسبة مذكّرًا ومؤنثًا (كهربائية <-> كهربائي) - بشروط صارمة تمنع التخمين.
 *   5) CLITIC     سوابق (و/ف) + (ب/ك/ل) + "ال"/"لل" + جذع موجود، مع تطبيق قواعد النطق (شدّة الشمسية، سكون القمرية).
 *   6) SUFFIX     جذع موجود + ضمير متصل (ها/هم/هما/هن/كم/كما/نا) مع استرجاع التاء المربوطة (عضلة -> عضلتها) والألف المقصورة (مرضى -> مرضاهم).
 *   7) PREDICTED  (الملاذ الأخير) كلمة غير موجودة بأي صورة: LetterModel (الذكاء المحلي الذي تعلّم القاموس حرفًا حرفًا) يتنبأ
 *                 بتشكيلها، لكن فقط بثقة عالية على الكلمة كلها وبعد اجتياز قواعد النطق؛ وإلا تبقى بلا تشكيل كما كانت. اختياري ومتوقف
 *                 افتراضيًا: يفعّله المستخدم من إعدادات مكتبات الكلمات. لا يُحسب هذا النوع "موجودًا في القاموس" في تقرير التغطية.
 *
 * التدقيق (لا تُقبل أي نتيجة إلا بعد اجتيازه):
 *   - نفس هيكل الحروف تمامًا بين الكلمة الأصلية والمشكولة (مع تسامح الهمزات فقط) فلا يضيع حرف ولا يُضاف حرف.
 *   - التشكيل سليم البنية: لا علامة في أول الكلمة، لا حركتان متتاليتان على حرف، التنوين لا يكون إلا في الآخر.
 * ولا تُنفَّذ أي "تصحيح إملائي" تلقائي بالتقريب (edit distance) على النطق: الكلمة الطبية الغريبة تبقى كما هي بدل أن
 * تُستبدل بكلمة شبيهة خاطئة. التقريب موجود في suggest() للتشخيص فقط.
 *
 * كل شيء هنا لا يلمس النص المعروض؛ هو للنطق فقط. والصنف آمن للاستدعاء من أي خيط.
 */
final class WordVerifier {

    private WordVerifier() {
    }

    enum Kind {LEARNED, EXACT, NORMALIZED, DERIVED, CLITIC, SUFFIX, PREDICTED, NONE}

    /** نتيجة مطابقة: الصيغة المشكولة، نوع المطابقة، والجذع الذي استُخدم من القاموس. */
    static final class Match {
        final String shaped;
        final Kind kind;
        final String stem;

        Match(String shaped, Kind kind, String stem) {
            this.shaped = shaped;
            this.kind = kind;
            this.stem = stem;
        }

        boolean found() {
            return shaped != null;
        }
    }

    private static final Match NONE = new Match(null, Kind.NONE, null);

    private static final char SHADDA = '\u0651';
    private static final char SUKUN = '\u0652';
    private static final char FATHA = '\u064E';
    private static final char DAMMA = '\u064F';
    private static final char KASRA = '\u0650';
    private static final char ALEF = '\u0627';
    private static final char LAM = '\u0644';
    private static final String SUN = "\u062A\u062B\u062F\u0630\u0631\u0632\u0633\u0634\u0635\u0636\u0637\u0638\u0644\u0646";

    // ------------------------------------------------------------------ عدّادات (تشخيص)

    private static final AtomicInteger C_LOOKUPS = new AtomicInteger();
    private static final AtomicInteger C_MISS = new AtomicInteger();
    private static final AtomicInteger[] C_KIND = new AtomicInteger[Kind.values().length];

    static {
        for (int i = 0; i < C_KIND.length; i++) C_KIND[i] = new AtomicInteger();
    }

    /** ملخص أداء القاموس منذ تشغيل التطبيق (للتشخيص). */
    static String stats() {
        int look = C_LOOKUPS.get();
        int miss = C_MISS.get();
        int hit = look - miss;
        return String.format(Locale.ROOT,
                "dict=%d | lookups=%d hit=%d (%.0f%%) [learned=%d exact=%d norm=%d derived=%d clitic=%d suffix=%d predicted=%d] miss=%d",
                TashkeelDict.size(), look, hit, look == 0 ? 0.0 : 100.0 * hit / look,
                C_KIND[Kind.LEARNED.ordinal()].get(), C_KIND[Kind.EXACT.ordinal()].get(),
                C_KIND[Kind.NORMALIZED.ordinal()].get(), C_KIND[Kind.DERIVED.ordinal()].get(),
                C_KIND[Kind.CLITIC.ordinal()].get(),
                C_KIND[Kind.SUFFIX.ordinal()].get(), C_KIND[Kind.PREDICTED.ordinal()].get(), miss);
    }

    // ------------------------------------------------------------------ مفتاح التوحيد

    /**
     * مفتاح مطابقة متسامح: بلا تشكيل ولا تطويل، أشكال الكاف/الياء موحّدة، الهمزات على ألف (أ إ آ ٱ -> ا)،
     * ولا نوحّد ؤ/ئ: اختبار القاموس أظهر أنها تخلط كلمات مختلفة (وطؤها/وطئها).
     * لا نوحّد ة/ه عمدًا: هذا شأن خيار "تصحيح التاء" في الإعدادات (ArabicPhonetics.fixTaaTypo).
     * ولا نوحّد ى/ي: اختبار على بيانات القاموس أظهر أن ذلك يخطئ غالبًا (معنى/معني، تغلى/تغلي: نطقان مختلفان).
     */
    static String normKey(String s) {
        if (s == null || s.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = SpeechAuditor.unifyLetter(s.charAt(i));
            if (c == '\u0640' || ArabicPhonetics.isMark(c)) continue;
            switch (c) {
                case '\u0623':
                case '\u0625':
                case '\u0622':
                case '\u0671':
                    c = ALEF;
                    break;
                default:
                    break;
            }
            sb.append(c);
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ تدقيق الصيغة المشكولة

    private static boolean isTanween(char c) {
        return c >= '\u064B' && c <= '\u064D';
    }

    private static boolean isShortVowel(char c) {
        return c == FATHA || c == DAMMA || c == KASRA;
    }

    /** بنية التشكيل سليمة: لا علامة في أول الكلمة، لا حركتان متتاليتان، لا شدّتان، التنوين في الآخر فقط. */
    static boolean wellFormed(String shaped) {
        if (shaped == null || shaped.isEmpty()) return false;
        if (ArabicPhonetics.isMark(shaped.charAt(0))) return false;
        boolean shadda = false;
        boolean vowel = false;
        for (int i = 0; i < shaped.length(); i++) {
            char c = shaped.charAt(i);
            if (!ArabicPhonetics.isMark(c)) {
                shadda = false;
                vowel = false;
                continue;
            }
            if (c == SHADDA) {
                if (shadda) return false;
                shadda = true;
            } else if (isShortVowel(c) || isTanween(c) || c == SUKUN) {
                if (vowel) return false;
                vowel = true;
                if (isTanween(c)) {
                    int rest = shaped.length() - i - 1;
                    if (rest > 1 || (rest == 1 && shaped.charAt(i + 1) != ALEF)) return false;
                }
            }
        }
        return true;
    }

    /** الحروف الأساسية متطابقة (مع تسامح مقاعد الهمزة على الألف فقط): لا حرف ضائع ولا مضاف. */
    static boolean sameLetters(String plain, String shaped) {
        return normKey(plain).equals(normKey(shaped));
    }

    /** هل يُقبل هذا السطر من ملف القاموس؟ (يُستعمل عند تحميله لتنظيفه من الأسطر الفاسدة.) */
    static boolean acceptEntry(String plain, String shaped) {
        if (plain == null || shaped == null || plain.length() < 3) return false;
        if (plain.equals(shaped)) return false; // بلا أي علامة: لا فائدة منه
        StringBuilder sb = new StringBuilder(shaped.length());
        for (int i = 0; i < shaped.length(); i++) {
            char c = shaped.charAt(i);
            if (!ArabicPhonetics.isMark(c)) sb.append(c);
        }
        if (!plain.equals(sb.toString())) return false; // حروف مختلفة
        return wellFormed(shaped);
    }

    /** لا نستبدل همزة الكلمة الأصلية (أ إ آ) بألف عارية من القاموس: هذا يُضعف نطق الهمز. */
    private static boolean keepsHamza(String plain, String shaped) {
        int j = 0;
        for (int i = 0; i < plain.length(); i++) {
            char c = plain.charAt(i);
            if (ArabicPhonetics.isMark(c) || c == '\u0640') continue;
            while (j < shaped.length() && (ArabicPhonetics.isMark(shaped.charAt(j)) || shaped.charAt(j) == '\u0640')) j++;
            if (j >= shaped.length()) return false;
            char d = shaped.charAt(j++);
            if ((c == '\u0623' || c == '\u0625' || c == '\u0622') && d == ALEF) return false;
        }
        return true;
    }

    /**
     * لا نحوّل ألفًا في أول الكلمة إلى همزة (اغسل -> أغسل): قد تكون ألف وصل (أمر، أفعال ٧-١٠، اسم، ابن) وتخمين الهمز يغيّر
     * المعنى. الهمزة الداخلية (الالم -> الألم، الاعصاب -> الأعصاب) أوثق فتُوحَّد. الألف الأولى بعد "ال" داخلية أيضًا.
     */
    private static boolean keepsInitialAlif(String plain, String shaped) {
        if (plain.isEmpty() || shaped.isEmpty() || plain.charAt(0) != ALEF) return true;
        char d = shaped.charAt(0);
        return !(d == '\u0623' || d == '\u0625' || d == '\u0622');
    }

    private static boolean accept(String plain, String shaped) {
        return shaped != null && wellFormed(shaped) && sameLetters(plain, shaped);
    }

    // ------------------------------------------------------------------ البحث عن جذع

    private static final class Hit {
        final String shaped;
        final Kind kind;

        Hit(String shaped, Kind kind) {
            this.shaped = shaped;
            this.kind = kind;
        }
    }

    /** بحث في المصادر فقط: متعلَّم، ثم حرفي، ثم موحَّد. */
    private static Hit dictHit(String s) {
        if (s == null || s.length() < 3) return null;
        try {
            String l = SpeechLearner.learnedForm(s);
            if (l != null && accept(s, l)) return new Hit(l, Kind.LEARNED);
        } catch (RuntimeException ignored) {
        }
        String e = TashkeelDict.exact(s);
        if (e != null) return new Hit(e, Kind.EXACT);
        String nk = normKey(s);
        if (nk.length() >= 3) {
            String n = TashkeelDict.normalized(nk);
            if (n != null && accept(s, n) && keepsHamza(s, n) && keepsInitialAlif(s, n)) return new Hit(n, Kind.NORMALIZED);
        }
        return null;
    }

    /** بحث بلا سوابق ولا لواحق: المصادر ثم الاشتقاق المضبوط. */
    private static Hit lookupStem(String s) {
        Hit h = dictHit(s);
        return h != null ? h : derived(s);
    }

    // ------------------------------------------------------------------ اشتقاق مضبوط

    private static boolean isLongVowelLetter(char c) {
        return c == ALEF || c == '\u0648' || c == '\u064A';
    }

    /** من كلمة مؤنثة بتاء مربوطة (مشكولة): الجذع بلا التاء وآخر حرفه بعلامات {شدّة، فتحة} فقط؛ أو null. */
    private static String taaBase(String shaped, boolean allowBareLongVowel) {
        int k = shaped.lastIndexOf('\u0629');
        if (k < 3) return null;
        for (int i = k + 1; i < shaped.length(); i++) if (!ArabicPhonetics.isMark(shaped.charAt(i))) return null;
        String b = shaped.substring(0, k);
        if (hasTanween(b)) return null;
        int j = b.length();
        boolean shadda = false;
        while (j > 0 && ArabicPhonetics.isMark(b.charAt(j - 1))) {
            char m = b.charAt(j - 1);
            if (m == SHADDA) shadda = true;
            else if (m != FATHA) return null;
            j--;
        }
        if (j < 2) return null;
        char l1 = b.charAt(j - 1);
        if (isLongVowelLetter(l1) && !shadda && !allowBareLongVowel) return null;
        return b.substring(0, j) + (shadda ? String.valueOf(SHADDA) : "") + FATHA;
    }

    /** الحرف قبل آخر حرف في الجذع ليس ساكنًا ومشكول (فَعَلَة لا فَعْلَة): شرط جمع المؤنث السالم المضبوط. */
    private static boolean middleIsVoweled(String base) {
        int j = base.length();
        while (j > 0 && ArabicPhonetics.isMark(base.charAt(j - 1))) j--; // علامات آخر حرف
        int l2 = j - 1; // مؤشر آخر حرف
        int m = l2;
        boolean vowel = false;
        while (m > 0 && ArabicPhonetics.isMark(base.charAt(m - 1))) {
            char c = base.charAt(m - 1);
            if (c == SUKUN) return false;
            if (isShortVowel(c) || c == SHADDA) vowel = true;
            m--;
        }
        return vowel;
    }

    private static Hit derived(String s) {
        int n = s.length();
        if (n < 5) return null;
        // جمع المؤنث السالم: عضلة -> عضلات (فَعَلَة فقط؛ الساكنة الوسط مثل جلسة تُترك لئلا نخطئ فَعَلات/فَعْلات)
        if (s.endsWith("\u0627\u062A")) {
            Hit b = dictHit(s.substring(0, n - 2) + '\u0629');
            String base = b == null ? null : taaBase(b.shaped, false);
            if (base != null && middleIsVoweled(base)) {
                String v = ArabicPhonetics.orderMarks(base + "\u0627\u062A");
                if (accept(s, v)) return new Hit(v, Kind.DERIVED);
            }
            return null;
        }
        // مثنى المؤنث: عضلة -> عضلتين / عضلتان
        if (n >= 6 && (s.endsWith("\u062A\u064A\u0646") || s.endsWith("\u062A\u0627\u0646"))) {
            boolean ayn = s.charAt(n - 2) == '\u064A';
            Hit b = dictHit(s.substring(0, n - 3) + '\u0629');
            String base = b == null ? null : taaBase(b.shaped, true);
            if (base != null) {
                String v = ArabicPhonetics.orderMarks(base + (ayn ? "\u062A\u064E\u064A\u0652\u0646\u0650"
                        : "\u062A\u064E\u0627\u0646\u0650"));
                if (accept(s, v)) return new Hit(v, Kind.DERIVED);
            }
            return null;
        }
        // نسبة مؤنثة -> مذكّرة: كهربائية -> كهربائي (شدّة الياء تبقى وتسقط الفتحة والتاء)
        if (s.endsWith("\u064A")) {
            Hit f = dictHit(s + '\u0629');
            if (f != null && f.shaped.endsWith("\u064A\u0651\u064E\u0629")) {
                String v = f.shaped.substring(0, f.shaped.length() - 2);
                if (accept(s, v)) return new Hit(v, Kind.DERIVED);
            }
            return null;
        }
        // نسبة مذكّرة -> مؤنثة: كهربائي -> كهربائية
        if (s.endsWith("\u064A\u0629")) {
            Hit m = dictHit(s.substring(0, n - 1));
            if (m != null && m.shaped.endsWith("\u064A\u0651")) {
                String v = m.shaped + FATHA + '\u0629';
                if (accept(s, v)) return new Hit(v, Kind.DERIVED);
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ السوابق

    private static final String[] CONJ = {"", "\u0648", "\u0641"};
    private static final String[] CONJ_V = {"", "\u0648\u064E", "\u0641\u064E"};
    private static final String[] PREP = {"", "\u0628", "\u0643", "\u0644"};
    private static final String[] PREP_V = {"", "\u0628\u0650", "\u0643\u064E", "\u0644\u0650"};
    private static final int LAM_PREP = 3;

    /** "ال" + جذع مشكول: قمرية -> الْ، شمسية -> الشّ (شدّة على أول حرف الجذع). */
    private static String withArticle(String stemShaped) {
        char f = stemShaped.charAt(0);
        StringBuilder sb = new StringBuilder(stemShaped.length() + 4).append(ALEF).append(LAM);
        if (SUN.indexOf(f) >= 0) sb.append(f).append(SHADDA).append(stemShaped, 1, stemShaped.length());
        else sb.append(SUKUN).append(stemShaped);
        return ArabicPhonetics.orderMarks(sb.toString());
    }

    /** لام الجر + "ال" تُدمج: لِلْمَرِيض (قمرية) / لِلتَّحْفِيز (شمسية: اللام بلا سكون). يُعيد ما بعد لام الجر. */
    private static String mergedLamArticle(String stemShaped) {
        char f = stemShaped.charAt(0);
        StringBuilder sb = new StringBuilder(stemShaped.length() + 4).append(LAM);
        if (SUN.indexOf(f) >= 0) sb.append(f).append(SHADDA).append(stemShaped, 1, stemShaped.length());
        else sb.append(SUKUN).append(stemShaped);
        return ArabicPhonetics.orderMarks(sb.toString());
    }

    private static boolean startsWithAl(String s) {
        return s.length() >= 4 && s.charAt(0) == ALEF && s.charAt(1) == LAM;
    }

    /** لام الجر + لام التعريف المدموجة (للعلاج = لِ + الْعلاج): الشكل المشكول بعد لام الجر، أو null. */
    private static String lamArticleMerged(String r2) {
        if (r2.length() < 4 || r2.charAt(0) != LAM) return null;
        String rest = r2.substring(1); // "لعلاج" = لام التعريف + "علاج"
        Hit full = lookupStem("\u0627\u0644" + rest);
        if (full != null && full.shaped.length() > 2 && full.shaped.charAt(0) == ALEF && full.shaped.charAt(1) == LAM) {
            return full.shaped.substring(1); // لِ + لْعِلَاج
        }
        if (rest.charAt(0) == ALEF) return null; // ألف وصل: نتركها للمحرك
        Hit s = lookupStem(rest);
        if (s != null && s.shaped.charAt(0) != ALEF) return mergedLamArticle(s.shaped);
        return null;
    }

    /** الجزء الاسمي بعد السوابق: جذع حرفي، أو "ال" + جذع، أو (لام جر + لام التعريف المدموجة). null لو لا يوجد. */
    private static String nounWithArticle(String r2, boolean lamPrefix) {
        // "لل..." بعد لام الجر هي غالبًا لام جر + "ال" مدموجة (للمقاصد = لِلْمَقَاصِد) لا لام جر + كلمة تبدأ بلام: الدمج أولًا
        if (lamPrefix) {
            String merged = lamArticleMerged(r2);
            if (merged != null) return merged;
        }
        Hit h = lookupStem(r2);
        if (h != null) return h.shaped;
        if (startsWithAl(r2) && r2.length() >= 5) {
            String stem = r2.substring(2);
            if (stem.charAt(0) == ALEF) return null; // ألف وصل بعد ال: نتركها للمحرك
            Hit s = lookupStem(stem);
            if (s != null && s.shaped.charAt(0) != ALEF) return withArticle(s.shaped);
        }
        return null;
    }

    // ------------------------------------------------------------------ اللواحق (الضمائر المتصلة)

    private static final String[] SUF = {"\u0647\u0645\u0627", "\u0643\u0645\u0627", "\u0647\u0645", "\u0647\u0646",
            "\u0647\u0627", "\u0643\u0645", "\u0646\u0627"};

    private static String suffixShaped(int i, boolean kasra) {
        switch (i) {
            case 0:
                return kasra ? "\u0647\u0650\u0645\u064E\u0627" : "\u0647\u064F\u0645\u064E\u0627";
            case 1:
                return "\u0643\u064F\u0645\u064E\u0627";
            case 2:
                return kasra ? "\u0647\u0650\u0645\u0652" : "\u0647\u064F\u0645\u0652";
            case 3:
                return kasra ? "\u0647\u0650\u0646\u0651\u064E" : "\u0647\u064F\u0646\u0651\u064E";
            case 4:
                return "\u0647\u064E\u0627";
            case 5:
                return "\u0643\u064F\u0645\u0652";
            default:
                return "\u0646\u064E\u0627";
        }
    }

    private static final int MODE_NONE = 0;
    private static final int MODE_TAA = 1;
    private static final int MODE_MAQSURA = 2;

    private static boolean hasTanween(String s) {
        for (int i = 0; i < s.length(); i++) if (isTanween(s.charAt(i))) return true;
        return false;
    }

    /** يلصق الضمير بالجذع المشكول (حركة إعراب الجذع تبقى كما في القاموس؛ تاء مربوطة -> تاء مفتوحة). */
    private static String attach(String stemShaped, int mode, int sufIndex) {
        if (hasTanween(stemShaped)) return null; // تنوين الجذع لا يصلح قبل الضمير
        String base = stemShaped;
        if (mode == MODE_TAA) {
            int k = base.lastIndexOf('\u0629');
            if (k < 0) return null;
            for (int i = k + 1; i < base.length(); i++) if (!ArabicPhonetics.isMark(base.charAt(i))) return null;
            base = base.substring(0, k) + '\u062A';
        } else if (mode == MODE_MAQSURA) { // مرضى + هم -> مرضاهم (تُكتب الألف المقصورة ألفًا قبل الضمير)
            int k = base.lastIndexOf('\u0649');
            if (k < 0) return null;
            for (int i = k + 1; i < base.length(); i++) if (!ArabicPhonetics.isMark(base.charAt(i))) return null;
            base = base.substring(0, k) + ALEF;
        }
        while (!base.isEmpty() && base.charAt(base.length() - 1) == SUKUN) base = base.substring(0, base.length() - 1);
        if (base.length() < 3) return null;
        char last = base.charAt(base.length() - 1);
        boolean kasra = last == KASRA || last == '\u064A'; // كسرة أو ياء قبل الضمير: مريضِهِم
        return ArabicPhonetics.orderMarks(base + suffixShaped(sufIndex, kasra));
    }

    private static String suffixed(String r2) {
        for (int i = 0; i < SUF.length; i++) {
            String suf = SUF[i];
            if (r2.length() - suf.length() < 3 || !r2.endsWith(suf)) continue;
            String stem = r2.substring(0, r2.length() - suf.length());
            Hit h = lookupStem(stem);
            int mode = MODE_NONE;
            if (h == null && stem.length() >= 4 && stem.charAt(stem.length() - 1) == '\u062A') {
                h = lookupStem(stem.substring(0, stem.length() - 1) + '\u0629'); // عضلتها -> عضلة
                if (h != null) mode = MODE_TAA;
            }
            if (h == null && stem.length() >= 4 && stem.charAt(stem.length() - 1) == ALEF) {
                h = lookupStem(stem.substring(0, stem.length() - 1) + '\u0649'); // مرضاهم -> مرضى
                if (h != null) mode = MODE_MAQSURA;
            }
            if (h == null) continue;
            String s = attach(h.shaped, mode, i);
            if (s != null) return s;
        }
        return null;
    }

    // ------------------------------------------------------------------ المطابقة

    /** يطابق كلمة عربية مجرّدة (حروف فقط بلا تشكيل) ويُعيد أفضل تشكيل مدقَّق، أو NONE. لا يحدّث العدّادات. */
    static Match match(String plain) {
        if (plain == null || plain.length() < 3) return NONE;
        Hit h = lookupStem(plain);
        if (h != null) return new Match(h.shaped, h.kind, plain);
        for (int pass = 0; pass <= 2; pass++) { // عدد اللواصق الأمامية تصاعديًا: الأقل تدخّلًا أولًا
            for (int c = 0; c < CONJ.length; c++) {
                for (int p = 0; p < PREP.length; p++) {
                    int count = (c > 0 ? 1 : 0) + (p > 0 ? 1 : 0);
                    if (count != pass) continue;
                    if (!plain.startsWith(CONJ[c])) continue;
                    String r1 = plain.substring(CONJ[c].length());
                    if (!r1.startsWith(PREP[p])) continue;
                    String r2 = r1.substring(PREP[p].length());
                    if (r2.length() < 3) continue;
                    Kind kind = Kind.CLITIC;
                    String core = nounWithArticle(r2, p == LAM_PREP);
                    if (core == null) {
                        core = suffixed(r2);
                        if (count == 0) kind = Kind.SUFFIX;
                    }
                    if (core == null) continue;
                    String shaped = ArabicPhonetics.orderMarks(CONJ_V[c] + PREP_V[p] + core);
                    if (accept(plain, shaped)) return new Match(shaped, kind, r2);
                }
            }
        }
        // الملاذ الأخير: الذكاء المحلي يتنبأ حرفًا حرفًا مما تعلّمه من القاموس (بثقة عالية وقواعد نطق، وإلا لا شيء)
        try {
            String guess = LetterModel.predict(plain);
            if (guess != null && accept(plain, guess)) return new Match(guess, Kind.PREDICTED, null);
        } catch (RuntimeException ignored) {
        }
        return NONE;
    }

    /**
     * نقطة الدخول للقارئ: التشكيل المدقَّق لكلمة عربية مجرّدة (3 أحرف فأكثر)، أو null لو لا يوجد ما يوثق به.
     * يحدّث عدّادات التشخيص.
     */
    static String shape(String plain) {
        if (plain == null || plain.length() < 3) return null;
        C_LOOKUPS.incrementAndGet();
        Match m = match(plain);
        if (!m.found()) {
            C_MISS.incrementAndGet();
            return null;
        }
        C_KIND[m.kind.ordinal()].incrementAndGet();
        return m.shaped;
    }

    // ------------------------------------------------------------------ تحليل نص كامل

    /** تقرير تغطية القاموس لنص (صفحة/مقطع). */
    static final class Report {
        int words;          // كلمات عربية مجرّدة من 3 أحرف فأكثر
        int voweled;        // كلمات مشكولة أصلًا في الملف
        int shortWords;     // كلمات أقل من 3 أحرف
        final int[] byKind = new int[Kind.values().length];
        final Map<String, Integer> unknown = new HashMap<>();

        /** كلمات وُجدت فعلًا في المكتبات (المتنبَّأ بها لا تُحسب). */
        int known() {
            return words - byKind[Kind.NONE.ordinal()] - byKind[Kind.PREDICTED.ordinal()];
        }

        double coverage() {
            return words == 0 ? 0.0 : 100.0 * known() / words;
        }

        /** أكثر الكلمات غير الموجودة تكرارًا. */
        List<Map.Entry<String, Integer>> topUnknown(int max) {
            List<Map.Entry<String, Integer>> l = new ArrayList<>(unknown.entrySet());
            Collections.sort(l, (a, b) -> {
                int d = b.getValue() - a.getValue();
                return d != 0 ? d : a.getKey().compareTo(b.getKey());
            });
            return l.size() > max ? new ArrayList<>(l.subList(0, max)) : l;
        }

        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder();
            sb.append(String.format(Locale.ROOT,
                    "كلمات عربية: %d | موجودة في القاموس: %d (%.0f%%) | مشكولة أصلًا: %d | قصيرة: %d",
                    words, known(), coverage(), voweled, shortWords));
            sb.append(String.format(Locale.ROOT,
                    "\nمطابقة: متعلَّمة %d، حرفية %d، موحَّدة %d، مشتقة %d، بسوابق %d، بضمائر %d | متنبَّأ بها %d",
                    byKind[Kind.LEARNED.ordinal()], byKind[Kind.EXACT.ordinal()], byKind[Kind.NORMALIZED.ordinal()],
                    byKind[Kind.DERIVED.ordinal()], byKind[Kind.CLITIC.ordinal()], byKind[Kind.SUFFIX.ordinal()],
                    byKind[Kind.PREDICTED.ordinal()]));
            List<Map.Entry<String, Integer>> top = topUnknown(10);
            if (!top.isEmpty()) {
                sb.append("\nغير موجودة (الأكثر تكرارًا): ");
                for (int i = 0; i < top.size(); i++) {
                    if (i > 0) sb.append("، ");
                    sb.append(top.get(i).getKey()).append(" ×").append(top.get(i).getValue());
                }
            }
            return sb.toString();
        }
    }

    private static boolean isArabicLetter(char c) {
        return c >= 0x0621 && c <= 0x064A;
    }

    /** يحلّل نصًا: يطابق كل كلمة عربية مع القاموس ويعدّ ما وُجد وما لم يُوجد. لا يغيّر النص ولا يحدّث عدّادات التشغيل. */
    static Report analyze(String text) {
        Report r = new Report();
        if (text == null || text.isEmpty()) return r;
        StringBuilder plain = new StringBuilder();
        boolean marks = false;
        int n = text.length();
        for (int i = 0; i <= n; i++) {
            char c = i < n ? text.charAt(i) : ' ';
            char u = SpeechAuditor.unifyLetter(c);
            if (isArabicLetter(u)) {
                plain.append(u);
            } else if (ArabicPhonetics.isMark(c)) {
                if (plain.length() > 0) marks = true;
            } else if (c == '\u0640') {
                // تطويل داخل الكلمة: لا يقطعها
            } else if (plain.length() > 0) {
                String w = plain.toString();
                plain.setLength(0);
                boolean hadMarks = marks;
                marks = false;
                if (hadMarks) {
                    r.voweled++;
                } else if (w.length() < 3) {
                    r.shortWords++;
                } else {
                    r.words++;
                    Match m = match(w);
                    r.byKind[m.kind.ordinal()]++;
                    if (!m.found() || m.kind == Kind.PREDICTED) {
                        Integer k = r.unknown.get(w);
                        r.unknown.put(w, k == null ? 1 : k + 1);
                    }
                }
            }
        }
        return r;
    }

    // ------------------------------------------------------------------ اقتراحات (تشخيص فقط)

    /** هل a و b يختلفان بحرف واحد (استبدال أو حذف أو إضافة)؟ */
    private static boolean edit1(String a, String b) {
        int la = a.length();
        int lb = b.length();
        if (Math.abs(la - lb) > 1 || a.equals(b)) return false;
        int i = 0;
        while (i < la && i < lb && a.charAt(i) == b.charAt(i)) i++;
        if (la == lb) return a.regionMatches(i + 1, b, i + 1, la - i - 1);
        if (la > lb) return a.regionMatches(i + 1, b, i, lb - i);
        return b.regionMatches(i + 1, a, i, la - i);
    }

    /**
     * كلمات القاموس التي تبعد عن الكلمة حرفًا واحدًا (لتدقيق الإملاء يدويًا أو في تقرير). لا تُستعمل في النطق إطلاقًا
     * لأنها قد تبدّل كلمة طبية صحيحة بأخرى شبيهة خاطئة.
     */
    static List<String> suggest(String plain, int max) {
        List<String> out = new ArrayList<>();
        if (plain == null || plain.length() < 4 || max <= 0) return out;
        String key = normKey(plain);
        Collection<String> keys = TashkeelDict.keys();
        for (String k : keys) {
            if (Math.abs(k.length() - key.length()) <= 1 && edit1(normKey(k), key)) {
                out.add(k);
                if (out.size() >= max) break;
            }
        }
        return out;
    }
}
