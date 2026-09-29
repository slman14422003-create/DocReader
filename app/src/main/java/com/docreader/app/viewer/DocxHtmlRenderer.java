package com.docreader.app.viewer;

import static com.docreader.app.viewer.OoxmlUtil.attr;
import static com.docreader.app.viewer.OoxmlUtil.child;
import static com.docreader.app.viewer.OoxmlUtil.childEls;
import static com.docreader.app.viewer.OoxmlUtil.containsRtl;
import static com.docreader.app.viewer.OoxmlUtil.descendant;
import static com.docreader.app.viewer.OoxmlUtil.descendants;
import static com.docreader.app.viewer.OoxmlUtil.dirOf;
import static com.docreader.app.viewer.OoxmlUtil.firstStrongIsRtl;
import static com.docreader.app.viewer.OoxmlUtil.flag;
import static com.docreader.app.viewer.OoxmlUtil.intAttr;
import static com.docreader.app.viewer.OoxmlUtil.isEl;
import static com.docreader.app.viewer.OoxmlUtil.isRtlChar;
import static com.docreader.app.viewer.OoxmlUtil.local;
import static com.docreader.app.viewer.OoxmlUtil.parsePart;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.zip.ZipFile;

/**
 * يحوّل ملف Word (.docx) إلى HTML عالي الأمانة للعرض داخل WebView:
 * أنماط الفقرات والأحرف (styles.xml)، الترقيم والقوائم (numbering.xml)، العناوين،
 * الجداول (دمج الخلايا، حدود، تظليل، أنماط الجداول)، الصور والقصّ، مربعات النص،
 * الروابط والحقول، الحواشي السفلية والختامية، والاتجاه من اليمين لليسار (العربية).
 */
public final class DocxHtmlRenderer {

    private static final int MAX_XML = 30 * 1024 * 1024;
    private static final int MAX_IMAGE = 12 * 1024 * 1024;
    private static final long MAX_IMAGE_TOTAL = 40L * 1024 * 1024;

    public static String render(File file) throws Exception {
        try (ZipFile zip = new ZipFile(file)) {
            return new DocxHtmlRenderer(zip).run();
        }
    }

    // ===================================================================== النماذج

    static final class RPr {
        Boolean b, bCs, i, iCs, strike, dstrike, caps, smallCaps, vanish, rtl;
        String u, color, highlight, shd, vertAlign, fAscii, fCs;
        Double sz, szCs;

        RPr copy() {
            RPr r = new RPr();
            r.merge(this);
            return r;
        }

        void merge(RPr o) {
            if (o == null) return;
            if (o.b != null) b = o.b;
            if (o.bCs != null) bCs = o.bCs;
            if (o.i != null) i = o.i;
            if (o.iCs != null) iCs = o.iCs;
            if (o.strike != null) strike = o.strike;
            if (o.dstrike != null) dstrike = o.dstrike;
            if (o.caps != null) caps = o.caps;
            if (o.smallCaps != null) smallCaps = o.smallCaps;
            if (o.vanish != null) vanish = o.vanish;
            if (o.rtl != null) rtl = o.rtl;
            if (o.u != null) u = o.u;
            if (o.color != null) color = o.color;
            if (o.highlight != null) highlight = o.highlight;
            if (o.shd != null) shd = o.shd;
            if (o.vertAlign != null) vertAlign = o.vertAlign;
            if (o.fAscii != null) fAscii = o.fAscii;
            if (o.fCs != null) fCs = o.fCs;
            if (o.sz != null) sz = o.sz;
            if (o.szCs != null) szCs = o.szCs;
        }
    }

    static final class PPr {
        String jc;
        Boolean bidi;
        Integer start, end, firstLine, hanging;
        Integer before, after, line;
        String lineRule;
        Integer outline, numId, ilvl;
        Boolean pageBreakBefore;
        String shd;
        final String[] bdr = new String[4]; // top, start, bottom, end

        PPr copy() {
            PPr p = new PPr();
            p.merge(this);
            return p;
        }

        void merge(PPr o) {
            if (o == null) return;
            if (o.jc != null) jc = o.jc;
            if (o.bidi != null) bidi = o.bidi;
            mergeInd(o);
            if (o.before != null) before = o.before;
            if (o.after != null) after = o.after;
            if (o.line != null) line = o.line;
            if (o.lineRule != null) lineRule = o.lineRule;
            if (o.outline != null) outline = o.outline;
            if (o.numId != null) numId = o.numId;
            if (o.ilvl != null) ilvl = o.ilvl;
            if (o.pageBreakBefore != null) pageBreakBefore = o.pageBreakBefore;
            if (o.shd != null) shd = o.shd;
            for (int k = 0; k < 4; k++) if (o.bdr[k] != null) bdr[k] = o.bdr[k];
        }

        void mergeInd(PPr o) {
            if (o.start != null) start = o.start;
            if (o.end != null) end = o.end;
            if (o.firstLine != null) {
                firstLine = o.firstLine;
                hanging = null;
            }
            if (o.hanging != null) {
                hanging = o.hanging;
                firstLine = null;
            }
        }
    }

    static final class Style {
        String id, name, basedOn, type;
        boolean isDefault;
        PPr ppr = new PPr();
        RPr rpr = new RPr();
        Element tblPr;
        final Map<String, Cond> cond = new HashMap<>();
    }

    static final class Cond {
        RPr rpr = new RPr();
        String shd;
        final String[] bdr = new String[6]; // top,start,bottom,end,insideH,insideV
    }

    static final class TStyle {
        final String[] bdr = new String[6];
        final int[] mar = {-1, -1, -1, -1}; // top,start,bottom,end
        final Map<String, Cond> cond = new HashMap<>();
        final RPr rpr = new RPr();
    }

    static final class Lvl {
        int start = 1;
        String fmt = "decimal";
        String text = "";
        String suff = "tab";
        boolean legal;
        PPr ppr = new PPr();
        RPr rpr = new RPr();
    }

    static final class AbsNum {
        final Lvl[] lvls = new Lvl[9];
    }

    static final class Label {
        String text;
        Lvl lvl;
    }

    static final class Field {
        final StringBuilder instr = new StringBuilder();
        boolean result;
        boolean link;
    }

    // ===================================================================== الحالة

    private final ZipFile zip;
    private String mainPath = "word/document.xml";
    private Map<String, OoxmlUtil.Rel> rels = new HashMap<>();

    private RPr docR = new RPr();
    private PPr docP = new PPr();
    private String defaultParaStyle;
    private final Map<String, Style> styles = new HashMap<>();
    private final Map<String, PPr> pCache = new HashMap<>();
    private final Map<String, RPr> rCache = new HashMap<>();

    private final Map<Integer, AbsNum> abs = new HashMap<>();
    private final Map<Integer, Integer> numToAbs = new HashMap<>();
    private final Map<Integer, Map<Integer, Integer>> startOv = new HashMap<>();
    private final Map<Integer, Map<Integer, Lvl>> lvlOv = new HashMap<>();
    private final Map<String, int[]> counters = new HashMap<>();

    private double defaultSize = 11;
    private String defaultColor = "#1a1a1a";
    private String defaultGeneric = "sans-serif";

    private final LinkedHashMap<String, String> runClasses = new LinkedHashMap<>();
    private final LinkedHashMap<String, String> paraClasses = new LinkedHashMap<>();
    private final LinkedHashMap<String, String> cellClasses = new LinkedHashMap<>();

    private final Map<String, String> imageCache = new HashMap<>();
    private long imageBytesTotal = 0;

    private final List<String> footnoteIds = new ArrayList<>();
    private final List<String> endnoteIds = new ArrayList<>();
    private Map<String, Element> footnotes = new HashMap<>();
    private Map<String, Element> endnotes = new HashMap<>();
    private boolean sawRtl = false;

    // سياق الخلية الحالية
    private RPr cellR = null;
    private boolean cellDark = false;

    private DocxHtmlRenderer(ZipFile zip) {
        this.zip = zip;
    }

    // ===================================================================== التحميل

    private String run() throws Exception {
        locateMainPart();
        loadStyles();
        loadNumbering();
        rels = OoxmlUtil.readRels(zip, mainPath);
        footnotes = loadNotes("word/footnotes.xml", "footnote");
        endnotes = loadNotes("word/endnotes.xml", "endnote");

        byte[] xml = OoxmlUtil.read(zip, mainPath, MAX_XML);
        if (xml == null) throw new IllegalStateException("document.xml missing or too large");
        Document doc = OoxmlUtil.parseXml(xml);
        Element body = child(doc.getDocumentElement(), "body");
        if (body == null) body = doc.getDocumentElement();

        StringBuilder out = new StringBuilder(64 * 1024);
        renderBlocks(body, out);
        renderNotes(out);

        return "<style>" + buildCss() + "</style><div class=\"paper doc\">" + out + "</div>";
    }

    private void locateMainPart() {
        Map<String, OoxmlUtil.Rel> root = OoxmlUtil.readRels(zip, "");
        // readRels("") يعطي "_rels/.rels" عبر relsPathFor
        for (OoxmlUtil.Rel r : root.values()) {
            if ("officeDocument".equals(OoxmlUtil.relType(r)) && r.target != null && !r.external) {
                String p = OoxmlUtil.resolve("", r.target);
                if (OoxmlUtil.has(zip, p)) {
                    mainPath = p;
                    return;
                }
            }
        }
    }

    private void loadStyles() {
        String dir = dirOf(mainPath);
        Document d = parsePart(zip, dir + "styles.xml", 16 * 1024 * 1024);
        if (d == null) return;
        Element root = d.getDocumentElement();
        Element dd = child(root, "docDefaults");
        if (dd != null) {
            Element rpd = child(dd, "rPrDefault");
            if (rpd != null) docR = parseRPr(child(rpd, "rPr"));
            Element ppd = child(dd, "pPrDefault");
            if (ppd != null) docP = parsePPr(child(ppd, "pPr"));
        }
        for (Element s : childEls(root, "style")) {
            Style st = new Style();
            st.id = attr(s, "styleId");
            st.type = attr(s, "type");
            st.isDefault = "1".equals(attr(s, "default")) || "true".equals(attr(s, "default"));
            st.name = attr(child(s, "name"), "val");
            st.basedOn = attr(child(s, "basedOn"), "val");
            st.ppr = parsePPr(child(s, "pPr"));
            st.rpr = parseRPr(child(s, "rPr"));
            if ("table".equals(st.type)) {
                st.tblPr = child(s, "tblPr");
                for (Element sp : childEls(s, "tblStylePr")) {
                    String type = attr(sp, "type");
                    if (type != null) st.cond.put(type, parseCond(sp));
                }
            }
            if (st.id != null) styles.put(st.id, st);
            if ("paragraph".equals(st.type) && st.isDefault) defaultParaStyle = st.id;
        }
        // القيم الافتراضية للنص
        RPr base = docR.copy();
        if (defaultParaStyle != null) base.merge(styleOnlyR(defaultParaStyle));
        if (base.sz != null) defaultSize = base.sz;
        if (base.color != null && !"auto".equalsIgnoreCase(base.color)) defaultColor = "#" + base.color;
        if (base.fAscii != null) defaultGeneric = genericFor(base.fAscii);
    }

    private void loadNumbering() {
        Document d = parsePart(zip, dirOf(mainPath) + "numbering.xml", 8 * 1024 * 1024);
        if (d == null) return;
        Element root = d.getDocumentElement();
        for (Element an : childEls(root, "abstractNum")) {
            Integer id = intAttr(an, "abstractNumId");
            if (id == null) continue;
            AbsNum a = new AbsNum();
            for (Element lv : childEls(an, "lvl")) {
                Integer il = intAttr(lv, "ilvl");
                if (il != null && il >= 0 && il < 9) a.lvls[il] = parseLvl(lv);
            }
            abs.put(id, a);
        }
        for (Element n : childEls(root, "num")) {
            Integer numId = intAttr(n, "numId");
            Integer absId = intAttr(child(n, "abstractNumId"), "val");
            if (numId == null || absId == null) continue;
            numToAbs.put(numId, absId);
            for (Element lo : childEls(n, "lvlOverride")) {
                Integer il = intAttr(lo, "ilvl");
                if (il == null) continue;
                Element so = child(lo, "startOverride");
                if (so != null) {
                    Integer v = intAttr(so, "val");
                    if (v != null) startOv.computeIfAbsent(numId, k -> new HashMap<>()).put(il, v);
                }
                Element lv = child(lo, "lvl");
                if (lv != null) lvlOv.computeIfAbsent(numId, k -> new HashMap<>()).put(il, parseLvl(lv));
            }
        }
    }

    private Lvl parseLvl(Element lv) {
        Lvl l = new Lvl();
        Integer st = intAttr(child(lv, "start"), "val");
        if (st != null) l.start = st;
        String fmt = attr(child(lv, "numFmt"), "val");
        if (fmt != null) l.fmt = fmt;
        String tx = attr(child(lv, "lvlText"), "val");
        if (tx != null) l.text = tx;
        String sf = attr(child(lv, "suff"), "val");
        if (sf != null) l.suff = sf;
        Element lg = child(lv, "isLgl");
        l.legal = lg != null && flag(lg);
        l.ppr = parsePPr(child(lv, "pPr"));
        l.rpr = parseRPr(child(lv, "rPr"));
        return l;
    }

    private Map<String, Element> loadNotes(String path, String tag) {
        Map<String, Element> map = new HashMap<>();
        Document d = parsePart(zip, path, 8 * 1024 * 1024);
        if (d == null) return map;
        for (Element e : childEls(d.getDocumentElement(), tag)) {
            String type = attr(e, "type");
            if (type != null && !type.equals("normal")) continue;
            String id = attr(e, "id");
            if (id != null) map.put(id, e);
        }
        return map;
    }

    // ===================================================================== تحليل الخصائص

    static RPr parseRPr(Element rpr) {
        RPr r = new RPr();
        if (rpr == null) return r;
        for (Element e : childEls(rpr)) {
            switch (local(e)) {
                case "b": r.b = flag(e); break;
                case "bCs": r.bCs = flag(e); break;
                case "i": r.i = flag(e); break;
                case "iCs": r.iCs = flag(e); break;
                case "strike": r.strike = flag(e); break;
                case "dstrike": r.dstrike = flag(e); break;
                case "caps": r.caps = flag(e); break;
                case "smallCaps": r.smallCaps = flag(e); break;
                case "vanish": r.vanish = flag(e); break;
                case "rtl": r.rtl = flag(e); break;
                case "u": {
                    String v = attr(e, "val");
                    r.u = v == null ? "single" : v;
                    break;
                }
                case "color": {
                    String v = attr(e, "val");
                    if (v != null) r.color = v.equalsIgnoreCase("auto") ? "auto" : v;
                    break;
                }
                case "highlight": {
                    String v = attr(e, "val");
                    if (v != null) r.highlight = v;
                    break;
                }
                case "shd": {
                    String fill = attr(e, "fill");
                    if (fill != null && !fill.equalsIgnoreCase("auto")) r.shd = fill;
                    break;
                }
                case "sz": {
                    Integer v = intAttr(e, "val");
                    if (v != null) r.sz = v / 2.0;
                    break;
                }
                case "szCs": {
                    Integer v = intAttr(e, "val");
                    if (v != null) r.szCs = v / 2.0;
                    break;
                }
                case "vertAlign": r.vertAlign = attr(e, "val"); break;
                case "rFonts": {
                    String a = attr(e, "ascii");
                    if (a == null) a = attr(e, "hAnsi");
                    if (a != null) r.fAscii = a;
                    String cs = attr(e, "cs");
                    if (cs != null) r.fCs = cs;
                    break;
                }
                default: break;
            }
        }
        return r;
    }

    static PPr parsePPr(Element ppr) {
        PPr p = new PPr();
        if (ppr == null) return p;
        for (Element e : childEls(ppr)) {
            switch (local(e)) {
                case "jc": p.jc = attr(e, "val"); break;
                case "bidi": p.bidi = flag(e); break;
                case "ind": {
                    Integer st = intAttr(e, "start");
                    if (st == null) st = intAttr(e, "left");
                    Integer en = intAttr(e, "end");
                    if (en == null) en = intAttr(e, "right");
                    p.start = st;
                    p.end = en;
                    p.firstLine = intAttr(e, "firstLine");
                    p.hanging = intAttr(e, "hanging");
                    if (p.hanging != null) p.firstLine = null;
                    break;
                }
                case "spacing": {
                    p.before = intAttr(e, "before");
                    p.after = intAttr(e, "after");
                    if ("1".equals(attr(e, "beforeAutospacing")) || "true".equals(attr(e, "beforeAutospacing"))) p.before = 280;
                    if ("1".equals(attr(e, "afterAutospacing")) || "true".equals(attr(e, "afterAutospacing"))) p.after = 280;
                    p.line = intAttr(e, "line");
                    p.lineRule = attr(e, "lineRule");
                    break;
                }
                case "outlineLvl": p.outline = intAttr(e, "val"); break;
                case "numPr": {
                    Integer n = intAttr(child(e, "numId"), "val");
                    Integer l = intAttr(child(e, "ilvl"), "val");
                    if (n != null) p.numId = n;
                    if (l != null) p.ilvl = l;
                    break;
                }
                case "pageBreakBefore": p.pageBreakBefore = flag(e); break;
                case "shd": {
                    String fill = attr(e, "fill");
                    if (fill != null && !fill.equalsIgnoreCase("auto")) p.shd = fill;
                    break;
                }
                case "pBdr": {
                    for (Element b : childEls(e)) {
                        switch (local(b)) {
                            case "top": p.bdr[0] = borderCss(b); break;
                            case "left": case "start": p.bdr[1] = borderCss(b); break;
                            case "bottom": p.bdr[2] = borderCss(b); break;
                            case "right": case "end": p.bdr[3] = borderCss(b); break;
                            default: break;
                        }
                    }
                    break;
                }
                default: break;
            }
        }
        return p;
    }

    static String borderCss(Element b) {
        String val = attr(b, "val");
        if (val == null || val.equals("nil") || val.equals("none")) return "none";
        Integer sz = intAttr(b, "sz");
        double px = sz == null ? 1 : Math.max(1, Math.round(sz / 8.0 * 1.333));
        String style;
        switch (val) {
            case "double": style = "double"; px = Math.max(px, 3); break;
            case "dotted": style = "dotted"; break;
            case "dashed": case "dashSmallGap": case "dotDash": case "dotDotDash": style = "dashed"; break;
            default: style = "solid";
        }
        if (val.startsWith("thick")) px = Math.max(px, 3);
        String color = attr(b, "color");
        String col = (color == null || color.equalsIgnoreCase("auto")) ? "#000" : "#" + color;
        return (int) px + "px " + style + " " + col;
    }

    private Cond parseCond(Element sp) {
        Cond c = new Cond();
        c.rpr = parseRPr(child(sp, "rPr"));
        Element tcPr = child(sp, "tcPr");
        if (tcPr != null) {
            String fill = attr(child(tcPr, "shd"), "fill");
            if (fill != null && !fill.equalsIgnoreCase("auto")) c.shd = fill;
            parseBordersInto(child(tcPr, "tcBorders"), c.bdr);
        }
        return c;
    }

    private static void parseBordersInto(Element borders, String[] target) {
        if (borders == null) return;
        for (Element b : childEls(borders)) {
            switch (local(b)) {
                case "top": target[0] = borderCss(b); break;
                case "left": case "start": target[1] = borderCss(b); break;
                case "bottom": target[2] = borderCss(b); break;
                case "right": case "end": target[3] = borderCss(b); break;
                case "insideH": target[4] = borderCss(b); break;
                case "insideV": target[5] = borderCss(b); break;
                default: break;
            }
        }
    }

    // ===================================================================== الأنماط

    private List<Style> chain(String id) {
        LinkedList<Style> list = new LinkedList<>();
        Set<String> seen = new HashSet<>();
        while (id != null && seen.add(id)) {
            Style s = styles.get(id);
            if (s == null) break;
            list.addFirst(s);
            id = s.basedOn;
        }
        return list;
    }

    private PPr styleP(String id) {
        String key = id == null ? "" : id;
        PPr cached = pCache.get(key);
        if (cached != null) return cached;
        PPr p = docP.copy();
        for (Style s : chain(id)) p.merge(s.ppr);
        pCache.put(key, p);
        return p;
    }

    private RPr styleOnlyR(String id) {
        String key = id == null ? "" : id;
        RPr cached = rCache.get(key);
        if (cached != null) return cached;
        RPr r = new RPr();
        for (Style s : chain(id)) r.merge(s.rpr);
        rCache.put(key, r);
        return r;
    }

    private TStyle tableStyle(String id) {
        TStyle t = new TStyle();
        for (Style s : chain(id)) {
            if (s.tblPr != null) {
                parseBordersInto(child(s.tblPr, "tblBorders"), t.bdr);
                Element mar = child(s.tblPr, "tblCellMar");
                if (mar != null) applyCellMar(mar, t.mar);
            }
            t.cond.putAll(s.cond);
            t.rpr.merge(s.rpr);
        }
        Cond whole = t.cond.get("wholeTable");
        if (whole != null) t.rpr.merge(whole.rpr);
        return t;
    }

    private static void applyCellMar(Element mar, int[] target) {
        for (Element m : childEls(mar)) {
            Integer w = intAttr(m, "w");
            if (w == null) continue;
            switch (local(m)) {
                case "top": target[0] = w; break;
                case "left": case "start": target[1] = w; break;
                case "bottom": target[2] = w; break;
                case "right": case "end": target[3] = w; break;
                default: break;
            }
        }
    }

    // ===================================================================== الترقيم

    private Lvl lvlFor(int numId, AbsNum a, int il) {
        Map<Integer, Lvl> ov = lvlOv.get(numId);
        if (ov != null && ov.get(il) != null) return ov.get(il);
        return a.lvls[il];
    }

    private int startFor(int numId, AbsNum a, int il) {
        Map<Integer, Integer> so = startOv.get(numId);
        if (so != null && so.get(il) != null) return so.get(il);
        Lvl l = lvlFor(numId, a, il);
        return l == null ? 1 : l.start;
    }

    private Label nextLabel(int numId, int ilvl) {
        Integer absId = numToAbs.get(numId);
        if (absId == null) return null;
        AbsNum a = abs.get(absId);
        if (a == null) return null;
        if (ilvl < 0 || ilvl > 8) ilvl = 0;
        Lvl l = lvlFor(numId, a, ilvl);
        if (l == null) return null;
        boolean own = startOv.containsKey(numId) || lvlOv.containsKey(numId);
        String key = own ? "n" + numId : "a" + absId;
        int[] c = counters.get(key);
        if (c == null) {
            c = new int[9];
            Arrays.fill(c, -1);
            counters.put(key, c);
        }
        if (c[ilvl] < 0) c[ilvl] = startFor(numId, a, ilvl);
        else c[ilvl]++;
        for (int j = ilvl + 1; j < 9; j++) c[j] = -1;

        Label label = new Label();
        label.lvl = l;
        if ("bullet".equals(l.fmt)) {
            label.text = bulletChar(l.text);
        } else if ("none".equals(l.fmt)) {
            label.text = "";
        } else {
            StringBuilder sb = new StringBuilder();
            String pattern = l.text;
            for (int i = 0; i < pattern.length(); i++) {
                char ch = pattern.charAt(i);
                if (ch == '%' && i + 1 < pattern.length() && Character.isDigit(pattern.charAt(i + 1))) {
                    int k = pattern.charAt(i + 1) - '1';
                    i++;
                    if (k < 0 || k > 8) continue;
                    Lvl lk = lvlFor(numId, a, k);
                    int val = c[k] < 0 ? startFor(numId, a, k) : c[k];
                    String f = l.legal ? "decimal" : (lk != null ? lk.fmt : "decimal");
                    sb.append(fmtNum(f, val));
                } else {
                    sb.append(ch);
                }
            }
            label.text = sb.toString();
        }
        return label;
    }

    private static String bulletChar(String t) {
        if (t == null || t.isEmpty()) return "•";
        char c = t.charAt(0);
        switch (c) {
            case '\uF0B7': return "•";
            case '\uF0A7': return "▪";
            case '\uF0D8': return "➢";
            case '\uF0FC': return "✓";
            case '\uF076': return "❖";
            case '\uF0A8': return "◻";
            case '\uF06E': return "■";
            case 'o': return "◦";
            case '§': return "▪";
            default:
                if (c >= 0xF000 && c <= 0xF0FF) return "•";
                return t;
        }
    }

    static String fmtNum(String fmt, int n) {
        switch (fmt) {
            case "decimalZero": return n < 10 ? "0" + n : String.valueOf(n);
            case "upperRoman": return roman(n).toUpperCase(Locale.ROOT);
            case "lowerRoman": return roman(n);
            case "upperLetter": return letters(n).toUpperCase(Locale.ROOT);
            case "lowerLetter": return letters(n);
            case "ordinal": return n + ordinalSuffix(n);
            case "hindiNumbers": return arabicIndic(String.valueOf(n));
            case "arabicAlpha": case "hindiLetters": return fromList(ARABIC_ALPHA, n);
            case "arabicAbjad": case "hindiCounting": return fromList(ARABIC_ABJAD, n);
            case "decimalEnclosedParen": return "(" + n + ")";
            default: return String.valueOf(n);
        }
    }

    private static final String[] ARABIC_ALPHA = {"أ", "ب", "ت", "ث", "ج", "ح", "خ", "د", "ذ", "ر", "ز", "س", "ش",
            "ص", "ض", "ط", "ظ", "ع", "غ", "ف", "ق", "ك", "ل", "م", "ن", "هـ", "و", "ي"};
    private static final String[] ARABIC_ABJAD = {"أ", "ب", "ج", "د", "هـ", "و", "ز", "ح", "ط", "ي", "ك", "ل", "م",
            "ن", "س", "ع", "ف", "ص", "ق", "ر", "ش", "ت", "ث", "خ", "ذ", "ض", "ظ", "غ"};

    private static String fromList(String[] list, int n) {
        if (n < 1) return String.valueOf(n);
        int idx = (n - 1) % list.length;
        int rep = (n - 1) / list.length + 1;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < rep; i++) sb.append(list[idx]);
        return sb.toString();
    }

    private static String arabicIndic(String digits) {
        StringBuilder sb = new StringBuilder();
        for (char ch : digits.toCharArray()) {
            if (ch >= '0' && ch <= '9') sb.append((char) ('\u0660' + (ch - '0')));
            else sb.append(ch);
        }
        return sb.toString();
    }

    private static String letters(int n) {
        if (n < 1) return String.valueOf(n);
        char ch = (char) ('a' + (n - 1) % 26);
        int rep = (n - 1) / 26 + 1;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < rep; i++) sb.append(ch);
        return sb.toString();
    }

    private static String roman(int n) {
        if (n < 1 || n > 3999) return String.valueOf(n);
        int[] v = {1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1};
        String[] s = {"m", "cm", "d", "cd", "c", "xc", "l", "xl", "x", "ix", "v", "iv", "i"};
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < v.length; i++) {
            while (n >= v[i]) {
                sb.append(s[i]);
                n -= v[i];
            }
        }
        return sb.toString();
    }

    private static String ordinalSuffix(int n) {
        int m100 = n % 100;
        if (m100 >= 11 && m100 <= 13) return "th";
        switch (n % 10) {
            case 1: return "st";
            case 2: return "nd";
            case 3: return "rd";
            default: return "th";
        }
    }

    // ===================================================================== الكتل

    private void renderBlocks(Element container, StringBuilder out) {
        int emptyRun = 0;
        for (Node n = container.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (!(n instanceof Element)) continue;
            Element e = (Element) n;
            switch (local(e)) {
                case "p": {
                    boolean empty = renderParagraph(e, out, emptyRun >= 3);
                    emptyRun = empty ? emptyRun + 1 : 0;
                    break;
                }
                case "tbl":
                    renderTable(e, out);
                    emptyRun = 0;
                    break;
                case "sdt": {
                    Element content = child(e, "sdtContent");
                    if (content != null) renderBlocks(content, out);
                    break;
                }
                case "customXml":
                case "ins":
                case "moveTo":
                    renderBlocks(e, out);
                    break;
                default: break;
            }
        }
    }

    /** يعيد true إن كانت الفقرة فارغة. */
    private boolean renderParagraph(Element p, StringBuilder out, boolean skipIfEmpty) {
        Element pPrEl = child(p, "pPr");
        PPr direct = parsePPr(pPrEl);
        String styleId = attr(child(pPrEl, "pStyle"), "val");
        if (styleId == null || !styles.containsKey(styleId)) styleId = defaultParaStyle;

        PPr eff = styleP(styleId).copy();
        // القوائم: ترقيم من النمط أو من الفقرة نفسها
        Integer numId = direct.numId != null ? direct.numId : eff.numId;
        Integer ilvl = direct.ilvl != null ? direct.ilvl : (eff.ilvl != null ? eff.ilvl : Integer.valueOf(0));
        Label label = null;
        if (numId != null && numId != 0) {
            label = nextLabel(numId, ilvl);
            if (label != null) eff.mergeInd(label.lvl.ppr);
        }
        eff.merge(direct);
        if (label != null && (direct.start == null && direct.hanging == null && direct.firstLine == null)) {
            // مستوى الترقيم يفرض إزاحته على إزاحة النمط
            eff.start = label.lvl.ppr.start != null ? label.lvl.ppr.start : eff.start;
            if (label.lvl.ppr.hanging != null) eff.hanging = label.lvl.ppr.hanging;
        }

        String plain = plainText(p);
        boolean explicitBidi = direct.bidi != null || (styleP(styleId).bidi != null);
        boolean bidi;
        if (eff.bidi != null) {
            bidi = eff.bidi;
        } else {
            Boolean fs = firstStrongIsRtl(plain);
            bidi = fs != null && fs;
        }
        explicitBidi = eff.bidi != null && eff.bidi;
        if (bidi || containsRtl(plain)) sawRtl = true;

        RPr paraR = docR.copy();
        if (cellR != null) paraR.merge(cellR);
        paraR.merge(styleOnlyR(styleId));

        Inline in = new Inline();
        Deque<Field> fields = new ArrayDeque<>();
        if (label != null) appendLabel(in, label, paraR, eff);
        boolean hadPageBreak = Boolean.TRUE.equals(eff.pageBreakBefore);
        renderInlineChildren(p, in, paraR, fields, bidi);
        in.flush();

        String inner = in.out.toString();
        boolean empty = !in.hasContent && label == null;
        if (empty && skipIfEmpty) return true;

        int level = headingLevel(styleId, eff);
        String tag = level >= 0 ? "h" + Math.min(level + 1, 6) : "p";

        String css = paraCss(eff, bidi, explicitBidi, styleId);
        String cls = css.isEmpty() ? null : classFor(paraClasses, css, "p");

        if (hadPageBreak) out.append("<div class=\"pbk\"></div>");
        out.append('<').append(tag);
        if (cls != null) out.append(" class=\"").append(cls).append('"');
        out.append(" dir=\"").append(bidi ? "rtl" : "ltr").append("\">");
        if (empty) out.append("<br>");
        else out.append(inner);
        out.append("</").append(tag).append(">\n");
        return empty;
    }

    private int headingLevel(String styleId, PPr eff) {
        Style st = styleId == null ? null : styles.get(styleId);
        String nm = st != null && st.name != null ? st.name.toLowerCase(Locale.ROOT) : "";
        if (eff.outline != null && eff.outline >= 0 && eff.outline <= 8) return eff.outline;
        if (nm.startsWith("heading ")) {
            try {
                return Math.max(0, Integer.parseInt(nm.substring(8).trim()) - 1);
            } catch (NumberFormatException ignored) {
                return -1;
            }
        }
        if (nm.equals("title")) return 0;
        return -1;
    }

    private void appendLabel(Inline in, Label label, RPr paraR, PPr eff) {
        RPr r = paraR.copy();
        r.merge(label.lvl.rpr);
        r.fAscii = null;
        r.fCs = null;
        boolean cs = containsRtl(label.text);
        String cls = runClass(r, cs, cellDark);
        String txt = HtmlPage.esc(label.text);
        int hang = eff.hanging != null ? eff.hanging : 0;
        StringBuilder sb = new StringBuilder("<span class=\"lb");
        sb.append('"');
        if (hang > 0 && !"nothing".equals(label.lvl.suff)) {
            sb.append(" style=\"min-width:").append(fmt(hang / 20.0)).append("pt\"");
        }
        sb.append('>');
        if (cls != null) sb.append("<span class=\"").append(cls).append("\">").append(txt).append("</span>");
        else sb.append(txt);
        sb.append("</span>");
        if ("space".equals(label.lvl.suff)) sb.append(' ');
        in.raw(sb.toString());
        in.hasContent = true;
    }

    private String plainText(Element p) {
        StringBuilder sb = new StringBuilder();
        collectText(p, sb);
        return sb.toString();
    }

    private void collectText(Element e, StringBuilder sb) {
        for (Node n = e.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (!(n instanceof Element)) continue;
            Element c = (Element) n;
            String l = local(c);
            if (l.equals("t")) sb.append(c.getTextContent());
            else if (l.equals("pPr") || l.equals("rPr") || l.equals("del") || l.equals("drawing") || l.equals("pict")) continue;
            else collectText(c, sb);
        }
    }

    // ===================================================================== المحتوى المضمّن

    private final class Inline {
        final StringBuilder out = new StringBuilder();
        boolean hasContent;
        private StringBuilder curText;
        private String curCls;

        void text(String s, String cls) {
            if (s.isEmpty()) return;
            hasContent = true;
            if (curText != null && Objects.equals(cls, curCls)) {
                curText.append(s);
                return;
            }
            flush();
            curText = new StringBuilder(s);
            curCls = cls;
        }

        void raw(String html) {
            flush();
            out.append(html);
        }

        void flush() {
            if (curText == null) return;
            String t = HtmlPage.esc(curText.toString());
            if (curCls == null) out.append(t);
            else out.append("<span class=\"").append(curCls).append("\">").append(t).append("</span>");
            curText = null;
            curCls = null;
        }
    }

    private void renderInlineChildren(Element parent, Inline in, RPr paraR, Deque<Field> fields, boolean bidi) {
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (!(n instanceof Element)) continue;
            Element e = (Element) n;
            switch (local(e)) {
                case "r":
                    renderRun(e, in, paraR, fields, bidi);
                    break;
                case "hyperlink": {
                    String href = null;
                    String rid = attr(e, "id");
                    if (rid != null) {
                        OoxmlUtil.Rel r = rels.get(rid);
                        if (r != null && r.external) href = HtmlPage.safeHref(r.target);
                    }
                    String anchor = attr(e, "anchor");
                    if (href == null && anchor != null) href = "#bm_" + sanitizeId(anchor);
                    if (href != null) in.raw("<a href=\"" + HtmlPage.esc(href) + "\">");
                    renderInlineChildren(e, in, paraR, fields, bidi);
                    if (href != null) in.raw("</a>");
                    break;
                }
                case "fldSimple": {
                    String instr = attr(e, "instr");
                    String href = hyperlinkFromInstr(instr);
                    if (href != null) in.raw("<a href=\"" + HtmlPage.esc(href) + "\">");
                    renderInlineChildren(e, in, paraR, fields, bidi);
                    if (href != null) in.raw("</a>");
                    break;
                }
                case "sdt": {
                    Element content = child(e, "sdtContent");
                    if (content != null) renderInlineChildren(content, in, paraR, fields, bidi);
                    break;
                }
                case "ins": case "moveTo": case "smartTag": case "customXml": case "dir": case "bdo":
                    renderInlineChildren(e, in, paraR, fields, bidi);
                    break;
                case "bookmarkStart": {
                    String name = attr(e, "name");
                    if (name != null && !name.startsWith("_GoBack")) {
                        in.raw("<a id=\"bm_" + sanitizeId(name) + "\"></a>");
                    }
                    break;
                }
                case "oMath": case "oMathPara": {
                    List<Element> ts = new ArrayList<>();
                    descendants(e, "t", ts);
                    StringBuilder sb = new StringBuilder();
                    for (Element t : ts) sb.append(t.getTextContent());
                    if (sb.length() > 0) {
                        in.raw("<span class=\"math\" dir=\"ltr\">" + HtmlPage.esc(sb.toString()) + "</span>");
                        in.hasContent = true;
                    }
                    break;
                }
                case "AlternateContent": {
                    Element choice = child(e, "Choice");
                    Element pick = choice != null ? choice : child(e, "Fallback");
                    if (pick != null) renderInlineChildren(pick, in, paraR, fields, bidi);
                    break;
                }
                default: break; // pPr, proofErr, del, moveFrom, commentRange* ...
            }
        }
    }

    private static String sanitizeId(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_' || c == '-') sb.append(c);
            else sb.append('x').append(Integer.toHexString(c));
        }
        return sb.toString();
    }

    private static String hyperlinkFromInstr(String instr) {
        if (instr == null) return null;
        String t = instr.trim();
        if (!t.toUpperCase(Locale.ROOT).startsWith("HYPERLINK")) return null;
        int a = t.indexOf('"');
        if (a >= 0) {
            int b = t.indexOf('"', a + 1);
            if (b > a) return HtmlPage.safeHref(t.substring(a + 1, b));
        }
        return null;
    }

    private void renderRun(Element r, Inline in, RPr paraR, Deque<Field> fields, boolean bidi) {
        RPr rp = paraR.copy();
        Element rPrEl = child(r, "rPr");
        String rStyle = attr(child(rPrEl, "rStyle"), "val");
        if (rStyle != null) rp.merge(styleOnlyR(rStyle));
        rp.merge(parseRPr(rPrEl));
        if (Boolean.TRUE.equals(rp.vanish)) return;

        for (Node n = r.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (!(n instanceof Element)) continue;
            Element e = (Element) n;
            switch (local(e)) {
                case "t": emitText(e.getTextContent(), rp, in); break;
                case "tab": case "ptab": emitText("\t", rp, in); break;
                case "br": {
                    String type = attr(e, "type");
                    if ("page".equals(type)) in.raw("<span class=\"pbk\"></span>");
                    else if (!"column".equals(type)) in.raw("<br>");
                    in.hasContent = true;
                    break;
                }
                case "cr": in.raw("<br>"); in.hasContent = true; break;
                case "noBreakHyphen": emitText("\u2011", rp, in); break;
                case "softHyphen": emitText("\u00AD", rp, in); break;
                case "sym": {
                    String ch = attr(e, "char");
                    if (ch != null) {
                        try {
                            emitText(symChar(Integer.parseInt(ch, 16)), rp, in);
                        } catch (NumberFormatException ignored) { /* تجاهل */ }
                    }
                    break;
                }
                case "footnoteReference": noteRef(e, in, footnoteIds, "fn", "fr"); break;
                case "endnoteReference": noteRef(e, in, endnoteIds, "en", "er"); break;
                case "fldChar": {
                    String type = attr(e, "fldCharType");
                    if ("begin".equals(type)) {
                        fields.push(new Field());
                    } else if ("separate".equals(type)) {
                        Field f = fields.peek();
                        if (f != null) {
                            f.result = true;
                            String href = hyperlinkFromInstr(f.instr.toString());
                            if (href != null) {
                                in.raw("<a href=\"" + HtmlPage.esc(href) + "\">");
                                f.link = true;
                            }
                        }
                    } else if ("end".equals(type)) {
                        Field f = fields.poll();
                        if (f != null && f.link) in.raw("</a>");
                    }
                    break;
                }
                case "instrText": {
                    Field f = fields.peek();
                    if (f != null && !f.result) f.instr.append(e.getTextContent());
                    break;
                }
                case "drawing": renderDrawing(e, in); break;
                case "pict": case "object": renderPict(e, in); break;
                case "AlternateContent": {
                    Element choice = child(e, "Choice");
                    Element pick = choice != null ? choice : child(e, "Fallback");
                    if (pick != null) {
                        for (Element d : childEls(pick)) {
                            if (isEl(d, "drawing")) renderDrawing(d, in);
                            else if (isEl(d, "pict")) renderPict(d, in);
                        }
                    }
                    break;
                }
                default: break;
            }
        }
    }

    private static String symChar(int cp) {
        switch (cp) {
            case 0xF0B7: return "•";
            case 0xF0A7: return "▪";
            case 0xF0D8: return "➢";
            case 0xF0FC: return "✓";
            case 0xF0E0: return "→";
            default:
                if (cp >= 0xF000 && cp <= 0xF0FF) return "•";
                return new String(Character.toChars(cp));
        }
    }

    private void noteRef(Element e, Inline in, List<String> ids, String prefix, String backPrefix) {
        String id = attr(e, "id");
        if (id == null) return;
        int idx = ids.indexOf(id);
        if (idx < 0) {
            ids.add(id);
            idx = ids.size() - 1;
        }
        in.raw("<sup class=\"fnr\"><a id=\"" + backPrefix + HtmlPage.esc(sanitizeId(id)) + "\" href=\"#" + prefix
                + HtmlPage.esc(sanitizeId(id)) + "\">" + (idx + 1) + "</a></sup>");
        in.hasContent = true;
    }

    // --------------------------------------------------------------- النص والأنماط

    private void emitText(String text, RPr rp, Inline in) {
        if (text == null || text.isEmpty()) return;
        boolean rtlHint = Boolean.TRUE.equals(rp.rtl);
        if (!rtlHint && !containsRtl(text)) {
            in.text(text, runClass(rp, false, cellDark));
            return;
        }
        Boolean cur = null;
        StringBuilder seg = new StringBuilder();
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            Boolean t = strongType(cp);
            if (t == null) t = cur != null ? cur : Boolean.valueOf(rtlHint);
            if (cur != null && t.booleanValue() != cur.booleanValue()) {
                in.text(seg.toString(), runClass(rp, cur, cellDark));
                seg.setLength(0);
            }
            cur = t;
            seg.appendCodePoint(cp);
        }
        if (seg.length() > 0) in.text(seg.toString(), runClass(rp, cur != null && cur, cellDark));
    }

    /** TRUE = نص معقّد (عربي/عبري)، FALSE = لاتيني/غيره، null = محايد */
    private static Boolean strongType(int cp) {
        if (isRtlChar(cp)) return Boolean.TRUE;
        byte d = Character.getDirectionality(cp);
        if (d == Character.DIRECTIONALITY_ARABIC_NUMBER) return Boolean.TRUE;
        if (d == Character.DIRECTIONALITY_NONSPACING_MARK) return null;
        if (Character.isLetter(cp)) return Boolean.FALSE;
        return null;
    }

    private String runClass(RPr r, boolean cs, boolean darkBg) {
        String css = runCss(r, cs, darkBg);
        if (css.isEmpty()) return null;
        return classFor(runClasses, css, "r");
    }

    private static String classFor(LinkedHashMap<String, String> map, String css, String prefix) {
        String c = map.get(css);
        if (c == null) {
            c = prefix + map.size();
            map.put(css, c);
        }
        return c;
    }

    private String runCss(RPr r, boolean cs, boolean darkBg) {
        StringBuilder sb = new StringBuilder();
        boolean sup = "superscript".equals(r.vertAlign);
        boolean sub = "subscript".equals(r.vertAlign);
        Double size = cs ? (r.szCs != null ? r.szCs : r.sz) : r.sz;
        double eff = size != null ? size : defaultSize;
        if (sup || sub) eff *= 0.65;
        if (Math.abs(eff - defaultSize) > 0.01) sb.append("font-size:").append(fmt(eff)).append("pt;");

        Boolean bold = cs ? r.bCs : r.b;
        Boolean italic = cs ? r.iCs : r.i;
        if (Boolean.TRUE.equals(bold)) sb.append("font-weight:bold;");
        if (Boolean.TRUE.equals(italic)) sb.append("font-style:italic;");

        String color = r.color;
        if (color != null && !"auto".equalsIgnoreCase(color)) {
            if (!("#" + color).equalsIgnoreCase(defaultColor)) sb.append("color:#").append(color).append(';');
            else if (darkBg) sb.append("color:#").append(color).append(';');
        } else if (darkBg) {
            sb.append("color:#fff;");
        }

        String bg = null;
        if (r.highlight != null && !"none".equalsIgnoreCase(r.highlight)) bg = highlightHex(r.highlight);
        else if (r.shd != null) bg = "#" + r.shd;
        if (bg != null) sb.append("background:").append(bg).append(';');

        boolean under = r.u != null && !"none".equalsIgnoreCase(r.u);
        boolean strike = Boolean.TRUE.equals(r.strike) || Boolean.TRUE.equals(r.dstrike);
        if (under || strike) {
            sb.append("text-decoration:");
            if (under) sb.append("underline ");
            if (strike) sb.append("line-through");
            sb.append(';');
            if (under) {
                String u = r.u.toLowerCase(Locale.ROOT);
                if (u.contains("double")) sb.append("text-decoration-style:double;");
                else if (u.contains("dot")) sb.append("text-decoration-style:dotted;");
                else if (u.contains("dash")) sb.append("text-decoration-style:dashed;");
                else if (u.contains("wave")) sb.append("text-decoration-style:wavy;");
            }
        }
        if (Boolean.TRUE.equals(r.caps)) sb.append("text-transform:uppercase;");
        if (Boolean.TRUE.equals(r.smallCaps)) sb.append("font-variant:small-caps;");
        if (sup) sb.append("vertical-align:super;");
        else if (sub) sb.append("vertical-align:sub;");

        String font = cs ? r.fCs : r.fAscii;
        if (font != null) {
            String clean = font.replace("\"", "").replace(";", "").replace("'", "");
            if (!clean.isEmpty()) sb.append("font-family:\"").append(clean).append("\",").append(genericFor(clean)).append(';');
        }
        return sb.toString();
    }

    private static String genericFor(String font) {
        String f = font.toLowerCase(Locale.ROOT);
        if (f.contains("courier") || f.contains("consolas") || f.contains("mono") || f.contains("lucida console")
                || f.contains("menlo") || f.contains("monaco")) return "monospace";
        if (f.contains("times") || f.contains("cambria") || f.contains("georgia") || f.contains("garamond")
                || f.contains("palatino") || f.contains("book antiqua") || f.contains("sylfaen")
                || f.contains("traditional arabic") || f.contains("simplified arabic") || f.contains("arabic typesetting")
                || f.contains("amiri") || f.contains("scheherazade") || f.contains("naskh") || f.contains("serif")
                && !f.contains("sans")) return "serif";
        return "sans-serif";
    }

    private static String highlightHex(String name) {
        switch (name) {
            case "yellow": return "#FFFF00";
            case "green": return "#00FF00";
            case "cyan": return "#00FFFF";
            case "magenta": return "#FF00FF";
            case "blue": return "#0000FF";
            case "red": return "#FF0000";
            case "darkBlue": return "#000080";
            case "darkCyan": return "#008080";
            case "darkGreen": return "#008000";
            case "darkMagenta": return "#800080";
            case "darkRed": return "#800000";
            case "darkYellow": return "#808000";
            case "darkGray": return "#808080";
            case "lightGray": return "#C0C0C0";
            case "black": return "#000000";
            case "white": return "#FFFFFF";
            default: return null;
        }
    }

    private String paraCss(PPr p, boolean bidi, boolean explicitBidi, String styleId) {
        StringBuilder sb = new StringBuilder();
        String jc = p.jc;
        if (jc != null) {
            String align = null;
            switch (jc) {
                case "center": align = "center"; break;
                case "both": case "distribute": case "highKashida": case "mediumKashida":
                case "lowKashida": case "thaiDistribute": align = "justify"; break;
                case "left": case "start": align = explicitBidi ? "start" : "left"; break;
                case "right": case "end": align = explicitBidi ? "end" : "right"; break;
                default: break;
            }
            if (align != null) sb.append("text-align:").append(align).append(';');
        }
        if (p.before != null && p.before > 0) sb.append("margin-top:").append(fmt(p.before / 20.0)).append("pt;");
        if (p.after != null && p.after > 0) sb.append("margin-bottom:").append(fmt(p.after / 20.0)).append("pt;");
        if (p.line != null && p.line > 0) {
            if (p.lineRule == null || "auto".equals(p.lineRule)) {
                sb.append("line-height:").append(fmt(p.line / 240.0 * 1.2)).append(';');
            } else {
                sb.append("line-height:").append(fmt(p.line / 20.0)).append("pt;");
            }
        }
        if (p.start != null && p.start != 0) sb.append("margin-inline-start:").append(fmt(p.start / 20.0)).append("pt;");
        if (p.end != null && p.end != 0) sb.append("margin-inline-end:").append(fmt(p.end / 20.0)).append("pt;");
        if (p.hanging != null && p.hanging > 0) sb.append("text-indent:-").append(fmt(p.hanging / 20.0)).append("pt;");
        else if (p.firstLine != null && p.firstLine > 0) sb.append("text-indent:").append(fmt(p.firstLine / 20.0)).append("pt;");
        String[] sides = {"border-top", "border-inline-start", "border-bottom", "border-inline-end"};
        boolean anyBorder = false;
        for (int i = 0; i < 4; i++) {
            if (p.bdr[i] != null) {
                sb.append(sides[i]).append(':').append(p.bdr[i]).append(';');
                if (!"none".equals(p.bdr[i])) anyBorder = true;
            }
        }
        if (anyBorder) sb.append("padding:2px 4px;");
        if (p.shd != null) sb.append("background:#").append(p.shd).append(';');

        // حجم الخط الأساسي (يحدد ارتفاع الفقرات الفارغة)
        RPr base = docR.copy();
        base.merge(styleOnlyR(styleId));
        if (base.sz != null && Math.abs(base.sz - defaultSize) > 0.01) {
            sb.append("font-size:").append(fmt(base.sz)).append("pt;");
        }
        return sb.toString();
    }

    private static String fmt(double v) {
        if (Math.abs(v - Math.rint(v)) < 0.005) return String.valueOf((long) Math.rint(v));
        return String.format(Locale.ROOT, "%.2f", v);
    }

    // ===================================================================== الصور والرسومات

    private String imageFor(String relId) {
        if (relId == null) return null;
        String cached = imageCache.get(relId);
        if (cached != null) return cached.isEmpty() ? null : cached;
        OoxmlUtil.Rel r = rels.get(relId);
        String result = "";
        if (r != null && !r.external && r.target != null) {
            String path = OoxmlUtil.resolve(dirOf(mainPath), r.target);
            byte[] bytes = OoxmlUtil.read(zip, path, MAX_IMAGE);
            if (bytes != null && imageBytesTotal + bytes.length <= MAX_IMAGE_TOTAL) {
                String uri = OoxmlUtil.dataUri(bytes);
                if (uri != null) {
                    imageBytesTotal += bytes.length;
                    result = uri;
                }
            }
        }
        imageCache.put(relId, result);
        return result.isEmpty() ? null : result;
    }

    private void renderDrawing(Element drawing, Inline in) {
        Element docPr = descendant(drawing, "docPr");
        String alt = docPr != null ? attr(docPr, "descr") : null;
        if (alt == null) alt = "";

        List<Element> blips = new ArrayList<>();
        descendants(drawing, "blip", blips);
        Element extent = descendant(drawing, "extent");
        double wpx = 0, hpx = 0;
        if (extent != null) {
            Double cx = OoxmlUtil.dblAttr(extent, "cx");
            Double cy = OoxmlUtil.dblAttr(extent, "cy");
            if (cx != null) wpx = cx / 9525.0;
            if (cy != null) hpx = cy / 9525.0;
        }
        for (Element blip : blips) {
            String rid = attr(blip, "embed");
            String uri = imageFor(rid);
            Node parent = blip.getParentNode();
            double w = wpx, h = hpx;
            Element srcRect = null;
            if (parent instanceof Element) {
                srcRect = child((Element) parent, "srcRect");
                Node pic = parent.getParentNode();
                if (pic instanceof Element) {
                    Element ext = descendant(child((Element) pic, "spPr"), "ext");
                    if (ext != null) {
                        Double cx = OoxmlUtil.dblAttr(ext, "cx");
                        Double cy = OoxmlUtil.dblAttr(ext, "cy");
                        if (cx != null && cx > 0) w = cx / 9525.0;
                        if (cy != null && cy > 0) h = cy / 9525.0;
                    }
                }
            }
            if (uri == null) {
                in.raw("<span class=\"ph\">▣</span>");
                in.hasContent = true;
                continue;
            }
            in.raw(imageTag(uri, alt, w, h, srcRect));
            in.hasContent = true;
        }
        if (blips.isEmpty() && descendant(drawing, "chart") != null) {
            in.raw("<span class=\"ph\">[مخطط]</span>");
            in.hasContent = true;
        }
        List<Element> boxes = new ArrayList<>();
        descendants(drawing, "txbxContent", boxes);
        for (Element box : boxes) renderTextBox(box, in);
    }

    private String imageTag(String uri, String alt, double widthPx, double heightPx, Element srcRect) {
        String w = widthPx > 0 ? fmt(widthPx) : null;
        if (srcRect != null && widthPx > 0 && heightPx > 0) {
            double l = pct(srcRect, "l"), t = pct(srcRect, "t"), r = pct(srcRect, "r"), b = pct(srcRect, "b");
            double vw = 1 - l - r, vh = 1 - t - b;
            if (vw > 0.05 && vh > 0.05 && (l != 0 || t != 0 || r != 0 || b != 0)) {
                // إطار بحجم الجزء الظاهر، والصورة الكاملة مُزاحة داخله لمحاكاة القص
                double fullW = widthPx / vw, fullH = heightPx / vh;
                return "<span class=\"crop\" style=\"width:" + fmt(widthPx) + "px;height:" + fmt(heightPx) + "px\">"
                        + "<img src=\"" + uri + "\" alt=\"" + HtmlPage.esc(alt) + "\" style=\"width:" + fmt(fullW)
                        + "px;height:" + fmt(fullH) + "px;left:" + fmt(-l * fullW) + "px;top:" + fmt(-t * fullH) + "px\"></span>";
            }
        }
        return "<img src=\"" + uri + "\" alt=\"" + HtmlPage.esc(alt) + "\""
                + (w != null ? " style=\"width:" + w + "px\"" : "") + ">";
    }

    private static double pct(Element e, String name) {
        Double v = OoxmlUtil.dblAttr(e, name);
        return v == null ? 0 : v / 100000.0;
    }

    private void renderPict(Element pict, Inline in) {
        List<Element> imgs = new ArrayList<>();
        descendants(pict, "imagedata", imgs);
        Element shape = descendant(pict, "shape");
        double w = 0;
        if (shape != null) {
            String style = attr(shape, "style");
            w = cssLengthPx(style, "width");
        }
        for (Element im : imgs) {
            String uri = imageFor(attr(im, "id"));
            if (uri != null) {
                in.raw(imageTag(uri, "", w, 0, null));
                in.hasContent = true;
            }
        }
        List<Element> boxes = new ArrayList<>();
        descendants(pict, "txbxContent", boxes);
        for (Element box : boxes) renderTextBox(box, in);
    }

    private static double cssLengthPx(String style, String prop) {
        if (style == null) return 0;
        for (String part : style.split(";")) {
            int i = part.indexOf(':');
            if (i < 0) continue;
            if (!part.substring(0, i).trim().equalsIgnoreCase(prop)) continue;
            String v = part.substring(i + 1).trim().toLowerCase(Locale.ROOT);
            try {
                if (v.endsWith("pt")) return Double.parseDouble(v.substring(0, v.length() - 2)) * 1.333;
                if (v.endsWith("px")) return Double.parseDouble(v.substring(0, v.length() - 2));
                if (v.endsWith("in")) return Double.parseDouble(v.substring(0, v.length() - 2)) * 96;
                if (v.endsWith("cm")) return Double.parseDouble(v.substring(0, v.length() - 2)) * 37.8;
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    private void renderTextBox(Element box, Inline in) {
        StringBuilder sb = new StringBuilder();
        renderBlocks(box, sb);
        if (sb.length() == 0) return;
        boolean rtl = Boolean.TRUE.equals(firstStrongIsRtl(plainText(box)));
        in.raw("<div class=\"tbx\" dir=\"" + (rtl ? "rtl" : "ltr") + "\">" + sb + "</div>");
        in.hasContent = true;
    }

    // ===================================================================== الجداول

    private static final class CellInfo {
        Element tc;
        int col, span = 1, rowspan = 1;
        boolean skip;
        int gapBefore;
    }

    private void renderTable(Element tbl, StringBuilder out) {
        Element tblPr = child(tbl, "tblPr");
        String styleId = attr(child(tblPr, "tblStyle"), "val");
        TStyle ts = tableStyle(styleId);

        String[] bdr = ts.bdr.clone();
        parseBordersInto(child(tblPr, "tblBorders"), bdr);
        int[] mar = ts.mar.clone();
        Element marEl = child(tblPr, "tblCellMar");
        if (marEl != null) applyCellMar(marEl, mar);
        if (mar[0] < 0) mar[0] = 0;
        if (mar[2] < 0) mar[2] = 0;
        if (mar[1] < 0) mar[1] = 108;
        if (mar[3] < 0) mar[3] = 108;

        Element bv = child(tblPr, "bidiVisual");
        boolean rtl = bv != null && flag(bv);
        if (rtl) sawRtl = true;

        // خيارات المظهر
        boolean firstRow = true, lastRow = false, firstCol = true, lastCol = false, noH = false, noV = true;
        Element look = child(tblPr, "tblLook");
        if (look != null) {
            String val = attr(look, "val");
            if (val != null) {
                try {
                    int m = Integer.parseInt(val.trim(), 16);
                    firstRow = (m & 0x20) != 0;
                    lastRow = (m & 0x40) != 0;
                    firstCol = (m & 0x80) != 0;
                    lastCol = (m & 0x100) != 0;
                    noH = (m & 0x200) != 0;
                    noV = (m & 0x400) != 0;
                } catch (NumberFormatException ignored) { /* تجاهل */ }
            } else {
                firstRow = "1".equals(attr(look, "firstRow"));
                lastRow = "1".equals(attr(look, "lastRow"));
                firstCol = "1".equals(attr(look, "firstColumn"));
                lastCol = "1".equals(attr(look, "lastColumn"));
                noH = "1".equals(attr(look, "noHBand"));
                noV = "1".equals(attr(look, "noVBand"));
            }
        }

        // الشبكة
        List<Integer> grid = new ArrayList<>();
        Element tg = child(tbl, "tblGrid");
        if (tg != null) {
            for (Element gc : childEls(tg, "gridCol")) {
                Integer w = intAttr(gc, "w");
                grid.add(w == null ? 0 : w);
            }
        }

        // الصفوف والخلايا مع الدمج
        List<List<CellInfo>> rows = new ArrayList<>();
        List<Element> trEls = new ArrayList<>();
        List<CellInfo> open = new ArrayList<>();
        int maxCols = grid.size();
        for (Element tr : childEls(tbl, "tr")) {
            trEls.add(tr);
            List<CellInfo> row = new ArrayList<>();
            int col = 0;
            Element trPr = child(tr, "trPr");
            Integer gb = intAttr(child(trPr, "gridBefore"), "val");
            if (gb != null && gb > 0) {
                CellInfo gap = new CellInfo();
                gap.col = 0;
                gap.span = gb;
                gap.gapBefore = gb;
                row.add(gap);
                col += gb;
            }
            List<Element> tcs = new ArrayList<>();
            for (Element c : childEls(tr)) {
                if (isEl(c, "tc")) tcs.add(c);
                else if (isEl(c, "sdt")) {
                    Element content = child(c, "sdtContent");
                    if (content != null) tcs.addAll(childEls(content, "tc"));
                }
            }
            for (Element tc : tcs) {
                Element tcPr = child(tc, "tcPr");
                CellInfo ci = new CellInfo();
                ci.tc = tc;
                ci.col = col;
                Integer gs = intAttr(child(tcPr, "gridSpan"), "val");
                ci.span = gs != null && gs > 0 ? gs : 1;
                Element vm = child(tcPr, "vMerge");
                while (open.size() < col + ci.span + 1) open.add(null);
                if (vm != null) {
                    String v = attr(vm, "val");
                    if ("restart".equals(v)) {
                        open.set(col, ci);
                    } else {
                        CellInfo origin = open.get(col);
                        if (origin != null) {
                            origin.rowspan++;
                            ci.skip = true;
                        } else {
                            open.set(col, ci);
                        }
                    }
                } else {
                    open.set(col, null);
                }
                row.add(ci);
                col += ci.span;
            }
            maxCols = Math.max(maxCols, col);
            rows.add(row);
        }
        if (rows.isEmpty()) return;

        // عرض الجدول
        String widthCss = "width:100%;";
        int totalGrid = 0;
        for (int w : grid) totalGrid += w;
        Element tw = child(tblPr, "tblW");
        if (tw != null) {
            String type = attr(tw, "type");
            Integer w = intAttr(tw, "w");
            if ("pct".equals(type) && w != null && w > 0) widthCss = "width:" + fmt(Math.min(100, w / 50.0)) + "%;";
            else if ("dxa".equals(type) && w != null && w > 0) widthCss = "width:100%;max-width:" + fmt(w / 15.0) + "px;";
            else if (totalGrid > 0) widthCss = "width:100%;max-width:" + fmt(totalGrid / 15.0) + "px;";
        } else if (totalGrid > 0) {
            widthCss = "width:100%;max-width:" + fmt(totalGrid / 15.0) + "px;";
        }
        String jc = attr(child(tblPr, "jc"), "val");
        String alignCss = "";
        if ("center".equals(jc)) alignCss = "margin-inline:auto;";
        else if ("right".equals(jc) || "end".equals(jc)) alignCss = "margin-inline-start:auto;";
        Integer tind = intAttr(child(tblPr, "tblInd"), "w");
        if (tind != null && tind > 0 && alignCss.isEmpty()) alignCss = "margin-inline-start:" + fmt(tind / 20.0) + "pt;";

        out.append("<div class=\"tw\"><table class=\"t\" dir=\"").append(rtl ? "rtl" : "ltr")
                .append("\" style=\"").append(widthCss).append(alignCss).append("\">");
        if (!grid.isEmpty() && totalGrid > 0) {
            out.append("<colgroup>");
            for (int w : grid) out.append("<col style=\"width:").append(fmt(w * 100.0 / totalGrid)).append("%\">");
            out.append("</colgroup>");
        }

        int nrows = rows.size();
        for (int r = 0; r < nrows; r++) {
            List<CellInfo> row = rows.get(r);
            Element trPr = child(trEls.get(r), "trPr");
            Integer th = intAttr(child(trPr, "trHeight"), "val");
            out.append("<tr");
            if (th != null && th > 0) out.append(" style=\"height:").append(fmt(th / 20.0)).append("pt\"");
            out.append('>');
            for (CellInfo ci : row) {
                if (ci.skip) continue;
                if (ci.tc == null) {
                    out.append("<td colspan=\"").append(ci.span).append("\"></td>");
                    continue;
                }
                renderCell(ci, r, nrows, maxCols, ts, bdr, mar, rtl, firstRow, lastRow, firstCol, lastCol, noH, noV, out);
            }
            out.append("</tr>");
        }
        out.append("</table></div>\n");
    }

    private void renderCell(CellInfo ci, int r, int nrows, int ncols, TStyle ts, String[] bdr, int[] mar, boolean rtl,
                            boolean firstRow, boolean lastRow, boolean firstCol, boolean lastCol,
                            boolean noH, boolean noV, StringBuilder out) {
        Element tcPr = child(ci.tc, "tcPr");
        int c = ci.col;
        boolean isFirstRow = firstRow && r == 0;
        boolean isLastRow = lastRow && r == nrows - 1;
        boolean isFirstCol = firstCol && c == 0;
        boolean isLastCol = lastCol && c + ci.span >= ncols;

        // الأنماط الشرطية بترتيب الأولوية
        List<Cond> conds = new ArrayList<>();
        addCond(conds, ts, "wholeTable");
        if (!noH && !isFirstRow && !isLastRow) {
            int hidx = r - (firstRow ? 1 : 0);
            addCond(conds, ts, hidx % 2 == 0 ? "band1Horz" : "band2Horz");
        }
        if (!noV && !isFirstCol && !isLastCol) {
            int vidx = c - (firstCol ? 1 : 0);
            addCond(conds, ts, vidx % 2 == 0 ? "band1Vert" : "band2Vert");
        }
        if (isFirstCol) addCond(conds, ts, "firstCol");
        if (isLastCol) addCond(conds, ts, "lastCol");
        if (isFirstRow) addCond(conds, ts, "firstRow");
        if (isLastRow) addCond(conds, ts, "lastRow");
        if (isFirstRow && isFirstCol) addCond(conds, ts, "nwCell");
        if (isFirstRow && isLastCol) addCond(conds, ts, "neCell");
        if (isLastRow && isFirstCol) addCond(conds, ts, "swCell");
        if (isLastRow && isLastCol) addCond(conds, ts, "seCell");

        String fill = null;
        RPr cr = new RPr();
        String[] condBdr = new String[6];
        for (Cond cd : conds) {
            if (cd.shd != null) fill = cd.shd;
            cr.merge(cd.rpr);
            for (int k = 0; k < 6; k++) if (cd.bdr[k] != null) condBdr[k] = cd.bdr[k];
        }
        // خصائص الخلية المباشرة
        String[] cellBdr = new String[6];
        if (tcPr != null) {
            String f = attr(child(tcPr, "shd"), "fill");
            if (f != null) fill = f.equalsIgnoreCase("auto") ? null : f;
            parseBordersInto(child(tcPr, "tcBorders"), cellBdr);
        }

        // حدود الأضلاع: [top, start, bottom, end]
        boolean atTop = r == 0, atBottom = r + ci.rowspan >= nrows;
        boolean atStart = c == 0, atEnd = c + ci.span >= ncols;
        String top = pick(cellBdr[0], condBdr[0], atTop ? bdr[0] : bdr[4]);
        String bottom = pick(cellBdr[2], condBdr[2], atBottom ? bdr[2] : bdr[4]);
        String start = pick(cellBdr[1], condBdr[1], atStart ? bdr[1] : bdr[5]);
        String end = pick(cellBdr[3], condBdr[3], atEnd ? bdr[3] : bdr[5]);

        StringBuilder css = new StringBuilder();
        if (top != null) css.append("border-top:").append(top).append(';');
        if (bottom != null) css.append("border-bottom:").append(bottom).append(';');
        if (start != null) css.append("border-inline-start:").append(start).append(';');
        if (end != null) css.append("border-inline-end:").append(end).append(';');
        css.append("padding:").append(fmt(mar[0] / 20.0 + 1)).append("pt ").append(fmt(mar[3] / 20.0))
                .append("pt ").append(fmt(mar[2] / 20.0 + 1)).append("pt ").append(fmt(mar[1] / 20.0)).append("pt;");
        if (fill != null) css.append("background:#").append(fill).append(';');
        String va = attr(child(tcPr, "vAlign"), "val");
        if ("center".equals(va)) css.append("vertical-align:middle;");
        else if ("bottom".equals(va)) css.append("vertical-align:bottom;");
        Element nw = child(tcPr, "noWrap");
        if (nw != null && flag(nw)) css.append("white-space:nowrap;");
        String cls = classFor(cellClasses, css.toString(), "c");

        out.append("<td class=\"").append(cls).append('"');
        if (ci.span > 1) out.append(" colspan=\"").append(ci.span).append('"');
        if (ci.rowspan > 1) out.append(" rowspan=\"").append(ci.rowspan).append('"');
        out.append('>');

        RPr savedR = cellR;
        boolean savedDark = cellDark;
        RPr merged = ts.rpr.copy();
        merged.merge(cr);
        cellR = merged;
        cellDark = fill != null && OoxmlUtil.luminance("#" + fill) < 0.45;
        StringBuilder inner = new StringBuilder();
        renderBlocks(ci.tc, inner);
        cellR = savedR;
        cellDark = savedDark;

        if (inner.length() == 0) inner.append("<p></p>");
        out.append(inner).append("</td>");
    }

    private static void addCond(List<Cond> list, TStyle ts, String key) {
        Cond c = ts.cond.get(key);
        if (c != null) list.add(c);
    }

    private static String pick(String... options) {
        for (String o : options) {
            if (o != null) return "none".equals(o) ? null : o;
        }
        return null;
    }

    // ===================================================================== الحواشي

    private void renderNotes(StringBuilder out) {
        renderNoteList(out, footnoteIds, footnotes, "fn", "fr", sawRtl ? "الحواشي" : "Footnotes");
        renderNoteList(out, endnoteIds, endnotes, "en", "er", sawRtl ? "الحواشي الختامية" : "Endnotes");
    }

    private void renderNoteList(StringBuilder out, List<String> ids, Map<String, Element> notes,
                                String prefix, String backPrefix, String title) {
        if (ids.isEmpty()) return;
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < ids.size(); i++) {
            String id = ids.get(i);
            Element note = notes.get(id);
            if (note == null) continue;
            StringBuilder inner = new StringBuilder();
            renderBlocks(note, inner);
            boolean rtl = Boolean.TRUE.equals(firstStrongIsRtl(plainText(note)));
            String sid = HtmlPage.esc(sanitizeId(id));
            body.append("<div class=\"fnb\" dir=\"").append(rtl ? "rtl" : "ltr").append("\" id=\"").append(prefix).append(sid)
                    .append("\"><a class=\"fnn\" href=\"#").append(backPrefix).append(sid).append("\">").append(i + 1)
                    .append("</a><div class=\"fnc\">").append(inner).append("</div></div>");
        }
        if (body.length() > 0) {
            out.append("<hr class=\"fnsep\"><div class=\"fntitle\">").append(HtmlPage.esc(title)).append("</div>").append(body);
        }
    }

    // ===================================================================== CSS

    private String buildCss() {
        StringBuilder css = new StringBuilder(8192);
        css.append(".doc{font-size:").append(fmt(defaultSize)).append("pt;color:").append(defaultColor)
                .append(";font-family:").append(defaultGeneric.equals("serif") ? "\"Noto Naskh Arabic\",\"Times New Roman\",serif"
                        : defaultGeneric.equals("monospace") ? "monospace"
                        : "\"Segoe UI\",Roboto,\"Noto Sans Arabic\",Arial,sans-serif")
                .append(";line-height:1.5;tab-size:6}")
                .append(".doc p,.doc h1,.doc h2,.doc h3,.doc h4,.doc h5,.doc h6{margin:0;white-space:pre-wrap;")
                .append("font-size:inherit;font-weight:normal;overflow-wrap:anywhere}")
                .append(".doc .tw{overflow-x:auto;max-width:100%;margin:4px 0}")
                .append(".doc table.t{border-collapse:collapse;table-layout:auto}")
                .append(".doc td{vertical-align:top;overflow-wrap:anywhere}")
                .append(".doc td>:first-child{margin-top:0!important}.doc td>:last-child{margin-bottom:0!important}")
                .append(".doc .pbk{display:block;height:0;border-top:1px dashed #b5b5b5;margin:16px 0}")
                .append(".doc .lb{display:inline-block;text-indent:0}")
                .append(".doc .tbx{display:block;border:1px solid #9a9a9a;border-radius:4px;padding:6px 10px;margin:6px 0;background:#fafafa}")
                .append(".doc .ph{display:inline-block;padding:2px 8px;border:1px dashed #aaa;border-radius:4px;color:#888;font-size:12px}")
                .append(".doc .crop{display:inline-block;position:relative;overflow:hidden;vertical-align:bottom}")
                .append(".doc .crop img{position:absolute;max-width:none}")
                .append(".doc img{max-width:100%;height:auto}")
                .append(".doc .math{font-family:\"Cambria Math\",serif;font-style:italic}")
                .append(".doc sup.fnr{font-size:.75em;line-height:0}.doc sup.fnr a{text-decoration:none}")
                .append(".doc .fnsep{border:0;border-top:1px solid #ccc;margin:24px 0 8px;width:35%}")
                .append(".doc .fntitle{font-weight:bold;font-size:.9em;margin-bottom:6px;color:#555}")
                .append(".doc .fnb{display:flex;gap:8px;font-size:.9em;margin:4px 0}")
                .append(".doc .fnn{flex:none;text-decoration:none;font-weight:bold;min-width:1.4em}")
                .append(".doc .fnc{flex:1;min-width:0}");
        for (Map.Entry<String, String> e : runClasses.entrySet()) {
            css.append(".doc .").append(e.getValue()).append('{').append(e.getKey()).append('}');
        }
        for (Map.Entry<String, String> e : paraClasses.entrySet()) {
            css.append(".doc .").append(e.getValue()).append('{').append(e.getKey()).append('}');
        }
        for (Map.Entry<String, String> e : cellClasses.entrySet()) {
            css.append(".doc .").append(e.getValue()).append('{').append(e.getKey()).append('}');
        }
        return css.toString();
    }
}
