package com.docreader.app.pdf;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * مدقّق الجملة قبل النطق (يعمل داخل SpeechPrep قبل إرسال النص للمحرك العصبي أو محرك الجهاز).
 *
 * المشكلة: حرف الكاف (ك) وأشباهه لا يُنطق في بعض الحالات، سواء كان متصلًا بكلمته أو منفصلًا عنها:
 *  1) ملفات الـ PDF تُخرج الكاف بأشكال غير قياسية (ک ڪ ݢ ...) فيتجاهلها المحرك.
 *  2) الكاف (أو "ال") تنفصل عن كلمتها في الاستخراج: "ذل ك" / "ال ك تاب" / "ـك" / "كـ"، فتُنطق حرفًا مبتورًا أو تضيع.
 *  3) الكاف المنفردة (بلا كلمة) لا ينطقها المحرك أصلًا.
 *  4) أي تحويل داخلي (تشكيل، اختصارات، تصحيح إملاء) قد يُسقط حرفًا من الكلمة دون أن ينتبه أحد.
 *
 * الحل - ثلاث مراحل، كلها على مستوى الكلمة فتبقى خريطة التظليل (موضع المنطوق -> موضع الأصل) سليمة:
 *  أ) unifyLetters: توحيد أشكال الكاف والياء إلى الشكل القياسي (بنفس الطول تمامًا).
 *  ب) mergeDetached: إعادة وصل الكاف و"ال" المنفصلتين بكلمتهما (باتجاه الوصل الصحيح: ـك للخلف، كـ للأمام).
 *  ج) lostLetters + safeForm + loneKaf: مقارنة حروف الكلمة الأصلية بحروف المنطوق؛ لو ضاع حرف نعود لصيغة آمنة،
 *     والكاف المنفردة تُنطق باسمها (كَاف).
 * لا يلمس النص المعروض إطلاقًا - التدقيق للنطق فقط.
 */
final class SpeechAuditor {

    private SpeechAuditor() {
    }

    private static final char TATWEEL = '\u0640';
    private static final char KAF = '\u0643';
    private static final String PUNCT = ".,;:!?\u060C\u061B\u061F";
    private static final String WEAK = "\u0627\u0648\u064A\u0647"; // ا و ي ه : تتبدّل كثيرًا بين الإملاءات فلا نعدّها ضياعًا

    // ------------------------------------------------------------------ أ) توحيد الأشكال

    /** شكل الكاف/الياء غير القياسي -> القياسي. بنفس الطول (حرف بحرف). */
    static char unifyLetter(char c) {
        switch (c) {
            case '\u06A9': // ک  (فارسية)
            case '\u06AA': // ڪ
            case '\u06AB':
            case '\u06AC':
            case '\u06AD': // ڭ
            case '\u06AE':
            case '\u0762':
            case '\u0763':
            case '\u0764':
                return KAF;
            case '\u06CC': // ی (فارسية)
                return '\u064A';
            default:
                return c;
        }
    }

    static String unifyLetters(String s) {
        if (s == null || s.isEmpty()) return s;
        char[] a = null;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            char u = unifyLetter(c);
            if (u != c) {
                if (a == null) a = s.toCharArray();
                a[i] = u;
            }
        }
        return a == null ? s : new String(a);
    }

    // ------------------------------------------------------------------ هيكل الحروف

    private static char eq(char c) {
        switch (c) {
            case '\u0623':
            case '\u0625':
            case '\u0622':
            case '\u0671':
                return '\u0627';
            case '\u0624':
                return '\u0648';
            case '\u0626':
            case '\u0649':
                return '\u064A';
            case '\u0629':
                return '\u0647';
            default:
                return c;
        }
    }

    /** حروف الكلمة العربية الأساسية فقط (بلا تشكيل ولا تطويل ولا رموز)، بعد توحيد الأشكال والهمزات. */
    static String skeleton(String s) {
        if (s == null || s.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = unifyLetter(s.charAt(i));
            if (c >= 0x0621 && c <= 0x064A && c != 0x0621 && c != TATWEEL) sb.append(eq(c));
        }
        return sb.toString();
    }

    private static boolean isMarkOrTatweel(char c) {
        return c == TATWEEL || ArabicPhonetics.isMark(c);
    }

    private static boolean isArabicLetterChar(char c) {
        char u = unifyLetter(c);
        return u >= 0x0621 && u <= 0x064A && u != TATWEEL;
    }

    /** الرمز كله حروف عربية/علامات/تطويل (بلا أرقام ولا ترقيم ولا لاتينية). */
    private static boolean isPureArabic(String s) {
        if (s == null || s.isEmpty()) return false;
        boolean any = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (isArabicLetterChar(c)) any = true;
            else if (!isMarkOrTatweel(c)) return false;
        }
        return any;
    }

    private static boolean hasLatinOrDigit(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) return true;
        }
        return false;
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || Character.getType(c) == Character.NON_SPACING_MARK;
    }

    private static boolean startsWithArabicLetter(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == TATWEEL) continue;
            return isArabicLetterChar(c);
        }
        return false;
    }

    // ------------------------------------------------------------------ ب) وصل الأجزاء المنفصلة

    /** كلمات تنتهي بكاف كثيرًا ما تنفصل كافها عنها في الـ PDF (بصيغة الهيكل الموحّد). */
    private static final Set<String> KAF_WORDS = new HashSet<>();
    /** كلمة قبل الحرف تدل أنه رمز يُذكر بذاته (النقطة ك، حرف ك) فلا يُلصق بها. */
    private static final Set<String> LETTER_CONTEXT = new HashSet<>();
    private static final Set<String> AL_ONLY = new HashSet<>();

    static {
        for (String w : new String[]{"ذلك", "تلك", "ذاك", "هناك", "هنالك", "أولئك", "اولئك", "كذلك", "لذلك", "بذلك",
                "ولذلك", "وكذلك", "فلذلك", "وبذلك", "إليك", "عليك", "لديك", "منك", "عنك", "فيك", "معك", "بينك",
                "حولك", "قبلك", "بعدك", "لكنك", "أنك", "إنك", "لأنك", "بك", "لك", "وذلك", "فذلك", "حينذاك"}) {
            KAF_WORDS.add(skeleton(w));
        }
        for (String w : new String[]{"حرف", "الحرف", "بحرف", "لحرف", "حروف", "الحروف", "الرمز", "رمز", "المتغير",
                "متغير", "النقطة", "نقطة", "الزاوية", "زاوية", "المحور", "محور", "الخانة", "خانة", "العمود", "عمود"}) {
            LETTER_CONTEXT.add(skeleton(w));
        }
        for (String w : new String[]{"ال", "وال", "فال", "بال", "كال", "لل", "ولل", "فلل"}) {
            AL_ONLY.add(skeleton(w));
        }
    }

    /** يحذف علامات الترقيم/الأقواس من طرفي الرمز (يُبقي الحروف والتشكيل). */
    private static String stripEdgePunct(String t) {
        int a = 0, b = t.length();
        while (a < b && !isWordChar(t.charAt(a)) && t.charAt(a) != TATWEEL) a++;
        while (b > a && !isWordChar(t.charAt(b - 1)) && t.charAt(b - 1) != TATWEEL) b--;
        return t.substring(a, b);
    }

    /**
     * الكلمة تدل أن ما بعدها حرف/رمز يُذكر بذاته (النقطة ك، حرف ب، والنقطة ب): لا نلصقه بالكلمة التالية.
     * يتجاوز حرف عطف/جر ملتصقًا في أولها (والنقطة).
     */
    static boolean isLetterContext(String tok) {
        if (tok == null || tok.isEmpty()) return false;
        String sk = skeleton(tok);
        if (sk.isEmpty()) return false;
        if (LETTER_CONTEXT.contains(sk)) return true;
        return sk.length() > 2 && "\u0648\u0641\u0628\u0644\u0643".indexOf(sk.charAt(0)) >= 0
                && LETTER_CONTEXT.contains(sk.substring(1));
    }

    /** الرمز حرف واحد فقط (مع علامات/تطويل اختياريًا) - مثل: ك / كـ / ـك / كَ */
    private static boolean isLoneLetter(String tok) {
        return isPureArabic(tok) && skeleton(tok).length() == 1;
    }

    private static boolean isLoneKafToken(String tok) {
        return isLoneLetter(tok) && skeleton(tok).charAt(0) == KAF;
    }

    /** الكلمة السابقة صالحة لاستقبال لاصقة (ك) في آخرها: عربية خالصة، لا ترقيم في آخرها. */
    private static boolean canTakeSuffix(String prev) {
        if (!isPureArabic(prev)) return false;
        String sk = skeleton(prev);
        if (sk.length() < 2) return false;
        return !AL_ONLY.contains(sk); // "ال" وحدها تنتظر ما بعدها لا ما قبلها
    }

    /**
     * يعيد وصل الأجزاء المنفصلة (المواضع الأصلية لكل رمز محفوظة، والتظليل يبقى على أول جزء):
     *  - ك منفصلة بعد كلمة: "ذل ك" -> "ذلك"؛ "كتاب ـك" -> "كتابك" (التطويل قبل الكاف يدل أنها متصلة بما قبلها).
     *  - كـ (تطويل بعد الكاف) تُترك لتتصل بما بعدها.
     *  - "ال" + حرف منفصل + بقية الكلمة: "ال ك تاب" -> "الكتاب".
     */
    static void mergeDetached(List<Integer> starts, List<String> toks) {
        final int n = toks.size();
        List<Integer> ns = new ArrayList<>(n);
        List<String> nt = new ArrayList<>(n);
        for (int k = 0; k < n; k++) {
            String cur = toks.get(k);

            // (1) كاف منفصلة عن الكلمة التي قبلها
            if (!nt.isEmpty() && isLoneKafToken(cur)) {
                String prev = nt.get(nt.size() - 1);
                if (canTakeSuffix(prev)) {
                    boolean leadT = cur.charAt(0) == TATWEEL;
                    boolean trailT = cur.charAt(cur.length() - 1) == TATWEEL;
                    String nextTok = k + 1 < n ? toks.get(k + 1) : null;
                    // الكلمة التالية قد تلتصق بها علامة ترقيم (المعلم؟ / الجامعة،): كانت تُعدّ "ليست كلمة" فتلتصق الكاف بما قبلها خطأً
                    String nextCore = nextTok == null ? null : stripEdgePunct(nextTok);
                    boolean nextWord = nextCore != null && isPureArabic(nextCore) && startsWithArabicLetter(nextCore)
                            && skeleton(nextCore).length() >= 2;
                    String pk = skeleton(prev);
                    boolean known = KAF_WORDS.contains(pk + KAF);
                    boolean back;
                    if (leadT && !trailT) back = true;
                    else if (trailT && !leadT) back = false;
                    else if (known) back = true;
                    else if (pk.charAt(pk.length() - 1) == KAF) back = false; // "لديك ك": الكاف الثانية ليست تكملة
                    else back = !nextWord && !LETTER_CONTEXT.contains(pk);
                    if (back) {
                        nt.set(nt.size() - 1, prev + cur);
                        continue;
                    }
                }
            }

            // (1-ب) أي حرف منفصل بتطويل يحدد اتجاه وصله: "ـل" تتصل بما قبلها، و"ل ـ" / "لـ" بما بعدها.
            //       وحروف لا تقف وحدها أبدًا (ة ء ئ ؤ) تتصل بما قبلها: "عضل ة" -> "عضلة".
            if (!nt.isEmpty() && isLoneLetter(cur) && cur.charAt(0) != KAF && !isLoneKafToken(cur)) {
                String prev = nt.get(nt.size() - 1);
                char b0 = skeleton(cur).charAt(0);
                boolean leadT = cur.charAt(0) == TATWEEL;
                boolean neverAlone = "\u0629\u0621\u0626\u0624".indexOf(unifyLetter(stripLead(cur))) >= 0;
                if ((leadT || neverAlone) && canTakeSuffix(prev) && b0 != '\u0648') {
                    nt.set(nt.size() - 1, prev + cur);
                    continue;
                }
            }
            if (k + 1 < n && isLoneLetter(cur) && cur.charAt(cur.length() - 1) == TATWEEL) {
                String nx = toks.get(k + 1);
                if (isPureArabic(nx) && startsWithArabicLetter(nx)) {
                    ns.add(starts.get(k));
                    nt.add(cur + nx);
                    k++;
                    continue;
                }
            }

            // (2) "ال" + حرف منفصل + بقية الكلمة -> كلمة واحدة (ال ك تاب)
            if (k + 2 < n && isPureArabic(cur) && AL_ONLY.contains(skeleton(cur))) {
                String mid = toks.get(k + 1);
                String tail = toks.get(k + 2);
                if (isLoneLetter(mid) && isPureArabic(tail) && skeleton(tail).length() >= 2
                        && !skeleton(tail).startsWith("\u0627\u0644")) {
                    ns.add(starts.get(k));
                    nt.add(cur + mid + tail);
                    k += 2;
                    continue;
                }
            }

            ns.add(starts.get(k));
            nt.add(cur);
        }
        starts.clear();
        starts.addAll(ns);
        toks.clear();
        toks.addAll(nt);
    }

    // ------------------------------------------------------------------ ج) حارس الحروف

    private static int[] strongCounts(String skel) {
        int[] c = new int[0x064A - 0x0621 + 1];
        for (int i = 0; i < skel.length(); i++) {
            char ch = skel.charAt(i);
            if (WEAK.indexOf(ch) >= 0) continue;
            int idx = ch - 0x0621;
            if (idx >= 0 && idx < c.length) c[idx]++;
        }
        return c;
    }

    /**
     * هل ضاع حرف من الكلمة العربية الأصلية عند تحويلها إلى المنطوق؟
     * نقارن الحروف "القوية" فقط (بلا ا و ي ه لأنها تتبدّل بين الإملاءات: ة/ه، ى/ي، الهمزات).
     * يُعيد false لو الكلمة فيها أرقام/لاتينية أو حرفان أقل (بنود التعداد وأسماء الحروف).
     */
    static boolean lostLetters(String tok, String spoken) {
        if (tok == null || spoken == null || spoken.isEmpty()) return false;
        if (hasLatinOrDigit(tok)) return false;
        String a = skeleton(tok);
        if (a.length() < 2) return false;
        String b = skeleton(spoken);
        if (b.isEmpty()) return false; // المنطوق ليس عربيًا (قاموس المستخدم/رقم...): لا نتدخل
        int[] ca = strongCounts(a);
        int[] cb = strongCounts(b);
        for (int i = 0; i < ca.length; i++) {
            if (ca[i] > cb[i]) return true;
        }
        return false;
    }

    private static char lastPunct(String tail) {
        for (int i = tail.length() - 1; i >= 0; i--) {
            char c = tail.charAt(i);
            if (isWordChar(c)) break;
            if (c == '\u2026') return '.';
            if (PUNCT.indexOf(c) >= 0) return c;
        }
        return 0;
    }

    /** صيغة آمنة للكلمة: حروفها كما هي (موحّدة الأشكال) + آخر علامة وقف. تُستعمل لو أضاع التحويل حرفًا. */
    static String safeForm(String ct) {
        if (ct == null || ct.isEmpty()) return "";
        int a = 0, b = ct.length();
        while (a < b && !isWordChar(ct.charAt(a))) a++;
        while (b > a && !isWordChar(ct.charAt(b - 1))) b--;
        if (a >= b) return "";
        String core = ct.substring(a, b);
        StringBuilder sb = new StringBuilder(core.length());
        for (int i = 0; i < core.length(); i++) {
            char c = unifyLetter(core.charAt(i));
            if (c == TATWEEL) continue;
            sb.append(isWordChar(c) ? c : ' ');
        }
        String r = sb.toString().replaceAll("\\s+", " ").trim();
        char p = lastPunct(ct.substring(b));
        return p == 0 ? r : r + p;
    }

    /**
     * الكاف المنفردة (بلا كلمة) لا ينطقها المحرك: تُنطق باسمها "كَاف".
     * ct = الرمز بعد التنظيف؛ sp = المنطوق الحالي. يُعيد null لو الرمز ليس كافًا منفردة.
     */
    static String loneKaf(String ct, String sp) {
        if (ct == null || sp == null) return null;
        int a = 0, b = ct.length();
        while (a < b && !isWordChar(ct.charAt(a))) a++;
        while (b > a && !isWordChar(ct.charAt(b - 1))) b--;
        if (a >= b) return null;
        String core = ct.substring(a, b);
        if (!isLoneKafToken(core)) return null;
        if (!skeleton(sp).equals("\u0643")) return null; // تغيّر المنطوق لسبب آخر (اسم حرف مثلًا): نتركه
        String name = ArabicLetters.spokenName(KAF);
        if (name == null) name = "\u0643\u064E\u0627\u0641";
        char p = lastPunct(ct.substring(b));
        return p == 0 ? name : name + p;
    }

    private static char stripLead(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == TATWEEL || ArabicPhonetics.isMark(c)) continue;
            return c;
        }
        return ' ';
    }

    // ------------------------------------------------------------------ د) إطلاق الحروف الأخيرة

    /**
     * حروف انفجارية إذا جاءت آخر الكلمة بلا حركة (ذَلِك، كتاب، وقت) ابتلعها المحرك العصبي فلا يُسمع الحرف:
     * الكاف أكثرها، ثم ق ط ب د ت ج ض. نضع عليها سكونًا صريحًا فيُنطق الحرف مُطلَقًا واضحًا.
     * لا نمسّ: كلمة فيها حركة/تنوين على آخرها، كلمة أقل من ثلاثة حروف، التاء المربوطة، ما فيه لاتينية/أرقام.
     */
    private static final String RELEASE = "\u0643\u0642\u0637\u0628\u062F\u062A\u062C\u0636";

    static String releaseFinals(String sp) {
        if (sp == null || sp.isEmpty() || sp.indexOf(' ') < 0 && sp.length() < 3) return sp;
        String[] parts = sp.split(" ", -1);
        boolean changed = false;
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            int b = part.length();
            while (b > 0 && !isWordChar(part.charAt(b - 1))) b--;
            if (b < 3) continue;
            int a = 0;
            while (a < b && !isWordChar(part.charAt(a))) a++;
            String w = part.substring(a, b);
            if (!isPureArabic(w) || skeleton(w).length() < 3) continue;
            char last = w.charAt(w.length() - 1);
            if (RELEASE.indexOf(unifyLetter(last)) < 0) continue;
            parts[i] = part.substring(0, b) + '\u0652' + part.substring(b);
            changed = true;
        }
        if (!changed) return sp;
        StringBuilder sb = new StringBuilder(sp.length() + 8);
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) sb.append(' ');
            sb.append(parts[i]);
        }
        return sb.toString();
    }
}
