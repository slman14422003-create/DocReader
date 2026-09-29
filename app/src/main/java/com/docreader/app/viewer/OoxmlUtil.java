package com.docreader.app.viewer;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

/**
 * أدوات مشتركة لقراءة ملفات Office الحديثة (docx / xlsx / pptx): الوصول إلى أجزاء ZIP،
 * تحليل XML بأمان، العلاقات بين الأجزاء، الألوان، والصور. كلها Java نقية (تُختبر خارج أندرويد).
 */
public final class OoxmlUtil {

    private OoxmlUtil() { }

    public static final class Rel {
        public String id;
        public String type;
        public String target;
        public boolean external;
    }

    // ------------------------------------------------------------------ ZIP

    public static ZipEntry entry(ZipFile zip, String name) {
        if (name == null) return null;
        ZipEntry e = zip.getEntry(name);
        if (e != null) return e;
        String lower = name.toLowerCase(Locale.ROOT);
        Enumeration<? extends ZipEntry> en = zip.entries();
        while (en.hasMoreElements()) {
            ZipEntry x = en.nextElement();
            if (x.getName().toLowerCase(Locale.ROOT).equals(lower)) return x;
        }
        return null;
    }

    public static boolean has(ZipFile zip, String name) {
        return entry(zip, name) != null;
    }

    /** يقرأ جزءًا كاملًا في الذاكرة؛ يعيد null إن لم يوجد أو تجاوز الحد الأقصى. */
    public static byte[] read(ZipFile zip, String name, int maxBytes) {
        try {
            ZipEntry e = entry(zip, name);
            if (e == null) return null;
            try (InputStream in = zip.getInputStream(e)) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                int len;
                int total = 0;
                while ((len = in.read(buf)) > 0) {
                    total += len;
                    if (total > maxBytes) return null;
                    bos.write(buf, 0, len);
                }
                return bos.toByteArray();
            }
        } catch (IOException ex) {
            return null;
        }
    }

    public static InputStream open(ZipFile zip, String name) throws IOException {
        ZipEntry e = entry(zip, name);
        if (e == null) return null;
        return zip.getInputStream(e);
    }

    // ------------------------------------------------------------------ XML / DOM

    public static Document parseXml(byte[] data) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(false);
        f.setExpandEntityReferences(false);
        trySet(f, "http://apache.org/xml/features/disallow-doctype-decl", true);
        trySet(f, "http://xml.org/sax/features/external-general-entities", false);
        trySet(f, "http://xml.org/sax/features/external-parameter-entities", false);
        DocumentBuilder b = f.newDocumentBuilder();
        b.setEntityResolver((publicId, systemId) -> new InputSource(new StringReader("")));
        return b.parse(new ByteArrayInputStream(data));
    }

    private static void trySet(DocumentBuilderFactory f, String feature, boolean value) {
        try {
            f.setFeature(feature, value);
        } catch (Exception ignored) {
            // بعض المحللات (ومنها أندرويد) لا تدعم كل الخصائص
        }
    }

    /** يحلّل جزءًا من الحزمة إلى DOM، أو يعيد null إن لم يوجد/تعذّر. */
    public static Document parsePart(ZipFile zip, String name, int maxBytes) {
        try {
            byte[] data = read(zip, name, maxBytes);
            if (data == null) return null;
            return parseXml(data);
        } catch (Exception e) {
            return null;
        }
    }

    public static String local(Node n) {
        String s = n.getNodeName();
        int i = s.indexOf(':');
        return i < 0 ? s : s.substring(i + 1);
    }

    public static boolean isEl(Node n, String local) {
        return n instanceof Element && local(n).equals(local);
    }

    public static List<Element> childEls(Element parent) {
        List<Element> out = new ArrayList<>();
        if (parent == null) return out;
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element) out.add((Element) n);
        }
        return out;
    }

    public static List<Element> childEls(Element parent, String local) {
        List<Element> out = new ArrayList<>();
        if (parent == null) return out;
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (isEl(n, local)) out.add((Element) n);
        }
        return out;
    }

    public static Element child(Element parent, String local) {
        if (parent == null) return null;
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (isEl(n, local)) return (Element) n;
        }
        return null;
    }

    /** أول عنصر حفيد (بأي عمق) بهذا الاسم المحلي. */
    public static Element descendant(Element parent, String local) {
        if (parent == null) return null;
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element) {
                if (local(n).equals(local)) return (Element) n;
                Element d = descendant((Element) n, local);
                if (d != null) return d;
            }
        }
        return null;
    }

    public static void descendants(Element parent, String local, List<Element> out) {
        if (parent == null) return;
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element) {
                if (local(n).equals(local)) out.add((Element) n);
                descendants((Element) n, local, out);
            }
        }
    }

    /** قيمة سمة بغضّ النظر عن البادئة (w:val أو val أو r:id ...). */
    public static String attr(Element e, String local) {
        if (e == null) return null;
        if (e.hasAttribute(local)) return e.getAttribute(local);
        NamedNodeMap map = e.getAttributes();
        for (int i = 0; i < map.getLength(); i++) {
            Node a = map.item(i);
            String name = a.getNodeName();
            if (name.endsWith(":" + local)) return a.getNodeValue();
        }
        return null;
    }

    public static Integer intAttr(Element e, String local) {
        String v = attr(e, local);
        if (v == null) return null;
        try {
            return (int) Math.round(Double.parseDouble(v.trim()));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    public static Double dblAttr(Element e, String local) {
        String v = attr(e, local);
        if (v == null) return null;
        try {
            return Double.parseDouble(v.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /** عنصر منطقي (مثل w:b): موجود بلا قيمة أو بقيمة غير false/0/off/none يعني تفعيلًا. */
    public static boolean flag(Element e) {
        String v = attr(e, "val");
        if (v == null) return true;
        v = v.trim().toLowerCase(Locale.ROOT);
        return !(v.equals("0") || v.equals("false") || v.equals("off") || v.equals("none"));
    }

    // ------------------------------------------------------------------ العلاقات والمسارات

    public static String dirOf(String path) {
        int i = path.lastIndexOf('/');
        return i < 0 ? "" : path.substring(0, i + 1);
    }

    public static String relsPathFor(String partPath) {
        int i = partPath.lastIndexOf('/');
        String dir = i < 0 ? "" : partPath.substring(0, i + 1);
        String name = i < 0 ? partPath : partPath.substring(i + 1);
        return dir + "_rels/" + name + ".rels";
    }

    public static Map<String, Rel> readRels(ZipFile zip, String partPath) {
        Map<String, Rel> map = new HashMap<>();
        Document doc = parsePart(zip, relsPathFor(partPath), 4 * 1024 * 1024);
        if (doc == null) return map;
        for (Element e : childEls(doc.getDocumentElement(), "Relationship")) {
            Rel r = new Rel();
            r.id = e.getAttribute("Id");
            r.type = e.getAttribute("Type");
            r.target = e.getAttribute("Target");
            r.external = "External".equalsIgnoreCase(e.getAttribute("TargetMode"));
            if (!r.id.isEmpty()) map.put(r.id, r);
        }
        return map;
    }

    public static String resolve(String baseDir, String target) {
        if (target == null) return null;
        String t = target.replace('\\', '/');
        String path = t.startsWith("/") ? t.substring(1) : baseDir + t;
        LinkedList<String> parts = new LinkedList<>();
        for (String seg : path.split("/")) {
            if (seg.isEmpty() || seg.equals(".")) continue;
            if (seg.equals("..")) {
                if (!parts.isEmpty()) parts.removeLast();
            } else {
                parts.addLast(seg);
            }
        }
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (sb.length() > 0) sb.append('/');
            sb.append(p);
        }
        return sb.toString();
    }

    public static String relType(Rel r) {
        if (r == null || r.type == null) return "";
        int i = r.type.lastIndexOf('/');
        return i < 0 ? r.type : r.type.substring(i + 1);
    }

    // ------------------------------------------------------------------ الصور

    /** نوع الصورة إن كان WebView يعرضه؛ وإلا null (EMF/WMF/TIFF غير مدعومة). */
    public static String imageMime(byte[] b) {
        if (b == null || b.length < 4) return null;
        if ((b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') return "image/png";
        if ((b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8) return "image/jpeg";
        if (b[0] == 'G' && b[1] == 'I' && b[2] == 'F') return "image/gif";
        if (b[0] == 'B' && b[1] == 'M') return "image/bmp";
        if (b.length > 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') return "image/webp";
        String head = new String(b, 0, Math.min(b.length, 300), StandardCharsets.UTF_8).trim().toLowerCase(Locale.ROOT);
        if (head.startsWith("<svg") || (head.startsWith("<?xml") && head.contains("<svg"))) return "image/svg+xml";
        return null;
    }

    public static String dataUri(byte[] bytes) {
        String mime = imageMime(bytes);
        if (mime == null) return null;
        return "data:" + mime + ";base64," + java.util.Base64.getEncoder().encodeToString(bytes);
    }

    // ------------------------------------------------------------------ الألوان

    public static int[] hexToRgb(String hex) {
        if (hex == null) return null;
        String h = hex.trim();
        if (h.startsWith("#")) h = h.substring(1);
        if (h.length() == 8) h = h.substring(2); // ARGB
        if (h.length() != 6) return null;
        try {
            return new int[]{
                    Integer.parseInt(h.substring(0, 2), 16),
                    Integer.parseInt(h.substring(2, 4), 16),
                    Integer.parseInt(h.substring(4, 6), 16)};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static String toHex(int r, int g, int b) {
        return String.format(Locale.ROOT, "#%02X%02X%02X", clamp255(r), clamp255(g), clamp255(b));
    }

    private static int clamp255(int v) {
        return Math.max(0, Math.min(255, v));
    }

    /** إضاءة نسبية تقريبية 0..1 */
    public static double luminance(String hex) {
        int[] c = hexToRgb(hex);
        if (c == null) return 1.0;
        return (0.299 * c[0] + 0.587 * c[1] + 0.114 * c[2]) / 255.0;
    }

    private static double[] rgbToHsl(int[] c) {
        double r = c[0] / 255.0, g = c[1] / 255.0, b = c[2] / 255.0;
        double max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
        double h = 0, s, l = (max + min) / 2;
        if (max == min) {
            s = 0;
        } else {
            double d = max - min;
            s = l > 0.5 ? d / (2 - max - min) : d / (max + min);
            if (max == r) h = (g - b) / d + (g < b ? 6 : 0);
            else if (max == g) h = (b - r) / d + 2;
            else h = (r - g) / d + 4;
            h /= 6;
        }
        return new double[]{h, s, l};
    }

    private static double hue2rgb(double p, double q, double t) {
        if (t < 0) t += 1;
        if (t > 1) t -= 1;
        if (t < 1.0 / 6) return p + (q - p) * 6 * t;
        if (t < 1.0 / 2) return q;
        if (t < 2.0 / 3) return p + (q - p) * (2.0 / 3 - t) * 6;
        return p;
    }

    private static String hslToHex(double h, double s, double l) {
        double r, g, b;
        if (s == 0) {
            r = g = b = l;
        } else {
            double q = l < 0.5 ? l * (1 + s) : l + s - l * s;
            double p = 2 * l - q;
            r = hue2rgb(p, q, h + 1.0 / 3);
            g = hue2rgb(p, q, h);
            b = hue2rgb(p, q, h - 1.0 / 3);
        }
        return toHex((int) Math.round(r * 255), (int) Math.round(g * 255), (int) Math.round(b * 255));
    }

    /** lumMod / lumOff في DrawingML (كسور: 1.0 = 100%). */
    public static String adjustLum(String hex, double lumMod, double lumOff) {
        int[] c = hexToRgb(hex);
        if (c == null) return hex;
        double[] hsl = rgbToHsl(c);
        double l = Math.max(0, Math.min(1, hsl[2] * lumMod + lumOff));
        return hslToHex(hsl[0], hsl[1], l);
    }

    /** tint في Excel: موجب يفتّح وسالب يغمّق. */
    public static String excelTint(String hex, double tint) {
        int[] c = hexToRgb(hex);
        if (c == null || tint == 0) return hex;
        double[] hsl = rgbToHsl(c);
        double l = tint < 0 ? hsl[2] * (1 + tint) : hsl[2] * (1 - tint) + tint;
        return hslToHex(hsl[0], hsl[1], Math.max(0, Math.min(1, l)));
    }

    /** يمزج اللون مع الأبيض (tint) أو الأسود (shade) بنسبة 0..1 */
    public static String mix(String hex, String otherHex, double amountOfOther) {
        int[] a = hexToRgb(hex), b = hexToRgb(otherHex);
        if (a == null || b == null) return hex;
        return toHex((int) Math.round(a[0] * (1 - amountOfOther) + b[0] * amountOfOther),
                (int) Math.round(a[1] * (1 - amountOfOther) + b[1] * amountOfOther),
                (int) Math.round(a[2] * (1 - amountOfOther) + b[2] * amountOfOther));
    }

    // ------------------------------------------------------------------ اتجاه النص

    public static boolean isRtlChar(int cp) {
        byte d = Character.getDirectionality(cp);
        return d == Character.DIRECTIONALITY_RIGHT_TO_LEFT
                || d == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC;
    }

    private static boolean isLtrLetter(int cp) {
        return Character.getDirectionality(cp) == Character.DIRECTIONALITY_LEFT_TO_RIGHT
                && Character.isLetter(cp);
    }

    /** true إن كان أول حرف قوي عربيًا/عبريًا، false إن كان لاتينيًا، null إن لم يوجد. */
    public static Boolean firstStrongIsRtl(CharSequence s) {
        for (int i = 0; i < s.length(); ) {
            int cp = Character.codePointAt(s, i);
            i += Character.charCount(cp);
            if (isRtlChar(cp)) return Boolean.TRUE;
            if (isLtrLetter(cp)) return Boolean.FALSE;
        }
        return null;
    }

    public static boolean containsRtl(CharSequence s) {
        for (int i = 0; i < s.length(); ) {
            int cp = Character.codePointAt(s, i);
            i += Character.charCount(cp);
            if (isRtlChar(cp)) return true;
        }
        return false;
    }
}
