package com.docreader.app.pdf;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * مساعد اتجاه النص (Bidi) للعرض فقط.
 *
 * المشكلة: عند وجود أرقام أو مصطلحات لاتينية بعد حرف عربي داخل فقرة RTL،
 * خوارزمية Unicode Bidi تحوّل الرقم الأوروبي إلى "رقم عربي" (القاعدة W2)،
 * فتنقلب المدى الرقمية: "80-100Hz" تظهر "100Hz-80" و"4-6 أسابيع" تظهر
 * "6-4 أسابيع". الحل القياسي: إحاطة كل مقطع لاتيني/رقمي بعلامة LRM
 * (U+200E) من الجانبين ليُعامل كوحدة LTR سليمة داخل النص العربي.
 *
 * تنبيه: النتيجة للعرض فقط (TextView) — لا تُحفظ ولا تُستخدم في البحث أو
 * المشاركة لأنها تحتوي أحرف تحكم غير مرئية.
 */
public final class BidiText {

    private BidiText() {}

    public static final char LRM = '\u200E';
    public static final char RLM = '\u200F';

    /** مقطع لاتيني/رقمي: كلمات/أرقام يفصل بينها فراغ أو - أو / أو . أو : أو , */
    private static final Pattern LTR_RUN = Pattern.compile(
            "[A-Za-z0-9\u00B5\u00B0%]+(?:[ .\\-\u2013/&+:'\u2019,]+[A-Za-z0-9\u00B5\u00B0%]+)*");

    /** يعيد نصًا آمنًا للعرض داخل فقرة عربية (RTL). */
    public static String fix(CharSequence text) {
        if (text == null) return "";
        String s = text.toString();
        if (s.isEmpty()) return s;
        Matcher m = LTR_RUN.matcher(s);
        StringBuilder sb = null;
        int last = 0;
        while (m.find()) {
            if (sb == null) sb = new StringBuilder(s.length() + 16);
            sb.append(s, last, m.start());
            // RLM على الجانبين يمنع دمج مقطعين لاتينيين متجاورين (يفصل بينهما فراغ أو قوس)
            // في مقطع LTR واحد، وLRM يجعل الأرقام تُعامل كـ LTR بدل "رقم عربي".
            sb.append(RLM).append(LRM).append(m.group()).append(LRM).append(RLM);
            last = m.end();
        }
        if (sb == null) return s;
        sb.append(s, last, s.length());
        return sb.toString();
    }

    /** يزيل أحرف التحكم التي أضافتها fix() (لو احتجنا النص الأصلي مرة أخرى). */
    public static String strip(CharSequence text) {
        if (text == null) return "";
        return text.toString().replace(String.valueOf(LRM), "").replace(String.valueOf(RLM), "");
    }
}
