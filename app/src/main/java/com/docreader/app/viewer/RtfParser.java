package com.docreader.app.viewer;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * مستخرج نص من ملفات RTF: يتجاهل الوجهات غير النصية (جداول الخطوط والألوان
 * والمعلومات..) ويفكّ ترميز \uN و\'hh بشكل صحيح، وينتج فقرات نظيفة تُعرض
 * بنفس مُحوّل عرض ملفات Word.
 */
public class RtfParser {

    public static List<DocxParser.Block> parse(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int len;
        while ((len = in.read(buf)) > 0) bos.write(buf, 0, len);
        byte[] data = bos.toByteArray();
        int n = data.length;

        List<DocxParser.Block> blocks = new ArrayList<>();
        StringBuilder paragraph = new StringBuilder();

        Deque<Boolean> ignoreStack = new ArrayDeque<>();
        boolean ignoring = false;
        Charset charset = charsetForCodepage(1252);
        int ucSkip = 1;
        int i = 0;

        while (i < n) {
            int b = data[i] & 0xFF;

            if (b == '{') {
                ignoreStack.push(ignoring);
                i++;
                if (i < n && data[i] == '\\') {
                    int j = i + 1;
                    if (j < n && data[j] == '*') {
                        ignoring = true;
                    } else {
                        String word = peekWord(data, j, n);
                        if (isIgnorableDestination(word)) ignoring = true;
                    }
                }
                continue;
            }
            if (b == '}') {
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
                    if (i + 1 < n) {
                        String hex = "" + (char) data[i] + (char) data[i + 1];
                        i += 2;
                        if (!ignoring) {
                            try {
                                int val = Integer.parseInt(hex, 16);
                                paragraph.append(new String(new byte[]{(byte) val}, charset));
                            } catch (NumberFormatException ignored) { }
                        }
                    }
                    continue;
                }

                if (Character.isLetter(c)) {
                    int wordStart = i;
                    while (i < n && Character.isLetter(data[i] & 0xFF)) i++;
                    String word = new String(data, wordStart, i - wordStart, Charset.forName("US-ASCII"));

                    boolean neg = false;
                    if (i < n && data[i] == '-') { neg = true; i++; }
                    int numStart = i;
                    while (i < n && Character.isDigit(data[i] & 0xFF)) i++;
                    Integer num = null;
                    if (i > numStart) num = (neg ? -1 : 1) * Integer.parseInt(new String(data, numStart, i - numStart, Charset.forName("US-ASCII")));

                    if (i < n && data[i] == ' ') i++; // مسافة الفصل الاختيارية بعد كلمة التحكم

                    switch (word) {
                        case "par": case "line": case "page":
                            finishParagraph(blocks, paragraph);
                            break;
                        case "tab":
                            if (!ignoring) paragraph.append('\t');
                            break;
                        case "ansicpg":
                            if (num != null) charset = charsetForCodepage(num);
                            break;
                        case "uc":
                            if (num != null) ucSkip = num;
                            break;
                        case "u":
                            if (num != null) {
                                if (!ignoring) {
                                    int cp = num < 0 ? num + 65536 : num;
                                    paragraph.append((char) cp);
                                }
                                // تجاوز أحرف الاستبدال الاحتياطية بعد \u
                                int skipped = 0;
                                while (skipped < ucSkip && i < n && data[i] != '\\' && data[i] != '{' && data[i] != '}') {
                                    i++; skipped++;
                                }
                            }
                            break;
                        default:
                            if (isIgnorableDestination(word)) ignoring = true;
                            break;
                    }
                    continue;
                }

                // رمز تحكم بحرف واحد (\\ أو \{ أو \} أو \~ إلخ)
                if (!ignoring && (c == '\\' || c == '{' || c == '}')) {
                    paragraph.append((char) c);
                } else if (!ignoring && c == '~') {
                    paragraph.append(' ');
                }
                i++;
                continue;
            }

            if (b == '\r' || b == '\n') { i++; continue; }

            int start = i;
            while (i < n && data[i] != '\\' && data[i] != '{' && data[i] != '}') i++;
            if (!ignoring) paragraph.append(new String(data, start, i - start, charset));
        }
        finishParagraph(blocks, paragraph);
        return blocks;
    }

    private static void finishParagraph(List<DocxParser.Block> blocks, StringBuilder paragraph) {
        String text = paragraph.toString().trim();
        paragraph.setLength(0);
        if (text.isEmpty()) return;
        DocxParser.Block block = new DocxParser.Block(DocxParser.Block.Type.PARAGRAPH);
        DocxParser.Run run = new DocxParser.Run();
        run.text = text;
        block.runs.add(run);
        blocks.add(block);
    }

    private static String peekWord(byte[] data, int from, int n) {
        int i = from;
        while (i < n && Character.isLetter(data[i] & 0xFF)) i++;
        return new String(data, from, i - from, Charset.forName("US-ASCII"));
    }

    private static boolean isIgnorableDestination(String word) {
        switch (word) {
            case "fonttbl": case "colortbl": case "stylesheet": case "info":
            case "generator": case "pict": case "object": case "header":
            case "footer": case "headerf": case "footerf": case "themedata":
            case "colorschememapping": case "latentstyles": case "listtable":
            case "listoverridetable": case "rsidtbl": case "xmlnstbl":
            case "revtbl": case "datastore":
                return true;
            default:
                return false;
        }
    }

    private static Charset charsetForCodepage(int cpg) {
        String name;
        switch (cpg) {
            case 1256: name = "windows-1256"; break; // عربي
            case 1252: name = "windows-1252"; break;
            case 1250: name = "windows-1250"; break;
            case 1251: name = "windows-1251"; break;
            case 65001: name = "UTF-8"; break;
            default: name = "windows-1252";
        }
        try {
            return Charset.forName(name);
        } catch (Exception e) {
            return Charset.forName("ISO-8859-1");
        }
    }
}
