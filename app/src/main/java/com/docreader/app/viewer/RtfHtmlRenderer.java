package com.docreader.app.viewer;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * قارئ RTF حقيقي: يحوّل الملف إلى HTML مع التنسيقات الفعلية — عريض/مائل/تسطير/
 * يتوسّطه خط، الألوان من جدول الألوان، المحاذاة والاتجاه (عربي/إنجليزي تلقائياً
 * لكل فقرة)، جداول بسيطة، صور مضمّنة (PNG/JPEG)، وروابط HYPERLINK.
 */
public final class RtfHtmlRenderer {

    private static final int MAX_BYTES = 40 * 1024 * 1024;
    private static final Pattern HREF_RE = Pattern.compile("HYPERLINK\\s+\"([^\"]+)\"");

    private static final class Fmt {
        boolean bold, italic, underline, strike, sup, sub;
        int colorIdx = -1;
        double sizePt = 11;
        String href;

        Fmt copy() {
            Fmt f = new Fmt();
            f.bold = bold; f.italic = italic; f.underline = underline; f.strike = strike;
            f.sup = sup; f.sub = sub; f.colorIdx = colorIdx; f.sizePt = sizePt; f.href = href;
            return f;
        }
    }

    private static final class Par {
        char align = 'l';
        Boolean rtl;   // null = غير محدَّد صراحةً؛ سيُستنتج من أول محرف قوي في النص
        boolean inTable;

        Par copy() {
            Par p = new Par();
            p.align = align; p.rtl = rtl; p.inTable = inTable;
            return p;
        }
    }

    private static final class Run {
        String text;
        Fmt fmt;
    }

    private final List<String> colors = new ArrayList<>();      // فهرس 0 = تلقائي (null)
    private final StringBuilder out = new StringBuilder(1 << 16);

    private RtfHtmlRenderer() { colors.add(null); }

    public static String render(File file) throws Exception {
        try (InputStream in = new FileInputStream(file)) {
            return new RtfHtmlRenderer().parse(in);
        }
    }

    // ------------------------------------------------------------------ التحليل

    private String parse(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int len;
        int total = 0;
        while ((len = in.read(buf)) > 0) {
            total += len;
            if (total > MAX_BYTES) break;
            bos.write(buf, 0, len);
        }
        byte[] data = bos.toByteArray();
        int n = data.length;

        Deque<Boolean> ignoreStack = new ArrayDeque<>();
        Deque<Fmt> fmtStack = new ArrayDeque<>();
        Deque<Par> parStack = new ArrayDeque<>();
        boolean ignoring = false;
        Fmt fmt = new Fmt();
        Par par = new Par();

        Charset docCharset = charsetForCodepage(1252);
        Map<Integer, Charset> fontCharset = new LinkedHashMap<>();
        int curFont = -1, defCpgFont = -1;
        boolean inFontTbl = false, inColorTbl = false;
        int fontTblDepth = -1, colorTblDepth = -1;
        int colR = -1, colG = -1, colB = -1;

        boolean[] tableOpenBox = {false};
        int[] ucSkip = {1};
        StringBuilder cellBuf = new StringBuilder();
        StringBuilder rowHtml = new StringBuilder();
        boolean anyCellInRow = false;

        boolean pictMode = false;
        int pictDepth = -1;
        String pictFmt = null;
        StringBuilder pictHex = new StringBuilder();

        boolean captureField = false; // داخل \fldinst: نجمع نص التعليمة لاستخراج الرابط
        int fieldDepth = -1;
        StringBuilder fieldBuf = new StringBuilder();
        String pendingHref = null;

        List<Run> para = new ArrayList<>();
        StringBuilder curText = new StringBuilder();

        int i = 0;
        while (i < n) {
            int depth = ignoreStack.size();
            int b = data[i] & 0xFF;

            if (pictMode) {
                if (b == '{') { ignoreStack.push(ignoring); i++; continue; }
                if (b == '}') {
                    if (depth - 1 == pictDepth) {
                        finishPict(pictFmt, pictHex, para, fmt);
                        pictMode = false;
                        pictFmt = null;
                        pictHex.setLength(0);
                    }
                    if (!ignoreStack.isEmpty()) ignoring = ignoreStack.pop();
                    i++;
                    continue;
                }
                if (b == '\\') {
                    i++;
                    if (i >= n) break;
                    String word = readWord(data, i, n);
                    int adv = word.length();
                    i += adv;
                    Integer num = readNum(data, i, n);
                    if (num != null) i = skipNumDigits(data, i, n);
                    if (i < n && data[i] == ' ') i++;
                    if (word.equals("pngblip")) pictFmt = "image/png";
                    else if (word.equals("jpegblip")) pictFmt = "image/jpeg";
                    else if (word.equals("'") ) { /* غير متوقع هنا */ }
                    continue;
                }
                if ((b >= '0' && b <= '9') || (b >= 'a' && b <= 'f') || (b >= 'A' && b <= 'F')) pictHex.append((char) b);
                i++;
                continue;
            }

            if (captureField) {
                if (b == '{') { ignoreStack.push(ignoring); i++; continue; }
                if (b == '}') {
                    if (depth - 1 == fieldDepth) {
                        Matcher m = HREF_RE.matcher(fieldBuf);
                        pendingHref = m.find() ? HtmlPage.safeHref(m.group(1)) : null;
                        captureField = false;
                        fieldBuf.setLength(0);
                    }
                    if (!ignoreStack.isEmpty()) ignoring = ignoreStack.pop();
                    i++;
                    continue;
                }
                if (b == '\\') {
                    i++;
                    if (i >= n) break;
                    int c = data[i] & 0xFF;
                    if (c == '\'') {
                        i++;
                        if (i + 1 < n) { fieldBuf.append((char) Integer.parseInt("" + (char) data[i] + (char) data[i + 1], 16)); i += 2; }
                        continue;
                    }
                    if (Character.isLetter(c)) {
                        String word = readWord(data, i, n);
                        i += word.length();
                        i = skipNumDigits(data, i, n);
                        if (i < n && data[i] == ' ') i++;
                    } else i++;
                    continue;
                }
                if (b != '\r' && b != '\n') fieldBuf.append((char) b);
                i++;
                continue;
            }

            if (b == '{') {
                ignoreStack.push(ignoring);
                fmtStack.push(fmt.copy());
                parStack.push(par.copy());
                i++;
                if (i < n && data[i] == '\\') {
                    int j = i + 1;
                    boolean star = j < n && data[j] == '*';
                    if (star) {
                        j++;
                        if (j < n && data[j] == '\\') j++; // تجاوز الخلفية بين \* والكلمة التالية، مثل \*\fldinst
                    }
                    String word = peekWord(data, j, n);
                    if (word.equals("fldinst")) {
                        captureField = true;
                        fieldDepth = depth;
                        fieldBuf.setLength(0);
                    } else if (word.equals("fldrslt")) {
                        flushRun(para, curText, fmt);
                        fmt.href = pendingHref;
                    } else if (word.equals("pict")) {
                        pictMode = true;
                        pictDepth = depth;
                        pictFmt = null;
                        pictHex.setLength(0);
                    } else if (star || isIgnorableDestination(word)) {
                        ignoring = true;
                    }
                }
                continue;
            }
            if (b == '}') {
                flushRun(para, curText, fmt);
                if (!ignoreStack.isEmpty()) ignoring = ignoreStack.pop();
                if (!fmtStack.isEmpty()) fmt = fmtStack.pop();
                if (!parStack.isEmpty()) par = parStack.pop();
                if (inFontTbl && depth == fontTblDepth) inFontTbl = false;
                if (inColorTbl && depth == colorTblDepth) {
                    inColorTbl = false;
                    if (colR >= 0 || colG >= 0 || colB >= 0) colors.add(hex3(colR, colG, colB));
                    colR = -1; colG = -1; colB = -1;
                }
                i++;
                continue;
            }
            if (b == '\\') {
                i++;
                if (i >= n) break;
                int c = data[i] & 0xFF;

                if (c == '\'') {
                    i++;
                    if (i + 1 < n) {
                        String hex = "" + (char) data[i] + (char) data[i + 1];
                        i += 2;
                        if (!ignoring) {
                            try {
                                int val = Integer.parseInt(hex, 16);
                                Charset cs = curFont >= 0 && fontCharset.get(curFont) != null ? fontCharset.get(curFont) : docCharset;
                                curText.append(new String(new byte[]{(byte) val}, cs));
                            } catch (NumberFormatException ignored) { }
                        }
                        continue;
                    }
                }

                if (Character.isLetter(c)) {
                    int wordStart = i;
                    while (i < n && Character.isLetter(data[i] & 0xFF)) i++;
                    String word = new String(data, wordStart, i - wordStart, Charset.forName("US-ASCII"));
                    boolean neg = false;
                    if (i < n && data[i] == '-') { neg = true; i++; }
                    int numStart = i;
                    while (i < n && Character.isDigit(data[i] & 0xFF)) i++;
                    Integer num = i > numStart ? (neg ? -1 : 1) * Integer.parseInt(new String(data, numStart, i - numStart, Charset.forName("US-ASCII"))) : null;
                    if (i < n && data[i] == ' ') i++;

                    switch (word) {
                        case "par":
                            flushRun(para, curText, fmt);
                            if (tableOpenBox[0] && !par.inTable) { out.append("</table>"); tableOpenBox[0] = false; }
                            emitParagraph(para, par, false, null, out);
                            par = new Par();
                            break;
                        case "line":
                            curText.append('\n');
                            break;
                        case "page":
                            flushRun(para, curText, fmt);
                            if (tableOpenBox[0] && !par.inTable) { out.append("</table>"); tableOpenBox[0] = false; }
                            emitParagraph(para, par, false, null, out);
                            par = new Par();
                            break;
                        case "tab":
                            if (!ignoring) curText.append('\t');
                            break;
                        case "pard":
                            flushRun(para, curText, fmt);
                            if (par.inTable) {
                                cellBuf.append(paraToInline(para));
                                para.clear();
                            }
                            par = new Par();
                            break;
                        case "plain":
                            flushRun(para, curText, fmt);
                            String keepHref = fmt.href;
                            fmt = new Fmt();
                            fmt.href = keepHref;
                            break;
                        case "b": flushRun(para, curText, fmt); fmt.bold = num == null || num != 0; break;
                        case "i": flushRun(para, curText, fmt); fmt.italic = num == null || num != 0; break;
                        case "ul": flushRun(para, curText, fmt); fmt.underline = num == null || num != 0; break;
                        case "ulnone": flushRun(para, curText, fmt); fmt.underline = false; break;
                        case "strike": flushRun(para, curText, fmt); fmt.strike = num == null || num != 0; break;
                        case "super": flushRun(para, curText, fmt); fmt.sup = true; fmt.sub = false; break;
                        case "sub": flushRun(para, curText, fmt); fmt.sub = true; fmt.sup = false; break;
                        case "nosupersub": flushRun(para, curText, fmt); fmt.sup = false; fmt.sub = false; break;
                        case "fs": if (num != null) { flushRun(para, curText, fmt); fmt.sizePt = num / 2.0; } break;
                        case "cf": if (num != null) { flushRun(para, curText, fmt); fmt.colorIdx = num; } break;
                        case "highlight": break; // تبسيط: لا نلوّن الخلفية حالياً
                        case "ql": par.align = 'l'; break;
                        case "qr": par.align = 'r'; break;
                        case "qc": par.align = 'c'; break;
                        case "qj": par.align = 'j'; break;
                        case "rtlpar": par.rtl = true; break;
                        case "ltrpar": par.rtl = false; break;
                        case "intbl": par.inTable = true; break;
                        case "trowd":
                            flushRun(para, curText, fmt);
                            if (!tableOpenBox[0]) { out.append("<table class=\"rt\">"); tableOpenBox[0] = true; }
                            rowHtml.setLength(0);
                            anyCellInRow = false;
                            par.inTable = true;
                            break;
                        case "cell": {
                            flushRun(para, curText, fmt);
                            String inline = paraToInline(para);
                            para.clear();
                            cellBuf.append(inline);
                            String content = cellBuf.toString().trim();
                            rowHtml.append("<td>").append(content.isEmpty() ? "&nbsp;" : content).append("</td>");
                            cellBuf.setLength(0);
                            anyCellInRow = true;
                            break;
                        }
                        case "row":
                            if (anyCellInRow) out.append("<tr>").append(rowHtml).append("</tr>");
                            rowHtml.setLength(0);
                            anyCellInRow = false;
                            par.inTable = false;
                            break;
                        case "ansicpg":
                            if (num != null) docCharset = charsetForCodepage(num);
                            break;
                        case "deff":
                            defCpgFont = num == null ? -1 : num;
                            break;
                        case "fonttbl":
                            inFontTbl = true;
                            fontTblDepth = depth;
                            break;
                        case "colortbl":
                            inColorTbl = true;
                            colorTblDepth = depth;
                            colR = -1; colG = -1; colB = -1;
                            break;
                        case "f":
                            if (num != null) {
                                curFont = num;
                                if (inFontTbl && !fontCharset.containsKey(num)) fontCharset.put(num, null);
                            }
                            break;
                        case "fcharset":
                            if (inFontTbl && num != null && curFont >= 0) {
                                Charset special = charsetForFcharset(num);
                                if (special != null) fontCharset.put(curFont, special);
                            }
                            break;
                        case "red":
                            if (inColorTbl && num != null) colR = num;
                            break;
                        case "green":
                            if (inColorTbl && num != null) colG = num;
                            break;
                        case "blue":
                            if (inColorTbl && num != null) colB = num;
                            break;
                        case "uc":
                            if (num != null) ucSkip[0] = num;
                            break;
                        case "u":
                            if (num != null && !ignoring) {
                                int cp = num < 0 ? num + 65536 : num;
                                curText.append((char) cp);
                                int skipped = 0;
                                while (skipped < ucSkip[0] && i < n && data[i] != '\\' && data[i] != '{' && data[i] != '}') { i++; skipped++; }
                            }
                            break;
                        default:
                            if (isIgnorableDestination(word)) ignoring = true;
                            break;
                    }
                    continue;
                }

                if (inColorTbl && c == ';') { /* افصل ألوان محتمل مفقود القيم */ }
                if (!ignoring && (c == '\\' || c == '{' || c == '}')) curText.append((char) c);
                else if (!ignoring && c == '~') curText.append('\u00A0');
                else if (!ignoring && c == '-') curText.append('\u00AD');
                else if (!ignoring && c == '_') curText.append('\u2011');
                i++;
                continue;
            }

            if (b == '\r' || b == '\n') { i++; continue; }

            if (inColorTbl) {
                if (b == ';') {
                    if (colR < 0 && colG < 0 && colB < 0) colors.add(null);
                    else colors.add(hex3(colR, colG, colB));
                    colR = -1; colG = -1; colB = -1;
                }
                i++;
                continue;
            }
            if (inFontTbl) {
                // نتخطى اسم الخط حتى الفاصلة المنقوطة داخل جدول الخطوط
                while (i < n && data[i] != ';' && data[i] != '{' && data[i] != '}' && data[i] != '\\') i++;
                if (i < n && data[i] == ';') i++;
                continue;
            }

            int start = i;
            while (i < n && data[i] != '\\' && data[i] != '{' && data[i] != '}') i++;
            if (!ignoring) {
                Charset cs = curFont >= 0 && fontCharset.get(curFont) != null ? fontCharset.get(curFont) : docCharset;
                curText.append(new String(data, start, i - start, cs));
            }
        }
        flushRun(para, curText, fmt);
        if (tableOpenBox[0] && !par.inTable) { out.append("</table>"); tableOpenBox[0] = false; }
        if (!para.isEmpty()) emitParagraph(para, par, false, null, out);
        if (tableOpenBox[0]) out.append("</table>");

        if (out.length() == 0) return HtmlPage.errorFragment("المستند فارغ أو تعذّرت قراءته");
        return "<style>" + buildCss() + "</style><div class=\"paper doc\">" + out + "</div>";
    }

    // ------------------------------------------------------------------ الفقرات والتشغيلات

    private void flushRun(List<Run> para, StringBuilder curText, Fmt fmt) {
        if (curText.length() == 0) return;
        Run r = new Run();
        r.text = curText.toString();
        r.fmt = fmt.copy();
        para.add(r);
        curText.setLength(0);
    }

    private String paraToInline(List<Run> runs) {
        StringBuilder sb = new StringBuilder();
        for (Run r : runs) sb.append(runSpan(r));
        runs.clear();
        return sb.toString();
    }

    private void emitParagraph(List<Run> runs, Par par, boolean forceEmpty, String unused, StringBuilder out) {
        if (runs.isEmpty()) return;
        StringBuilder plain = new StringBuilder();
        for (Run r : runs) plain.append(r.text);
        String text = plain.toString();
        if (text.trim().isEmpty()) { runs.clear(); return; }

        boolean rtl = par.rtl != null ? par.rtl : Boolean.TRUE.equals(OoxmlUtil.firstStrongIsRtl(text));
        String align = alignCss(par.align, rtl);
        StringBuilder pcss = new StringBuilder();
        if (align != null) pcss.append("text-align:").append(align).append(';');
        String pcls = classFor(pcss.toString());

        out.append("<p");
        if (pcls != null) out.append(" class=\"").append(pcls).append('"');
        if (rtl) out.append(" dir=\"rtl\"");
        out.append('>');
        for (Run r : runs) out.append(runSpan(r));
        out.append("</p>");
        runs.clear();
    }

    private String runSpan(Run r) {
        String t = normalizeWs(r.text);
        if (t.isEmpty()) return "";
        String esc = HtmlPage.esc(t).replace("\n", "<br>");
        String css = runCss(r.fmt);
        String cls = css.isEmpty() ? null : classFor(css);
        String inner = cls != null ? "<span class=\"" + cls + "\">" + esc + "</span>" : esc;
        if (r.fmt.href != null) return "<a href=\"" + HtmlPage.esc(r.fmt.href) + "\">" + inner + "</a>";
        return inner;
    }

    private static String normalizeWs(String s) {
        // إزالة الفراغات الزائدة الناتجة عن تنسيق RTF مع الحفاظ على الأسطر/التبويبات
        return s;
    }

    private String runCss(Fmt f) {
        StringBuilder css = new StringBuilder();
        if (f.bold) css.append("font-weight:bold;");
        if (f.italic) css.append("font-style:italic;");
        if (f.underline || f.strike) {
            css.append("text-decoration:");
            if (f.underline) css.append("underline ");
            if (f.strike) css.append("line-through ");
            css.append(';');
        }
        if (f.sup) css.append("vertical-align:super;font-size:.7em;");
        if (f.sub) css.append("vertical-align:sub;font-size:.7em;");
        if (Math.abs(f.sizePt - 11) > 0.4) css.append("font-size:").append(trimNum(f.sizePt)).append("pt;");
        if (f.colorIdx > 0 && f.colorIdx < colors.size() && colors.get(f.colorIdx) != null) {
            css.append("color:").append(colors.get(f.colorIdx)).append(';');
        }
        return css.toString();
    }

    private static String alignCss(char a, boolean rtl) {
        switch (a) {
            case 'c': return "center";
            case 'j': return "justify";
            case 'r': return rtl ? "left" : "right";
            case 'l': default: return rtl ? "right" : null;
        }
    }

    // ------------------------------------------------------------------ الصور

    private void finishPict(String mime, StringBuilder hex, List<Run> para, Fmt fmt) {
        String h = hex.toString();
        if (mime == null || h.length() < 8) {
            Run r = new Run(); r.fmt = fmt.copy();
            r.text = "";
            para.add(r);
            return;
        }
        try {
            int len = h.length() / 2;
            byte[] bytes = new byte[len];
            for (int k = 0; k < len; k++) bytes[k] = (byte) Integer.parseInt(h.substring(k * 2, k * 2 + 2), 16);
            String uri = "data:" + mime + ";base64," + java.util.Base64.getEncoder().encodeToString(bytes);
            out.append("<img class=\"rimg\" src=\"").append(uri).append("\" alt=\"\">");
        } catch (RuntimeException ignore) {
            out.append("<span class=\"ph\">▣</span>");
        }
    }

    // ------------------------------------------------------------------ أدوات مساعدة

    private static String readWord(byte[] data, int from, int n) {
        int i = from;
        while (i < n && Character.isLetter(data[i] & 0xFF)) i++;
        return new String(data, from, i - from, Charset.forName("US-ASCII"));
    }

    private static Integer readNum(byte[] data, int pos, int n) {
        int i = pos;
        boolean neg = i < n && data[i] == '-';
        if (neg) i++;
        int start = i;
        while (i < n && Character.isDigit(data[i] & 0xFF)) i++;
        if (i == start) return null;
        return (neg ? -1 : 1) * Integer.parseInt(new String(data, start, i - start, Charset.forName("US-ASCII")));
    }

    private static int skipNumDigits(byte[] data, int pos, int n) {
        int i = pos;
        if (i < n && data[i] == '-') i++;
        while (i < n && Character.isDigit(data[i] & 0xFF)) i++;
        return i;
    }

    private static String peekWord(byte[] data, int from, int n) {
        int i = from;
        while (i < n && Character.isLetter(data[i] & 0xFF)) i++;
        return new String(data, from, i - from, Charset.forName("US-ASCII"));
    }

    private static boolean isIgnorableDestination(String word) {
        switch (word) {
            case "fonttbl": case "colortbl": case "stylesheet": case "info":
            case "generator": case "object": case "header":
            case "footer": case "headerf": case "footerf": case "themedata":
            case "colorschememapping": case "latentstyles": case "listtable":
            case "listoverridetable": case "rsidtbl": case "xmlnstbl":
            case "revtbl": case "datastore": case "panose": case "fldrslt": // fldrslt يُدار خصيصاً أعلاه
            case "nonshppict": case "shprslt": case "bkmkstart": case "bkmkend":
                return word.equals("fldrslt") ? false : true;
            default:
                return false;
        }
    }

    private static Charset charsetForCodepage(int cpg) {
        String name;
        switch (cpg) {
            case 1256: name = "windows-1256"; break;
            case 1252: name = "windows-1252"; break;
            case 1250: name = "windows-1250"; break;
            case 1251: name = "windows-1251"; break;
            case 1253: name = "windows-1253"; break;
            case 1255: name = "windows-1255"; break;
            case 65001: name = "UTF-8"; break;
            default: name = "windows-1252";
        }
        try { return Charset.forName(name); } catch (Exception e) { return Charset.forName("ISO-8859-1"); }
    }

    private static Charset charsetForFcharset(int fcharset) {
        String name;
        switch (fcharset) {
            case 178: case 162: name = "windows-1256"; break; // عربي/تركي عثماني تقريباً
            case 177: name = "windows-1255"; break;            // عبري
            case 161: name = "windows-1253"; break;            // يوناني
            case 204: name = "windows-1251"; break;            // كيريلي
            default: return null;
        }
        try { return Charset.forName(name); } catch (Exception e) { return null; }
    }

    private static String hex3(int r, int g, int b) {
        return String.format(Locale.ROOT, "#%02X%02X%02X", Math.max(0, r), Math.max(0, g), Math.max(0, b));
    }

    private static String trimNum(double v) {
        if (v == Math.rint(v)) return String.valueOf((long) v);
        return String.format(Locale.ROOT, "%.1f", v);
    }

    // ------------------------------------------------------------------ CSS المُولَّد

    private final Map<String, String> cssClasses = new LinkedHashMap<>();

    private String classFor(String css) {
        if (css.isEmpty()) return null;
        String cls = cssClasses.get(css);
        if (cls == null) { cls = "g" + cssClasses.size(); cssClasses.put(css, cls); }
        return cls;
    }

    private String buildCss() {
        StringBuilder css = new StringBuilder(1024);
        css.append(".doc{font-size:11pt;color:#1F1E1D;font-family:\"Segoe UI\",Roboto,\"Noto Sans Arabic\",\"Noto Naskh Arabic\",Arial,sans-serif;line-height:1.5}")
                .append(".doc p{margin:0 0 .55em;white-space:pre-wrap;overflow-wrap:anywhere}")
                .append(".doc table.rt{border-collapse:collapse;margin:8px 0;width:100%}")
                .append(".doc table.rt td{border:1px solid #B9B7AE;padding:4px 8px;vertical-align:top}")
                .append(".doc .rimg{max-width:100%;height:auto;display:block;margin:6px 0}")
                .append(".doc .ph{display:inline-block;padding:2px 8px;border:1px dashed #aaa;border-radius:4px;color:#888;font-size:12px}");
        for (Map.Entry<String, String> e : cssClasses.entrySet()) css.append('.').append(e.getValue()).append('{').append(e.getKey()).append('}');
        return css.toString();
    }
}
