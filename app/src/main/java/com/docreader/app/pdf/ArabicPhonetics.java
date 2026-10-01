package com.docreader.app.pdf;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * فهم التشكيل العربي وتحسين اللفظ الأصيل قبل إرسال النص للمحرك الصوتي:
 *
 *  1) normalize: توحيد الحروف الدخيلة (ک ی ہ)، ألف الوصل (ٱ)، الألف الخنجرية (ٰ) تُحوَّل لألف مدّ،
 *     حذف علامات الوقف القرآنية الصغيرة، تركيب الهمزات (أ إ ؤ ئ آ)، وترتيب العلامات فوق/تحت الحرف
 *     على ترتيب ثابت: الشدّة أولًا ثم الحركة/التنوين (بعض المحركات تسقط الشدّة لو جاءت بعد الفتحة).
 *  2) pausal: النص المشكول عند الوقف (قبل نقطة/فاصلة) يُنطق بالسكون: "العِلْمُ." -> "العِلْمْ"،
 *     "كِتَابًا." -> "كِتَابَا"، "حَقٌّ." -> "حَقّ" - كما يقرأ القارئ العربي الفصيح.
 *  3) lookup: قاموس مصطلحات (طب/علاج طبيعي/دراسة) بتشكيل كامل بلا حركة إعراب، مع دعم السوابق
 *     (و/ف + ب/ك/ل + ال) وإدغام اللام الشمسية بالشدّة: "والتمارين" -> "وَالتَّمَارِين".
 */
final class ArabicPhonetics {

    private ArabicPhonetics() {
    }

    static final char SHADDA = '\u0651';
    static final char SUKUN = '\u0652';
    static final char FATHA = '\u064E';
    static final char DAMMA = '\u064F';
    static final char KASRA = '\u0650';
    static final char FATHATAN = '\u064B';
    static final char DAMMATAN = '\u064C';
    static final char KASRATAN = '\u064D';

    static boolean isMark(char c) {
        return (c >= 0x064B && c <= 0x065F) || c == 0x0670;
    }

    static boolean isArabicLetter(char c) {
        return (c >= 0x0621 && c <= 0x064A) || c == 0x0671 || (c >= 0x066E && c <= 0x06D3);
    }

    static boolean hasArabic(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= 0x0600 && c <= 0x06FF) || (c >= 0x0750 && c <= 0x077F)) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ 1) التوحيد

    static String normalize(String s) {
        if (s == null || s.isEmpty() || !hasArabic(s)) return s;
        StringBuilder sb = new StringBuilder(s.length() + 4);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\u0671':
                    sb.append('\u0627');
                    continue;
                case '\u06A9':
                    sb.append('\u0643');
                    continue;
                case '\u06CC':
                    sb.append('\u064A');
                    continue;
                case '\u06C1':
                case '\u06BE':
                case '\u06D5':
                case '\u06C0':
                    sb.append('\u0647');
                    continue;
                case '\u06E1':
                    sb.append(SUKUN);
                    continue;
                case '\u0640': // تطويل: لا يُنطق (وقد يفصل بين الحرف وعلامته)
                    continue;
                case '\u0670': { // ألف خنجرية: تُنطق مدًّا. بعد ى/ي تكون المدّ نفسه فلا نكرّره
                    char base = lastBase(sb);
                    if (base != '\u0649' && base != '\u064A' && base != '\u0627') sb.append('\u0627');
                    continue;
                }
                default:
                    break;
            }
            // حروف فارسية/أردية/أعجمية (پ چ ڤ گ ژ ٹ ...): تُردّ إلى أقرب حرف عربي من قاموس الحروف
            String fr = ArabicLetters.foreign(c);
            if (fr != null) {
                sb.append(fr);
                continue;
            }
            // علامات القراءة القرآنية الصغيرة وأرقام الآيات: لا تُنطق
            if ((c >= 0x06D6 && c <= 0x06DE) || c == 0x06DF || c == 0x06E0 || (c >= 0x06E2 && c <= 0x06E8)
                    || (c >= 0x06EA && c <= 0x06ED) || c == 0x08F0) {
                continue;
            }
            sb.append(c);
        }
        String x = sb.toString();
        try {
            x = Normalizer.normalize(x, Normalizer.Form.NFC); // أ إ ؤ ئ آ
        } catch (Throwable ignored) {
        }
        return fixDefiniteArticles(relocateTanween(orderMarks(x)));
    }

    private static char lastBase(StringBuilder sb) {
        for (int i = sb.length() - 1; i >= 0; i--) {
            char c = sb.charAt(i);
            if (!isMark(c)) return c;
        }
        return ' ';
    }

    /** الشدّة أولًا ثم بقية العلامات (بلا تكرار) لكل حرف. */
    static String orderMarks(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        int i = 0;
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (!isMark(c)) {
                sb.append(c);
                i++;
                continue;
            }
            int j = i;
            boolean shadda = false;
            char vowel = 0;
            StringBuilder other = new StringBuilder();
            while (j < n && isMark(s.charAt(j))) {
                char m = s.charAt(j);
                if (m == SHADDA) shadda = true;
                else if (m >= 0x064B && m <= 0x0652) vowel = m; // آخر حركة تغلب
                else if (other.indexOf(String.valueOf(m)) < 0) other.append(m);
                j++;
            }
            if (shadda) sb.append(SHADDA);
            if (vowel != 0) sb.append(vowel);
            sb.append(other);
            i = j;
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ 1b) تنوين الفتح + ال التعريف + الشدّة

    private static boolean isWordPart(char c) {
        return isArabicLetter(c) || isMark(c);
    }

    private static boolean isShortVowel(char c) {
        return c == FATHA || c == DAMMA || c == KASRA;
    }

    /**
     * تنوين الفتح يُكتب أحيانًا على الألف (كتاباً) وأحيانًا على الحرف قبلها (كتابًا). نوحّده على الحرف قبل الألف
     * كي يقرأه المحرك بنفس الطريقة في الحالتين (بلا تكرار الألف أو إسقاط التنوين).
     */
    static String relocateTanween(String s) {
        if (s == null || s.indexOf(FATHATAN) < 0) return s;
        StringBuilder sb = new StringBuilder(s);
        boolean changed = false;
        for (int i = 1; i + 1 < sb.length(); i++) {
            if (sb.charAt(i) != '\u0627' || sb.charAt(i + 1) != FATHATAN) continue;
            if (i + 2 < sb.length() && isMark(sb.charAt(i + 2))) continue; // علامات أخرى على الألف: لا نمسّها
            int p = i - 1;
            while (p >= 0 && isMark(sb.charAt(p))) p--;
            if (p < 0) continue;
            char base = sb.charAt(p);
            if (base < '\u0621' || base > '\u064A' || base == '\u0627') continue;
            boolean hasVowel = false;
            for (int k = p + 1; k < i; k++) {
                if (sb.charAt(k) != SHADDA) hasVowel = true;
            }
            if (hasVowel) continue;
            sb.deleteCharAt(i + 1);
            sb.insert(i, FATHATAN); // قبل الألف مباشرة = على الحرف السابق (بعد شدّته لو وُجدت)
            changed = true;
        }
        return changed ? orderMarks(sb.toString()) : s;
    }

    /** جذوع تبدأ بـ "ال" لكنها ليست أداة تعريف (اِلْتِهَاب، اِلْتِزَام...): لا نضع عليها شدّة الإدغام. */
    private static final String[] IL_FORMS = {
            "التهاب", "التزام", "التقاط", "التقاء", "التحاق", "التحام", "التفاف", "التفات", "التماس",
            "التصاق", "التواء", "الحاق", "الزام", "الغاء", "الهام", "الصاق", "الحاح", "التقى",
            "التحم", "التهب", "التزم", "التفت", "التمس", "التصق", "التوى", "التقط", "التحق"
    };

    private static boolean isIlForm(String stem) {
        for (String f : IL_FORMS) {
            if (stem.startsWith(f)) return true;
        }
        return false;
    }

    /**
     * ضبط "ال" التعريف في النص المشكول (كلّ كلمة على حدة):
     *  - الحرف الشمسي بعد ال: الشدّة عليه إلزامية واللام لا علامة عليها (الشَّمْس لا الْشَمْس). لو سقطت الشدّة عند
     *    استخراج الـ PDF (والكلمة مشكولة) نُعيدها، ولو بقي سكون/حركة على لام ال قبل حرف شمسي مشدّد نحذفه.
     *  - الحرف القمري: سكون على اللام (الْقَمَر) لو الكلمة مشكولة وبلا علامة على اللام.
     * الكلمات بلا تشكيل تُترك كما هي (المحرك يضبطها)، وكذلك ما يبدأ بـ "ال" وهو من الجذر (التهاب، التزام).
     */
    static String fixDefiniteArticles(String s) {
        if (s == null || s.length() < 5) return s;
        boolean anyMark = false;
        for (int i = 0; i < s.length(); i++) {
            if (isMark(s.charAt(i))) {
                anyMark = true;
                break;
            }
        }
        if (!anyMark) return s;
        StringBuilder out = null;
        int n = s.length();
        int i = 0;
        int copied = 0;
        while (i < n) {
            if (!isWordPart(s.charAt(i))) {
                i++;
                continue;
            }
            int j = i;
            while (j < n && isWordPart(s.charAt(j))) j++;
            String w = s.substring(i, j);
            String fixed = fixArticleWord(w);
            if (!fixed.equals(w)) {
                if (out == null) out = new StringBuilder(n + 4);
                out.append(s, copied, i).append(fixed);
                copied = j;
            }
            i = j;
        }
        if (out == null) return s;
        out.append(s, copied, n);
        return out.toString();
    }

    private static String fixArticleWord(String w) {
        if (w.length() < 5 || isMark(w.charAt(0))) return w;
        List<Character> base = new ArrayList<>(w.length());
        List<StringBuilder> marks = new ArrayList<>(w.length());
        for (int i = 0; i < w.length(); i++) {
            char c = w.charAt(i);
            if (isMark(c)) {
                marks.get(marks.size() - 1).append(c);
            } else {
                base.add(c);
                marks.add(new StringBuilder());
            }
        }
        final int nb = base.size();
        StringBuilder plainSb = new StringBuilder(nb);
        for (char c : base) plainSb.append(c);
        String plain = plainSb.toString();
        int a = -1;
        if (plain.startsWith("\u0627\u0644")) {
            a = 0;
        } else if (plain.length() >= 3 && "\u0648\u0641\u0628\u0643".indexOf(plain.charAt(0)) >= 0
                && plain.startsWith("\u0627\u0644", 1)) {
            a = 1;
        } else if (plain.length() >= 4 && "\u0648\u0641".indexOf(plain.charAt(0)) >= 0
                && "\u0628\u0643\u0644".indexOf(plain.charAt(1)) >= 0 && plain.startsWith("\u0627\u0644", 2)) {
            a = 2;
        }
        if (a < 0) return w;
        if (a + 4 > nb) return w; // بعد ال حرفان على الأقل
        if (isIlForm(plain.substring(a))) return w;
        StringBuilder alifMarks = marks.get(a);
        StringBuilder lam = marks.get(a + 1);
        char x = base.get(a + 2);
        StringBuilder xm = marks.get(a + 2);
        if (alifMarks.indexOf(String.valueOf(KASRA)) >= 0 && lam.indexOf(String.valueOf(SUKUN)) >= 0) return w; // اِلْتِ...
        boolean xShadda = xm.indexOf(String.valueOf(SHADDA)) >= 0;
        boolean xVowel = false;
        for (int k = 0; k < xm.length(); k++) {
            char m = xm.charAt(k);
            if ((m >= FATHATAN && m <= KASRATAN) || isShortVowel(m)) xVowel = true;
        }
        boolean vocalizedRest = false;
        for (int k = a + 3; k < nb && !vocalizedRest; k++) {
            StringBuilder mm = marks.get(k);
            for (int q = 0; q < mm.length(); q++) {
                char m = mm.charAt(q);
                if (m == SHADDA || m == SUKUN || isShortVowel(m) || (m >= FATHATAN && m <= KASRATAN)) {
                    vocalizedRest = true;
                    break;
                }
            }
        }
        boolean lamSukun = lam.indexOf(String.valueOf(SUKUN)) >= 0;
        boolean lamVowel = false;
        for (int k = 0; k < lam.length(); k++) {
            if (isShortVowel(lam.charAt(k))) lamVowel = true;
        }
        boolean sun = SUN.indexOf(x) >= 0;
        boolean changed = false;
        if (sun) {
            if (xShadda) {
                if (lam.length() > 0) {
                    lam.setLength(0); // ال + حرف شمسي مشدّد: لا علامة على اللام
                    changed = true;
                }
            } else if (!lamSukun && (xVowel || vocalizedRest)) {
                xm.insert(0, SHADDA); // سقطت الشدّة عند الاستخراج
                lam.setLength(0);
                changed = true;
            }
        } else if (x != '\u0627' && !lamSukun && !lamVowel && (xVowel || vocalizedRest)) {
            lam.append(SUKUN);
            changed = true;
        }
        if (!changed) return w;
        StringBuilder sb = new StringBuilder(w.length() + 2);
        for (int k = 0; k < nb; k++) sb.append(base.get(k)).append(marks.get(k));
        return orderMarks(sb.toString());
    }

    // ------------------------------------------------------------------ 2) الوقف

    /** كلمة مشكولة قبل علامة وقف: تُحذف حركة الإعراب الأخيرة والتنوين كما في القراءة الفصيحة. */
    static String pausal(String w) {
        if (w == null || w.length() < 2 || !hasArabic(w)) return w;
        if (stripMarks(w).length() < 2) return w; // مقطع من حرف واحد (بَ، بِ، بُ): حركته مقصودة فلا تُسكَّن
        int idx = w.length() - 1;
        while (idx >= 0 && isMark(w.charAt(idx))) idx--;
        if (idx < 0) return w;
        char base = w.charAt(idx);
        String marks = w.substring(idx + 1);
        String head = w.substring(0, idx);

        if (marks.isEmpty()) {
            // "بًا" : تنوين الفتح على الحرف قبل الألف -> فتحة (مدّ)
            if ((base == '\u0627') && head.length() >= 2) {
                int p = head.length() - 1;
                int e = p;
                while (p >= 0 && isMark(head.charAt(p))) p--;
                if (p >= 0 && e > p && head.indexOf(FATHATAN, p + 1) >= 0) {
                    String pm = head.substring(p + 1).replace(String.valueOf(FATHATAN), String.valueOf(FATHA));
                    return head.substring(0, p + 1) + pm + base;
                }
            }
            return w;
        }
        boolean shadda = marks.indexOf(SHADDA) >= 0;
        boolean longBase = base == '\u0627' || base == '\u0649' || base == '\u0648' || base == '\u064A';
        boolean taa = base == '\u0629';
        StringBuilder nm = new StringBuilder();
        if (shadda) nm.append(SHADDA);
        boolean changed = false;
        for (int i = 0; i < marks.length(); i++) {
            char m = marks.charAt(i);
            if (m == SHADDA) continue;
            if (m == DAMMATAN || m == KASRATAN) {
                changed = true;
                continue;
            }
            if (m == FATHATAN) {
                if (base == '\u0627' || base == '\u0649' || taa) {
                    changed = true;
                    continue;
                }
                nm.append(m);
                continue;
            }
            if (m == FATHA || m == DAMMA || m == KASRA) {
                if (longBase) {
                    nm.append(m);
                    continue;
                }
                changed = true;
                continue; // نحذف الحركة، ونضع سكونًا أدناه لو لم تكن شدّة
            }
            nm.append(m);
        }
        if (!changed) return w;
        boolean hasVowelLeft = false;
        for (int i = 0; i < nm.length(); i++) {
            char m = nm.charAt(i);
            if (m != SHADDA) hasVowelLeft = true;
        }
        if (!shadda && !hasVowelLeft && !taa && !longBase) nm.append(SUKUN);
        return head + base + nm;
    }

    // ------------------------------------------------------------------ 2b) واو الجماعة + الألف الفارقة

    /** أفعال ناقصة ماضية واوها من أصل الفعل (دَعَوْا): تُنطق بفتحة ثم واو ساكنة لا بمدّ. */
    private static final Map<String, String> DEFECTIVE_PAST = new HashMap<>();
    /** كلمات تنتهي بـ "وا" وليست واو جماعة (الألف فيها تنوين أو جزء من الكلمة) فلا نمسّها. */
    private static final java.util.Set<String> KEEP_WAW_ALIF = new java.util.HashSet<>(Arrays.asList(
            "\u0639\u0641\u0648\u0627", "\u0644\u0647\u0648\u0627", "\u0639\u0644\u0648\u0627", "\u063A\u0644\u0648\u0627",
            "\u0633\u0645\u0648\u0627", "\u0645\u0627\u0648\u0627"));

    static {
        DEFECTIVE_PAST.put("\u062F\u0639\u0648\u0627", "\u062F\u064E\u0639\u064E\u0648\u0652");   // دعوا
        DEFECTIVE_PAST.put("\u0633\u0639\u0648\u0627", "\u0633\u064E\u0639\u064E\u0648\u0652");   // سعوا
        DEFECTIVE_PAST.put("\u063A\u0632\u0648\u0627", "\u063A\u064E\u0632\u064E\u0648\u0652");   // غزوا
        DEFECTIVE_PAST.put("\u0631\u062C\u0648\u0627", "\u0631\u064E\u062C\u064E\u0648\u0652");   // رجوا
        DEFECTIVE_PAST.put("\u0646\u062C\u0648\u0627", "\u0646\u064E\u062C\u064E\u0648\u0652");   // نجوا
        DEFECTIVE_PAST.put("\u0628\u062F\u0648\u0627", "\u0628\u064E\u062F\u064E\u0648\u0652");   // بدوا
    }

    private static String stripMarks(String w) {
        StringBuilder sb = new StringBuilder(w.length());
        for (int i = 0; i < w.length(); i++) {
            char c = w.charAt(i);
            if (!isMark(c)) sb.append(c);
        }
        return sb.toString();
    }

    private static boolean isBaseLetter(char c) {
        return c >= 0x0621 && c <= 0x064A;
    }

    /**
     * واو الجماعة تُكتب بألف بعدها لا تُنطق (تَجَمَّعُوا = tajammaʿū). المحركات الصوتية تقرأ "وا" كـ wā
     * فيخرج "تجمّعْوَا". نحذف الألف الفارقة ونضمّ ما قبل الواو (لو بلا حركة) فتُنطق مدًّا صافيًا "ــوا" -> "ــُو".
     * لا تُمَسّ الكلمات التي تنتهي بتنوين على الواو (عَفْوًا) ولا القصيرة (سوا/هوا).
     */
    static String fixJamaa(String w) {
        if (w == null || w.length() < 4) return w;
        final int n = w.length();
        if (w.charAt(n - 1) != '\u0627') return w;
        int j = n - 2;
        while (j >= 0 && isMark(w.charAt(j))) j--;
        if (j < 1 || w.charAt(j) != '\u0648') return w;
        String wawMarks = w.substring(j + 1, n - 1);
        boolean wawSukun = false;
        for (int i = 0; i < wawMarks.length(); i++) {
            char m = wawMarks.charAt(i);
            if (m == SUKUN) wawSukun = true;
            else if (m != DAMMA) return w; // فتحة/تنوين/شدّة/كسرة: ليست واو جماعة
        }
        int k = j - 1;
        while (k >= 0 && isMark(w.charAt(k))) k--;
        if (k < 0) return w;
        char prev = w.charAt(k);
        if (!isBaseLetter(prev) || prev == '\u0627' || prev == '\u0648' || prev == '\u0649') return w;
        String plain = stripMarks(w);
        if (plain.length() < 4) return w;
        if (KEEP_WAW_ALIF.contains(plain)) return w;
        if (w.equals(plain)) {
            String def = DEFECTIVE_PAST.get(plain);
            if (def != null) return def;
        }
        String prevMarks = w.substring(k + 1, j);
        boolean prevHasVowel = false;
        for (int i = 0; i < prevMarks.length(); i++) {
            char m = prevMarks.charAt(i);
            if (m >= 0x064B && m <= 0x0652 && m != SHADDA) prevHasVowel = true;
        }
        StringBuilder out = new StringBuilder(n);
        out.append(w, 0, k + 1).append(prevMarks);
        if (!prevHasVowel && !wawSukun) out.append(DAMMA); // ...عُو : مدّ الضمّ
        out.append('\u0648');
        if (wawSukun) out.append(SUKUN);
        return orderMarks(out.toString());
    }

    // ------------------------------------------------------------------ 3) القاموس

    private static final Map<String, String> LEX = new HashMap<>();
    private static final String SUN = "\u062A\u062B\u062F\u0630\u0631\u0632\u0633\u0634\u0635\u0636\u0637\u0638\u0644\u0646";

    private static void lx(String... pairs) {
        for (String p : pairs) {
            int k = p.indexOf('=');
            LEX.put(p.substring(0, k), p.substring(k + 1));
        }
    }

    static {
        // تشريح
        lx("عضلة=عَضَلَة", "عضلات=عَضَلَات", "عضلي=عَضَلِي", "عضلية=عَضَلِيَّة",
                "مفصل=مَفْصِل", "مفاصل=مَفَاصِل", "عظم=عَظْم", "عظام=عِظَام", "عظمي=عَظْمِي",
                "وتر=وَتَر", "أوتار=أَوْتَار", "رباط=رِبَاط", "أربطة=أَرْبِطَة", "رباطي=رِبَاطِي",
                "غضروف=غُضْرُوف", "غضاريف=غَضَارِيف", "عصب=عَصَب", "أعصاب=أَعْصَاب", "عصبي=عَصَبِي",
                "عصبية=عَصَبِيَّة", "فقرة=فَقْرَة", "فقرات=فَقَرَات", "فقري=فَقَرِي", "فقارية=فَقَارِيَّة",
                "عمود=عَمُود", "ركبة=رُكْبَة", "كتف=كَتِف", "أكتاف=أَكْتَاف", "كاحل=كَاحِل", "مرفق=مِرْفَق",
                "رسغ=رُسْغ", "ورك=وَرِك", "فخذ=فَخِذ", "ساق=سَاق", "قدم=قَدَم", "يد=يَد", "ذراع=ذِرَاع",
                "عنق=عُنُق", "ظهر=ظَهْر", "صدر=صَدْر", "بطن=بَطْن", "حوض=حَوْض", "جمجمة=جُمْجُمَة",
                "قلب=قَلْب", "رئة=رِئَة", "دم=دَم", "شريان=شِرْيَان", "شرايين=شَرَايِين", "وريد=وَرِيد",
                "أوردة=أَوْرِدَة", "جلد=جِلْد", "نسيج=نَسِيج", "أنسجة=أَنْسِجَة", "دماغ=دِمَاغ",
                "نخاع=نُخَاع", "شوكي=شَوْكِي", "حبل=حَبْل");
        // حالات وأعراض وعلاج
        lx("ألم=أَلَم", "آلام=آلَام", "التهاب=اِلْتِهَاب", "التهابات=اِلْتِهَابَات", "التواء=اِلْتِوَاء",
                "التصاق=اِلْتِصَاق", "التصاقات=اِلْتِصَاقَات", "تمزق=تَمَزُّق", "تمزقات=تَمَزُّقَات",
                "تورم=تَوَرُّم", "تشنج=تَشَنُّج", "تشنجات=تَشَنُّجَات", "تيبس=تَيَبُّس", "ضمور=ضُمُور",
                "شلل=شَلَل", "كسر=كَسْر", "كسور=كُسُور", "خلع=خَلْع", "إصابة=إِصَابَة", "إصابات=إِصَابَات",
                "علاج=عِلَاج", "علاجي=عِلَاجِي", "علاجية=عِلَاجِيَّة", "تأهيل=تَأْهِيل", "تشخيص=تَشْخِيص",
                "فحص=فَحْص", "أعراض=أَعْرَاض", "مرض=مَرَض", "مريض=مَرِيض", "مرضى=مَرْضَى", "جراحة=جِرَاحَة",
                "دواء=دَوَاء", "أدوية=أَدْوِيَة", "جرعة=جُرْعَة", "تدليك=تَدْلِيك", "شد=شَدّ");
        // حركة وتمرين
        lx("حركة=حَرَكَة", "حركات=حَرَكَات", "مدى=مَدَى", "قوة=قُوَّة", "توازن=تَوَازُن", "مرونة=مُرُونَة",
                "تمرين=تَمْرِين", "تمارين=تَمَارِين", "تقوية=تَقْوِيَة", "إطالة=إِطَالَة", "تمدد=تَمَدُّد",
                "انقباض=اِنْقِبَاض", "انبساط=اِنْبِسَاط", "ثني=ثَنْي", "بسط=بَسْط", "تقريب=تَقْرِيب",
                "تبعيد=تَبْعِيد", "دوران=دَوَرَان", "مشي=مَشْي", "وقوف=وُقُوف", "جلوس=جُلُوس",
                "استلقاء=اِسْتِلْقَاء", "وضعية=وَضْعِيَّة", "وضعيات=وَضْعِيَّات", "تحمل=تَحَمُّل",
                "ثبات=ثَبَات", "تناسق=تَنَاسُق");
        // أجهزة وأدوات ومصطلحات عامة
        lx("جهاز=جِهَاز", "أجهزة=أَجْهِزَة", "تيار=تَيَّار", "تردد=تَرَدُّد", "شدة=شِدَّة", "مدة=مُدَّة",
                "جلسة=جَلْسَة", "جلسات=جَلَسَات", "مرحلة=مَرْحَلَة", "مراحل=مَرَاحِل", "درجة=دَرَجَة",
                "حرارة=حَرَارَة", "برودة=بُرُودَة", "ضغط=ضَغْط", "علم=عِلْم", "دراسة=دِرَاسَة",
                "دراسات=دِرَاسَات", "بحث=بَحْث", "نتيجة=نَتِيجَة", "نتائج=نَتَائِج", "طريقة=طَرِيقَة",
                "طرق=طُرُق", "هدف=هَدَف", "أهداف=أَهْدَاف", "مثال=مِثَال", "أمثلة=أَمْثِلَة", "خطوة=خُطْوَة",
                "خطوات=خُطُوَات", "فائدة=فَائِدَة", "فوائد=فَوَائِد", "سبب=سَبَب", "أسباب=أَسْبَاب",
                "عوامل=عَوَامِل", "نوع=نَوْع", "أنواع=أَنْوَاع", "وظيفة=وَظِيفَة", "وظائف=وَظَائِف",
                "تعريف=تَعْرِيف", "مقدمة=مُقَدِّمَة", "خاتمة=خَاتِمَة", "ملخص=مُلَخَّص", "فصل=فَصْل",
                "صفحة=صَفْحَة", "جدول=جَدْوَل", "شكل=شَكْل", "صورة=صُورَة", "مصدر=مَصْدَر", "مراجع=مَرَاجِع",
                "مرجع=مَرْجِع");
    }


    /** كلمات تُطابَق بحروفها فقط (بلا سوابق) حتى لا تُخطَّأ: ظروف منصوبة بالتنوين وكلمات كثيرة الاستعمال. */
    private static final Map<String, String> EXACT = new HashMap<>();

    private static void ex(String... pairs) {
        for (String p : pairs) {
            int k = p.indexOf('=');
            EXACT.put(p.substring(0, k), p.substring(k + 1));
        }
    }

    static {
        // ظروف وأحوال منصوبة بالتنوين (تُنطق "ـًا" لا "ـا")
        ex("جدا=جِدًّا", "دائما=دَائِمًا", "غالبا=غَالِبًا", "أحيانا=أَحْيَانًا", "احيانا=أَحْيَانًا",
                "تقريبا=تَقْرِيبًا", "مثلا=مَثَلًا", "خصوصا=خُصُوصًا", "عموما=عُمُومًا", "أولا=أَوَّلًا",
                "اولا=أَوَّلًا", "ثانيا=ثَانِيًا", "ثالثا=ثَالِثًا", "رابعا=رَابِعًا", "خامسا=خَامِسًا",
                "أخيرا=أَخِيرًا", "اخيرا=أَخِيرًا", "حاليا=حَالِيًّا", "سابقا=سَابِقًا", "لاحقا=لَاحِقًا",
                "فورا=فَوْرًا", "تماما=تَمَامًا", "كثيرا=كَثِيرًا", "قليلا=قَلِيلًا", "نادرا=نَادِرًا",
                "أساسا=أَسَاسًا", "اساسا=أَسَاسًا", "فعلا=فِعْلًا", "طبعا=طَبْعًا", "شكرا=شُكْرًا",
                "عفوا=عَفْوًا", "أبدا=أَبَدًا", "ابدا=أَبَدًا", "معا=مَعًا", "حقا=حَقًّا", "ايضا=أَيْضًا",
                "سريعا=سَرِيعًا", "بطيئا=بَطِيئًا", "جيدا=جَيِّدًا", "كاملا=كَامِلًا", "مؤقتا=مُؤَقَّتًا",
                "إطلاقا=إِطْلَاقًا", "اطلاقا=إِطْلَاقًا", "نهائيا=نِهَائِيًّا", "تلقائيا=تِلْقَائِيًّا",
                "مبدئيا=مَبْدَئِيًّا", "نسبيا=نِسْبِيًّا", "تدريجيا=تَدْرِيجِيًّا", "جزئيا=جُزْئِيًّا",
                "كليا=كُلِّيًّا", "عمليا=عَمَلِيًّا", "نظريا=نَظَرِيًّا", "سريريا=سَرِيرِيًّا", "طبيا=طِبِّيًّا",
                "علاجيا=عِلَاجِيًّا", "جراحيا=جِرَاحِيًّا", "وظيفيا=وَظِيفِيًّا", "يوميا=يَوْمِيًّا",
                "أسبوعيا=أُسْبُوعِيًّا", "اسبوعيا=أُسْبُوعِيًّا", "شهريا=شَهْرِيًّا", "سنويا=سَنَوِيًّا",
                "مرحبا=مَرْحَبًا", "أهلا=أَهْلًا", "سهلا=سَهْلًا", "صباحا=صَبَاحًا", "فقط=فَقَطْ",
                "ربما=رُبَّمَا", "مائة=مِئَة", "مئة=مِئَة", "مئات=مِئَات", "ألف=أَلْف", "آلاف=آلَاف",
                "مليون=مِلْيُون", "ملايين=مَلَايِين");

        // كلمات وظيفية مبنية (حروف، ضمائر، إشارة، ظروف): تشكيل كامل ثابت بدل ترك المحرك يخمّن نصفها
        // (كانت بعضها مشكولًا وبعضها بلا حركة أصلًا فيتغير النطق من كلمة لأخرى)
        ex("هذا=هَذَا", "هذه=هَذِهِ", "ذلك=ذَلِكَ", "تلك=تِلْكَ", "هؤلاء=هَؤُلَاءِ", "أولئك=أُولَئِكَ", "اولئك=أُولَئِكَ",
                "هنا=هُنَا", "هناك=هُنَاكَ", "هنالك=هُنَالِكَ", "إذا=إِذَا", "اذا=إِذَا", "إذ=إِذْ", "إذن=إِذَنْ",
                "حيث=حَيْثُ", "كيف=كَيْفَ", "أين=أَيْنَ", "متى=مَتَى", "منذ=مُنْذُ", "لكن=لَكِنْ", "لكنه=لَكِنَّهُ",
                "لكنها=لَكِنَّهَا", "لكنهم=لَكِنَّهُمْ", "ثم=ثُمَّ", "قد=قَدْ", "لم=لَمْ", "لن=لَنْ",
                "هل=هَلْ", "بل=بَلْ", "أو=أَوْ", "او=أَوْ", "أم=أَمْ", "إلا=إِلَّا", "الا=إِلَّا", "لا=لَا", "ما=مَا",
                "مع=مَعَ", "بين=بَيْن", "بعد=بَعْد", "قبل=قَبْل", "فوق=فَوْق", "تحت=تَحْت", "عند=عِنْد",
                "لدى=لَدَى", "خلال=خِلَال", "حول=حَوْل", "نحو=نَحْو", "عبر=عَبْر", "ضد=ضِدّ", "دون=دُون",
                "سوى=سِوَى", "عن=عَنْ", "من=مِنْ", "في=فِي", "على=عَلَى", "علي=عَلَى", "إلى=إِلَى", "الى=إِلَى",
                "حتى=حَتَّى", "حتي=حَتَّى", "هو=هُوَ", "هي=هِيَ", "هم=هُمْ", "هن=هُنَّ", "نحن=نَحْنُ", "أنا=أَنَا",
                "أنتم=أَنْتُمْ", "كما=كَمَا", "لأن=لِأَنَّ", "لان=لِأَنَّ", "لكي=لِكَيْ", "كي=كَيْ", "عندما=عِنْدَمَا",
                "حينما=حِينَمَا", "كلما=كُلَّمَا", "مهما=مَهْمَا", "إنما=إِنَّمَا", "انما=إِنَّمَا", "أما=أَمَّا",
                "اما=أَمَّا", "لو=لَوْ", "لولا=لَوْلَا", "لعل=لَعَلَّ", "ليت=لَيْتَ", "سوف=سَوْفَ", "ليس=لَيْسَ",
                "ليست=لَيْسَتْ", "كلا=كِلَا", "بعدما=بَعْدَمَا", "قبلما=قَبْلَمَا", "بينما=بَيْنَمَا",
                "ماذا=مَاذَا", "لماذا=لِمَاذَا", "كذلك=كَذَلِكَ", "لذلك=لِذَلِكَ", "لذا=لِذَا", "بذلك=بِذَلِكَ",
                "أيضا=أَيْضًا", "وكذلك=وَكَذَلِكَ", "ولذلك=وَلِذَلِكَ", "وهو=وَهُوَ", "وهي=وَهِيَ", "وهم=وَهُمْ",
                "فهو=فَهُوَ", "فهي=فَهِيَ", "ومع=وَمَعَ", "ومن=وَمِنْ", "وفي=وَفِي", "وعن=وَعَنْ", "وعلى=وَعَلَى",
                "وإلى=وَإِلَى", "والى=وَإِلَى", "وقد=وَقَدْ", "ولا=وَلَا", "وما=وَمَا", "ولم=وَلَمْ", "ولن=وَلَنْ",
                "فقد=فَقَدْ", "فلا=فَلَا", "فما=فَمَا", "بلا=بِلَا", "بدون=بِدُون", "غير=غَيْرَ",
                "الثاني=الثَّانِي", "الثالث=الثَّالِث", "الرابع=الرَّابِع", "الخامس=الْخَامِس",
                "إما=إِمَّا");
        // أفعال وأدوات كثيرة الورود في النصوص العلمية (بلا حركة إعراب على الآخر)
        ex("يمكن=يُمْكِن", "يجب=يَجِب", "ينبغي=يَنْبَغِي", "يؤدي=يُؤَدِّي", "تؤدي=تُؤَدِّي", "يساعد=يُسَاعِد",
                "تساعد=تُسَاعِد", "يسبب=يُسَبِّب", "تسبب=تُسَبِّب", "يعتمد=يَعْتَمِد", "يختلف=يَخْتَلِف",
                "تختلف=تَخْتَلِف", "يتراوح=يَتَرَاوَح", "تتراوح=تَتَرَاوَح", "يعمل=يَعْمَل", "تعمل=تَعْمَل",
                "يحدث=يَحْدُث", "تحدث=تَحْدُث", "يظهر=يَظْهَر", "تظهر=تَظْهَر", "يزيد=يَزِيد", "تزيد=تَزِيد",
                "يقلل=يُقَلِّل", "تقلل=تُقَلِّل", "يؤثر=يُؤَثِّر", "تؤثر=تُؤَثِّر", "يتم=يَتِمّ", "تتم=تَتِمّ",
                "يكون=يَكُون", "تكون=تَكُون", "كان=كَانَ", "كانت=كَانَتْ", "كانوا=كَانُو", "يوجد=يُوجَد",
                "توجد=تُوجَد", "نحتاج=نَحْتَاج", "يحتاج=يَحْتَاج", "تحتاج=تَحْتَاج", "لاحظ=لَاحِظ",
                "تذكر=تَذَكَّر", "ملاحظة=مُلَاحَظَة", "تنبيه=تَنْبِيه",
                "تحذير=تَحْذِير", "احتياطات=اِحْتِيَاطَات", "موانع=مَوَانِع", "استطباب=اِسْتِطْبَاب",
                "استطبابات=اِسْتِطْبَابَات", "مضاد=مُضَاد", "مضادات=مُضَادَّات", "آثار=آثَار",
                "جانبية=جَانِبِيَّة", "تأثير=تَأْثِير", "تأثيرات=تَأْثِيرَات", "فعالية=فَعَّالِيَّة",
                "فعال=فَعَّال", "آمن=آمِن", "آمنة=آمِنَة", "خطر=خَطَر", "مخاطر=مَخَاطِر", "خطير=خَطِير");
    }

    private static final String[] CONJ = {"", "\u0648", "\u0641"};
    private static final String[] PREP = {"", "\u0628", "\u0643", "\u0644"};

    /** تشكيل كلمة عربية مجرّدة من القاموس (مع سوابقها)، أو null لو غير موجودة. */
    static String lookup(String plain) {
        if (plain == null || plain.length() < 2) return null;
        String ex = EXACT.get(plain);
        if (ex != null) return ex;
        String v = lookupCore(plain);
        if (v != null) return v;
        // كلمة + ضمير متصل (عضلاتها، أعصابهم): نشكّل الجذع من القاموس ونلصق الضمير
        for (int i = 0; i < SUF_PLAIN.length; i++) {
            String suf = SUF_PLAIN[i];
            if (plain.length() - suf.length() < 3 || !plain.endsWith(suf)) continue;
            String stem = lookupCore(plain.substring(0, plain.length() - suf.length()));
            if (stem != null) return orderMarks(stem + SUF_SHAPED[i]);
        }
        return null;
    }

    private static final String[] SUF_PLAIN = {"\u0647\u0645\u0627", "\u0647\u0645", "\u0647\u0646", "\u0647\u0627", "\u0643\u0645"};
    private static final String[] SUF_SHAPED = {"\u0647\u064F\u0645\u064E\u0627", "\u0647\u064F\u0645", "\u0647\u064F\u0646\u0651\u064E",
            "\u0647\u064E\u0627", "\u0643\u064F\u0645"};

    private static String lookupCore(String plain) {
        if (plain == null || plain.length() < 2) return null;
        for (String cj : CONJ) {
            if (!cj.isEmpty() && !plain.startsWith(cj)) continue;
            String r1 = plain.substring(cj.length());
            for (String pp : PREP) {
                if (!pp.isEmpty() && !r1.startsWith(pp)) continue;
                String r2 = r1.substring(pp.length());
                if (r2.isEmpty()) continue;
                boolean article = false;
                String stem = r2;
                if (pp.equals("\u0644") && r2.startsWith("\u0644") && LEX.containsKey(r2.substring(1))) {
                    article = true;
                    stem = r2.substring(1);
                } else if (r2.startsWith("\u0627\u0644") && r2.length() > 3 && LEX.containsKey(r2.substring(2))) {
                    article = true;
                    stem = r2.substring(2);
                }
                String v = LEX.get(stem);
                if (v == null) continue;
                if (cj.isEmpty() && pp.isEmpty() && !article) return v;
                if (article && v.charAt(0) == '\u0627') continue; // ألف وصل بعد ال: نتركها للمحرك
                StringBuilder out = new StringBuilder();
                if (!cj.isEmpty()) out.append(cj).append(cj.equals("\u0648") ? FATHA : FATHA);
                if (!pp.isEmpty()) {
                    out.append(pp).append(pp.equals("\u0628") || pp.equals("\u0644") ? KASRA : FATHA);
                }
                if (article) {
                    boolean sun = SUN.indexOf(v.charAt(0)) >= 0;
                    if (pp.equals("\u0644")) {
                        out.append('\u0644'); // لل
                    } else {
                        out.append("\u0627\u0644");
                    }
                    if (sun) {
                        out.append(v.charAt(0)).append(SHADDA).append(v, 1, v.length());
                    } else {
                        out.append(SUKUN).append(v);
                    }
                } else {
                    out.append(v);
                }
                return orderMarks(out.toString());
            }
        }
        return null;
    }

    /** لاصقة "ال" منفصلة قبل كلمة لاتينية: بالـ MRI -> بِالْ ... تُنطق "al" لا "الف لام". */
    static String clitic(String plainArabic) {
        String p = plainArabic;
        String pre = "";
        if (p.startsWith("\u0648") || p.startsWith("\u0641")) {
            pre += p.charAt(0) + "" + FATHA;
            p = p.substring(1);
        }
        if (p.equals("\u0644\u0644")) return pre + "\u0644" + KASRA + "\u0644" + SUKUN;
        if (p.startsWith("\u0628") || p.startsWith("\u0643") || p.startsWith("\u0644")) {
            char c = p.charAt(0);
            pre += c + "" + (c == '\u0643' ? FATHA : KASRA);
            p = p.substring(1);
        }
        if (p.equals("\u0627\u0644")) return pre + "\u0627\u0644" + SUKUN;
        return null;
    }

    // ------------------------------------------------------------------ 4) التاء المربوطة (ة) والهاء (ه)

    static final char TAA_MARBUTA = '\u0629';
    static final char HAA = '\u0647';
    static final char TAA = '\u062A';

    /** المحرك يقرّر بنفسه (السلوك القديم). */
    static final int TAA_AUTO = 0;
    /** قاعدة العربية الفصحى: تاء (ـَتْ) داخل الجملة عند اتصال الكلمة بما بعدها، وهاء خفيفة (ـَهْ) عند الوقف. */
    static final int TAA_FUSHA = 1;
    /** هاء خفيفة دائمًا (ـَهْ) كما في القراءة بالوقف واللهجات. */
    static final int TAA_HAA = 2;

    private static final String STOP_CHARS = ".,;:!?\u060C\u061B\u061F\u2026)]\"\u00BB";
    /** كلمات لا نضع سكونًا على آخرها أبدًا (لفظ الجلالة له نطق خاص عند المحركات). */
    private static final String[] NO_SUKUN_PARTS = {"\u0644\u0644\u0647"}; // ...لله (الله، لله، بالله، والله، اللهم)

    private static boolean isPureArabicWord(String w) {
        for (int i = 0; i < w.length(); i++) {
            char c = w.charAt(i);
            if (!isArabicLetter(c) && !isMark(c)) return false;
        }
        return true;
    }

    private static boolean hasStop(String trail) {
        for (int i = 0; i < trail.length(); i++) {
            if (STOP_CHARS.indexOf(trail.charAt(i)) >= 0) return true;
        }
        return false;
    }

    /**
     * يضبط أواخر الكلمات العربية في الجملة المنطوقة (يُستدعى بعد التشكيل الذكي):
     *  - التاء المربوطة: تاء داخل الجملة وهاء عند الوقف (أو هاء دائمًا) حسب {@code taaMode}.
     *  - {@code noIrab}: سكون على آخر الكلمة (قراءة بلا إعراب) فلا يخترع المحرك حركات إعراب خاطئة.
     *
     * @param sp         الكلمة المنطوقة (قد تحوي علامات ترقيم أو أكثر من كلمة)
     * @param nextArabic هل الكلمة التالية في الجملة عربية (بلا وقف بينهما)
     */
    static String shapeEndings(String sp, boolean nextArabic, int taaMode, boolean noIrab) {
        if (sp == null || sp.isEmpty()) return sp;
        if (taaMode == TAA_AUTO && !noIrab) return sp;
        String[] parts = sp.split(" ", -1);
        boolean changed = false;
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            int a = 0;
            int b = part.length();
            while (a < b && !isArabicLetter(part.charAt(a))) a++;
            while (b > a && !isArabicLetter(part.charAt(b - 1)) && !isMark(part.charAt(b - 1))) b--;
            if (a >= b) continue;
            String word = part.substring(a, b);
            if (!isPureArabicWord(word)) continue;
            String trail = part.substring(b);
            boolean last = i == parts.length - 1;
            boolean connected = !hasStop(trail) && (!last || nextArabic);
            String w2 = shapeWord(word, connected, taaMode, noIrab);
            if (!w2.equals(word)) {
                parts[i] = part.substring(0, a) + w2 + trail;
                changed = true;
            }
        }
        if (!changed) return sp;
        StringBuilder sb = new StringBuilder(sp.length() + 8);
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) sb.append(' ');
            sb.append(parts[i]);
        }
        return sb.toString();
    }

    private static String shapeWord(String w, boolean connected, int taaMode, boolean noIrab) {
        String r = shapeTaa(w, connected, taaMode);
        if (noIrab && r.equals(w)) r = sukunEnding(r);
        return r;
    }

    /**
     * كلمة تنتهي بـ ة (بلا حركة عليها) -> نطق واضح بحسب الأسلوب:
     *  وقف : "عضلة" -> "عَضَلَهْ"   (الهاء الخفيفة كما في تجويد الوقف على تاء التأنيث)
     *  وصل : "عضلة" -> "عَضَلَتْ"   (تاء ساكنة داخل الجملة: عضلةُ الفخذ)
     * الكلمات المشكولة صراحةً (عَضَلَةُ) لا نمسّها. وما قبل التاء إن كان واوًا/ياءً بلا شدّة نتركه للمحرك
     * (علاجية، دعوة) حتى لا نغيّر مدّ الحرف.
     */
    static String shapeTaa(String w, boolean connected, int mode) {
        if (mode == TAA_AUTO || w == null) return w;
        int t = w.length() - 1;
        if (t < 1 || w.charAt(t) != TAA_MARBUTA) return w;
        int p = t - 1;
        while (p >= 0 && isMark(w.charAt(p))) p--;
        if (p < 0) return w;
        char prev = w.charAt(p);
        String prevMarks = w.substring(p + 1, t);
        boolean hasShadda = prevMarks.indexOf(SHADDA) >= 0;
        boolean hasVowel = false;
        for (int i = 0; i < prevMarks.length(); i++) {
            char m = prevMarks.charAt(i);
            if (m >= FATHATAN && m <= SUKUN && m != SHADDA) hasVowel = true;
        }
        final boolean alif = prev == '\u0627';
        final boolean semi = prev == '\u0648' || prev == '\u064A' || prev == '\u0649';
        final boolean waqf = !connected || mode == TAA_HAA;
        StringBuilder sb = new StringBuilder(w.length() + 2);
        sb.append(w, 0, p).append(prev).append(prevMarks);
        if (waqf) {
            if (semi) return w;
            if (!alif && !hasVowel) sb.append(FATHA);
            return sb.append(HAA).append(SUKUN).toString();
        }
        if (semi && !hasShadda) {
            // "طبيعية جدًا": كانت تُترك للمحرك فيقرؤها هاءً بينما غيرها تاء (خربطة). نجعلها تاءً مثل بقية الكلمات.
            //  ـاية (بداية، نهاية): ياء واحدة مفتوحة  ->  ـايَتْ
            //  ـية بعد حرف صامت (علاجية، سريرية): ياء النسب مشدّدة  ->  ـيَّتْ
            //  ـوة (دعوة): واو مفتوحة  ->  ـوَتْ
            if (prev == '\u0649' || hasVowel) return w;
            int q = p - 1;
            while (q >= 0 && isMark(w.charAt(q))) q--;
            boolean afterAlif = q >= 0 && w.charAt(q) == '\u0627';
            if (prev == '\u064A' && !afterAlif && plainLen(w) >= 5) sb.append(SHADDA);
            sb.append(FATHA);
            return sb.append(TAA).append(SUKUN).toString();
        }
        if (!alif && !semi && !hasVowel) sb.append(FATHA);
        return sb.append(TAA).append(SUKUN).toString();
    }

    private static int plainLen(String w) {
        int n = 0;
        for (int i = 0; i < w.length(); i++) if (!isMark(w.charAt(i))) n++;
        return n;
    }

    /** سكون على آخر حرف صامت من كلمة بلا علامة عليه (قراءة بلا إعراب). لا يمسّ حروف المدّ ولا ة ولا لفظ الجلالة. */
    static String sukunEnding(String w) {
        if (w == null || w.length() < 3) return w;
        char last = w.charAt(w.length() - 1);
        if (isMark(last)) return w;
        if ("\u0627\u0648\u0649\u064A\u0629\u0621\u0623\u0625\u0624\u0626\u0622".indexOf(last) >= 0) return w;
        String plain = stripMarks(w);
        if (plain.length() < 3) return w;
        for (String bad : NO_SUKUN_PARTS) {
            if (plain.contains(bad)) return w;
        }
        return w + SUKUN;
    }

    // ---- تصحيح إملاء ة/ه: "الحركه" -> "الحركة" (فقط بعد "ال" حيث لا يمكن أن تكون ضمير غائب متصلًا)

    /** كلمات مؤنثة شائعة (مفرد/صفة) - تُستعمل لتصحيح الإملاء فقط (بلا تشكيل). */
    private static final java.util.Set<String> TAA_NOUNS = new java.util.HashSet<>(Arrays.asList((
            "مدرسة جامعة كلية حالة مشكلة مسألة قضية نقطة منطقة مساحة مجموعة عملية وسيلة أداة مهمة فكرة رسالة قاعدة "
                    + "قدرة طاقة سرعة دقة صعوبة سهولة أهمية خبرة تجربة معلومة نظرية تقنية برمجة ممارسة مراجعة متابعة "
                    + "مقارنة مناقشة محاضرة مادة مناسبة مساعدة مشاركة مسؤولية نسبة كمية كتابة قراءة رياضة صحة سلامة "
                    + "راحة عادة رغبة حاجة فرصة فترة لحظة ساعة دقيقة ثانية سنة ليلة مرة طبيعة بيئة ثقافة إدارة "
                    + "شركة مؤسسة منظمة هيئة لجنة وزارة حكومة دولة مدينة عائلة أسرة شخصية طفولة شيخوخة ولادة أزمة "
                    + "صدمة جلطة سكتة نوبة عدوى حساسية خلية صفيحة شبكة رقبة معدة قصبة حنجرة سلسلة فئة مادة جملة "
                    + "كلمة لغة قصة حكاية صفة ميزة خاصية نقطة زاوية دائرة مسافة كتلة كثافة مقاومة استجابة "
                    + "عضلية عصبية فقارية علاجية طبية وظيفية جسدية جسمية بدنية حركية نفسية عقلية مفصلية عظمية رباطية "
                    + "وعائية قلبية تنفسية هضمية هرمونية مناعية التهابية مزمنة حادة شديدة خفيفة بسيطة معقدة أساسية "
                    + "رئيسية ثانوية مباشرة مستمرة متكررة مؤقتة دائمة سريرية تشخيصية وقائية تأهيلية قوية ضعيفة "
                    + "سريعة بطيئة كبيرة صغيرة طويلة قصيرة جديدة قديمة مهمة صحيحة خاطئة سليمة مؤلمة طبيعية عادية").split(" ")));

    /** كلمات بعد "ال" تنتهي بـ ـيه وهاؤها أصلية (مصادر تفعيل من جذور معتلة/هائية) فلا تُصحَّح إلى ة. */
    private static final java.util.Set<String> HAA_KEEP = new java.util.HashSet<>(Arrays.asList(
            "\u0627\u0644\u062A\u0648\u062C\u064A\u0647", "\u0627\u0644\u062A\u0646\u0628\u064A\u0647", "\u0627\u0644\u062A\u0634\u0628\u064A\u0647",
            "\u0627\u0644\u062A\u0645\u0648\u064A\u0647", "\u0627\u0644\u062A\u0631\u0641\u064A\u0647", "\u0627\u0644\u062A\u0646\u0648\u064A\u0647",
            "\u0627\u0644\u062A\u0634\u0648\u064A\u0647", "\u0627\u0644\u062A\u0633\u0641\u064A\u0647", "\u0627\u0644\u062A\u0646\u0632\u064A\u0647",
            "\u0627\u0644\u062A\u0648\u0642\u064A\u0647", "\u0627\u0644\u062A\u0641\u0642\u064A\u0647", "\u0627\u0644\u062A\u0648\u0631\u064A\u0647",
            "\u0627\u0644\u062A\u0648\u062C\u064A\u0647", "\u0627\u0644\u062A\u0648\u0628\u064A\u0647", "\u0627\u0644\u062A\u0645\u0647\u064A\u062F"));

    /**
     * كلمة مجرّدة معرّفة بـ "ال" وتنتهي بـ ه وهي في الحقيقة مؤنث بتاء مربوطة كُتب بالهاء (خطأ إملائي شائع في
     * النصوص المكتوبة سريعًا أو الممسوحة ضوئيًا): "الحركه" -> "الحركة". تُترك الكلمة كما هي لو لم نتأكد.
     */
    static String fixTaaTypo(String plain) {
        if (plain == null || plain.length() < 5 || plain.charAt(plain.length() - 1) != HAA) return plain;
        for (int i = 0; i < plain.length(); i++) {
            char c = plain.charAt(i);
            if (c < 0x0621 || c > 0x064A) return plain; // مجرّدة من العلامات والحروف الغريبة فقط
        }
        // السابقة (و/ف) ثم (ب/ك/ل) ثم "ال" - لازم "ال" ليستحيل الضمير المتصل
        String r = plain;
        if ((r.startsWith("\u0648") || r.startsWith("\u0641")) && r.length() > 5) r = r.substring(1);
        String core = null;
        if (r.startsWith("\u0627\u0644")) core = r;
        else if ((r.startsWith("\u0628") || r.startsWith("\u0643")) && r.startsWith("\u0627\u0644", 1)) core = r.substring(1);
        else if (r.startsWith("\u0644\u0644")) core = "\u0627\u0644" + r.substring(2);
        if (core == null || core.length() < 5) return plain;
        if (HAA_KEEP.contains(core) || core.contains("\u0644\u0644\u0647")) return plain; // الله، الوجه...
        String stem = core.substring(2, core.length() - 1); // بلا ال وبلا ه
        String withTaa = stem + TAA_MARBUTA;
        boolean known = TAA_NOUNS.contains(withTaa) || LEX.containsKey(withTaa);
        // نسبة طويلة (علاجي، وظيفي): 5 أحرف فأكثر؛ الأقصر قد تكون فعيلًا هاؤه أصلية (الفقيه، السفيه، النبيه)
        boolean nisba = stem.length() >= 5 && stem.endsWith("\u064A");
        if (!known && !nisba) return plain;
        return plain.substring(0, plain.length() - 1) + TAA_MARBUTA;
    }

    static List<String> words(String s) {
        return new ArrayList<>(Arrays.asList(s.split(" ")));
    }
}
