package com.docreader.app.viewer;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * محرّك تنسيق الأرقام والتواريخ على طريقة Excel (رموز التنسيق مثل
 * {@code #,##0.00} و{@code 0%} و{@code yyyy-mm-dd} و{@code [$-401]dddd}).
 * لا يعتمد على android.* ليمكن اختباره خارج أندرويد.
 */
public final class XlsxNumFmt {

    /** نتيجة التنسيق: النص + لون اختياري (مثل [Red]) + هل القيمة سالبة بلا إشارة تلقائية. */
    public static final class Result {
        public final String text;
        public final String color;
        Result(String text, String color) { this.text = text; this.color = color; }
    }

    private static final Map<Integer, String> BUILTIN = new HashMap<>();
    static {
        BUILTIN.put(0, "General");
        BUILTIN.put(1, "0");
        BUILTIN.put(2, "0.00");
        BUILTIN.put(3, "#,##0");
        BUILTIN.put(4, "#,##0.00");
        BUILTIN.put(9, "0%");
        BUILTIN.put(10, "0.00%");
        BUILTIN.put(11, "0.00E+00");
        BUILTIN.put(12, "# ?/?");
        BUILTIN.put(13, "# ??/??");
        BUILTIN.put(14, "yyyy-mm-dd");
        BUILTIN.put(15, "d-mmm-yy");
        BUILTIN.put(16, "d-mmm");
        BUILTIN.put(17, "mmm-yy");
        BUILTIN.put(18, "h:mm AM/PM");
        BUILTIN.put(19, "h:mm:ss AM/PM");
        BUILTIN.put(20, "h:mm");
        BUILTIN.put(21, "h:mm:ss");
        BUILTIN.put(22, "yyyy-mm-dd h:mm");
        BUILTIN.put(37, "#,##0 ;(#,##0)");
        BUILTIN.put(38, "#,##0 ;[Red](#,##0)");
        BUILTIN.put(39, "#,##0.00;(#,##0.00)");
        BUILTIN.put(40, "#,##0.00;[Red](#,##0.00)");
        BUILTIN.put(45, "mm:ss");
        BUILTIN.put(46, "[h]:mm:ss");
        BUILTIN.put(47, "mmss.0");
        BUILTIN.put(48, "##0.0E+0");
        BUILTIN.put(49, "@");
    }

    private static final String[] MONTHS_EN = {"January", "February", "March", "April", "May", "June",
            "July", "August", "September", "October", "November", "December"};
    private static final String[] DAYS_EN = {"Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday"};
    private static final String[] MONTHS_AR = {"يناير", "فبراير", "مارس", "أبريل", "مايو", "يونيو",
            "يوليو", "أغسطس", "سبتمبر", "أكتوبر", "نوفمبر", "ديسمبر"};
    private static final String[] DAYS_AR = {"الأحد", "الاثنين", "الثلاثاء", "الأربعاء", "الخميس", "الجمعة", "السبت"};

    private final boolean date1904;
    private final Map<String, Parsed> cache = new HashMap<>();

    public XlsxNumFmt(boolean date1904) { this.date1904 = date1904; }

    public static String builtinCode(int id) { return BUILTIN.get(id); }

    /** هل الرمز تنسيق تاريخ/وقت؟ */
    public static boolean isDateCode(String code) {
        if (code == null) return false;
        return parse0(code, null).sections.length > 0 && anyDate(code);
    }

    private static boolean anyDate(String code) {
        Parsed p = parse0(code, null);
        for (Section s : p.sections) if (s.isDate) return true;
        return false;
    }

    // ------------------------------------------------------------------ التحليل

    private static final class Tok {
        static final int LIT = 0, DIGIT = 1, POINT = 2, PERCENT = 3, EXP = 4, DATE = 5, THOUSANDS_SCALE = 6,
                AMPM = 7, SLASH = 8, TEXT = 9, SPACE = 10, FILL = 11, ELAPSED = 12;
        int type;
        String s;      // للحرفي أو رمز التاريخ
        char digit;    // 0 # ?
        Tok(int type, String s) { this.type = type; this.s = s; }
    }

    private static final class Section {
        List<Tok> toks = new ArrayList<>();
        String color;
        String condOp;      // > < >= <= = <>
        double condVal;
        boolean hasCond;
        boolean isDate;
        boolean hasText;
        boolean isGeneral;
        boolean arabic;     // [$-401]
        int percent;
        boolean thousandsSep;
        int scaleCommas;
        int intDigits, decDigits;
        boolean sci;
        boolean fraction;
        int fracDen;        // 0 = غير محدد (#/#)
        int fracDenDigits;
        boolean hasAmPm;
    }

    private static final class Parsed {
        Section[] sections = new Section[0];
    }

    private Parsed parse(String code) {
        Parsed p = cache.get(code);
        if (p == null) {
            p = parse0(code, null);
            cache.put(code, p);
        }
        return p;
    }

    private static Parsed parse0(String code, Object unused) {
        Parsed p = new Parsed();
        List<String> parts = splitSections(code);
        p.sections = new Section[parts.size()];
        for (int i = 0; i < parts.size(); i++) p.sections[i] = parseSection(parts.get(i));
        return p;
    }

    private static List<String> splitSections(String code) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quote = false;
        for (int i = 0; i < code.length(); i++) {
            char c = code.charAt(i);
            if (c == '"') { quote = !quote; cur.append(c); continue; }
            if (!quote && c == '\\' && i + 1 < code.length()) { cur.append(c).append(code.charAt(++i)); continue; }
            if (!quote && c == ';') { out.add(cur.toString()); cur.setLength(0); continue; }
            cur.append(c);
        }
        out.add(cur.toString());
        return out;
    }

    private static boolean isDateLetter(char c) {
        c = Character.toLowerCase(c);
        return c == 'y' || c == 'm' || c == 'd' || c == 'h' || c == 's' || c == 'e' || c == 'g' || c == 'b';
    }

    private static Section parseSection(String code) {
        Section s = new Section();
        String trimmed = code.trim();
        if (trimmed.equalsIgnoreCase("general") || trimmed.isEmpty() && code.isEmpty()) {
            s.isGeneral = trimmed.equalsIgnoreCase("general");
        }
        int i = 0, n = code.length();
        boolean seenPoint = false;
        boolean inDigits = false;
        while (i < n) {
            char c = code.charAt(i);
            if (c == '[') {
                int j = code.indexOf(']', i);
                if (j < 0) j = n - 1;
                String tag = code.substring(i + 1, j);
                String low = tag.toLowerCase(Locale.ROOT);
                if (low.startsWith("$")) {
                    // [$€-407] أو [$-401]
                    int dash = tag.indexOf('-');
                    String cur = dash >= 0 ? tag.substring(1, dash) : tag.substring(1);
                    if (!cur.isEmpty()) s.toks.add(new Tok(Tok.LIT, cur));
                    if (dash >= 0) {
                        try {
                            String hex = tag.substring(dash + 1);
                            int lcid = Integer.parseInt(hex, 16);
                            if ((lcid & 0x3FF) == 0x01) s.arabic = true;
                        } catch (NumberFormatException ignore) { }
                    }
                } else if (low.equals("red") || low.equals("green") || low.equals("blue") || low.equals("black")
                        || low.equals("white") || low.equals("cyan") || low.equals("magenta") || low.equals("yellow")) {
                    s.color = colorHex(low);
                } else if (low.startsWith("color")) {
                    s.color = null;
                } else if (low.startsWith("h") || low.startsWith("m") || low.startsWith("s")) {
                    // [h] [mm] [ss] : زمن منقضٍ
                    Tok t = new Tok(Tok.ELAPSED, low);
                    s.toks.add(t);
                    s.isDate = true;
                } else if (low.startsWith(">") || low.startsWith("<") || low.startsWith("=")) {
                    int k = 0;
                    while (k < low.length() && "<>=".indexOf(low.charAt(k)) >= 0) k++;
                    s.condOp = low.substring(0, k);
                    try { s.condVal = Double.parseDouble(low.substring(k)); s.hasCond = true; }
                    catch (NumberFormatException ignore) { }
                }
                i = j + 1;
                continue;
            }
            if (c == '"') {
                int j = code.indexOf('"', i + 1);
                if (j < 0) j = n;
                s.toks.add(new Tok(Tok.LIT, code.substring(i + 1, j)));
                i = j + 1;
                continue;
            }
            if (c == '\\') {
                if (i + 1 < n) s.toks.add(new Tok(Tok.LIT, String.valueOf(code.charAt(i + 1))));
                i += 2;
                continue;
            }
            if (c == '_') {
                s.toks.add(new Tok(Tok.SPACE, " "));
                i += 2;
                continue;
            }
            if (c == '*') { i += 2; continue; }
            if (c == '@') { Tok t = new Tok(Tok.TEXT, "@"); s.toks.add(t); s.hasText = true; i++; continue; }
            if (c == '%') { s.percent++; s.toks.add(new Tok(Tok.PERCENT, "%")); i++; continue; }
            if ((c == 'E' || c == 'e') && i + 1 < n && (code.charAt(i + 1) == '+' || code.charAt(i + 1) == '-')) {
                Tok t = new Tok(Tok.EXP, code.substring(i, i + 2));
                s.toks.add(t);
                s.sci = true;
                i += 2;
                continue;
            }
            if (c == '0' || c == '#' || c == '?') {
                Tok t = new Tok(Tok.DIGIT, String.valueOf(c));
                t.digit = c;
                s.toks.add(t);
                if (s.sci && hasExpBefore(s)) { /* أرقام الأس */ }
                else if (seenPoint) s.decDigits++;
                else s.intDigits++;
                inDigits = true;
                i++;
                continue;
            }
            if (c == '.') {
                if (inDigits || (i + 1 < n && (code.charAt(i + 1) == '0' || code.charAt(i + 1) == '#' || code.charAt(i + 1) == '?'))) {
                    s.toks.add(new Tok(Tok.POINT, "."));
                    seenPoint = true;
                } else {
                    s.toks.add(new Tok(Tok.LIT, "."));
                }
                i++;
                continue;
            }
            if (c == ',') {
                // فاصلة بين خانتين = فاصل آلاف؛ فاصلة بعد آخر خانة = قسمة على ألف
                boolean nextDigit = i + 1 < n && "0#?".indexOf(code.charAt(i + 1)) >= 0;
                if (inDigits && nextDigit) {
                    s.thousandsSep = true;
                } else if (inDigits) {
                    s.scaleCommas++;
                    s.toks.add(new Tok(Tok.THOUSANDS_SCALE, ","));
                } else {
                    s.toks.add(new Tok(Tok.LIT, ","));
                }
                i++;
                continue;
            }
            if (c == '/') {
                if (inDigits) {
                    s.fraction = true;
                    s.toks.add(new Tok(Tok.SLASH, "/"));
                    // قراءة المقام: أرقام ثابتة أو ؟/#
                    int k = i + 1;
                    StringBuilder den = new StringBuilder();
                    while (k < n && (Character.isDigit(code.charAt(k)) || "#?".indexOf(code.charAt(k)) >= 0)) {
                        den.append(code.charAt(k));
                        k++;
                    }
                    String d = den.toString();
                    s.fracDenDigits = d.length();
                    if (!d.isEmpty() && d.chars().allMatch(Character::isDigit)) s.fracDen = Integer.parseInt(d);
                    for (int q = 0; q < d.length(); q++) {
                        Tok t = new Tok(Tok.DIGIT, String.valueOf(d.charAt(q)));
                        t.digit = 'F';
                        s.toks.add(t);
                    }
                    i = k;
                    continue;
                }
                s.toks.add(new Tok(Tok.LIT, "/"));
                i++;
                continue;
            }
            if ((c == 'A' || c == 'a') && (code.regionMatches(true, i, "AM/PM", 0, 5))) {
                s.toks.add(new Tok(Tok.AMPM, "AM/PM"));
                s.hasAmPm = true;
                s.isDate = true;
                i += 5;
                continue;
            }
            if ((c == 'A' || c == 'a') && (code.regionMatches(true, i, "A/P", 0, 3))) {
                s.toks.add(new Tok(Tok.AMPM, "A/P"));
                s.hasAmPm = true;
                s.isDate = true;
                i += 3;
                continue;
            }
            if (isDateLetter(c) && !inDigits) {
                int j = i;
                char lc = Character.toLowerCase(c);
                while (j < n && Character.toLowerCase(code.charAt(j)) == lc) j++;
                s.toks.add(new Tok(Tok.DATE, code.substring(i, j)));
                s.isDate = true;
                i = j;
                continue;
            }
            if (isDateLetter(c) && inDigits && (Character.toLowerCase(c) == 's' || Character.toLowerCase(c) == 'h'
                    || Character.toLowerCase(c) == 'm' || Character.toLowerCase(c) == 'd' || Character.toLowerCase(c) == 'y')) {
                int j = i;
                char lc = Character.toLowerCase(c);
                while (j < n && Character.toLowerCase(code.charAt(j)) == lc) j++;
                s.toks.add(new Tok(Tok.DATE, code.substring(i, j)));
                s.isDate = true;
                i = j;
                continue;
            }
            // أي محرف آخر حرفي
            s.toks.add(new Tok(Tok.LIT, String.valueOf(c)));
            i++;
        }
        return s;
    }

    private static boolean hasExpBefore(Section s) {
        for (Tok t : s.toks) if (t.type == Tok.EXP) return true;
        return false;
    }

    private static String colorHex(String name) {
        switch (name) {
            case "red": return "#FF0000";
            case "green": return "#00B050";
            case "blue": return "#0000FF";
            case "white": return "#FFFFFF";
            case "cyan": return "#00B7EB";
            case "magenta": return "#FF00FF";
            case "yellow": return "#E6B800";
            default: return null;
        }
    }

    // ------------------------------------------------------------------ التنسيق

    /** تنسيق قيمة رقمية. code=null أو "General" يعني عامّ. */
    public Result formatNumber(double v, String code) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return new Result(String.valueOf(v), null);
        if (code == null || code.trim().isEmpty() || code.trim().equalsIgnoreCase("general")) {
            return new Result(general(v), null);
        }
        Parsed p = parse(code);
        Section sec = pickSection(p, v);
        if (sec == null) return new Result(general(v), null);
        boolean negSectionUsed = p.sections.length >= 2 && sec == p.sections[1] && !sec.hasCond;
        if (sec.isGeneral && sec.toks.isEmpty()) return new Result(general(v), sec.color);
        double val = negSectionUsed ? Math.abs(v) : v;
        String text;
        if (sec.isDate) text = formatDate(val, sec);
        else text = formatNumeric(val, sec, !negSectionUsed && p.sections.length >= 1);
        return new Result(text, sec.color);
    }

    /** تنسيق نص (القسم الرابع أو @). */
    public Result formatText(String s, String code) {
        if (code == null) return new Result(s, null);
        Parsed p = parse(code);
        Section sec = null;
        if (p.sections.length >= 4) sec = p.sections[3];
        else for (Section x : p.sections) if (x.hasText) { sec = x; break; }
        if (sec == null || !sec.hasText) return new Result(s, null);
        StringBuilder sb = new StringBuilder();
        for (Tok t : sec.toks) {
            if (t.type == Tok.TEXT) sb.append(s);
            else if (t.type == Tok.LIT || t.type == Tok.SPACE) sb.append(t.s);
        }
        return new Result(sb.toString(), sec.color);
    }

    private static Section pickSection(Parsed p, double v) {
        Section[] ss = p.sections;
        if (ss.length == 0) return null;
        boolean anyCond = false;
        for (Section s : ss) if (s.hasCond) anyCond = true;
        if (anyCond) {
            for (int i = 0; i < ss.length; i++) {
                Section s = ss[i];
                if (s.hasCond && matches(s, v)) return s;
                if (!s.hasCond && i == ss.length - 1) return s;
            }
            return ss[ss.length - 1];
        }
        if (ss.length == 1) return ss[0];
        if (v > 0) return ss[0];
        if (v < 0) return ss[1];
        return ss.length >= 3 ? ss[2] : ss[0];
    }

    private static boolean matches(Section s, double v) {
        switch (s.condOp) {
            case ">": return v > s.condVal;
            case "<": return v < s.condVal;
            case ">=": return v >= s.condVal;
            case "<=": return v <= s.condVal;
            case "=": return v == s.condVal;
            case "<>": return v != s.condVal;
            default: return false;
        }
    }

    /** التنسيق العام: حتى 11 خانة معنوية دون أصفار زائدة. */
    public static String general(double v) {
        if (v == Math.rint(v) && Math.abs(v) < 1e15) {
            return new BigDecimal(v).setScale(0, RoundingMode.HALF_UP).toPlainString();
        }
        double a = Math.abs(v);
        if (a >= 1e11 || (a != 0 && a < 1e-4)) {
            String s = String.format(Locale.ROOT, "%.5E", v);
            // 1.23457E+011 -> 1.23457E+11
            int e = s.indexOf('E');
            String mant = s.substring(0, e);
            String exp = s.substring(e + 1);
            if (mant.contains(".")) mant = mant.replaceAll("0+$", "").replaceAll("\\.$", "");
            char sign = exp.charAt(0);
            String digits = exp.substring(1).replaceFirst("^0+(?=\\d{2})", "");
            return mant + "E" + sign + digits;
        }
        BigDecimal bd = new BigDecimal(v).round(new java.math.MathContext(11, RoundingMode.HALF_UP));
        String s = bd.stripTrailingZeros().toPlainString();
        return s;
    }

    private String formatNumeric(double v, Section s, boolean addMinus) {
        boolean neg = v < 0;
        double a = Math.abs(v);
        for (int i = 0; i < s.percent; i++) a *= 100;
        for (int i = 0; i < s.scaleCommas; i++) a /= 1000;

        StringBuilder out = new StringBuilder();
        if (neg && addMinus) out.append('-');

        if (s.sci) return (neg && addMinus ? "-" : "") + sci(a, s);
        if (s.fraction) return (neg && addMinus ? "-" : "") + fraction(a, s);

        BigDecimal bd = new BigDecimal(a).setScale(s.decDigits, RoundingMode.HALF_UP);
        String plain = bd.toPlainString();
        String ip = plain, fp = "";
        int dot = plain.indexOf('.');
        if (dot >= 0) { ip = plain.substring(0, dot); fp = plain.substring(dot + 1); }

        // عدد الأصفار/العلامات المطلوبة في الجزء الصحيح
        int minInt = 0, qInt = 0;
        boolean afterPoint = false;
        for (Tok t : s.toks) {
            if (t.type == Tok.POINT) afterPoint = true;
            if (t.type == Tok.DIGIT && t.digit != 'F' && !afterPoint) {
                if (t.digit == '0') minInt++;
                if (t.digit == '?') qInt++;
            }
        }
        // إزالة الصفر الأمامي إن لم يُطلب
        if (ip.equals("0") && minInt == 0) ip = "";
        while (ip.length() < minInt) ip = "0" + ip;

        if (s.thousandsSep && !ip.isEmpty()) {
            StringBuilder g = new StringBuilder();
            int cnt = 0;
            for (int i = ip.length() - 1; i >= 0; i--) {
                g.append(ip.charAt(i));
                if (++cnt % 3 == 0 && i > 0) g.append(',');
            }
            ip = g.reverse().toString();
        }

        // ضع الأرقام في القالب
        int intPlaceholders = s.intDigits;
        int ipIdx = 0;
        int extra = Math.max(0, ip.replace(",", "").length() - intPlaceholders);
        // نكتب الجزء الصحيح دفعة واحدة عند أول علامة خانة
        boolean intWritten = false;
        int fpIdx = 0;
        boolean pointWritten = false;
        afterPoint = false;
        for (Tok t : s.toks) {
            switch (t.type) {
                case Tok.DIGIT:
                    if (t.digit == 'F') break;
                    if (!afterPoint) {
                        if (!intWritten) {
                            out.append(padQuestion(ip, qInt));
                            intWritten = true;
                        }
                    } else {
                        char ch = fpIdx < fp.length() ? fp.charAt(fpIdx) : '0';
                        fpIdx++;
                        // # يحذف الأصفار الزائدة في نهاية الكسر، ? يستبدلها بمسافة
                        if (t.digit == '0') out.append(ch);
                        else {
                            boolean trailingZeros = true;
                            for (int k = fpIdx - 1; k < fp.length(); k++) if (fp.charAt(k) != '0') { trailingZeros = false; break; }
                            if (trailingZeros) { if (t.digit == '?') out.append(' '); }
                            else out.append(ch);
                        }
                    }
                    break;
                case Tok.POINT:
                    afterPoint = true;
                    if (!intWritten) { out.append(padQuestion(ip, qInt)); intWritten = true; }
                    // نُبقي النقطة فقط إن بقي رقم بعدها
                    if (hasVisibleDecimals(s, fp)) { out.append('.'); pointWritten = true; }
                    break;
                case Tok.PERCENT: out.append('%'); break;
                case Tok.LIT: out.append(t.s); break;
                case Tok.SPACE: out.append(' '); break;
                case Tok.THOUSANDS_SCALE: break;
                default: break;
            }
        }
        if (!intWritten && s.intDigits == 0 && s.decDigits == 0 && !s.toks.isEmpty()) {
            // قالب بلا خانات (نص حرفي فقط) — يُعرض كما هو
        }
        return out.toString();
    }

    private static boolean hasVisibleDecimals(Section s, String fp) {
        boolean afterPoint = false;
        int idx = 0;
        for (Tok t : s.toks) {
            if (t.type == Tok.POINT) { afterPoint = true; continue; }
            if (afterPoint && t.type == Tok.DIGIT && t.digit != 'F') {
                char ch = idx < fp.length() ? fp.charAt(idx) : '0';
                idx++;
                if (t.digit == '0') return true;
                for (int k = idx - 1; k < fp.length(); k++) if (fp.charAt(k) != '0') return true;
            }
        }
        return false;
    }

    private static String padQuestion(String ip, int q) {
        String base = ip.replace(",", "");
        if (q > 0 && base.length() < q) {
            StringBuilder sb = new StringBuilder();
            for (int i = base.length(); i < q; i++) sb.append(' ');
            return sb + ip;
        }
        return ip;
    }

    private String sci(double a, Section s) {
        int decs = s.decDigits;
        int expDigits = 0;
        boolean afterE = false;
        for (Tok t : s.toks) {
            if (t.type == Tok.EXP) afterE = true;
            else if (afterE && t.type == Tok.DIGIT) expDigits++;
        }
        if (expDigits == 0) expDigits = 2;
        int e = 0;
        double m = a;
        if (a != 0) {
            e = (int) Math.floor(Math.log10(a));
            m = a / Math.pow(10, e);
            // في التنسيق الهندسي ##0.0E+0 يكون الأس مضاعف 3
            int intD = s.intDigits;
            if (intD > 1) {
                int shift = e % intD;
                if (shift < 0) shift += intD;
                e -= shift;
                m = a / Math.pow(10, e);
            }
        }
        BigDecimal bd = new BigDecimal(m).setScale(decs, RoundingMode.HALF_UP);
        if (bd.compareTo(BigDecimal.TEN) >= 0 && s.intDigits <= 1) {
            e++;
            bd = new BigDecimal(a / Math.pow(10, e)).setScale(decs, RoundingMode.HALF_UP);
        }
        StringBuilder sb = new StringBuilder(bd.toPlainString());
        String es = String.valueOf(Math.abs(e));
        while (es.length() < expDigits) es = "0" + es;
        boolean plus = false;
        for (Tok t : s.toks) if (t.type == Tok.EXP) plus = t.s.endsWith("+");
        sb.append('E').append(e < 0 ? '-' : (plus ? '+' : "")).append(es);
        return sb.toString();
    }

    private String fraction(double a, Section s) {
        long whole = (long) Math.floor(a);
        double frac = a - whole;
        // هل للقالب جزء صحيح منفصل (# ?/?) أم كسر غير حقيقي (?/?)؟
        boolean hasWhole = false;
        for (Tok t : s.toks) {
            if (t.type == Tok.SLASH) break;
            if (t.type == Tok.LIT && t.s.trim().isEmpty() && hasWhole) { /* فاصل */ }
            if (t.type == Tok.DIGIT && t.digit != 'F') hasWhole = true;
        }
        // نعتبر وجود مسافة حرفية بين خانات الجزء الصحيح والبسط
        boolean mixed = false;
        int digitGroups = 0;
        boolean prevDigit = false;
        for (Tok t : s.toks) {
            if (t.type == Tok.SLASH) break;
            boolean d = t.type == Tok.DIGIT && t.digit != 'F';
            if (d && !prevDigit) digitGroups++;
            prevDigit = d;
        }
        mixed = digitGroups >= 2;
        int maxDen = s.fracDen > 0 ? s.fracDen : (int) Math.pow(10, Math.max(1, s.fracDenDigits)) - 1;
        long num, den;
        if (!mixed) {
            long total = 0;
            double x = a;
            long[] nd = bestFraction(x, maxDen, s.fracDen);
            num = nd[0];
            den = nd[1];
            return num + "/" + den;
        }
        long[] nd = bestFraction(frac, maxDen, s.fracDen);
        num = nd[0];
        den = nd[1];
        if (num == den) { whole++; num = 0; }
        StringBuilder sb = new StringBuilder();
        if (num == 0) {
            sb.append(whole);
            // أبقِ المسافة كي يتوازى العرض
            return sb.toString();
        }
        if (whole != 0) sb.append(whole).append(' ');
        sb.append(num).append('/').append(den);
        return sb.toString();
    }

    private static long[] bestFraction(double x, int maxDen, int fixedDen) {
        if (fixedDen > 0) {
            long n = Math.round(x * fixedDen);
            return new long[]{n, fixedDen};
        }
        long bestN = Math.round(x), bestD = 1;
        double bestErr = Math.abs(x - bestN);
        for (int d = 2; d <= maxDen; d++) {
            long n = Math.round(x * d);
            double err = Math.abs(x - (double) n / d);
            if (err < bestErr - 1e-12) { bestErr = err; bestN = n; bestD = d; }
        }
        return new long[]{bestN, bestD};
    }

    // ------------------------------------------------------------------ التواريخ

    /** تحويل رقم تسلسلي Excel إلى {سنة، شهر، يوم، ساعة، دقيقة، ثانية، مللي، يوم الأسبوع(0=أحد)}. */
    private int[] toDateParts(double serial) {
        double s = serial;
        long days = (long) Math.floor(s);
        double fr = s - days;
        long ms = Math.round(fr * 86400000.0);
        if (ms >= 86400000L) { days++; ms -= 86400000L; }
        int hh = (int) (ms / 3600000L);
        int mm = (int) (ms / 60000L % 60);
        int ss = (int) (ms / 1000L % 60);
        int mil = (int) (ms % 1000L);
        long epochDays; // أيام منذ 1970-01-01
        if (date1904) {
            epochDays = days - 24107; // 1904-01-01 هو الرقم 0 => 1904-01-01 = -24107
        } else {
            if (days < 60) epochDays = days - 25568; // قبل خطأ 1900 الكبيس (1 = 1900-01-01)
            else epochDays = days - 25569;
        }
        // خوارزمية civil_from_days
        long z = epochDays + 719468;
        long era = Math.floorDiv(z, 146097);
        long doe = z - era * 146097;
        long yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365;
        long y = yoe + era * 400;
        long doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
        long mp = (5 * doy + 2) / 153;
        long d = doy - (153 * mp + 2) / 5 + 1;
        long m = mp < 10 ? mp + 3 : mp - 9;
        if (m <= 2) y++;
        int wd = Math.floorMod(epochDays + 4, 7); // 1970-01-01 كان خميساً (4)
        if (!date1904 && days == 60) { // 1900-02-29 الوهمي
            return new int[]{1900, 2, 29, hh, mm, ss, mil, 3};
        }
        return new int[]{(int) y, (int) m, (int) d, hh, mm, ss, mil, wd};
    }

    private String formatDate(double v, Section s) {
        if (v < 0) return "########";
        int[] dp = toDateParts(v);
        String[] months = s.arabic ? MONTHS_AR : MONTHS_EN;
        String[] days = s.arabic ? DAYS_AR : DAYS_EN;

        // تحديد m: شهر أم دقيقة (تسبقها h أو تليها s)
        List<Tok> toks = s.toks;
        boolean[] isMinute = new boolean[toks.size()];
        for (int i = 0; i < toks.size(); i++) {
            Tok t = toks.get(i);
            if (t.type != Tok.DATE || Character.toLowerCase(t.s.charAt(0)) != 'm' || t.s.length() > 2) continue;
            boolean prevH = false, nextS = false;
            for (int k = i - 1; k >= 0; k--) {
                Tok p = toks.get(k);
                if (p.type == Tok.DATE) { prevH = Character.toLowerCase(p.s.charAt(0)) == 'h'; break; }
                if (p.type == Tok.ELAPSED) { prevH = p.s.startsWith("h"); break; }
            }
            for (int k = i + 1; k < toks.size(); k++) {
                Tok nx = toks.get(k);
                if (nx.type == Tok.DATE) { nextS = Character.toLowerCase(nx.s.charAt(0)) == 's'; break; }
            }
            isMinute[i] = prevH || nextS;
        }

        boolean ampm = s.hasAmPm;
        StringBuilder sb = new StringBuilder();
        double totalDays = Math.floor(v);
        double totalHours = v * 24, totalMinutes = v * 1440, totalSeconds = v * 86400;
        for (int i = 0; i < toks.size(); i++) {
            Tok t = toks.get(i);
            switch (t.type) {
                case Tok.LIT: sb.append(t.s); break;
                case Tok.SPACE: sb.append(' '); break;
                case Tok.POINT: sb.append('.'); break;
                case Tok.DIGIT: {
                    // أجزاء الثانية 0.00
                    int cnt = 0;
                    int k = i;
                    while (k < toks.size() && toks.get(k).type == Tok.DIGIT) { cnt++; k++; }
                    String ms = String.format(Locale.ROOT, "%03d", dp[6]);
                    sb.append(ms, 0, Math.min(3, cnt));
                    i = k - 1;
                    break;
                }
                case Tok.ELAPSED: {
                    String low = t.s;
                    char c = low.charAt(0);
                    long val = c == 'h' ? (long) Math.floor(totalHours) : c == 'm' ? (long) Math.floor(totalMinutes)
                            : (long) Math.floor(totalSeconds);
                    if (c == 'm' && low.contains("m") && toks.size() > i + 1) { /* [mm] */ }
                    String str = String.valueOf(val);
                    while (str.length() < low.length()) str = "0" + str;
                    sb.append(str);
                    break;
                }
                case Tok.AMPM: {
                    boolean pm = dp[3] >= 12;
                    if (t.s.equals("AM/PM")) sb.append(s.arabic ? (pm ? "م" : "ص") : (pm ? "PM" : "AM"));
                    else sb.append(pm ? "P" : "A");
                    break;
                }
                case Tok.DATE: {
                    String code = t.s;
                    char c = Character.toLowerCase(code.charAt(0));
                    int len = code.length();
                    switch (c) {
                        case 'y':
                            if (len <= 2) sb.append(two(dp[0] % 100));
                            else sb.append(String.format(Locale.ROOT, "%04d", dp[0]));
                            break;
                        case 'e': sb.append(String.format(Locale.ROOT, "%04d", dp[0])); break;
                        case 'm':
                            if (isMinute[i]) {
                                sb.append(len >= 2 ? two(dp[4]) : String.valueOf(dp[4]));
                            } else {
                                if (len == 1) sb.append(dp[1]);
                                else if (len == 2) sb.append(two(dp[1]));
                                else if (len == 3) sb.append(abbrev(months[dp[1] - 1], s.arabic));
                                else if (len == 5) sb.append(months[dp[1] - 1].charAt(0));
                                else sb.append(months[dp[1] - 1]);
                            }
                            break;
                        case 'd':
                            if (len == 1) sb.append(dp[2]);
                            else if (len == 2) sb.append(two(dp[2]));
                            else if (len == 3) sb.append(abbrev(days[dp[7]], s.arabic));
                            else sb.append(days[dp[7]]);
                            break;
                        case 'h': {
                            int h = dp[3];
                            if (ampm) { h = h % 12; if (h == 0) h = 12; }
                            sb.append(len >= 2 ? two(h) : String.valueOf(h));
                            break;
                        }
                        case 's':
                            sb.append(len >= 2 ? two(dp[5]) : String.valueOf(dp[5]));
                            break;
                        default:
                            sb.append(code);
                    }
                    break;
                }
                default: break;
            }
        }
        return sb.toString();
    }

    private static String abbrev(String name, boolean arabic) {
        if (arabic) return name;
        return name.substring(0, 3);
    }

    private static String two(int v) { return v < 10 ? "0" + v : String.valueOf(v); }

    /** تحويل التاريخ إلى نص لنوع الخلية t="d" (ISO 8601) عند غياب تنسيق. */
    public Result formatIsoDate(String iso, String code) {
        try {
            String d = iso.trim();
            int year = Integer.parseInt(d.substring(0, 4));
            int mon = Integer.parseInt(d.substring(5, 7));
            int day = Integer.parseInt(d.substring(8, 10));
            double serial = daysFromCivil(year, mon, day) + 25569;
            if (date1904) serial -= 1462;
            if (d.length() >= 19 && d.charAt(10) == 'T') {
                int hh = Integer.parseInt(d.substring(11, 13));
                int mi = Integer.parseInt(d.substring(14, 16));
                int ss = Integer.parseInt(d.substring(17, 19));
                serial += (hh * 3600 + mi * 60 + ss) / 86400.0;
            }
            String c = code != null && isDateCode(code) ? code : "yyyy-mm-dd";
            return formatNumber(serial, c);
        } catch (RuntimeException e) {
            return new Result(iso, null);
        }
    }

    private static long daysFromCivil(long y, long m, long d) {
        y -= m <= 2 ? 1 : 0;
        long era = Math.floorDiv(y, 400);
        long yoe = y - era * 400;
        long doy = (153 * (m + (m > 2 ? -3 : 9)) + 2) / 5 + d - 1;
        long doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
        return era * 146097 + doe - 719468;
    }
}
