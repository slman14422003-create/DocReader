package com.docreader.app.viewer;

/**
 * غلاف صفحة HTML موحّد تستخدمه كل القارئات (Word / Excel / PowerPoint / RTF / نص / Markdown).
 * الوضع الليلي يُطبَّق بمرشّح CSS يعكس ألوان الصفحة ويُبقي الصور على حالها.
 */
public final class HtmlPage {

    private HtmlPage() { }

    private static final String BASE_CSS = """
            *{box-sizing:border-box}
            html{-webkit-text-size-adjust:100%;background:#EDEBE4}
            html.night{background:#fff;filter:invert(1) hue-rotate(180deg)}
            html.night img,html.night svg image,html.night video{filter:invert(1) hue-rotate(180deg)}
            body{margin:0;padding:8px;color:#1F1E1D;font:15px/1.65 "Segoe UI",Roboto,"Noto Sans Arabic","Noto Naskh Arabic",Arial,sans-serif;overflow-wrap:anywhere;word-break:normal}
            a{color:#0563C1}
            img{max-width:100%;height:auto}
            .paper{max-width:920px;margin:0 auto 12px;background:#fff;padding:22px 18px;border-radius:6px;box-shadow:0 1px 3px rgba(0,0,0,.14)}
            html.night .paper{box-shadow:none;outline:1px solid #d8d8d8}
            @media (min-width:700px){body{padding:16px}.paper{padding:40px 48px}}
            .note{margin:10px auto;max-width:920px;padding:10px 14px;border-radius:8px;background:#FFF4E5;color:#7A4B00;font-size:13px}
            .empty{padding:48px 12px;text-align:center;color:#8a8a84}
            @media print{html{background:#fff}.paper{box-shadow:none}}
            """;

    public static String esc(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&': sb.append("&amp;"); break;
                case '<': sb.append("&lt;"); break;
                case '>': sb.append("&gt;"); break;
                case '"': sb.append("&quot;"); break;
                case '\'': sb.append("&#39;"); break;
                default:
                    // حذف محارف التحكم غير المسموحة في HTML/XML (نُبقي \t و\n)
                    if (c >= 0x20 || c == '\t' || c == '\n') sb.append(c);
                    else if (c == '\r') { /* تجاهل */ }
            }
        }
        return sb.toString();
    }

    /** يسمح فقط بروابط http/https/mailto/tel والمراسي الداخلية. */
    public static String safeHref(String href) {
        if (href == null) return null;
        String h = href.trim();
        if (h.isEmpty()) return null;
        String lower = h.toLowerCase(java.util.Locale.ROOT);
        if (lower.startsWith("http://") || lower.startsWith("https://")
                || lower.startsWith("mailto:") || lower.startsWith("tel:") || lower.startsWith("#")) {
            return h;
        }
        return null;
    }

    /**
     * @param fragment  المحتوى (قد يبدأ بوسم style خاص بالمحوّل)
     * @param night     الوضع الليلي
     * @param extraCss  CSS إضافي خاص بنوع المستند (اختياري)
     */
    public static String wrap(String fragment, boolean night, String extraCss) {
        StringBuilder sb = new StringBuilder(fragment.length() + 4096);
        sb.append("<!DOCTYPE html><html").append(night ? " class=\"night\"" : "")
                .append("><head><meta charset=\"utf-8\">")
                .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1, minimum-scale=0.25, maximum-scale=8, user-scalable=yes\">")
                .append("<style>").append(BASE_CSS);
        if (extraCss != null) sb.append(extraCss);
        sb.append("</style></head><body>").append(fragment).append("</body></html>");
        return sb.toString();
    }

    public static String errorFragment(String message) {
        return "<div class=\"paper empty\">" + esc(message) + "</div>";
    }
}
