package com.docreader.app.viewer;

import org.w3c.dom.Document;
import org.w3c.dom.Element;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipFile;

/**
 * قارئ PowerPoint حقيقي: يحوّل كل شريحة إلى HTML مع مواضع العناصر الفعلية، وراثة
 * أنماط النص من القالب الرئيسي والتخطيط، الفقاعات/الترقيم، الجداول، الصور، الألوان
 * والحدود، مع دعم المجموعات المتداخلة والاتجاه من اليمين لليسار.
 * يُعاد رسم كل الشريحة عبر وحدات vw مبنية على عرض الشريحة الأصلي، فتتحجّم الخطوط
 * والمواضع معاً تلقائياً دون الحاجة إلى JavaScript.
 */
public final class PptxHtmlRenderer {

    private static final int MAX_SMALL_XML = 24 * 1024 * 1024;
    private static final int MAX_IMAGE = 12 * 1024 * 1024;
    private static final int MAX_IMAGE_TOTAL = 30 * 1024 * 1024;
    private static final int MAX_SLIDES = 400;
    private static final double EMU_PER_PT = 12700.0;

    private static final String[] THEME_ORDER = {"lt1", "dk1", "lt2", "dk2", "accent1", "accent2", "accent3", "accent4", "accent5", "accent6", "hlink", "folHlink"};
    private static final String[] THEME_DEFAULT = {"#FFFFFF", "#000000", "#EEECE1", "#1F497D", "#4472C4", "#ED7D31", "#A5A5A5", "#FFC000", "#5B9BD5", "#70AD47", "#0563C1", "#954F72"};

    // ------------------------------------------------------------------ نماذج البيانات

    private static final class ThemeInfo {
        String[] colors = THEME_DEFAULT.clone();
        String majorFont, minorFont;
    }

    private static final class TextLvl {
        Double sizePt;
        Boolean bold, italic;
        String color;
        String font;
        Character buChar;
        String buFont;
        String buAutoNumType;
        boolean buNone;
        boolean buSet;
        String algn;
        double marLEmu = -1, indentEmu = 0;
    }

    private static final class TxStyles {
        TextLvl[] title = fresh();
        TextLvl[] body = fresh();
        TextLvl[] other = fresh();

        static TextLvl[] fresh() {
            TextLvl[] a = new TextLvl[9];
            for (int i = 0; i < 9; i++) a[i] = new TextLvl();
            return a;
        }
    }

    private static final class PhBox {
        double x, y, cx, cy;
        boolean has;
    }

    private static final class MasterInfo {
        ThemeInfo theme = new ThemeInfo();
        Map<String, String> clrMap = new HashMap<>();
        TxStyles styles = new TxStyles();
        Map<String, PhBox> phBox = new HashMap<>();
        String bgCss;
        String path;
    }

    private static final class LayoutInfo {
        MasterInfo master;
        Map<String, PhBox> phBox = new HashMap<>();
        String bgCss;
        String path;
    }

    private static final class Xfrm {
        double x, y, cx, cy, rotDeg;
        boolean flipH, flipV;
        boolean has;
    }

    private static final class Run {
        String text;
        boolean bold, italic, underline, strike;
        Double sizePt;
        String color;
        String font;
        boolean sub, sup;
        String linkHref;
    }

    private static final class Para {
        List<Run> runs = new ArrayList<>();
        int level;
        String algn;
        Character buChar;
        String buFont;
        String buAutoNumType;
        boolean buNone;
        double marLEmu = -1;
        boolean rtl;
    }

    // ------------------------------------------------------------------ الحالة

    private final ZipFile zip;
    private double slideWEmu = 12192000, slideHEmu = 6858000;
    private final Map<String, MasterInfo> masterCache = new HashMap<>();
    private final Map<String, LayoutInfo> layoutCache = new HashMap<>();
    private final Map<String, String> classes = new LinkedHashMap<>();
    private int imageBytes;

    private PptxHtmlRenderer(ZipFile zip) { this.zip = zip; }

    // ------------------------------------------------------------------ الدخول

    public static String render(File file) throws Exception {
        try (ZipFile zip = new ZipFile(file)) {
            PptxHtmlRenderer r = new PptxHtmlRenderer(zip);
            return r.build();
        }
    }

    private static String findMain(ZipFile zip) {
        Map<String, OoxmlUtil.Rel> rels = OoxmlUtil.readRels(zip, "");
        for (OoxmlUtil.Rel r : rels.values()) {
            if ("officeDocument".equals(OoxmlUtil.relType(r)) && r.target != null) {
                String p = OoxmlUtil.resolve("", r.target);
                if (OoxmlUtil.has(zip, p)) return p;
            }
        }
        return "ppt/presentation.xml";
    }

    private String build() throws Exception {
        String mainPath = findMain(zip);
        Document pres = OoxmlUtil.parsePart(zip, mainPath, MAX_SMALL_XML);
        if (pres == null) throw new IllegalStateException("presentation.xml missing");
        Element root = pres.getDocumentElement();
        Element sldSz = OoxmlUtil.child(root, "sldSz");
        if (sldSz != null) {
            Double cx = OoxmlUtil.dblAttr(sldSz, "cx"), cy = OoxmlUtil.dblAttr(sldSz, "cy");
            if (cx != null && cx > 0) slideWEmu = cx;
            if (cy != null && cy > 0) slideHEmu = cy;
        }
        Map<String, OoxmlUtil.Rel> rels = OoxmlUtil.readRels(zip, mainPath);
        String dir = OoxmlUtil.dirOf(mainPath);
        List<String> slidePaths = new ArrayList<>();
        Element sldIdLst = OoxmlUtil.child(root, "sldIdLst");
        if (sldIdLst != null) {
            for (Element sid : OoxmlUtil.childEls(sldIdLst, "sldId")) {
                // ملاحظة: sldId يحمل سمتَي id (رقم) و r:id (معرّف العلاقة) معاً، لذا يجب
                // قراءة "r:id" حرفياً كي لا يلتقط attr() السمة "id" الرقمية خطأً.
                String rid = OoxmlUtil.attr(sid, "r:id");
                if (rid == null) rid = OoxmlUtil.attr(sid, "id");
                OoxmlUtil.Rel r = rels.get(rid);
                if (r != null && r.target != null && !r.external) slidePaths.add(OoxmlUtil.resolve(dir, r.target));
            }
        }
        if (slidePaths.isEmpty()) return HtmlPage.errorFragment("لا توجد شرائح قابلة للعرض في هذا الملف");

        boolean truncated = slidePaths.size() > MAX_SLIDES;
        int n = Math.min(slidePaths.size(), MAX_SLIDES);

        StringBuilder pages = new StringBuilder(1 << 16);
        for (int i = 0; i < n; i++) {
            String frag;
            try {
                frag = renderSlide(slidePaths.get(i), i + 1, n);
            } catch (Exception e) {
                frag = "<div class=\"s-msg\">تعذّر عرض هذه الشريحة</div>";
            }
            pages.append("<div class=\"slide-page\"><div class=\"slide\">").append(frag).append("</div></div>");
        }

        double ratio = slideHEmu / slideWEmu;
        StringBuilder out = new StringBuilder(pages.length() + 4096);
        out.append("<style>").append(css(ratio)).append("</style>");
        out.append("<div class=\"ppt\"><div class=\"ppt-scroll\">").append(pages).append("</div>");
        out.append("<div class=\"ppt-count\">1 / ").append(n).append("</div></div>");
        if (truncated) out.append("<div class=\"note\">تم عرض أول ").append(MAX_SLIDES).append(" شريحة فقط من أصل ").append(slidePaths.size()).append(".</div>");
        return out.toString();
    }

    // ------------------------------------------------------------------ الثيم وخريطة الألوان

    private ThemeInfo parseTheme(String path) {
        ThemeInfo t = new ThemeInfo();
        Document d = OoxmlUtil.parsePart(zip, path, MAX_SMALL_XML);
        if (d == null) return t;
        Element scheme = OoxmlUtil.descendant(d.getDocumentElement(), "clrScheme");
        if (scheme != null) {
            Map<String, String> m = new HashMap<>();
            for (Element c : OoxmlUtil.childEls(scheme)) {
                String name = OoxmlUtil.local(c);
                Element clr = OoxmlUtil.childEls(c).isEmpty() ? null : OoxmlUtil.childEls(c).get(0);
                if (clr == null) continue;
                String v = OoxmlUtil.local(clr).equals("sysClr") ? OoxmlUtil.attr(clr, "lastClr") : OoxmlUtil.attr(clr, "val");
                if (v != null && v.matches("[0-9A-Fa-f]{6}")) m.put(name, "#" + v.toUpperCase(Locale.ROOT));
            }
            for (int i = 0; i < THEME_ORDER.length; i++) {
                String v = m.get(THEME_ORDER[i]);
                if (v != null) t.colors[i] = v;
            }
        }
        Element fontScheme = OoxmlUtil.descendant(d.getDocumentElement(), "fontScheme");
        if (fontScheme != null) {
            Element major = OoxmlUtil.child(fontScheme, "majorFont");
            Element minor = OoxmlUtil.child(fontScheme, "minorFont");
            if (major != null) { Element f = OoxmlUtil.child(major, "latin"); if (f != null) t.majorFont = OoxmlUtil.attr(f, "typeface"); }
            if (minor != null) { Element f = OoxmlUtil.child(minor, "latin"); if (f != null) t.minorFont = OoxmlUtil.attr(f, "typeface"); }
        }
        return t;
    }

    private static Map<String, String> defaultClrMap() {
        Map<String, String> m = new HashMap<>();
        m.put("bg1", "lt1"); m.put("tx1", "dk1"); m.put("bg2", "lt2"); m.put("tx2", "dk2");
        for (String a : new String[]{"accent1", "accent2", "accent3", "accent4", "accent5", "accent6", "hlink", "folHlink"}) m.put(a, a);
        return m;
    }

    private static Map<String, String> clrMapFrom(Element clrMapEl) {
        Map<String, String> m = defaultClrMap();
        if (clrMapEl == null) return m;
        for (String k : m.keySet().toArray(new String[0])) {
            String v = OoxmlUtil.attr(clrMapEl, k);
            if (v != null) m.put(k, v);
        }
        return m;
    }

    private static final Map<String, Integer> THEME_IDX = new HashMap<>();
    static { for (int i = 0; i < THEME_ORDER.length; i++) THEME_IDX.put(THEME_ORDER[i], i); }

    /** يحوّل لوناً DrawingML (srgbClr/schemeClr مع lumMod/lumOff/shade/tint/alpha) إلى CSS. */
    private String colorCss(Element colorParent, ThemeInfo theme, Map<String, String> clrMap) {
        if (colorParent == null) return null;
        Element c = null;
        for (Element x : OoxmlUtil.childEls(colorParent)) {
            String ln = OoxmlUtil.local(x);
            if (ln.equals("srgbClr") || ln.equals("schemeClr") || ln.equals("sysClr") || ln.equals("prstClr")) { c = x; break; }
        }
        if (c == null) return null;
        String base;
        String ln = OoxmlUtil.local(c);
        if (ln.equals("srgbClr")) {
            String v = OoxmlUtil.attr(c, "val");
            base = v != null && v.matches("[0-9A-Fa-f]{6}") ? "#" + v.toUpperCase(Locale.ROOT) : null;
        } else if (ln.equals("sysClr")) {
            String v = OoxmlUtil.attr(c, "lastClr");
            base = v != null && v.matches("[0-9A-Fa-f]{6}") ? "#" + v.toUpperCase(Locale.ROOT) : "#000000";
        } else if (ln.equals("prstClr")) {
            base = presetColor(OoxmlUtil.attr(c, "val"));
        } else {
            String name = OoxmlUtil.attr(c, "val");
            if (name == null) return null;
            if (name.equals("phClr")) return null;
            String mapped = clrMap.getOrDefault(name, name);
            Integer idx = THEME_IDX.get(mapped);
            base = idx != null ? theme.colors[idx] : null;
        }
        if (base == null) return null;
        for (Element mod : OoxmlUtil.childEls(c)) {
            String mn = OoxmlUtil.local(mod);
            Double v = OoxmlUtil.dblAttr(mod, "val");
            if (v == null) continue;
            double frac = v / 100000.0;
            switch (mn) {
                case "lumMod": base = OoxmlUtil.adjustLum(base, frac, 0); break;
                case "lumOff": base = OoxmlUtil.adjustLum(base, 1, frac); break;
                case "shade": base = OoxmlUtil.mix(base, "#000000", 1 - frac); break;
                case "tint": base = OoxmlUtil.mix(base, "#FFFFFF", 1 - frac); break;
                default: break;
            }
        }
        Element alphaEl = null;
        for (Element mod : OoxmlUtil.childEls(c)) if (OoxmlUtil.local(mod).equals("alpha")) alphaEl = mod;
        if (alphaEl != null) {
            Double a = OoxmlUtil.dblAttr(alphaEl, "val");
            if (a != null) {
                int[] rgb = OoxmlUtil.hexToRgb(base);
                if (rgb != null) return "rgba(" + rgb[0] + "," + rgb[1] + "," + rgb[2] + "," + trimNum(a / 100000.0) + ")";
            }
        }
        return base;
    }

    private static String presetColor(String name) {
        if (name == null) return "#000000";
        switch (name) {
            case "black": return "#000000";
            case "white": return "#FFFFFF";
            case "red": return "#FF0000";
            case "green": return "#008000";
            case "blue": return "#0000FF";
            case "yellow": return "#FFFF00";
            case "gray": case "grey": return "#808080";
            case "ltGray": case "ltGrey": return "#D3D3D3";
            case "dkGray": case "dkGrey": return "#A9A9A9";
            default: return "#000000";
        }
    }

    // ------------------------------------------------------------------ القالب الرئيسي والتخطيط

    private MasterInfo masterInfo(String path) {
        MasterInfo cached = masterCache.get(path);
        if (cached != null) return cached;
        MasterInfo m = new MasterInfo();
        m.path = path;
        Document d = OoxmlUtil.parsePart(zip, path, MAX_SMALL_XML);
        masterCache.put(path, m);
        if (d == null) return m;
        Element root = d.getDocumentElement();
        Map<String, OoxmlUtil.Rel> rels = OoxmlUtil.readRels(zip, path);
        String dir = OoxmlUtil.dirOf(path);
        for (OoxmlUtil.Rel r : rels.values()) {
            if (!r.external && r.target != null && OoxmlUtil.relType(r).equals("theme")) {
                m.theme = parseTheme(OoxmlUtil.resolve(dir, r.target));
                break;
            }
        }
        m.clrMap = clrMapFrom(OoxmlUtil.child(root, "clrMap"));
        Element cSld = OoxmlUtil.child(root, "cSld");
        m.bgCss = cSld != null ? backgroundCss(OoxmlUtil.child(cSld, "bg"), m.theme, m.clrMap) : null;
        if (cSld != null) {
            Element tree = OoxmlUtil.child(cSld, "spTree");
            if (tree != null) {
                for (Element sp : OoxmlUtil.childEls(tree, "sp")) {
                    Element nv = OoxmlUtil.descendant(sp, "nvSpPr");
                    Element ph = nv != null ? OoxmlUtil.descendant(nv, "ph") : null;
                    if (ph == null) continue;
                    Xfrm x = xfrmOf(OoxmlUtil.child(sp, "spPr"));
                    if (!x.has) continue;
                    String type = phType(ph);
                    String idx = OoxmlUtil.attr(ph, "idx");
                    PhBox b = new PhBox();
                    b.x = x.x; b.y = x.y; b.cx = x.cx; b.cy = x.cy; b.has = true;
                    m.phBox.put(type, b);
                    if (idx != null) m.phBox.put(type + "#" + idx, b);
                }
            }
        }
        Element txStyles = OoxmlUtil.child(root, "txStyles");
        if (txStyles != null) {
            fillLevels(OoxmlUtil.child(txStyles, "titleStyle"), m.styles.title, m.theme, m.clrMap, true);
            fillLevels(OoxmlUtil.child(txStyles, "bodyStyle"), m.styles.body, m.theme, m.clrMap, false);
            fillLevels(OoxmlUtil.child(txStyles, "otherStyle"), m.styles.other, m.theme, m.clrMap, false);
        }
        // قيم افتراضية معقولة إن لم يحدّدها القالب
        if (m.styles.title[0].sizePt == null) m.styles.title[0].sizePt = 44.0;
        if (m.styles.title[0].bold == null) m.styles.title[0].bold = false;
        if (m.styles.title[0].buNone == false && !m.styles.title[0].buSet) m.styles.title[0].buNone = true;
        if (m.styles.body[0].sizePt == null) m.styles.body[0].sizePt = 28.0;
        for (int i = 1; i < 9; i++) if (m.styles.body[i].sizePt == null) m.styles.body[i].sizePt = Math.max(12.0, 28.0 - i * 2);
        if (m.styles.other[0].sizePt == null) m.styles.other[0].sizePt = 18.0;
        return m;
    }

    private LayoutInfo layoutInfo(String path) {
        LayoutInfo cached = layoutCache.get(path);
        if (cached != null) return cached;
        LayoutInfo l = new LayoutInfo();
        l.path = path;
        layoutCache.put(path, l);
        Document d = OoxmlUtil.parsePart(zip, path, MAX_SMALL_XML);
        if (d == null) { l.master = masterInfo(guessMasterPath()); return l; }
        Map<String, OoxmlUtil.Rel> rels = OoxmlUtil.readRels(zip, path);
        String dir = OoxmlUtil.dirOf(path);
        String masterPath = null;
        for (OoxmlUtil.Rel r : rels.values()) {
            if (!r.external && r.target != null && OoxmlUtil.relType(r).equals("slideMaster")) {
                masterPath = OoxmlUtil.resolve(dir, r.target);
                break;
            }
        }
        l.master = masterInfo(masterPath != null ? masterPath : guessMasterPath());
        Element root = d.getDocumentElement();
        Element cSld = OoxmlUtil.child(root, "cSld");
        l.bgCss = cSld != null ? backgroundCss(OoxmlUtil.child(cSld, "bg"), l.master.theme, l.master.clrMap) : null;
        if (cSld != null) {
            Element tree = OoxmlUtil.child(cSld, "spTree");
            if (tree != null) {
                for (Element sp : OoxmlUtil.childEls(tree, "sp")) {
                    Element nv = OoxmlUtil.descendant(sp, "nvSpPr");
                    Element ph = nv != null ? OoxmlUtil.descendant(nv, "ph") : null;
                    if (ph == null) continue;
                    Xfrm x = xfrmOf(OoxmlUtil.child(sp, "spPr"));
                    String type = phType(ph);
                    String idx = OoxmlUtil.attr(ph, "idx");
                    if (x.has) {
                        PhBox b = new PhBox();
                        b.x = x.x; b.y = x.y; b.cx = x.cx; b.cy = x.cy; b.has = true;
                        l.phBox.put(type, b);
                        if (idx != null) l.phBox.put(type + "#" + idx, b);
                    }
                }
            }
        }
        return l;
    }

    private String guessMasterPath() {
        // احتياطي إن تعذّر تحديد القالب عبر العلاقات
        return "ppt/slideMasters/slideMaster1.xml";
    }

    private static String phType(Element ph) {
        String t = OoxmlUtil.attr(ph, "type");
        return t == null ? "body" : t;
    }

    private static boolean isTitleType(String type) { return type.equals("title") || type.equals("ctrTitle"); }

    private static boolean isBodyType(String type) {
        return type.equals("body") || type.equals("subTitle") || type.equals("obj") || type.equals("txBox");
    }

    private void fillLevels(Element styleEl, TextLvl[] out, ThemeInfo theme, Map<String, String> clrMap, boolean isTitle) {
        if (styleEl == null) return;
        for (int i = 1; i <= 9; i++) {
            Element lvl = OoxmlUtil.child(styleEl, "lvl" + i + "pPr");
            if (lvl == null) continue;
            TextLvl t = out[i - 1];
            String algn = OoxmlUtil.attr(lvl, "algn");
            if (algn != null) t.algn = algn;
            Double marL = OoxmlUtil.dblAttr(lvl, "marL");
            if (marL != null) t.marLEmu = marL;
            readBullet(lvl, t, theme, clrMap);
            Element rpr = OoxmlUtil.child(lvl, "defRPr");
            if (rpr != null) applyRPrDefaults(rpr, t, theme, clrMap);
        }
    }

    private void readBullet(Element pPr, TextLvl t, ThemeInfo theme, Map<String, String> clrMap) {
        Element none = OoxmlUtil.child(pPr, "buNone");
        Element ch = OoxmlUtil.child(pPr, "buChar");
        Element auto = OoxmlUtil.child(pPr, "buAutoNum");
        Element font = OoxmlUtil.child(pPr, "buFont");
        if (none != null) { t.buNone = true; t.buSet = true; }
        else if (ch != null) {
            String c = OoxmlUtil.attr(ch, "char");
            if (c != null && !c.isEmpty()) t.buChar = bulletChar(c.codePointAt(0));
            t.buSet = true;
        } else if (auto != null) {
            t.buAutoNumType = OoxmlUtil.attr(auto, "type");
            t.buSet = true;
        }
        if (font != null) t.buFont = OoxmlUtil.attr(font, "typeface");
    }

    private void applyRPrDefaults(Element rpr, TextLvl t, ThemeInfo theme, Map<String, String> clrMap) {
        Double sz = OoxmlUtil.dblAttr(rpr, "sz");
        if (sz != null) t.sizePt = sz / 100.0;
        String b = OoxmlUtil.attr(rpr, "b");
        if (b != null) t.bold = b.equals("1") || b.equalsIgnoreCase("true");
        String i = OoxmlUtil.attr(rpr, "i");
        if (i != null) t.italic = i.equals("1") || i.equalsIgnoreCase("true");
        Element fill = OoxmlUtil.child(rpr, "solidFill");
        if (fill != null) {
            String c = colorCss(fill, theme, clrMap);
            if (c != null) t.color = c;
        }
        Element latin = OoxmlUtil.child(rpr, "latin");
        if (latin != null) {
            String f = OoxmlUtil.attr(latin, "typeface");
            if (f != null && !f.startsWith("+")) t.font = f;
        }
    }

    // ------------------------------------------------------------------ خلفية الشريحة

    private String backgroundCss(Element bg, ThemeInfo theme, Map<String, String> clrMap) {
        if (bg == null) return null;
        Element bgPr = OoxmlUtil.child(bg, "bgPr");
        Element bgRef = OoxmlUtil.child(bg, "bgRef");
        if (bgPr != null) {
            Element solid = OoxmlUtil.child(bgPr, "solidFill");
            if (solid != null) {
                String c = colorCss(solid, theme, clrMap);
                if (c != null) return "background:" + c + ";";
            }
            Element grad = OoxmlUtil.child(bgPr, "gradFill");
            if (grad != null) {
                List<String> stops = gradientStops(grad, theme, clrMap);
                if (!stops.isEmpty()) return "background:linear-gradient(135deg," + String.join(",", stops) + ");";
            }
            Element none = OoxmlUtil.child(bgPr, "noFill");
            if (none != null) return null;
        } else if (bgRef != null) {
            String c = colorCss(bgRef, theme, clrMap);
            if (c != null) return "background:" + c + ";";
        }
        return null;
    }

    private List<String> gradientStops(Element grad, ThemeInfo theme, Map<String, String> clrMap) {
        List<String> out = new ArrayList<>();
        Element lst = OoxmlUtil.child(grad, "gsLst");
        if (lst == null) return out;
        for (Element gs : OoxmlUtil.childEls(lst, "gs")) {
            String c = colorCss(gs, theme, clrMap);
            Double pos = OoxmlUtil.dblAttr(gs, "pos");
            if (c != null) out.add(c + (pos != null ? " " + trimNum(pos / 1000.0) + "%" : ""));
        }
        return out;
    }

    // ------------------------------------------------------------------ xfrm

    private static Xfrm xfrmOf(Element spPr) {
        Xfrm x = new Xfrm();
        if (spPr == null) return x;
        Element xfrm = OoxmlUtil.child(spPr, "xfrm");
        if (xfrm == null) return x;
        Element off = OoxmlUtil.child(xfrm, "off");
        Element ext = OoxmlUtil.child(xfrm, "ext");
        if (off == null || ext == null) return x;
        Double ox = OoxmlUtil.dblAttr(off, "x"), oy = OoxmlUtil.dblAttr(off, "y");
        Double cx = OoxmlUtil.dblAttr(ext, "cx"), cy = OoxmlUtil.dblAttr(ext, "cy");
        if (ox == null || oy == null || cx == null || cy == null) return x;
        x.x = ox; x.y = oy; x.cx = cx; x.cy = cy; x.has = true;
        Integer rot = OoxmlUtil.intAttr(xfrm, "rot");
        if (rot != null) x.rotDeg = rot / 60000.0;
        x.flipH = "1".equals(OoxmlUtil.attr(xfrm, "flipH")) || "true".equalsIgnoreCase(OoxmlUtil.attr(xfrm, "flipH"));
        x.flipV = "1".equals(OoxmlUtil.attr(xfrm, "flipV")) || "true".equalsIgnoreCase(OoxmlUtil.attr(xfrm, "flipV"));
        return x;
    }

    // ------------------------------------------------------------------ رسم شريحة

    private final class SlideCtx {
        LayoutInfo layout;
        Map<String, String> clrMap;
        Map<String, OoxmlUtil.Rel> rels;
        String dir;
        Map<String, Integer> autoNum = new HashMap<>();
        StringBuilder shapes = new StringBuilder(4096);
    }

    private String renderSlide(String path, int index, int total) {
        Document d = OoxmlUtil.parsePart(zip, path, MAX_SMALL_XML);
        if (d == null) return "<div class=\"s-msg\">تعذّر قراءة الشريحة</div>";
        Element root = d.getDocumentElement();
        Map<String, OoxmlUtil.Rel> rels = OoxmlUtil.readRels(zip, path);
        String dir = OoxmlUtil.dirOf(path);
        String layoutPath = null;
        for (OoxmlUtil.Rel r : rels.values()) {
            if (!r.external && r.target != null && OoxmlUtil.relType(r).equals("slideLayout")) { layoutPath = OoxmlUtil.resolve(dir, r.target); break; }
        }
        LayoutInfo layout = layoutInfo(layoutPath != null ? layoutPath : guessLayoutFallback());

        SlideCtx ctx = new SlideCtx();
        ctx.layout = layout;
        Element clrOvr = OoxmlUtil.child(root, "clrMapOvr");
        Element explicit = clrOvr != null ? OoxmlUtil.child(clrOvr, "overrideClrMapping") : null;
        ctx.clrMap = explicit != null ? clrMapFrom(explicit) : layout.master.clrMap;
        ctx.rels = rels;
        ctx.dir = dir;

        Element cSld = OoxmlUtil.child(root, "cSld");
        String bg = cSld != null ? backgroundCss(OoxmlUtil.child(cSld, "bg"), layout.master.theme, ctx.clrMap) : null;
        if (bg == null) bg = layout.bgCss;
        if (bg == null) bg = layout.master.bgCss;
        if (bg == null) bg = "background:#FFFFFF;";

        Element tree = cSld != null ? OoxmlUtil.child(cSld, "spTree") : null;
        if (tree != null) renderChildren(tree, ctx, 0, 0, 1, 1, 0);

        StringBuilder out = new StringBuilder(ctx.shapes.length() + 128);
        out.append("<div class=\"s-bg\" style=\"").append(bg).append("\"></div>");
        out.append(ctx.shapes);
        return out.toString();
    }

    private String guessLayoutFallback() { return "ppt/slideLayouts/slideLayout1.xml"; }

    /** يُعيد رسم أبناء عنصر (spTree أو grpSp) مع تركيب تحويل المجموعات إحداثياً. */
    private void renderChildren(Element parent, SlideCtx ctx, double baseX, double baseY, double scaleX, double scaleY, int depth) {
        if (depth > 8) return;
        for (Element el : OoxmlUtil.childEls(parent)) {
            String ln = OoxmlUtil.local(el);
            switch (ln) {
                case "sp": renderShape(el, ctx, baseX, baseY, scaleX, scaleY); break;
                case "pic": renderPic(el, ctx, baseX, baseY, scaleX, scaleY); break;
                case "graphicFrame": renderGraphicFrame(el, ctx, baseX, baseY, scaleX, scaleY); break;
                case "cxnSp": renderShape(el, ctx, baseX, baseY, scaleX, scaleY); break;
                case "grpSp": {
                    Xfrm gx = xfrmOf(OoxmlUtil.child(el, "grpSpPr"));
                    if (!gx.has) break;
                    double absX = baseX + gx.x * scaleX, absY = baseY + gx.y * scaleY;
                    double absCx = gx.cx * scaleX, absCy = gx.cy * scaleY;
                    Element grpPr = OoxmlUtil.child(el, "grpSpPr");
                    Element xfrmEl = grpPr != null ? OoxmlUtil.child(grpPr, "xfrm") : null;
                    Element chOff = xfrmEl != null ? OoxmlUtil.child(xfrmEl, "chOff") : null;
                    Element chExt = xfrmEl != null ? OoxmlUtil.child(xfrmEl, "chExt") : null;
                    double chOffX = chOff != null ? num(OoxmlUtil.dblAttr(chOff, "x")) : gx.x;
                    double chOffY = chOff != null ? num(OoxmlUtil.dblAttr(chOff, "y")) : gx.y;
                    double chExtX = chExt != null ? num(OoxmlUtil.dblAttr(chExt, "cx")) : gx.cx;
                    double chExtY = chExt != null ? num(OoxmlUtil.dblAttr(chExt, "cy")) : gx.cy;
                    if (chExtX == 0) chExtX = gx.cx == 0 ? 1 : gx.cx;
                    if (chExtY == 0) chExtY = gx.cy == 0 ? 1 : gx.cy;
                    double childScaleX = scaleX * (gx.cx / chExtX);
                    double childScaleY = scaleY * (gx.cy / chExtY);
                    double childBaseX = absX - chOffX * childScaleX;
                    double childBaseY = absY - chOffY * childScaleY;
                    renderChildren(el, ctx, childBaseX, childBaseY, childScaleX, childScaleY, depth + 1);
                    break;
                }
                default: break;
            }
        }
    }

    private static double num(Double d) { return d == null ? 0 : d; }

    // ------------------------------------------------------------------ الأشكال النصية

    private void renderShape(Element sp, SlideCtx ctx, double baseX, double baseY, double scaleX, double scaleY) {
        Element nv = OoxmlUtil.descendant(sp, "nvSpPr");
        Element ph = nv != null ? OoxmlUtil.descendant(nv, "ph") : null;
        Element spPr = OoxmlUtil.child(sp, "spPr");
        Xfrm x = xfrmOf(spPr);
        String type = ph != null ? phType(ph) : null;
        String idx = ph != null ? OoxmlUtil.attr(ph, "idx") : null;

        if (!x.has && type != null) {
            PhBox b = ctx.layout.phBox.get(idx != null ? type + "#" + idx : type);
            if (b == null) b = ctx.layout.phBox.get(type);
            if (b == null) b = ctx.layout.master.phBox.get(type);
            if (b != null && b.has) { x.x = b.x; x.y = b.y; x.cx = b.cx; x.cy = b.cy; x.has = true; }
        }
        if (!x.has) {
            if (type != null && isTitleType(type)) { x.x = slideWEmu * 0.06; x.y = slideHEmu * 0.04; x.cx = slideWEmu * 0.88; x.cy = slideHEmu * 0.16; x.has = true; }
            else if (type != null) { x.x = slideWEmu * 0.06; x.y = slideHEmu * 0.24; x.cx = slideWEmu * 0.88; x.cy = slideHEmu * 0.68; x.has = true; }
            else return; // شكل بلا موضع ولا نائب — تجاهل
        }

        double absX = baseX + x.x * scaleX, absY = baseY + x.y * scaleY;
        double absCx = Math.max(1, x.cx * scaleX), absCy = Math.max(1, x.cy * scaleY);

        StringBuilder style = new StringBuilder();
        style.append("position:absolute;left:").append(vw(absX)).append(";top:").append(vw(absY))
                .append(";width:").append(vw(absCx)).append(";height:").append(vw(absCy)).append(';');
        List<String> tf = new ArrayList<>();
        if (x.rotDeg != 0) tf.add("rotate(" + trimNum(x.rotDeg) + "deg)");
        if (x.flipH) tf.add("scaleX(-1)");
        if (x.flipV) tf.add("scaleY(-1)");
        if (!tf.isEmpty()) style.append("transform:").append(String.join(" ", tf)).append(';');

        appendGeometryCss(style, spPr, ctx);

        Element txBody = OoxmlUtil.child(sp, "txBody");
        String bodyHtml = txBody != null ? renderTxBody(txBody, type, ctx) : "";

        ctx.shapes.append("<div class=\"shp\" style=\"").append(style).append("\">").append(bodyHtml).append("</div>");
    }

    private void appendGeometryCss(StringBuilder style, Element spPr, SlideCtx ctx) {
        if (spPr == null) return;
        Element fill = OoxmlUtil.child(spPr, "solidFill");
        Element noFill = OoxmlUtil.child(spPr, "noFill");
        Element grad = OoxmlUtil.child(spPr, "gradFill");
        if (fill != null) {
            String c = colorCss(fill, ctx.layout.master.theme, ctx.clrMap);
            if (c != null) style.append("background-color:").append(c).append(';');
        } else if (grad != null) {
            List<String> stops = gradientStops(grad, ctx.layout.master.theme, ctx.clrMap);
            if (!stops.isEmpty()) style.append("background:linear-gradient(135deg,").append(String.join(",", stops)).append(");");
        }
        Element ln = OoxmlUtil.child(spPr, "ln");
        if (ln != null) {
            Element lnFill = OoxmlUtil.child(ln, "solidFill");
            Element lnNo = OoxmlUtil.child(ln, "noFill");
            if (lnFill != null && lnNo == null) {
                String c = colorCss(lnFill, ctx.layout.master.theme, ctx.clrMap);
                Double w = OoxmlUtil.dblAttr(ln, "w");
                double wpx = w != null ? w / 12700.0 * 1.333 : 1;
                String dashSt = "solid";
                Element dash = OoxmlUtil.child(ln, "prstDash");
                if (dash != null) {
                    String v = OoxmlUtil.attr(dash, "val");
                    if (v != null && v.toLowerCase(Locale.ROOT).contains("dash")) dashSt = "dashed";
                    else if (v != null && v.toLowerCase(Locale.ROOT).contains("dot")) dashSt = "dotted";
                }
                if (c != null) style.append("border:").append(trimNum(Math.max(0.5, wpx))).append("px ").append(dashSt).append(' ').append(c).append(';');
            }
        }
        Element geom = OoxmlUtil.child(spPr, "prstGeom");
        String preset = geom != null ? OoxmlUtil.attr(geom, "prst") : null;
        if (preset != null) {
            if (preset.equals("ellipse")) style.append("border-radius:50%;");
            else if (preset.startsWith("round") || preset.contains("Round")) style.append("border-radius:10%;");
        }
        style.append("overflow:hidden;box-sizing:border-box;");
    }

    // ------------------------------------------------------------------ محتوى النص

    private String renderTxBody(Element txBody, String phType, SlideCtx ctx) {
        Element bodyPr = OoxmlUtil.child(txBody, "bodyPr");
        String anchor = bodyPr != null ? OoxmlUtil.attr(bodyPr, "anchor") : null;
        String justify = "flex-start";
        if ("ctr".equals(anchor)) justify = "center";
        else if ("b".equals(anchor)) justify = "flex-end";
        double lIns = insEmu(bodyPr, "lIns", 91440), tIns = insEmu(bodyPr, "tIns", 45720);
        double rIns = insEmu(bodyPr, "rIns", 91440), bIns = insEmu(bodyPr, "bIns", 45720);

        StringBuilder sb = new StringBuilder(512);
        sb.append("<div class=\"txw\" style=\"justify-content:").append(justify)
                .append(";padding:").append(vw(tIns)).append(' ').append(vw(rIns)).append(' ').append(vw(bIns)).append(' ').append(vw(lIns)).append(";\">");

        List<Element> paras = OoxmlUtil.childEls(txBody, "p");
        Map<Integer, Integer> counters = new HashMap<>();
        for (Element p : paras) {
            renderPara(p, phType, ctx, counters, sb);
        }
        sb.append("</div>");
        return sb.toString();
    }

    private static double insEmu(Element bodyPr, String name, double def) {
        Double v = bodyPr != null ? OoxmlUtil.dblAttr(bodyPr, name) : null;
        return v != null ? v : def;
    }

    private void renderPara(Element p, String phType, SlideCtx ctx, Map<Integer, Integer> counters, StringBuilder sb) {
        Element pPr = OoxmlUtil.child(p, "pPr");
        int level = pPr != null ? intOr(OoxmlUtil.intAttr(pPr, "lvl"), 0) : 0;
        level = Math.max(0, Math.min(8, level));

        TextLvl base = levelDefaults(phType, level, ctx);

        String algn = pPr != null ? OoxmlUtil.attr(pPr, "algn") : null;
        if (algn == null) algn = base.algn;
        boolean rtlPara = pPr != null && "1".equals(OoxmlUtil.attr(pPr, "rtl"));

        // الفقاعة/الترقيم: أولوية لتعريف الفقرة نفسها، وإلا افتراضي المستوى.
        // العناوين الفرعية subTitle تستعير حجم خط bodyStyle لكنها لا تُنقَّط في أي
        // قالب معروف، فنبدأ بلا فقاعة موروثة لها ما لم تفرضها الفقرة صراحةً.
        boolean isSubtitle = "subTitle".equals(phType);
        Character buChar = isSubtitle ? null : base.buChar;
        String buAuto = isSubtitle ? null : base.buAutoNumType;
        boolean buNone = isSubtitle || base.buNone;
        if (pPr != null) {
            Element none = OoxmlUtil.child(pPr, "buNone");
            Element ch = OoxmlUtil.child(pPr, "buChar");
            Element auto = OoxmlUtil.child(pPr, "buAutoNum");
            if (none != null) { buNone = true; buChar = null; buAuto = null; }
            else if (ch != null) { String c = OoxmlUtil.attr(ch, "char"); if (c != null && !c.isEmpty()) { buChar = bulletChar(c.codePointAt(0)); buNone = false; buAuto = null; } }
            else if (auto != null) { buAuto = OoxmlUtil.attr(auto, "type"); buNone = false; buChar = null; }
        }
        boolean isTitleLike = phType != null && isTitleType(phType);
        if (!buNone && buChar == null && buAuto == null && isTitleLike) buNone = true;

        List<Element> runEls = new ArrayList<>();
        for (Element c : OoxmlUtil.childEls(p)) {
            String ln = OoxmlUtil.local(c);
            if (ln.equals("r") || ln.equals("fld") || ln.equals("br")) runEls.add(c);
        }

        StringBuilder text = new StringBuilder();
        boolean any = false;
        String pcls = classFor(paraCss(algn, level, base), "pp");
        sb.append("<p class=\"").append(pcls).append('"');
        if (rtlPara) sb.append(" dir=\"rtl\"");
        sb.append('>');

        String label = null;
        if (!buNone) {
            if (buChar != null) label = String.valueOf(buChar);
            else if (buAuto != null) {
                int n = counters.merge(level, 1, Integer::sum);
                label = fmtAutoNum(buAuto, n);
            }
        }
        if (label != null) {
            sb.append("<span class=\"bl\">").append(HtmlPage.esc(label)).append("</span>");
        }

        boolean wroteAny = false;
        for (Element r : runEls) {
            String ln = OoxmlUtil.local(r);
            if (ln.equals("br")) { sb.append("<br>"); wroteAny = true; continue; }
            Element tEl = OoxmlUtil.child(r, "t");
            String txt = tEl != null ? tEl.getTextContent() : "";
            if (ln.equals("fld") && txt.isEmpty()) continue;
            Element rpr = OoxmlUtil.child(r, "rPr");
            Run run = mergeRun(rpr, base, ctx);
            if (txt.isEmpty() && !ln.equals("br")) continue;
            wroteAny = true;
            String cls = classFor(runCss(run), "rr");
            String esc = HtmlPage.esc(txt);
            String openTag = "<span class=\"" + cls + "\">";
            String href = run.linkHref;
            if (href != null) sb.append(openTag).append("<a href=\"").append(HtmlPage.esc(href)).append("\">").append(esc).append("</a></span>");
            else sb.append(openTag).append(esc).append("</span>");
        }
        if (!wroteAny && label == null) sb.append("&nbsp;");
        sb.append("</p>");
    }

    private String paraCss(String algn, int level, TextLvl base) {
        StringBuilder css = new StringBuilder();
        String ta = algn == null ? null : algnCss(algn);
        if (ta != null) css.append("text-align:").append(ta).append(';');
        double marL = base.marLEmu >= 0 ? base.marLEmu : level * 457200;
        css.append("padding-inline-start:").append(vw(marL)).append(";margin:0 0 ").append(vw(0.25 * EMU_PER_PT * (base.sizePt != null ? base.sizePt : 18))).append(" 0;position:relative;");
        return css.toString();
    }

    private static String algnCss(String v) {
        switch (v) {
            case "ctr": return "center";
            case "r": return "right";
            case "just": case "justLow": return "justify";
            case "l": return "left";
            default: return null;
        }
    }

    private TextLvl levelDefaults(String phType, int level, SlideCtx ctx) {
        // الأشكال الحرة (بلا نائب، phType=null) تأخذ نمط "other" كما في PowerPoint،
        // وليس نمط "body" — النمط الأخير مخصّص فقط لنائبات المحتوى الفعلية.
        TextLvl[] arr;
        if (phType != null && isTitleType(phType)) arr = ctx.layout.master.styles.title;
        else if (phType != null && isBodyType(phType)) arr = ctx.layout.master.styles.body;
        else arr = ctx.layout.master.styles.other;
        TextLvl base = arr[level];
        // إكمال الحقول الفارغة من المستوى الأول كاحتياط
        TextLvl first = arr[0];
        TextLvl merged = new TextLvl();
        merged.sizePt = base.sizePt != null ? base.sizePt : first.sizePt;
        merged.bold = base.bold != null ? base.bold : first.bold;
        merged.italic = base.italic != null ? base.italic : first.italic;
        merged.color = base.color != null ? base.color : first.color;
        merged.font = base.font != null ? base.font : first.font;
        merged.algn = base.algn != null ? base.algn : first.algn;
        merged.buChar = base.buSet ? base.buChar : first.buChar;
        merged.buAutoNumType = base.buSet ? base.buAutoNumType : first.buAutoNumType;
        merged.buNone = base.buSet ? base.buNone : first.buNone;
        merged.marLEmu = base.marLEmu >= 0 ? base.marLEmu : (first.marLEmu >= 0 ? first.marLEmu : -1);
        if (merged.color == null) merged.color = ctx.layout.master.theme.colors[1];
        if (merged.sizePt == null) merged.sizePt = 18.0;
        return merged;
    }

    private Run mergeRun(Element rpr, TextLvl base, SlideCtx ctx) {
        Run r = new Run();
        r.sizePt = base.sizePt;
        r.bold = base.bold != null && base.bold;
        r.italic = base.italic != null && base.italic;
        r.color = base.color;
        r.font = base.font;
        if (rpr != null) {
            Double sz = OoxmlUtil.dblAttr(rpr, "sz");
            if (sz != null) r.sizePt = sz / 100.0;
            String b = OoxmlUtil.attr(rpr, "b");
            if (b != null) r.bold = b.equals("1") || b.equalsIgnoreCase("true");
            String i = OoxmlUtil.attr(rpr, "i");
            if (i != null) r.italic = i.equals("1") || i.equalsIgnoreCase("true");
            String u = OoxmlUtil.attr(rpr, "u");
            if (u != null && !u.equals("none")) r.underline = true;
            String strike = OoxmlUtil.attr(rpr, "strike");
            if (strike != null && !strike.equals("noStrike")) r.strike = true;
            Element fill = OoxmlUtil.child(rpr, "solidFill");
            if (fill != null) {
                String c = colorCss(fill, ctx.layout.master.theme, ctx.clrMap);
                if (c != null) r.color = c;
            }
            Element latin = OoxmlUtil.child(rpr, "latin");
            if (latin != null) {
                String f = OoxmlUtil.attr(latin, "typeface");
                if (f != null && !f.startsWith("+")) r.font = f;
            }
            Integer base_ = OoxmlUtil.intAttr(rpr, "baseline");
            if (base_ != null) { if (base_ > 0) r.sup = true; else if (base_ < 0) r.sub = true; }
            Element hlink = OoxmlUtil.child(rpr, "hlinkClick");
            if (hlink != null) {
                String rid = OoxmlUtil.attr(hlink, "id");
                if (rid != null) {
                    OoxmlUtil.Rel rel = ctx.rels.get(rid);
                    if (rel != null && rel.external) r.linkHref = HtmlPage.safeHref(rel.target);
                }
            }
        }
        return r;
    }

    private String runCss(Run r) {
        StringBuilder css = new StringBuilder();
        if (r.sizePt != null) css.append("font-size:").append(vw(r.sizePt * EMU_PER_PT)).append(';');
        if (r.bold) css.append("font-weight:bold;");
        if (r.italic) css.append("font-style:italic;");
        if (r.underline || r.strike) {
            css.append("text-decoration:");
            if (r.underline) css.append("underline ");
            if (r.strike) css.append("line-through ");
            css.append(';');
        }
        if (r.color != null) css.append("color:").append(r.color).append(';');
        if (r.font != null) css.append("font-family:").append(fontFamilyCss(r.font)).append(';');
        if (r.sup) css.append("vertical-align:super;font-size:.7em;");
        if (r.sub) css.append("vertical-align:sub;font-size:.7em;");
        return css.toString();
    }

    private static String fontFamilyCss(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.contains("courier") || n.contains("consolas") || n.contains("mono")) return "Consolas,\"Courier New\",monospace";
        if (n.contains("arabic") || n.contains("times") || n.contains("georgia") || n.contains("cambria")) return "\"" + name.replace("\"", "") + "\",\"Noto Naskh Arabic\",serif";
        return "\"" + name.replace("\"", "") + "\",\"Segoe UI\",\"Noto Sans Arabic\",Arial,sans-serif";
    }

    private static int intOr(Integer v, int def) { return v == null ? def : v; }

    private static char bulletChar(int cp) {
        switch (cp) {
            case 0xF0B7: case 0x2022: return '•';
            case 0xF0A7: return '▪';
            case 0xF0D8: return '➢';
            case 0xF0E0: return '➔';
            case 0xF0FC: case 0x2713: return '✓';
            case 0x25CF: return '●';
            case 0x25AA: return '▪';
            case 0x2013: case 0x002D: return '–';
            default: return cp <= 0xFFFF ? (char) cp : '•';
        }
    }

    private static String fmtAutoNum(String type, int n) {
        if (type == null) return n + ".";
        String t = type.toLowerCase(Locale.ROOT);
        String num;
        if (t.startsWith("alphalc")) num = toAlpha(n, false);
        else if (t.startsWith("alphauc")) num = toAlpha(n, true);
        else if (t.startsWith("romanlc")) num = toRoman(n).toLowerCase(Locale.ROOT);
        else if (t.startsWith("romanuc")) num = toRoman(n);
        else num = String.valueOf(n);
        if (t.contains("parenr") || t.contains("parenboth")) return num + ")";
        if (t.contains("period")) return num + ".";
        return num + ".";
    }

    private static String toAlpha(int n, boolean upper) {
        StringBuilder sb = new StringBuilder();
        while (n > 0) { int m = (n - 1) % 26; sb.insert(0, (char) ((upper ? 'A' : 'a') + m)); n = (n - 1) / 26; }
        return sb.length() == 0 ? (upper ? "A" : "a") : sb.toString();
    }

    private static String toRoman(int n) {
        int[] vals = {1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1};
        String[] syms = {"M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I"};
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < vals.length && n > 0; i++) while (n >= vals[i]) { sb.append(syms[i]); n -= vals[i]; }
        return sb.length() == 0 ? "I" : sb.toString();
    }

    // ------------------------------------------------------------------ الصور

    private void renderPic(Element pic, SlideCtx ctx, double baseX, double baseY, double scaleX, double scaleY) {
        Element spPr = OoxmlUtil.child(pic, "spPr");
        Xfrm x = xfrmOf(spPr);
        if (!x.has) return;
        double absX = baseX + x.x * scaleX, absY = baseY + x.y * scaleY;
        double absCx = Math.max(1, x.cx * scaleX), absCy = Math.max(1, x.cy * scaleY);

        Element blipFill = OoxmlUtil.child(pic, "blipFill");
        Element blip = blipFill != null ? OoxmlUtil.child(blipFill, "blip") : null;
        String rid = blip != null ? OoxmlUtil.attr(blip, "embed") : null;
        String uri = null;
        if (rid != null) {
            OoxmlUtil.Rel r = ctx.rels.get(rid);
            if (r != null && !r.external && r.target != null) {
                byte[] bytes = OoxmlUtil.read(zip, OoxmlUtil.resolve(ctx.dir, r.target), MAX_IMAGE);
                if (bytes != null && imageBytes + bytes.length <= MAX_IMAGE_TOTAL) {
                    uri = OoxmlUtil.dataUri(bytes);
                    if (uri != null) imageBytes += bytes.length;
                }
            }
        }

        StringBuilder style = new StringBuilder();
        style.append("position:absolute;left:").append(vw(absX)).append(";top:").append(vw(absY))
                .append(";width:").append(vw(absCx)).append(";height:").append(vw(absCy)).append(';');
        List<String> tf = new ArrayList<>();
        if (x.rotDeg != 0) tf.add("rotate(" + trimNum(x.rotDeg) + "deg)");
        if (x.flipH) tf.add("scaleX(-1)");
        if (x.flipV) tf.add("scaleY(-1)");
        if (!tf.isEmpty()) style.append("transform:").append(String.join(" ", tf)).append(';');

        if (uri == null) {
            ctx.shapes.append("<div class=\"shp ph\" style=\"").append(style).append("\">▣</div>");
            return;
        }
        Element srcRect = blipFill != null ? OoxmlUtil.child(blipFill, "srcRect") : null;
        if (srcRect != null) {
            double l = pctOf(srcRect, "l"), t = pctOf(srcRect, "t"), rr = pctOf(srcRect, "r"), b = pctOf(srcRect, "b");
            double vw2 = 1 - l - rr, vh = 1 - t - b;
            if (vw2 > 0.03 && vh > 0.03 && (l != 0 || t != 0 || rr != 0 || b != 0)) {
                style.append("overflow:hidden;");
                ctx.shapes.append("<div class=\"shp\" style=\"").append(style).append("\">")
                        .append("<img style=\"position:absolute;max-width:none;width:").append(trimNum(100 / vw2)).append("%;height:")
                        .append(trimNum(100 / vh)).append("%;left:").append(trimNum(-l * 100 / vw2)).append("%;top:")
                        .append(trimNum(-t * 100 / vh)).append("%;\" src=\"").append(uri).append("\" alt=\"\"></div>");
                return;
            }
        }
        ctx.shapes.append("<img class=\"shp\" style=\"").append(style).append("object-fit:fill;\" src=\"").append(uri).append("\" alt=\"\">");
    }

    private static double pctOf(Element e, String name) {
        Double v = OoxmlUtil.dblAttr(e, name);
        return v == null ? 0 : v / 100000.0;
    }

    // ------------------------------------------------------------------ الجداول والمخططات

    private void renderGraphicFrame(Element gf, SlideCtx ctx, double baseX, double baseY, double scaleX, double scaleY) {
        Xfrm x = xfrmOf(gf);
        if (!x.has) {
            Element xfrmEl = OoxmlUtil.child(gf, "xfrm");
            if (xfrmEl != null) {
                Element off = OoxmlUtil.child(xfrmEl, "off");
                Element ext = OoxmlUtil.child(xfrmEl, "ext");
                if (off != null && ext != null) {
                    x.x = num(OoxmlUtil.dblAttr(off, "x")); x.y = num(OoxmlUtil.dblAttr(off, "y"));
                    x.cx = num(OoxmlUtil.dblAttr(ext, "cx")); x.cy = num(OoxmlUtil.dblAttr(ext, "cy"));
                    x.has = x.cx > 0 && x.cy > 0;
                }
            }
        }
        if (!x.has) return;
        double absX = baseX + x.x * scaleX, absY = baseY + x.y * scaleY;
        double absCx = Math.max(1, x.cx * scaleX), absCy = Math.max(1, x.cy * scaleY);
        String posStyle = "position:absolute;left:" + vw(absX) + ";top:" + vw(absY) + ";width:" + vw(absCx) + ";height:" + vw(absCy) + ";";

        Element graphic = OoxmlUtil.child(gf, "graphic");
        Element data = graphic != null ? OoxmlUtil.child(graphic, "graphicData") : null;
        Element tbl = data != null ? OoxmlUtil.child(data, "tbl") : null;
        if (tbl != null) {
            ctx.shapes.append("<div class=\"shp tblw\" style=\"").append(posStyle).append("\">").append(renderTable(tbl, ctx)).append("</div>");
            return;
        }
        if (data != null && OoxmlUtil.descendant(data, "chart") != null) {
            ctx.shapes.append("<div class=\"shp ph\" style=\"").append(posStyle).append("\">[مخطط]</div>");
            return;
        }
        ctx.shapes.append("<div class=\"shp ph\" style=\"").append(posStyle).append("\">▣</div>");
    }

    private String renderTable(Element tbl, SlideCtx ctx) {
        Element grid = OoxmlUtil.child(tbl, "tblGrid");
        List<Element> cols = grid != null ? OoxmlUtil.childEls(grid, "gridCol") : new ArrayList<>();
        double total = 0;
        double[] widths = new double[cols.size()];
        for (int i = 0; i < cols.size(); i++) { Double w = OoxmlUtil.dblAttr(cols.get(i), "w"); widths[i] = w == null ? 1 : w; total += widths[i]; }
        if (total <= 0) total = 1;

        List<Element> rows = OoxmlUtil.childEls(tbl, "tr");
        // احتساب rowSpan عبر vMerge
        int nCols = cols.size();
        boolean[][] vmerge = new boolean[rows.size()][nCols];
        for (int ri = 0; ri < rows.size(); ri++) {
            List<Element> tcs = OoxmlUtil.childEls(rows.get(ri), "tc");
            int ci = 0;
            for (Element tc : tcs) {
                if (ci >= nCols) break;
                vmerge[ri][ci] = "1".equals(OoxmlUtil.attr(tc, "vMerge")) || "true".equalsIgnoreCase(OoxmlUtil.attr(tc, "vMerge"));
                int span = intOr(OoxmlUtil.intAttr(tc, "gridSpan"), 1);
                ci += span;
            }
        }

        StringBuilder sb = new StringBuilder(1024);
        sb.append("<table class=\"pt\"><colgroup>");
        for (double w : widths) sb.append("<col style=\"width:").append(trimNum(w / total * 100)).append("%\">");
        sb.append("</colgroup><tbody>");
        for (int ri = 0; ri < rows.size(); ri++) {
            Element tr = rows.get(ri);
            sb.append("<tr>");
            int ci = 0;
            for (Element tc : OoxmlUtil.childEls(tr, "tc")) {
                boolean hMerge = "1".equals(OoxmlUtil.attr(tc, "hMerge")) || "true".equalsIgnoreCase(OoxmlUtil.attr(tc, "hMerge"));
                int span = intOr(OoxmlUtil.intAttr(tc, "gridSpan"), 1);
                if (hMerge) { ci += 1; continue; }
                boolean vm = ci < nCols && vmerge[ri][ci];
                if (vm) { ci += span; continue; }
                int rowspan = 1;
                for (int rr = ri + 1; rr < rows.size() && ci < nCols && vmerge[rr][ci]; rr++) rowspan++;

                Element tcPr = OoxmlUtil.child(tc, "tcPr");
                StringBuilder cellCss = new StringBuilder("border:1px solid #BFBFBF;padding:2px 6px;vertical-align:top;");
                if (tcPr != null) {
                    Element fill = OoxmlUtil.child(tcPr, "solidFill");
                    if (fill != null) {
                        String c = colorCss(fill, ctx.layout.master.theme, ctx.clrMap);
                        if (c != null) cellCss.append("background-color:").append(c).append(';');
                    }
                }
                sb.append("<td style=\"").append(cellCss).append('"');
                if (span > 1) sb.append(" colspan=\"").append(span).append('"');
                if (rowspan > 1) sb.append(" rowspan=\"").append(rowspan).append('"');
                sb.append('>');
                Element txBody = OoxmlUtil.child(tc, "txBody");
                if (txBody != null) {
                    Map<Integer, Integer> counters = new HashMap<>();
                    for (Element p : OoxmlUtil.childEls(txBody, "p")) renderPara(p, "other", ctx, counters, sb);
                }
                sb.append("</td>");
                ci += span;
            }
            sb.append("</tr>");
        }
        sb.append("</tbody></table>");
        return sb.toString();
    }

    // ------------------------------------------------------------------ وحدات القياس وCSS

    private String vw(double emu) { return trimNum(emu / slideWEmu * 100) + "vw"; }

    private static String trimNum(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return "0";
        if (Math.abs(v - Math.rint(v)) < 1e-6) return String.valueOf((long) Math.rint(v));
        return String.format(Locale.ROOT, "%.3f", v);
    }

    private static double trimNum(Double v, double def) { return v == null ? def : v; }

    private String classFor(String css, String prefix) {
        String key = prefix + "\u0000" + css;
        String cls = classes.get(key);
        if (cls == null) { cls = prefix + classes.size(); classes.put(key, cls); }
        return cls;
    }

    private String css(double ratio) {
        StringBuilder c = new StringBuilder(4096);
        c.append("html,body{height:100%}body{margin:0;padding:0;background:#2b2b2b;overflow:hidden}")
                .append(".ppt{position:relative;height:100vh;display:flex;flex-direction:column}")
                .append(".ppt-scroll{flex:1;min-height:0;display:flex;overflow-x:auto;overflow-y:hidden;scroll-snap-type:x mandatory;-webkit-overflow-scrolling:touch;background:#2b2b2b}")
                .append(".slide-page{flex:none;width:100vw;scroll-snap-align:center;display:flex;align-items:center;justify-content:center;box-sizing:border-box;padding:2vw}")
                .append(".slide{position:relative;width:100%;aspect-ratio:").append(trimNum(1 / ratio)).append("/1;background:#fff;overflow:hidden;box-shadow:0 2px 14px rgba(0,0,0,.35);font-family:Calibri,\"Segoe UI\",Roboto,\"Noto Sans Arabic\",Arial,sans-serif}")
                .append(".s-bg{position:absolute;inset:0}")
                .append(".shp{overflow:hidden}")
                .append(".shp.ph{display:flex;align-items:center;justify-content:center;color:#999;border:1px dashed #ccc;font-size:4vw;background:#f5f5f5}")
                .append(".txw{position:absolute;inset:0;display:flex;flex-direction:column;overflow:hidden}")
                .append(".txw p{margin:0;white-space:pre-wrap;overflow-wrap:anywhere;line-height:1.2}")
                .append(".bl{display:inline-block;min-width:1.4em;margin-inline-end:.3em}")
                .append(".tblw{overflow:auto}")
                .append(".pt{border-collapse:collapse;width:100%;table-layout:fixed;font-size:2vw}")
                .append(".ppt-count{flex:none;text-align:center;padding:6px;font:12px sans-serif;color:#ccc;background:#1f1f1f}")
                .append(".note{margin:8px;padding:8px 12px;border-radius:8px;background:#FFF4E5;color:#7A4B00;font:13px sans-serif}")
                .append(".s-msg{padding:56px 12px;text-align:center;color:#8A8A84;font:15px sans-serif}");
        // فئات الفقرات والنصوص المُولَّدة (المفتاح "بادئة\u0000CSS" -> اسم الصنف)
        for (Map.Entry<String, String> e : classes.entrySet()) {
            int sep = e.getKey().indexOf('\u0000');
            String cssBody = e.getKey().substring(sep + 1);
            c.append('.').append(e.getValue()).append('{').append(cssBody).append('}');
        }
        return c.toString();
    }
}
