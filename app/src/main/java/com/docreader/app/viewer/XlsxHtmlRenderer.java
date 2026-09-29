package com.docreader.app.viewer;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.helpers.DefaultHandler;

import java.io.InputStream;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipFile;

import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

/**
 * قارئ Excel حقيقي: يحوّل ملف xlsx إلى HTML بكل التنسيقات — تنسيق الأرقام والتواريخ والعملات،
 * ألوان الخلفية والخطوط والحدود، دمج الخلايا، عرض الأعمدة وارتفاع الصفوف، الأوراق المتعددة،
 * الاتجاه من اليمين لليسار، التنسيق الشرطي، جداول Excel، التعليقات، الروابط والصور.
 * التحليل عبر SAX (بثّ) كي لا تُحمَّل الأوراق الكبيرة كاملة في الذاكرة.
 */
public final class XlsxHtmlRenderer {

    private static final int MAX_ROWS = 10000;
    private static final int MAX_COLS = 256;
    private static final int MAX_CELLS_TOTAL = 200000;
    private static final int MAX_SMALL_XML = 24 * 1024 * 1024;
    private static final int MAX_IMAGE = 12 * 1024 * 1024;
    private static final int MAX_IMAGE_TOTAL = 30 * 1024 * 1024;
    private static final int ROW_HEADER_W = 46;
    private static final int COL_HEADER_H = 22;
    private static final double DEFAULT_COL_PX = 64;
    private static final double DEFAULT_ROW_PT = 15;

    private static final String[] INDEXED = {
            "000000", "FFFFFF", "FF0000", "00FF00", "0000FF", "FFFF00", "FF00FF", "00FFFF",
            "000000", "FFFFFF", "FF0000", "00FF00", "0000FF", "FFFF00", "FF00FF", "00FFFF",
            "800000", "008000", "000080", "808000", "800080", "008080", "C0C0C0", "808080",
            "9999FF", "993366", "FFFFCC", "CCFFFF", "660066", "FF8080", "0066CC", "CCCCFF",
            "000080", "FF00FF", "FFFF00", "00FFFF", "800080", "800000", "008080", "0000FF",
            "00CCFF", "CCFFFF", "CCFFCC", "FFFF99", "99CCFF", "FF99CC", "CC99FF", "FFCC99",
            "3366FF", "33CCCC", "99CC00", "FFCC00", "FF9900", "FF6600", "666699", "969696",
            "003366", "339966", "003300", "333300", "993300", "993366", "333399", "333333"};

    private static final String[] THEME_DEFAULT = {
            "#FFFFFF", "#000000", "#E7E6E6", "#44546A", "#4472C4", "#ED7D31",
            "#A5A5A5", "#FFC000", "#5B9BD5", "#70AD47", "#0563C1", "#954F72"};

    private static final byte K_BLANK = 0, K_STR = 1, K_NUM = 2, K_BOOL = 3;

    // ------------------------------------------------------------------ نماذج البيانات

    private static final class Xf {
        String css = "";
        String code;            // رمز تنسيق الأرقام (null = عام)
        boolean generalAlign = true;
        boolean wrap;
        char hAlign = 'g';      // g l r c j
        double fontPx = 14.67;
        boolean hasTop, hasBottom, hasLeft, hasRight;
    }

    private static final class Cell {
        int xf;
        byte kind;
        double num;
        boolean hasNum;
        String plain;
        String rich;
        String color;
        String link;
        String extra;
        boolean formulaLink;
    }

    private static final class RowData {
        int r;
        double htPt = -1;
        boolean hidden;
        Cell[] cells = new Cell[8];
        int maxCol;

        void set(int col, Cell c) {
            if (col >= cells.length) cells = Arrays.copyOf(cells, Math.max(col + 1, cells.length * 2));
            cells[col] = c;
            if (col > maxCol) maxCol = col;
        }

        Cell get(int col) { return col < cells.length ? cells[col] : null; }
    }

    private static final class Merge {
        int r1, c1, r2, c2;
    }

    private static final class CfRule {
        String type, operator, text;
        int priority, dxfId = -1;
        int rank = 10;
        boolean percent, bottom, aboveAverage = true, equalAverage;
        List<String> formulas = new ArrayList<>();
        List<String[]> cfvo = new ArrayList<>();   // {type, val}
        List<String> colors = new ArrayList<>();
        String barColor;
        List<int[]> ranges = new ArrayList<>();    // r1,c1,r2,c2
    }

    private static final class Sheet {
        String name, path, tabColor;
        boolean chart, rtl, showGrid = true;
        double[] colPx = new double[MAX_COLS + 2];
        boolean[] colHidden = new boolean[MAX_COLS + 2];
        double defColPx = DEFAULT_COL_PX;
        double defRowPt = DEFAULT_ROW_PT;
        HashMap<Integer, RowData> rows = new HashMap<>();
        int maxRow, maxCol;
        int fileRows;
        boolean truncRows, truncCols, truncCells;
        List<Merge> merges = new ArrayList<>();
        List<CfRule> cf = new ArrayList<>();
        int xSplit, ySplit;
        Map<String, String> comments = new LinkedHashMap<>();
        List<String> tableStyles = new ArrayList<>();
        List<Object[]> tables = new ArrayList<>();      // {r1,c1,r2,c2,styleName,rowStripes,colStripes,firstCol,header}
        String drawingRid;
        List<String> tableRids = new ArrayList<>();
        List<String[]> hyperlinks = new ArrayList<>();  // {ref, rid, location}
        boolean colWidthSet;
    }

    // ------------------------------------------------------------------ الحالة

    private final ZipFile zip;
    private final Map<String, Integer> classes = new LinkedHashMap<>();
    private final XlsxNumFmt fmt;
    private final boolean date1904;
    private String[] theme = THEME_DEFAULT.clone();
    private String[] palette = null;
    private final Map<Integer, String> numFmts = new HashMap<>();
    private final List<Xf> xfs = new ArrayList<>();
    private final List<String> dxfCss = new ArrayList<>();
    private String[] sstPlain = new String[0];
    private String[] sstRich = new String[0];
    private double defaultFontPt = 11;
    private int totalCells;
    private int imageBytes;
    private final String mainPath;
    private XlsxHtmlRenderer(ZipFile zip, String mainPath, boolean date1904) {
        this.zip = zip;
        this.mainPath = mainPath;
        this.date1904 = date1904;
        this.fmt = new XlsxNumFmt(date1904);
    }

    // ------------------------------------------------------------------ الدخول

    public static String render(java.io.File file) throws Exception {
        try (ZipFile zip = new ZipFile(file)) {
            String main = findMain(zip);
            Document wb = OoxmlUtil.parsePart(zip, main, MAX_SMALL_XML);
            if (wb == null) throw new IllegalStateException("workbook.xml missing");
            Element wbRoot = wb.getDocumentElement();
            Element wbPr = OoxmlUtil.child(wbRoot, "workbookPr");
            boolean d1904 = wbPr != null && "1".equals(OoxmlUtil.attr(wbPr, "date1904"))
                    || wbPr != null && "true".equalsIgnoreCase(OoxmlUtil.attr(wbPr, "date1904"));
            XlsxHtmlRenderer r = new XlsxHtmlRenderer(zip, main, d1904);
            return r.build(wbRoot);
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
        return "xl/workbook.xml";
    }

    // ------------------------------------------------------------------ البناء

    private String build(Element wbRoot) throws Exception {
        String dir = OoxmlUtil.dirOf(mainPath);
        Map<String, OoxmlUtil.Rel> rels = OoxmlUtil.readRels(zip, mainPath);

        String stylesPath = null, themePath = null, sstPath = null;
        for (OoxmlUtil.Rel r : rels.values()) {
            if (r.external || r.target == null) continue;
            String t = OoxmlUtil.relType(r);
            String p = OoxmlUtil.resolve(dir, r.target);
            if (t.equals("styles")) stylesPath = p;
            else if (t.equals("theme")) themePath = p;
            else if (t.equals("sharedStrings")) sstPath = p;
        }
        if (themePath != null) parseTheme(themePath);
        if (stylesPath != null) parseStyles(stylesPath);
        if (xfs.isEmpty()) xfs.add(new Xf());
        if (sstPath != null) parseShared(sstPath);

        List<Sheet> sheets = new ArrayList<>();
        Element sheetsEl = OoxmlUtil.child(wbRoot, "sheets");
        if (sheetsEl != null) {
            for (Element s : OoxmlUtil.childEls(sheetsEl, "sheet")) {
                String state = OoxmlUtil.attr(s, "state");
                if (state != null && (state.equals("hidden") || state.equals("veryHidden"))) continue;
                OoxmlUtil.Rel r = rels.get(OoxmlUtil.attr(s, "id"));
                if (r == null || r.target == null) continue;
                Sheet sh = new Sheet();
                sh.name = OoxmlUtil.attr(s, "name");
                if (sh.name == null) sh.name = "Sheet" + (sheets.size() + 1);
                sh.path = OoxmlUtil.resolve(dir, r.target);
                sh.chart = OoxmlUtil.relType(r).equals("chartsheet");
                sheets.add(sh);
            }
        }
        if (sheets.isEmpty()) return HtmlPage.errorFragment("لا توجد أوراق قابلة للعرض في هذا الملف");

        for (Sheet sh : sheets) {
            if (sh.chart) continue;
            try {
                loadSheet(sh);
            } catch (Exception e) {
                sh.rows.clear();
                sh.maxRow = 0;
            }
        }

        StringBuilder panes = new StringBuilder(1 << 16);
        StringBuilder tabs = new StringBuilder();
        StringBuilder inputs = new StringBuilder();
        StringBuilder sheetCss = new StringBuilder();
        for (int i = 0; i < sheets.size(); i++) {
            Sheet sh = sheets.get(i);
            inputs.append("<input type=\"radio\" name=\"xlsh\" id=\"sh").append(i).append('"').append(i == 0 ? " checked" : "").append('>');
            tabs.append("<label for=\"sh").append(i).append('"');
            if (sh.tabColor != null) tabs.append(" style=\"--tc:").append(sh.tabColor).append('"');
            tabs.append('>').append(HtmlPage.esc(sh.name)).append("</label>");
            sheetCss.append("#sh").append(i).append(":checked~.xl-panes #p").append(i).append("{display:block}")
                    .append("#sh").append(i).append(":checked~.xl-tabs label[for=sh").append(i).append("]{background:#fff;color:#1F1E1D;font-weight:600;box-shadow:inset 0 -3px 0 var(--tc,#217346)}");
            panes.append("<div class=\"xl-pane\" id=\"p").append(i).append("\">");
            if (sh.chart) panes.append("<div class=\"xl-msg\">[ورقة مخطط]</div>");
            else panes.append(renderSheet(sh));
            panes.append("</div>");
        }

        StringBuilder out = new StringBuilder(panes.length() + 8192);
        out.append("<style>").append(css(sheetCss.toString())).append("</style>");
        out.append("<div class=\"xl").append(sheets.size() == 1 ? " single" : "").append("\">")
                .append(inputs).append("<div class=\"xl-panes\">").append(panes).append("</div>");
        if (sheets.size() > 1) out.append("<div class=\"xl-tabs\">").append(tabs).append("</div>");
        out.append("</div>");
        return out.toString();
    }

    // ------------------------------------------------------------------ الثيم والألوان

    private void parseTheme(String path) {
        Document d = OoxmlUtil.parsePart(zip, path, MAX_SMALL_XML);
        if (d == null) return;
        Element scheme = OoxmlUtil.descendant(d.getDocumentElement(), "clrScheme");
        if (scheme == null) return;
        Map<String, String> m = new HashMap<>();
        for (Element c : OoxmlUtil.childEls(scheme)) {
            String name = OoxmlUtil.local(c);
            String hex = null;
            Element clr = null;
            for (Element x : OoxmlUtil.childEls(c)) { clr = x; break; }
            if (clr != null) {
                String v = OoxmlUtil.attr(clr, "val");
                if (OoxmlUtil.local(clr).equals("sysClr")) v = OoxmlUtil.attr(clr, "lastClr");
                if (v != null && v.matches("[0-9A-Fa-f]{6}")) hex = "#" + v.toUpperCase(Locale.ROOT);
            }
            if (hex != null) m.put(name, hex);
        }
        String[] order = {"lt1", "dk1", "lt2", "dk2", "accent1", "accent2", "accent3", "accent4", "accent5", "accent6", "hlink", "folHlink"};
        for (int i = 0; i < order.length; i++) {
            String v = m.get(order[i]);
            if (v != null) theme[i] = v;
        }
    }

    private String colorOf(Element e) {
        if (e == null) return null;
        String rgb = OoxmlUtil.attr(e, "rgb");
        String base = null;
        if (rgb != null) {
            String h = rgb.length() == 8 ? rgb.substring(2) : rgb;
            if (h.matches("[0-9A-Fa-f]{6}")) base = "#" + h.toUpperCase(Locale.ROOT);
        } else {
            Integer th = OoxmlUtil.intAttr(e, "theme");
            Integer idx = OoxmlUtil.intAttr(e, "indexed");
            if (th != null && th >= 0 && th < theme.length) base = theme[th];
            else if (idx != null) {
                if (palette != null && idx >= 0 && idx < palette.length && palette[idx] != null) base = palette[idx];
                else if (idx >= 0 && idx < INDEXED.length) base = "#" + INDEXED[idx];
                else if (idx == 64) base = "#000000";
                else if (idx == 65) base = "#FFFFFF";
            }
        }
        if (base == null) return null;
        Double tint = OoxmlUtil.dblAttr(e, "tint");
        if (tint != null && tint != 0) base = OoxmlUtil.excelTint(base, tint);
        return base;
    }

    private static String colorFromAttrs(XlsxHtmlRenderer r, Attributes a) {
        // نبني عنصراً افتراضياً للاستفادة من colorOf
        String rgb = attrOf(a, "rgb"), th = attrOf(a, "theme"), ix = attrOf(a, "indexed"), tint = attrOf(a, "tint");
        String base = null;
        if (rgb != null) {
            String h = rgb.length() == 8 ? rgb.substring(2) : rgb;
            if (h.matches("[0-9A-Fa-f]{6}")) base = "#" + h.toUpperCase(Locale.ROOT);
        } else if (th != null) {
            try {
                int t = Integer.parseInt(th.trim());
                if (t >= 0 && t < r.theme.length) base = r.theme[t];
            } catch (NumberFormatException ignore) { }
        } else if (ix != null) {
            try {
                int t = Integer.parseInt(ix.trim());
                if (r.palette != null && t >= 0 && t < r.palette.length && r.palette[t] != null) base = r.palette[t];
                else if (t >= 0 && t < INDEXED.length) base = "#" + INDEXED[t];
                else if (t == 64) base = "#000000";
                else if (t == 65) base = "#FFFFFF";
            } catch (NumberFormatException ignore) { }
        }
        if (base != null && tint != null) {
            try {
                double tv = Double.parseDouble(tint);
                if (tv != 0) base = OoxmlUtil.excelTint(base, tv);
            } catch (NumberFormatException ignore) { }
        }
        return base;
    }

    // ------------------------------------------------------------------ الأنماط

    private void parseStyles(String path) {
        Document d = OoxmlUtil.parsePart(zip, path, MAX_SMALL_XML);
        if (d == null) return;
        Element root = d.getDocumentElement();

        Element colors = OoxmlUtil.child(root, "colors");
        if (colors != null) {
            Element ind = OoxmlUtil.child(colors, "indexedColors");
            if (ind != null) {
                List<Element> l = OoxmlUtil.childEls(ind, "rgbColor");
                palette = new String[l.size()];
                for (int i = 0; i < l.size(); i++) {
                    String v = OoxmlUtil.attr(l.get(i), "rgb");
                    if (v != null) {
                        String h = v.length() == 8 ? v.substring(2) : v;
                        if (h.matches("[0-9A-Fa-f]{6}")) palette[i] = "#" + h.toUpperCase(Locale.ROOT);
                    }
                }
            }
        }

        Element nf = OoxmlUtil.child(root, "numFmts");
        if (nf != null) {
            for (Element e : OoxmlUtil.childEls(nf, "numFmt")) {
                Integer id = OoxmlUtil.intAttr(e, "numFmtId");
                String code = OoxmlUtil.attr(e, "formatCode");
                if (id != null && code != null) numFmts.put(id, code);
            }
        }

        // الخطوط
        List<String[]> fonts = new ArrayList<>();  // {css, sizePt}
        Element fe = OoxmlUtil.child(root, "fonts");
        if (fe != null) {
            for (Element f : OoxmlUtil.childEls(fe, "font")) fonts.add(fontCss(f));
        }
        if (!fonts.isEmpty()) {
            try { defaultFontPt = Double.parseDouble(fonts.get(0)[1]); } catch (RuntimeException ignore) { }
        }

        // التعبئة
        List<String> fills = new ArrayList<>();
        Element fl = OoxmlUtil.child(root, "fills");
        if (fl != null) {
            for (Element f : OoxmlUtil.childEls(fl, "fill")) fills.add(fillColor(f, false));
        }

        // الحدود
        List<String[]> borders = new ArrayList<>();  // {left,right,top,bottom}
        Element bl = OoxmlUtil.child(root, "borders");
        if (bl != null) {
            for (Element b : OoxmlUtil.childEls(bl, "border")) {
                String[] s = new String[4];
                s[0] = borderCss(OoxmlUtil.child(b, "left") != null ? OoxmlUtil.child(b, "left") : OoxmlUtil.child(b, "start"), "left");
                s[1] = borderCss(OoxmlUtil.child(b, "right") != null ? OoxmlUtil.child(b, "right") : OoxmlUtil.child(b, "end"), "right");
                s[2] = borderCss(OoxmlUtil.child(b, "top"), "top");
                s[3] = borderCss(OoxmlUtil.child(b, "bottom"), "bottom");
                borders.add(s);
            }
        }

        Element cx = OoxmlUtil.child(root, "cellXfs");
        if (cx != null) {
            for (Element x : OoxmlUtil.childEls(cx, "xf")) {
                Xf xf = new Xf();
                StringBuilder css = new StringBuilder();
                Integer nid = OoxmlUtil.intAttr(x, "numFmtId");
                if (nid != null && nid != 0) {
                    String code = numFmts.get(nid);
                    if (code == null) code = XlsxNumFmt.builtinCode(nid);
                    xf.code = code;
                }
                Integer fid = OoxmlUtil.intAttr(x, "fontId");
                if (fid != null && fid >= 0 && fid < fonts.size()) {
                    css.append(fonts.get(fid)[0]);
                    try {
                        double pt = Double.parseDouble(fonts.get(fid)[1]);
                        xf.fontPx = pt * 96.0 / 72.0;
                    } catch (RuntimeException ignore) { }
                } else xf.fontPx = defaultFontPt * 96.0 / 72.0;
                Integer fill = OoxmlUtil.intAttr(x, "fillId");
                if (fill != null && fill >= 0 && fill < fills.size() && fills.get(fill) != null) {
                    css.append("background-color:").append(fills.get(fill)).append(';');
                }
                Integer bid = OoxmlUtil.intAttr(x, "borderId");
                if (bid != null && bid >= 0 && bid < borders.size()) {
                    String[] b = borders.get(bid);
                    if (b[0] != null) { css.append(b[0]); xf.hasLeft = true; }
                    if (b[1] != null) { css.append(b[1]); xf.hasRight = true; }
                    if (b[2] != null) { css.append(b[2]); xf.hasTop = true; }
                    if (b[3] != null) { css.append(b[3]); xf.hasBottom = true; }
                }
                Element al = OoxmlUtil.child(x, "alignment");
                if (al != null) {
                    String h = OoxmlUtil.attr(al, "horizontal");
                    if (h != null) {
                        switch (h) {
                            case "left": css.append("text-align:left;"); xf.hAlign = 'l'; xf.generalAlign = false; break;
                            case "right": css.append("text-align:right;"); xf.hAlign = 'r'; xf.generalAlign = false; break;
                            case "center": case "centerContinuous": css.append("text-align:center;"); xf.hAlign = 'c'; xf.generalAlign = false; break;
                            case "justify": case "distributed": css.append("text-align:justify;"); xf.hAlign = 'j'; xf.generalAlign = false; break;
                            default: break;
                        }
                    }
                    String v = OoxmlUtil.attr(al, "vertical");
                    if (v != null) {
                        if (v.equals("top")) css.append("vertical-align:top;");
                        else if (v.equals("center")) css.append("vertical-align:middle;");
                    }
                    String w = OoxmlUtil.attr(al, "wrapText");
                    if ("1".equals(w) || "true".equalsIgnoreCase(w)) {
                        xf.wrap = true;
                        css.append("white-space:pre-wrap;overflow-wrap:anywhere;");
                    }
                    Integer ind = OoxmlUtil.intAttr(al, "indent");
                    if (ind != null && ind > 0) {
                        int px = 3 + ind * 9;
                        if (xf.hAlign == 'l') css.append("padding-left:").append(px).append("px;");
                        else if (xf.hAlign == 'r') css.append("padding-right:").append(px).append("px;");
                        else if (xf.hAlign == 'g') css.append("padding-inline-start:").append(px).append("px;");
                    }
                    Integer rot = OoxmlUtil.intAttr(al, "textRotation");
                    if (rot != null && rot == 255) css.append("writing-mode:vertical-rl;text-orientation:upright;");
                }
                xf.css = css.toString();
                xfs.add(xf);
            }
        }

        Element dx = OoxmlUtil.child(root, "dxfs");
        if (dx != null) {
            for (Element x : OoxmlUtil.childEls(dx, "dxf")) {
                StringBuilder css = new StringBuilder();
                Element f = OoxmlUtil.child(x, "font");
                if (f != null) css.append(fontCss(f)[0]);
                Element fill = OoxmlUtil.child(x, "fill");
                if (fill != null) {
                    String c = fillColor(fill, true);
                    if (c != null) css.append("background-color:").append(c).append(';');
                }
                Element b = OoxmlUtil.child(x, "border");
                if (b != null) {
                    String[] names = {"left", "right", "top", "bottom"};
                    for (String n : names) {
                        String s = borderCss(OoxmlUtil.child(b, n), n);
                        if (s != null) css.append(s);
                    }
                }
                dxfCss.add(css.toString());
            }
        }
    }

    /** {css, sizePt} */
    private String[] fontCss(Element f) {
        StringBuilder css = new StringBuilder();
        String size = String.valueOf(defaultFontPt);
        Element b = OoxmlUtil.child(f, "b");
        if (b != null && OoxmlUtil.flag(b)) css.append("font-weight:bold;");
        Element i = OoxmlUtil.child(f, "i");
        if (i != null && OoxmlUtil.flag(i)) css.append("font-style:italic;");
        StringBuilder deco = new StringBuilder();
        Element u = OoxmlUtil.child(f, "u");
        if (u != null) {
            String v = OoxmlUtil.attr(u, "val");
            if (v == null || !v.equals("none")) deco.append("underline");
        }
        Element st = OoxmlUtil.child(f, "strike");
        if (st != null && OoxmlUtil.flag(st)) deco.append(deco.length() > 0 ? " " : "").append("line-through");
        if (deco.length() > 0) css.append("text-decoration:").append(deco).append(';');
        Element sz = OoxmlUtil.child(f, "sz");
        Double pt = sz == null ? null : OoxmlUtil.dblAttr(sz, "val");
        if (pt != null && pt > 0 && pt < 200) {
            size = String.valueOf(pt);
            if (Math.abs(pt - defaultFontPt) > 0.01) css.append("font-size:").append(trim(pt)).append("pt;");
        }
        String col = colorOf(OoxmlUtil.child(f, "color"));
        if (col != null && !col.equals("#000000")) css.append("color:").append(col).append(';');
        Element va = OoxmlUtil.child(f, "vertAlign");
        if (va != null) {
            String v = OoxmlUtil.attr(va, "val");
            if ("superscript".equals(v)) css.append("vertical-align:super;font-size:.75em;");
            else if ("subscript".equals(v)) css.append("vertical-align:sub;font-size:.75em;");
        }
        Element nm = OoxmlUtil.child(f, "name");
        String name = nm == null ? null : OoxmlUtil.attr(nm, "val");
        if (name != null) {
            String n = name.toLowerCase(Locale.ROOT);
            if (n.contains("courier") || n.contains("consolas") || n.contains("mono")) css.append("font-family:Consolas,\"Courier New\",monospace;");
            else if (n.contains("times") || n.contains("traditional arabic") || n.contains("simplified arabic")
                    || n.contains("cambria") || n.contains("georgia") || n.contains("garamond")) {
                css.append("font-family:\"Noto Naskh Arabic\",\"Times New Roman\",serif;");
            }
        }
        return new String[]{css.toString(), size};
    }

    private String fillColor(Element fill, boolean dxf) {
        Element pf = OoxmlUtil.child(fill, "patternFill");
        if (pf != null) {
            String type = OoxmlUtil.attr(pf, "patternType");
            if (type == null && !dxf) return null;
            if (type != null && (type.equals("none") || type.equals("gray125"))) return null;
            Element fg = OoxmlUtil.child(pf, "fgColor");
            Element bg = OoxmlUtil.child(pf, "bgColor");
            String c;
            if (dxf) {
                // في dxf تكون التعبئة الصلبة في bgColor
                c = colorOf(bg);
                if (c == null) c = colorOf(fg);
            } else {
                c = colorOf(fg);
                if (c == null && (type == null || type.equals("solid"))) c = colorOf(bg);
                if (c == null && type != null && !type.equals("solid")) c = colorOf(bg);
            }
            return c;
        }
        Element gf = OoxmlUtil.child(fill, "gradientFill");
        if (gf != null) {
            Element stop = OoxmlUtil.child(gf, "stop");
            if (stop != null) return colorOf(OoxmlUtil.child(stop, "color"));
        }
        return null;
    }

    private String borderCss(Element side, String name) {
        if (side == null) return null;
        String style = OoxmlUtil.attr(side, "style");
        if (style == null || style.equals("none")) return null;
        String w, st;
        switch (style) {
            case "medium": w = "2px"; st = "solid"; break;
            case "thick": w = "3px"; st = "solid"; break;
            case "dashed": case "dashDot": case "dashDotDot": w = "1px"; st = "dashed"; break;
            case "mediumDashed": case "mediumDashDot": case "mediumDashDotDot": case "slantDashDot": w = "2px"; st = "dashed"; break;
            case "dotted": case "hair": w = "1px"; st = "dotted"; break;
            case "double": w = "3px"; st = "double"; break;
            default: w = "1px"; st = "solid";
        }
        String c = colorOf(OoxmlUtil.child(side, "color"));
        if (c == null) c = "#000000";
        return "border-" + name + ":" + w + " " + st + " " + c + ";";
    }

    private static String trim(double v) {
        if (v == Math.rint(v)) return String.valueOf((long) v);
        return String.format(Locale.ROOT, "%.2f", v).replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    // ------------------------------------------------------------------ SAX

    private static SAXParser newSax() throws Exception {
        SAXParserFactory f = SAXParserFactory.newInstance();
        f.setNamespaceAware(false);
        try { f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true); } catch (Exception ignore) { }
        try { f.setFeature("http://xml.org/sax/features/external-general-entities", false); } catch (Exception ignore) { }
        try { f.setFeature("http://xml.org/sax/features/external-parameter-entities", false); } catch (Exception ignore) { }
        return f.newSAXParser();
    }

    private static void runSax(ZipFile zip, String path, DefaultHandler h) throws Exception {
        try (InputStream in = OoxmlUtil.open(zip, path)) {
            if (in == null) throw new IllegalStateException("missing " + path);
            InputSource src = new InputSource(in);
            SAXParser p = newSax();
            p.getXMLReader().setEntityResolver((pub, sys) -> new InputSource(new StringReader("")));
            p.parse(src, h);
        }
    }

    private static String ln(String qName) {
        int i = qName.indexOf(':');
        return i < 0 ? qName : qName.substring(i + 1);
    }

    private static String attrOf(Attributes a, String name) {
        String v = a.getValue(name);
        if (v != null) return v;
        for (int i = 0; i < a.getLength(); i++) {
            String q = a.getQName(i);
            if (q.equals(name) || q.endsWith(":" + name)) return a.getValue(i);
        }
        return null;
    }

    private static int intOf(String s, int def) {
        if (s == null) return def;
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return def; }
    }

    private static double dblOf(String s, double def) {
        if (s == null) return def;
        try { return Double.parseDouble(s.trim()); } catch (NumberFormatException e) { return def; }
    }

    private static boolean boolOf(String s, boolean def) {
        if (s == null) return def;
        return s.equals("1") || s.equalsIgnoreCase("true");
    }

    // ---- السلاسل المشتركة

    private void parseShared(String path) throws Exception {
        final List<String> plain = new ArrayList<>();
        final List<String> rich = new ArrayList<>();
        runSax(zip, path, new DefaultHandler() {
            StringBuilder si, siRich, runText;
            boolean inT, inRPh, inR, anyRich;
            StringBuilder runCss;
            boolean inRPr;

            @Override public void startElement(String u, String l, String q, Attributes a) {
                String n = ln(q);
                switch (n) {
                    case "si": si = new StringBuilder(); siRich = new StringBuilder(); anyRich = false; break;
                    case "rPh": inRPh = true; break;
                    case "r": inR = true; runText = new StringBuilder(); runCss = new StringBuilder(); break;
                    case "t": if (!inRPh) { inT = true; if (!inR) runText = new StringBuilder(); } break;
                    case "rPr": inRPr = true; break;
                    case "b": if (inR && inRPr && boolOf(attrOf(a, "val"), true)) runCss.append("font-weight:bold;"); break;
                    case "i": if (inR && inRPr && boolOf(attrOf(a, "val"), true)) runCss.append("font-style:italic;"); break;
                    case "u": if (inR && inRPr && !"none".equals(attrOf(a, "val"))) runCss.append("text-decoration:underline;"); break;
                    case "strike": if (inR && inRPr && boolOf(attrOf(a, "val"), true)) runCss.append("text-decoration:line-through;"); break;
                    case "color": if (inR && inRPr) {
                        String c = colorFromAttrs(XlsxHtmlRenderer.this, a);
                        if (c != null && !c.equals("#000000")) runCss.append("color:").append(c).append(';');
                    } break;
                    case "vertAlign": if (inR && inRPr) {
                        String v = attrOf(a, "val");
                        if ("superscript".equals(v)) runCss.append("vertical-align:super;font-size:.75em;");
                        else if ("subscript".equals(v)) runCss.append("vertical-align:sub;font-size:.75em;");
                    } break;
                    default: break;
                }
            }

            @Override public void characters(char[] ch, int s, int len) {
                if (inT && runText != null) runText.append(ch, s, len);
            }

            @Override public void endElement(String u, String l, String q) {
                String n = ln(q);
                switch (n) {
                    case "t":
                        if (inT) {
                            inT = false;
                            if (!inR && si != null) {
                                si.append(runText);
                                siRich.append(HtmlPage.esc(runText.toString()));
                            }
                        }
                        break;
                    case "rPr": inRPr = false; break;
                    case "rPh": inRPh = false; break;
                    case "r":
                        if (inR && si != null) {
                            si.append(runText);
                            String esc = HtmlPage.esc(runText.toString());
                            if (runCss.length() > 0) { siRich.append("<span style=\"").append(runCss).append("\">").append(esc).append("</span>"); anyRich = true; }
                            else siRich.append(esc);
                        }
                        inR = false;
                        break;
                    case "si":
                        plain.add(si == null ? "" : si.toString());
                        rich.add(anyRich ? siRich.toString() : null);
                        si = null;
                        break;
                    default: break;
                }
            }
        });
        sstPlain = plain.toArray(new String[0]);
        sstRich = rich.toArray(new String[0]);
    }

    // ---- الورقة

    private void loadSheet(final Sheet sh) throws Exception {
        final Xf zero = xfs.get(0);
        runSax(zip, sh.path, new DefaultHandler() {
            boolean inData, capV, capF, capT, inIs, inRPh, capCfF, inColorScale, inDataBar;
            RowData row;
            int rowNum;
            Cell cell;
            int cellCol;
            boolean cellStore;
            String cellType;
            StringBuilder vBuf = new StringBuilder(), fBuf = new StringBuilder(), isBuf = new StringBuilder();
            StringBuilder cfBuf = new StringBuilder();
            CfRule rule;
            List<int[]> cfRanges;

            @Override public void startElement(String u, String l, String q, Attributes a) {
                String n = ln(q);
                switch (n) {
                    case "tabColor": sh.tabColor = colorFromAttrs(XlsxHtmlRenderer.this, a); break;
                    case "sheetFormatPr": {
                        sh.defRowPt = dblOf(attrOf(a, "defaultRowHeight"), DEFAULT_ROW_PT);
                        String dcw = attrOf(a, "defaultColWidth");
                        if (dcw != null) sh.defColPx = Math.round(dblOf(dcw, 9.14) * 7);
                        else {
                            double base = dblOf(attrOf(a, "baseColWidth"), 8);
                            sh.defColPx = Math.round(Math.floor((base * 7 + 5) / 8.0 + 0.999) * 8);
                            if (sh.defColPx < 48) sh.defColPx = DEFAULT_COL_PX;
                        }
                        break;
                    }
                    case "sheetView":
                        sh.showGrid = boolOf(attrOf(a, "showGridLines"), true);
                        sh.rtl = boolOf(attrOf(a, "rightToLeft"), false);
                        break;
                    case "pane": {
                        String state = attrOf(a, "state");
                        if (state != null && state.startsWith("frozen")) {
                            sh.xSplit = intOf(attrOf(a, "xSplit"), 0);
                            sh.ySplit = intOf(attrOf(a, "ySplit"), 0);
                        }
                        break;
                    }
                    case "col": {
                        int min = intOf(attrOf(a, "min"), 1), max = intOf(attrOf(a, "max"), min);
                        double w = dblOf(attrOf(a, "width"), -1);
                        boolean hid = boolOf(attrOf(a, "hidden"), false);
                        for (int c = Math.max(1, min); c <= Math.min(max, MAX_COLS); c++) {
                            if (w >= 0) { sh.colPx[c] = Math.round(w * 7); sh.colWidthSet = true; }
                            sh.colHidden[c] = hid;
                        }
                        break;
                    }
                    case "sheetData": inData = true; break;
                    case "row":
                        if (!inData) break;
                        rowNum = intOf(attrOf(a, "r"), rowNum + 1);
                        sh.fileRows = Math.max(sh.fileRows, rowNum);
                        if (rowNum <= MAX_ROWS) {
                            row = sh.rows.get(rowNum);
                            if (row == null) { row = new RowData(); row.r = rowNum; sh.rows.put(rowNum, row); }
                            String ht = attrOf(a, "ht");
                            if (ht != null) row.htPt = dblOf(ht, -1);
                            row.hidden = boolOf(attrOf(a, "hidden"), false);
                        } else {
                            row = null;
                            sh.truncRows = true;
                        }
                        break;
                    case "c":
                        if (!inData) break;
                        String ref = attrOf(a, "r");
                        cellCol = ref == null ? cellCol + 1 : colOf(ref);
                        cellType = attrOf(a, "t");
                        cell = new Cell();
                        cell.xf = intOf(attrOf(a, "s"), 0);
                        cellStore = row != null && cellCol >= 1;
                        if (cellCol > MAX_COLS) { cellStore = false; sh.truncCols = true; }
                        vBuf.setLength(0);
                        fBuf.setLength(0);
                        isBuf.setLength(0);
                        break;
                    case "v": capV = cell != null; vBuf.setLength(0); break;
                    case "f": capF = cell != null; fBuf.setLength(0); break;
                    case "is": inIs = true; isBuf.setLength(0); break;
                    case "rPh": inRPh = true; break;
                    case "t": if (inIs && !inRPh) capT = true; break;
                    case "mergeCell": {
                        int[] rg = rangeOf(attrOf(a, "ref"));
                        if (rg != null) {
                            Merge m = new Merge();
                            m.r1 = rg[0]; m.c1 = rg[1]; m.r2 = rg[2]; m.c2 = rg[3];
                            sh.merges.add(m);
                        }
                        break;
                    }
                    case "hyperlink":
                        sh.hyperlinks.add(new String[]{attrOf(a, "ref"), attrOf(a, "id"), attrOf(a, "location")});
                        break;
                    case "conditionalFormatting":
                        cfRanges = new ArrayList<>();
                        String sq = attrOf(a, "sqref");
                        if (sq != null) {
                            for (String part : sq.trim().split("\\s+")) {
                                int[] rg = rangeOf(part);
                                if (rg != null) cfRanges.add(rg);
                            }
                        }
                        break;
                    case "cfRule":
                        rule = new CfRule();
                        rule.type = attrOf(a, "type");
                        rule.operator = attrOf(a, "operator");
                        rule.text = attrOf(a, "text");
                        rule.priority = intOf(attrOf(a, "priority"), 9999);
                        rule.dxfId = intOf(attrOf(a, "dxfId"), -1);
                        rule.rank = intOf(attrOf(a, "rank"), 10);
                        rule.percent = boolOf(attrOf(a, "percent"), false);
                        rule.bottom = boolOf(attrOf(a, "bottom"), false);
                        rule.aboveAverage = boolOf(attrOf(a, "aboveAverage"), true);
                        rule.equalAverage = boolOf(attrOf(a, "equalAverage"), false);
                        if (cfRanges != null) rule.ranges.addAll(cfRanges);
                        break;
                    case "formula": if (rule != null) { capCfF = true; cfBuf.setLength(0); } break;
                    case "colorScale": inColorScale = true; break;
                    case "dataBar": inDataBar = true; break;
                    case "cfvo": if (rule != null) rule.cfvo.add(new String[]{attrOf(a, "type"), attrOf(a, "val")}); break;
                    case "color":
                        if (rule != null && (inColorScale || inDataBar)) {
                            String c = colorFromAttrs(XlsxHtmlRenderer.this, a);
                            if (c != null) {
                                if (inColorScale) rule.colors.add(c);
                                else rule.barColor = c;
                            }
                        }
                        break;
                    case "drawing": sh.drawingRid = attrOf(a, "id"); break;
                    case "tablePart": { String id = attrOf(a, "id"); if (id != null) sh.tableRids.add(id); break; }
                    default: break;
                }
            }

            @Override public void characters(char[] ch, int s, int len) {
                if (capV) vBuf.append(ch, s, len);
                else if (capF) fBuf.append(ch, s, len);
                else if (capT) isBuf.append(ch, s, len);
                else if (capCfF) cfBuf.append(ch, s, len);
            }

            @Override public void endElement(String u, String l, String q) {
                String n = ln(q);
                switch (n) {
                    case "v": capV = false; break;
                    case "f": capF = false; break;
                    case "t": capT = false; break;
                    case "is": inIs = false; break;
                    case "rPh": inRPh = false; break;
                    case "sheetData": inData = false; break;
                    case "colorScale": inColorScale = false; break;
                    case "dataBar": inDataBar = false; break;
                    case "formula": if (capCfF && rule != null) rule.formulas.add(cfBuf.toString().trim()); capCfF = false; break;
                    case "cfRule": if (rule != null) { sh.cf.add(rule); rule = null; } break;
                    case "c": finishCell(); break;
                    default: break;
                }
            }

            void finishCell() {
                if (cell == null) return;
                Cell c = cell;
                cell = null;
                if (!cellStore) return;
                if (totalCells >= MAX_CELLS_TOTAL) { sh.truncCells = true; return; }
                Xf xf = c.xf >= 0 && c.xf < xfs.size() ? xfs.get(c.xf) : zero;
                String v = vBuf.toString();
                String t = cellType;
                if (t == null) t = "n";
                switch (t) {
                    case "s": {
                        int i = intOf(v, -1);
                        if (i >= 0 && i < sstPlain.length) {
                            c.plain = sstPlain[i];
                            c.rich = sstRich[i];
                            c.kind = K_STR;
                        }
                        break;
                    }
                    case "str": c.plain = v; c.kind = K_STR; break;
                    case "inlineStr": c.plain = isBuf.toString(); c.kind = K_STR; break;
                    case "b": c.plain = v.trim().equals("1") ? "TRUE" : "FALSE"; c.kind = K_BOOL; break;
                    case "e": c.plain = v; c.kind = K_BOOL; break;
                    case "d": {
                        XlsxNumFmt.Result r = fmt.formatIsoDate(v, xf.code);
                        c.plain = r.text; c.kind = K_NUM; break;
                    }
                    default: {
                        if (!v.isEmpty()) {
                            try {
                                double d = Double.parseDouble(v.trim());
                                c.num = d;
                                c.hasNum = true;
                                XlsxNumFmt.Result r = fmt.formatNumber(d, xf.code);
                                c.plain = r.text;
                                c.color = r.color;
                                c.kind = K_NUM;
                            } catch (NumberFormatException e) {
                                c.plain = v;
                                c.kind = K_STR;
                            }
                        }
                    }
                }
                if (c.kind == K_STR && c.plain != null && xf.code != null && xf.code.indexOf('@') >= 0 && xf.code.length() > 1) {
                    XlsxNumFmt.Result r = fmt.formatText(c.plain, xf.code);
                    c.plain = r.text;
                    if (r.color != null) c.color = r.color;
                    c.rich = null;
                }
                // روابط HYPERLINK("url","name")
                if (fBuf.length() > 0 && c.kind != K_BLANK) {
                    Matcher m = HYPERLINK.matcher(fBuf);
                    if (m.find()) {
                        String href = HtmlPage.safeHref(m.group(1));
                        if (href != null) { c.link = href; c.formulaLink = true; }
                    }
                }
                boolean blank = c.plain == null || c.plain.isEmpty();
                if (blank) {
                    // الخلايا الفارغة تُحفظ فقط إن كانت ذات تعبئة أو حدود ظاهرة
                    if (!xf.css.contains("background-color") && !xf.css.contains("border-")) return;
                    c.kind = K_BLANK;
                }
                totalCells++;
                row.set(cellCol, c);
                if (row.maxCol > sh.maxCol) sh.maxCol = row.maxCol;
                if (rowNum > sh.maxRow) sh.maxRow = rowNum;
            }
        });

        // ما بعد التحليل: الروابط، الدمج، التعليقات، الجداول، الرسومات
        for (Merge m : sh.merges) {
            sh.maxCol = Math.max(sh.maxCol, Math.min(m.c2, MAX_COLS));
            sh.maxRow = Math.max(sh.maxRow, Math.min(m.r2, MAX_ROWS));
        }
        sh.maxRow = Math.min(sh.maxRow, MAX_ROWS);
        sh.maxCol = Math.min(sh.maxCol, MAX_COLS);

        Map<String, OoxmlUtil.Rel> rels = OoxmlUtil.readRels(zip, sh.path);
        String dir = OoxmlUtil.dirOf(sh.path);

        for (String[] h : sh.hyperlinks) {
            if (h[0] == null) continue;
            String href = null;
            if (h[1] != null) {
                OoxmlUtil.Rel r = rels.get(h[1]);
                if (r != null && r.external) href = HtmlPage.safeHref(r.target);
            }
            if (href == null) continue;
            int[] rg = rangeOf(h[0]);
            if (rg == null) continue;
            int count = 0;
            for (int r = rg[0]; r <= rg[2] && r <= MAX_ROWS; r++) {
                RowData row = sh.rows.get(r);
                if (row == null) continue;
                for (int c = rg[1]; c <= rg[3] && c <= MAX_COLS; c++) {
                    Cell cell = row.get(c);
                    if (cell != null && cell.kind != K_BLANK && cell.link == null) cell.link = href;
                    if (++count > 5000) break;
                }
            }
        }

        for (OoxmlUtil.Rel r : rels.values()) {
            if (r.external || r.target == null) continue;
            String t = OoxmlUtil.relType(r);
            String p = OoxmlUtil.resolve(dir, r.target);
            if (t.equals("comments")) loadComments(sh, p);
        }
        for (String rid : sh.tableRids) {
            OoxmlUtil.Rel r = rels.get(rid);
            if (r != null && r.target != null && !r.external) loadTable(sh, OoxmlUtil.resolve(dir, r.target));
        }
    }

    private static final Pattern HYPERLINK = Pattern.compile("(?i)HYPERLINK\\(\\s*\"([^\"]+)\"");

    private void loadComments(Sheet sh, String path) {
        Document d = OoxmlUtil.parsePart(zip, path, MAX_SMALL_XML);
        if (d == null) return;
        Element list = OoxmlUtil.descendant(d.getDocumentElement(), "commentList");
        if (list == null) return;
        for (Element c : OoxmlUtil.childEls(list, "comment")) {
            String ref = OoxmlUtil.attr(c, "ref");
            if (ref == null) continue;
            List<Element> ts = new ArrayList<>();
            OoxmlUtil.descendants(c, "t", ts);
            StringBuilder sb = new StringBuilder();
            for (Element t : ts) sb.append(t.getTextContent());
            String text = sb.toString().trim();
            if (!text.isEmpty()) sh.comments.put(ref.toUpperCase(Locale.ROOT), text);
        }
    }

    private void loadTable(Sheet sh, String path) {
        Document d = OoxmlUtil.parsePart(zip, path, MAX_SMALL_XML);
        if (d == null) return;
        Element root = d.getDocumentElement();
        int[] rg = rangeOf(OoxmlUtil.attr(root, "ref"));
        Element si = OoxmlUtil.child(root, "tableStyleInfo");
        if (rg == null || si == null) return;
        String name = OoxmlUtil.attr(si, "name");
        if (name == null) return;
        Integer header = OoxmlUtil.intAttr(root, "headerRowCount");
        boolean hasHeader = header == null || header > 0;
        sh.tables.add(new Object[]{rg, name, boolOf(OoxmlUtil.attr(si, "showRowStripes"), false),
                boolOf(OoxmlUtil.attr(si, "showColumnStripes"), false), boolOf(OoxmlUtil.attr(si, "showFirstColumn"), false), hasHeader,
                boolOf(OoxmlUtil.attr(root, "totalsRowShown"), false) && intOf(OoxmlUtil.attr(root, "totalsRowCount"), 0) > 0});
    }

    // ------------------------------------------------------------------ المراجع

    private static int colOf(String ref) {
        int c = 0, i = 0;
        while (i < ref.length()) {
            char ch = ref.charAt(i);
            if (ch >= 'A' && ch <= 'Z') c = c * 26 + (ch - 'A' + 1);
            else if (ch >= 'a' && ch <= 'z') c = c * 26 + (ch - 'a' + 1);
            else break;
            i++;
        }
        return c;
    }

    private static int rowOfRef(String ref) {
        int i = 0;
        while (i < ref.length() && Character.isLetter(ref.charAt(i))) i++;
        return intOf(ref.substring(i).replace("$", ""), 0);
    }

    private static int[] rangeOf(String ref) {
        if (ref == null) return null;
        String r = ref.replace("$", "");
        int bang = r.lastIndexOf('!');
        if (bang >= 0) r = r.substring(bang + 1);
        String a = r, b = r;
        int colon = r.indexOf(':');
        if (colon >= 0) { a = r.substring(0, colon); b = r.substring(colon + 1); }
        int c1 = colOf(a), c2 = colOf(b), r1 = rowOfRef(a), r2 = rowOfRef(b);
        if (c1 < 1 || c2 < 1 || r1 < 1 || r2 < 1) return null;
        return new int[]{Math.min(r1, r2), Math.min(c1, c2), Math.max(r1, r2), Math.max(c1, c2)};
    }

    static String colName(int c) {
        StringBuilder sb = new StringBuilder();
        while (c > 0) {
            int m = (c - 1) % 26;
            sb.insert(0, (char) ('A' + m));
            c = (c - 1) / 26;
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ التنسيق الشرطي

    private void applyConditionalFormats(Sheet sh) {
        if (sh.cf.isEmpty()) return;
        List<CfRule> rules = new ArrayList<>(sh.cf);
        // الأدنى رقماً = الأعلى أولوية؛ نطبّق الأدنى أولوية أولاً ليتغلّب الأعلى
        Collections.sort(rules, (x, y) -> Integer.compare(y.priority, x.priority));
        for (CfRule r : rules) {
            List<Cell> cells = new ArrayList<>();
            for (int[] rg : r.ranges) {
                for (int row = rg[0]; row <= Math.min(rg[2], sh.maxRow); row++) {
                    RowData rd = sh.rows.get(row);
                    if (rd == null) continue;
                    for (int c = rg[1]; c <= Math.min(rg[3], sh.maxCol); c++) {
                        Cell cell = rd.get(c);
                        if (cell != null) cells.add(cell);
                        if (cells.size() > 60000) break;
                    }
                }
            }
            if (cells.isEmpty() || r.type == null) continue;
            String dxf = r.dxfId >= 0 && r.dxfId < dxfCss.size() ? dxfCss.get(r.dxfId) : null;
            try {
                applyRule(r, cells, dxf);
            } catch (RuntimeException ignore) { }
        }
    }

    private void applyRule(CfRule r, List<Cell> cells, String dxf) {
        switch (r.type) {
            case "cellIs": {
                if (dxf == null || r.operator == null || r.formulas.isEmpty()) return;
                Double a = litNum(r.formulas.get(0));
                Double b = r.formulas.size() > 1 ? litNum(r.formulas.get(1)) : null;
                String sa = litStr(r.formulas.get(0));
                for (Cell c : cells) {
                    boolean hit;
                    if (c.hasNum && a != null) hit = cmp(r.operator, c.num, a, b);
                    else if (c.kind == K_STR && sa != null && (r.operator.equals("equal") || r.operator.equals("notEqual"))) {
                        boolean eq = c.plain.equalsIgnoreCase(sa);
                        hit = r.operator.equals("equal") == eq;
                    } else continue;
                    if (hit) addExtra(c, dxf);
                }
                break;
            }
            case "containsText": case "notContainsText": case "beginsWith": case "endsWith": {
                if (dxf == null || r.text == null) return;
                String needle = r.text.toLowerCase(Locale.ROOT);
                for (Cell c : cells) {
                    if (c.plain == null) continue;
                    String hay = c.plain.toLowerCase(Locale.ROOT);
                    boolean hit;
                    switch (r.type) {
                        case "containsText": hit = hay.contains(needle); break;
                        case "notContainsText": hit = !hay.contains(needle); break;
                        case "beginsWith": hit = hay.startsWith(needle); break;
                        default: hit = hay.endsWith(needle);
                    }
                    if (hit) addExtra(c, dxf);
                }
                break;
            }
            case "containsBlanks": case "notContainsBlanks": {
                if (dxf == null) return;
                for (Cell c : cells) {
                    boolean blank = c.kind == K_BLANK;
                    if (blank == r.type.equals("containsBlanks")) addExtra(c, dxf);
                }
                break;
            }
            case "containsErrors": case "notContainsErrors": {
                if (dxf == null) return;
                for (Cell c : cells) {
                    boolean err = c.kind == K_BOOL && c.plain != null && c.plain.startsWith("#");
                    if (err == r.type.equals("containsErrors")) addExtra(c, dxf);
                }
                break;
            }
            case "duplicateValues": case "uniqueValues": {
                if (dxf == null) return;
                Map<String, Integer> counts = new HashMap<>();
                for (Cell c : cells) if (c.kind != K_BLANK && c.plain != null) counts.merge(c.plain, 1, Integer::sum);
                for (Cell c : cells) {
                    if (c.kind == K_BLANK || c.plain == null) continue;
                    boolean dup = counts.get(c.plain) > 1;
                    if (dup == r.type.equals("duplicateValues")) addExtra(c, dxf);
                }
                break;
            }
            case "top10": {
                if (dxf == null) return;
                List<Double> vals = new ArrayList<>();
                for (Cell c : cells) if (c.hasNum) vals.add(c.num);
                if (vals.isEmpty()) return;
                Collections.sort(vals);
                int n = r.percent ? (int) Math.max(1, Math.floor(vals.size() * r.rank / 100.0)) : Math.max(1, r.rank);
                n = Math.min(n, vals.size());
                double threshold = r.bottom ? vals.get(n - 1) : vals.get(vals.size() - n);
                for (Cell c : cells) {
                    if (!c.hasNum) continue;
                    if (r.bottom ? c.num <= threshold : c.num >= threshold) addExtra(c, dxf);
                }
                break;
            }
            case "aboveAverage": {
                if (dxf == null) return;
                double sum = 0;
                int n = 0;
                for (Cell c : cells) if (c.hasNum) { sum += c.num; n++; }
                if (n == 0) return;
                double avg = sum / n;
                for (Cell c : cells) {
                    if (!c.hasNum) continue;
                    boolean hit = r.aboveAverage ? (r.equalAverage ? c.num >= avg : c.num > avg)
                            : (r.equalAverage ? c.num <= avg : c.num < avg);
                    if (hit) addExtra(c, dxf);
                }
                break;
            }
            case "colorScale": {
                if (r.colors.size() < 2 || r.cfvo.size() < 2) return;
                List<Double> vals = new ArrayList<>();
                for (Cell c : cells) if (c.hasNum) vals.add(c.num);
                if (vals.isEmpty()) return;
                Collections.sort(vals);
                int stops = Math.min(r.colors.size(), r.cfvo.size());
                double[] pos = new double[stops];
                for (int i = 0; i < stops; i++) pos[i] = cfvoValue(r.cfvo.get(i), vals);
                for (Cell c : cells) {
                    if (!c.hasNum) continue;
                    String col = scaleColor(c.num, pos, r.colors, stops);
                    addExtra(c, "background-color:" + col + ";");
                }
                break;
            }
            case "dataBar": {
                if (r.cfvo.size() < 2) return;
                List<Double> vals = new ArrayList<>();
                for (Cell c : cells) if (c.hasNum) vals.add(c.num);
                if (vals.isEmpty()) return;
                Collections.sort(vals);
                double lo = cfvoValue(r.cfvo.get(0), vals), hi = cfvoValue(r.cfvo.get(1), vals);
                if (r.cfvo.get(0)[0] != null && r.cfvo.get(0)[0].equals("min")) lo = Math.min(0, vals.get(0));
                if (hi <= lo) hi = lo + 1;
                String col = r.barColor != null ? r.barColor : "#638EC6";
                for (Cell c : cells) {
                    if (!c.hasNum) continue;
                    double p = Math.max(0, Math.min(1, (c.num - lo) / (hi - lo))) * 100;
                    addExtra(c, "background-image:linear-gradient(to right," + OoxmlUtil.mix(col, "#FFFFFF", 0.35)
                            + " " + trim(p) + "%,transparent " + trim(p) + "%);");
                }
                break;
            }
            default: break;
        }
    }

    private static void addExtra(Cell c, String css) {
        c.extra = c.extra == null ? css : c.extra + css;
    }

    private static boolean cmp(String op, double v, double a, Double b) {
        switch (op) {
            case "lessThan": return v < a;
            case "lessThanOrEqual": return v <= a;
            case "equal": return v == a;
            case "notEqual": return v != a;
            case "greaterThanOrEqual": return v >= a;
            case "greaterThan": return v > a;
            case "between": return b != null && v >= Math.min(a, b) && v <= Math.max(a, b);
            case "notBetween": return b != null && (v < Math.min(a, b) || v > Math.max(a, b));
            default: return false;
        }
    }

    private static Double litNum(String f) {
        if (f == null) return null;
        try { return Double.parseDouble(f.trim()); } catch (NumberFormatException e) { return null; }
    }

    private static String litStr(String f) {
        if (f == null) return null;
        String t = f.trim();
        if (t.length() >= 2 && t.startsWith("\"") && t.endsWith("\"")) return t.substring(1, t.length() - 1).replace("\"\"", "\"");
        return null;
    }

    private static double cfvoValue(String[] cfvo, List<Double> sorted) {
        String type = cfvo[0] == null ? "min" : cfvo[0];
        double min = sorted.get(0), max = sorted.get(sorted.size() - 1);
        double val = dblOf(cfvo[1], 0);
        switch (type) {
            case "min": return min;
            case "max": return max;
            case "num": return val;
            case "percent": return min + (max - min) * val / 100.0;
            case "percentile": {
                int idx = (int) Math.round((sorted.size() - 1) * val / 100.0);
                return sorted.get(Math.max(0, Math.min(sorted.size() - 1, idx)));
            }
            default: return min;
        }
    }

    private static String scaleColor(double v, double[] pos, List<String> colors, int stops) {
        if (v <= pos[0]) return colors.get(0);
        if (v >= pos[stops - 1]) return colors.get(stops - 1);
        for (int i = 0; i < stops - 1; i++) {
            if (v >= pos[i] && v <= pos[i + 1]) {
                double span = pos[i + 1] - pos[i];
                double t = span == 0 ? 0 : (v - pos[i]) / span;
                return OoxmlUtil.mix(colors.get(i), colors.get(i + 1), t);
            }
        }
        return colors.get(0);
    }

    // ------------------------------------------------------------------ أنماط جداول Excel

    private String[] tableStyleColors(String styleName) {
        // يعيد {لون اللمسة, نوع} بشكل تقريبي، أو null
        Matcher m = Pattern.compile("TableStyle(Light|Medium|Dark)(\\d+)").matcher(styleName);
        if (!m.matches()) return null;
        String family = m.group(1);
        int n = Integer.parseInt(m.group(2));
        int accent = ((n - 2) % 7 + 7) % 7;
        String col = accent <= 5 ? theme[4 + accent] : (family.equals("Dark") ? "#595959" : "#404040");
        return new String[]{col, family, String.valueOf(n)};
    }

    // ------------------------------------------------------------------ العرض

    private String renderSheet(Sheet sh) {
        StringBuilder sb = new StringBuilder(Math.max(4096, sh.maxRow * sh.maxCol * 24));
        if (sh.maxRow <= 0 || sh.maxCol <= 0) {
            sb.append("<div class=\"xl-msg\">الورقة فارغة</div>");
            appendNotes(sb, sh);
            return sb.toString();
        }
        applyConditionalFormats(sh);

        int maxRow = sh.maxRow, maxCol = sh.maxCol;
        double[] w = new double[maxCol + 2];
        boolean[] hidCol = new boolean[maxCol + 2];
        for (int c = 1; c <= maxCol; c++) {
            w[c] = sh.colPx[c] > 0 ? sh.colPx[c] : (sh.colHidden[c] ? 0 : sh.defColPx);
            hidCol[c] = sh.colHidden[c];
        }

        // الدمج
        Map<Integer, int[]> mergeAt = new HashMap<>();
        Set<Integer> covered = new HashSet<>();
        for (Merge m : sh.merges) {
            int r2 = Math.min(m.r2, maxRow), c2 = Math.min(m.c2, maxCol);
            if (m.r1 > maxRow || m.c1 > maxCol) continue;
            if (m.r1 == r2 && m.c1 == c2) continue;
            int rs = 0, cs = 0;
            for (int r = m.r1; r <= r2; r++) {
                RowData rd = sh.rows.get(r);
                if (rd == null || !rd.hidden) rs++;
            }
            for (int c = m.c1; c <= c2; c++) if (!hidCol[c]) cs++;
            mergeAt.put(m.r1 * 512 + m.c1, new int[]{Math.max(1, rs), Math.max(1, cs)});
            for (int r = m.r1; r <= r2; r++)
                for (int c = m.c1; c <= c2; c++)
                    if (!(r == m.r1 && c == m.c1)) covered.add(r * 512 + c);
        }

        // أنماط جداول Excel (تلوين الرأس والصفوف المتناوبة)
        Map<Integer, String> tableCls = new HashMap<>();
        for (Object[] t : sh.tables) {
            int[] rg = (int[]) t[0];
            String[] tc = tableStyleColors((String) t[1]);
            if (tc == null) continue;
            boolean stripes = (Boolean) t[2], hasHeader = (Boolean) t[5];
            String acc = tc[0];
            String family = tc[1];
            int n = Integer.parseInt(tc[2]);
            boolean solidHeader = family.equals("Medium") || family.equals("Dark") || (family.equals("Light") && n >= 8 && n <= 14);
            String headBg = family.equals("Dark") ? OoxmlUtil.excelTint(acc, -0.25) : acc;
            String headCss = solidHeader
                    ? "background-color:" + headBg + ";color:#fff;font-weight:bold;"
                    : "font-weight:bold;border-bottom:2px solid " + acc + ";";
            String band1 = family.equals("Dark") ? OoxmlUtil.excelTint(acc, 0.4)
                    : family.equals("Medium") ? OoxmlUtil.excelTint(acc, 0.8) : OoxmlUtil.excelTint(acc, 0.88);
            String band2 = family.equals("Dark") ? OoxmlUtil.excelTint(acc, 0.6) : null;
            for (int r = rg[0]; r <= Math.min(rg[2], maxRow); r++) {
                String css;
                if (hasHeader && r == rg[0]) css = headCss;
                else {
                    int idx = r - rg[0] - (hasHeader ? 1 : 0);
                    css = stripes && idx % 2 == 0 ? "background-color:" + band1 + ";" : (stripes && band2 != null ? "background-color:" + band2 + ";" : "");
                    if (family.equals("Dark") && css.isEmpty()) css = "background-color:" + OoxmlUtil.excelTint(acc, 0.6) + ";";
                    if (family.equals("Dark")) css += "color:#000;";
                }
                if (css.isEmpty()) continue;
                String cn = classFor("t:" + css, "tb");
                for (int c = rg[1]; c <= Math.min(rg[3], maxCol); c++) tableCls.put(r * 512 + c, cn);
            }
        }

        // الجمود
        int fzRows = Math.min(sh.ySplit, 8), fzCols = Math.min(sh.xSplit, 5);
        double[] fzTop = new double[fzRows + 1];
        double acc = COL_HEADER_H;
        for (int r = 1; r <= fzRows; r++) { fzTop[r] = acc; acc += rowPx(sh, r); }
        double[] fzLeft = new double[fzCols + 1];
        acc = ROW_HEADER_W;
        for (int c = 1; c <= fzCols; c++) { fzLeft[c] = acc; if (!hidCol[c]) acc += w[c]; }

        double tableW = ROW_HEADER_W;
        for (int c = 1; c <= maxCol; c++) if (!hidCol[c]) tableW += w[c];

        String dirAttr = sh.rtl ? "rtl" : "ltr";
        sb.append("<div class=\"xl-box").append(sh.showGrid ? "" : " nogrid").append("\" dir=\"").append(dirAttr)
                .append("\" style=\"width:").append(trim(tableW)).append("px\"><table style=\"width:").append(trim(tableW)).append("px\"><colgroup><col style=\"width:")
                .append(ROW_HEADER_W).append("px\">");
        for (int c = 1; c <= maxCol; c++) {
            if (hidCol[c]) continue;
            sb.append("<col style=\"width:").append(trim(w[c])).append("px\">");
        }
        sb.append("</colgroup><thead><tr><th class=\"corner\"></th>");
        for (int c = 1; c <= maxCol; c++) {
            if (hidCol[c]) continue;
            sb.append("<th>").append(colName(c)).append("</th>");
        }
        sb.append("</tr></thead><tbody>");

        double defaultFontPx = defaultFontPt * 96.0 / 72.0;
        for (int r = 1; r <= maxRow; r++) {
            RowData rd = sh.rows.get(r);
            if (rd != null && rd.hidden) continue;
            double h = rowPx(sh, r);
            sb.append("<tr style=\"height:").append(trim(h)).append("px\"><th class=\"rh\"");
            if (r <= fzRows) sb.append(" style=\"top:").append(trim(fzTop[r])).append("px;z-index:5\"");
            sb.append('>').append(r).append("</th>");
            int spillUntil = 0;
            for (int c = 1; c <= maxCol; c++) {
                if (hidCol[c]) continue;
                int key = r * 512 + c;
                if (covered.contains(key)) continue;
                if (c <= spillUntil) continue;
                Cell cell = rd == null ? null : rd.get(c);
                Xf xf = cell != null && cell.xf >= 0 && cell.xf < xfs.size() ? xfs.get(cell.xf) : xfs.get(0);
                int[] mg = mergeAt.get(key);
                int colspan = mg != null ? mg[1] : 1;
                int rowspan = mg != null ? mg[0] : 1;

                // انسياب النص فوق الخلايا الفارغة المجاورة
                if (mg == null && cell != null && cell.kind == K_STR && !xf.wrap && cell.plain != null
                        && (xf.hAlign == 'g' || (sh.rtl ? xf.hAlign == 'r' : xf.hAlign == 'l'))) {
                    double need = cell.plain.length() * xf.fontPx * 0.56 + 8;
                    double have = w[c];
                    int extra = 0;
                    int cc = c + 1;
                    while (have < need && cc <= maxCol) {
                        if (hidCol[cc]) { cc++; continue; }
                        int k2 = r * 512 + cc;
                        Cell nx = rd.get(cc);
                        boolean empty = nx == null || nx.kind == K_BLANK;
                        if (!empty || covered.contains(k2) || mergeAt.containsKey(k2)) break;
                        have += w[cc];
                        extra++;
                        spillUntil = cc;
                        cc++;
                    }
                    colspan += extra;
                }

                sb.append("<td");
                StringBuilder cls = new StringBuilder();
                String tcn = tableCls.get(key);
                if (tcn != null) cls.append(tcn).append(' ');
                boolean frozenRow = r <= fzRows;
                boolean frozenCol = c <= fzCols && colspan == 1 && rowspan == 1;
                boolean frozen = frozenRow || frozenCol;
                if (frozen) cls.append("fz ");
                if (cell != null) {
                    cls.append('x').append(cell.xf);
                    markXf(cell.xf);
                    if (xf.generalAlign) {
                        if (cell.kind == K_NUM) cls.append(" an");
                        else if (cell.kind == K_BOOL) cls.append(" ac");
                    }
                }
                String clsStr = cls.toString().trim();
                if (!clsStr.isEmpty()) sb.append(" class=\"").append(clsStr).append('"');
                if (colspan > 1) sb.append(" colspan=\"").append(colspan).append('"');
                if (rowspan > 1) sb.append(" rowspan=\"").append(rowspan).append('"');
                String ref = colName(c) + r;
                String comment = sh.comments.get(ref);
                if (comment != null) sb.append(" title=\"").append(HtmlPage.esc(comment)).append("\"");
                if (cell != null && cell.kind == K_STR) sb.append(" dir=\"auto\"");

                StringBuilder style = new StringBuilder();
                if (frozen) {
                    if (frozenRow) style.append("top:").append(trim(fzTop[r])).append("px;");
                    if (frozenCol) style.append(sh.rtl ? "right:" : "left:").append(trim(fzLeft[c])).append("px;");
                    style.append("z-index:").append(frozenRow && frozenCol ? 4 : 2).append(';');
                }
                if (cell != null) {
                    if (cell.color != null) style.append("color:").append(cell.color).append(';');
                    if (cell.extra != null) style.append(cell.extra);
                }
                // إلغاء خط الشبكة حيث يوجد حدّ صريح مجاور
                if (sh.showGrid) {
                    RowData below = sh.rows.get(r + rowspan);
                    Cell bc = below == null ? null : below.get(c);
                    if (bc != null && bc.xf < xfs.size() && xfs.get(bc.xf).hasTop && !xf.hasBottom) style.append("border-bottom-color:transparent;");
                    Cell rc = rd == null ? null : rd.get(c + colspan);
                    if (rc != null && rc.xf < xfs.size() && !xf.hasRight) {
                        Xf rx = xfs.get(rc.xf);
                        if (sh.rtl ? false : rx.hasLeft) style.append("border-right-color:transparent;");
                    }
                }
                if (style.length() > 0) sb.append(" style=\"").append(style).append('"');
                sb.append('>');

                if (cell != null && cell.kind != K_BLANK) {
                    String inner;
                    if (cell.rich != null && !xf.wrap) inner = cell.rich.replace('\n', ' ');
                    else if (cell.rich != null) inner = cell.rich;
                    else {
                        inner = HtmlPage.esc(cell.plain);
                        if (!xf.wrap) inner = inner.replace('\n', ' ');
                    }
                    if (cell.link != null) sb.append("<a href=\"").append(HtmlPage.esc(cell.link)).append("\">").append(inner).append("</a>");
                    else sb.append(inner);
                }
                sb.append("</td>");
                if (comment != null) { /* المؤشر عبر CSS على الخلية ذات title */ }
            }
            sb.append("</tr>");
        }
        sb.append("</tbody></table>");

        appendDrawings(sb, sh, w, hidCol);
        sb.append("</div>");
        appendNotes(sb, sh);
        if (!sh.comments.isEmpty()) {
            sb.append("<div class=\"xl-cm\"><b>التعليقات</b>");
            int n = 0;
            for (Map.Entry<String, String> e : sh.comments.entrySet()) {
                sb.append("<div dir=\"auto\"><span>").append(HtmlPage.esc(e.getKey())).append("</span> ").append(HtmlPage.esc(e.getValue())).append("</div>");
                if (++n >= 200) break;
            }
            sb.append("</div>");
        }
        return sb.toString();
    }

    private double rowPx(Sheet sh, int r) {
        RowData rd = sh.rows.get(r);
        double pt = rd != null && rd.htPt > 0 ? rd.htPt : sh.defRowPt;
        return Math.round(pt * 96.0 / 72.0 * 10) / 10.0;
    }

    private void appendNotes(StringBuilder sb, Sheet sh) {
        if (sh.truncRows || sh.fileRows > MAX_ROWS) {
            sb.append("<div class=\"xl-note\">تم عرض أول ").append(MAX_ROWS).append(" صف فقط من أصل ").append(sh.fileRows).append(" صف.</div>");
        }
        if (sh.truncCols) sb.append("<div class=\"xl-note\">تم عرض أول ").append(MAX_COLS).append(" عمود فقط.</div>");
        if (sh.truncCells) sb.append("<div class=\"xl-note\">الملف كبير جداً، تم عرض جزء من البيانات فقط.</div>");
    }

    // ------------------------------------------------------------------ الصور والمخططات

    private void appendDrawings(StringBuilder sb, Sheet sh, double[] w, boolean[] hidCol) {
        if (sh.drawingRid == null) return;
        Map<String, OoxmlUtil.Rel> rels = OoxmlUtil.readRels(zip, sh.path);
        OoxmlUtil.Rel dr = rels.get(sh.drawingRid);
        if (dr == null || dr.target == null || dr.external) return;
        String dpath = OoxmlUtil.resolve(OoxmlUtil.dirOf(sh.path), dr.target);
        Document d = OoxmlUtil.parsePart(zip, dpath, MAX_SMALL_XML);
        if (d == null) return;
        Map<String, OoxmlUtil.Rel> drels = OoxmlUtil.readRels(zip, dpath);
        String ddir = OoxmlUtil.dirOf(dpath);
        int count = 0;
        for (Element anchor : OoxmlUtil.childEls(d.getDocumentElement())) {
            String kind = OoxmlUtil.local(anchor);
            if (!kind.endsWith("Anchor")) continue;
            if (++count > 60) break;
            double left = 0, top = 0, wpx = 0, hpx = 0;
            Element from = OoxmlUtil.child(anchor, "from");
            if (from != null) {
                int c0 = textInt(OoxmlUtil.child(from, "col")), r0 = textInt(OoxmlUtil.child(from, "row"));
                double co = textInt(OoxmlUtil.child(from, "colOff")) / 9525.0, ro = textInt(OoxmlUtil.child(from, "rowOff")) / 9525.0;
                for (int c = 1; c <= c0; c++) left += (c < w.length && c <= sh.maxCol) ? (hidCol[c] ? 0 : w[c]) : sh.defColPx;
                for (int r = 1; r <= r0; r++) {
                    RowData rd = sh.rows.get(r);
                    top += rd != null && rd.hidden ? 0 : rowPx(sh, r);
                }
                left += co;
                top += ro;
            } else {
                Element pos = OoxmlUtil.child(anchor, "pos");
                if (pos != null) {
                    Double x = OoxmlUtil.dblAttr(pos, "x"), y = OoxmlUtil.dblAttr(pos, "y");
                    left = x == null ? 0 : x / 9525.0;
                    top = y == null ? 0 : y / 9525.0;
                }
            }
            Element ext = OoxmlUtil.child(anchor, "ext");
            if (ext == null) {
                Element pic0 = OoxmlUtil.descendant(anchor, "xfrm");
                if (pic0 != null) ext = OoxmlUtil.child(pic0, "ext");
            }
            if (ext != null) {
                Double cx = OoxmlUtil.dblAttr(ext, "cx"), cy = OoxmlUtil.dblAttr(ext, "cy");
                if (cx != null) wpx = cx / 9525.0;
                if (cy != null) hpx = cy / 9525.0;
            }
            Element to = OoxmlUtil.child(anchor, "to");
            if ((wpx <= 0 || hpx <= 0) && to != null && from != null) {
                int c0 = textInt(OoxmlUtil.child(from, "col")), r0 = textInt(OoxmlUtil.child(from, "row"));
                int c1 = textInt(OoxmlUtil.child(to, "col")), r1 = textInt(OoxmlUtil.child(to, "row"));
                double x = 0, y = 0;
                for (int c = c0 + 1; c <= c1; c++) x += (c < w.length && c <= sh.maxCol) ? (hidCol[c] ? 0 : w[c]) : sh.defColPx;
                for (int r = r0 + 1; r <= r1; r++) y += rowPx(sh, r);
                wpx = Math.max(wpx, x + textInt(OoxmlUtil.child(to, "colOff")) / 9525.0);
                hpx = Math.max(hpx, y + textInt(OoxmlUtil.child(to, "rowOff")) / 9525.0);
            }
            if (wpx < 8) wpx = 120;
            if (hpx < 8) hpx = 80;
            left += ROW_HEADER_W;
            top += COL_HEADER_H;
            String side = sh.rtl ? "right" : "left";
            String pos = "position:absolute;" + side + ":" + trim(left) + "px;top:" + trim(top) + "px;width:" + trim(wpx) + "px;height:" + trim(hpx) + "px;";

            Element blip = OoxmlUtil.descendant(anchor, "blip");
            if (blip != null) {
                String rid = OoxmlUtil.attr(blip, "embed");
                OoxmlUtil.Rel ir = rid == null ? null : drels.get(rid);
                String uri = null;
                if (ir != null && !ir.external && ir.target != null) {
                    byte[] bytes = OoxmlUtil.read(zip, OoxmlUtil.resolve(ddir, ir.target), MAX_IMAGE);
                    if (bytes != null && imageBytes + bytes.length <= MAX_IMAGE_TOTAL) {
                        uri = OoxmlUtil.dataUri(bytes);
                        if (uri != null) imageBytes += bytes.length;
                    }
                }
                if (uri != null) sb.append("<img class=\"xl-img\" style=\"").append(pos).append("\" src=\"").append(uri).append("\" alt=\"\">");
                else sb.append("<div class=\"xl-ph\" style=\"").append(pos).append("\">▣</div>");
            } else if (OoxmlUtil.descendant(anchor, "chart") != null) {
                sb.append("<div class=\"xl-ph\" style=\"").append(pos).append("\">[مخطط]</div>");
            }
        }
    }

    private static int textInt(Element e) {
        if (e == null) return 0;
        try { return Integer.parseInt(e.getTextContent().trim()); } catch (NumberFormatException ex) { return 0; }
    }

    // ------------------------------------------------------------------ CSS

    private final Set<Integer> usedXf = new HashSet<>();

    private void markXf(int i) { usedXf.add(i); }

    private String classFor(String css, String prefix) {
        Integer n = classes.get(css);
        if (n == null) { n = classes.size(); classes.put(css, n); }
        return prefix + n;
    }

    private String css(String tabsCss) {
        StringBuilder c = new StringBuilder(8192);
        c.append("html,body{height:100%}body{margin:0;padding:0;overflow:hidden;background:#fff}")
                .append(".xl{position:relative;height:100vh;display:flex;flex-direction:column;font-family:Calibri,\"Segoe UI\",Roboto,\"Noto Sans Arabic\",Arial,sans-serif;color:#000}")
                .append(".xl input{position:absolute;opacity:0;pointer-events:none;width:0;height:0}")
                .append(".xl-panes{flex:1;min-height:0;position:relative}")
                .append(".xl-pane{display:none;position:absolute;inset:0;overflow:auto;-webkit-overflow-scrolling:touch;background:#fff}")
                .append(".xl-box{position:relative;min-height:100%}")
                .append(".xl table{border-collapse:separate;border-spacing:0;table-layout:fixed;font-size:").append(trim(defaultFontPt)).append("pt;background:#fff}")
                .append(".xl td{padding:0 3px;border-right:1px solid #E1E1E1;border-bottom:1px solid #E1E1E1;overflow:hidden;white-space:nowrap;vertical-align:bottom;line-height:1.25;background-clip:padding-box}")
                .append(".xl .nogrid td{border-color:transparent}")
                .append(".xl td.an{text-align:right}.xl td.ac{text-align:center}")
                .append(".xl td a{color:#0563C1}")
                .append(".xl td[title]{background-image:linear-gradient(225deg,#E0463C 0,#E0463C 4px,transparent 4px)}")
                .append(".xl th{background:#F3F3F3;color:#555;font-weight:normal;font-size:11px;text-align:center;border-right:1px solid #CFCFCF;border-bottom:1px solid #CFCFCF;position:sticky;z-index:3;user-select:none;-webkit-user-select:none;padding:0;overflow:hidden}")
                .append(".xl thead th{top:0;height:").append(COL_HEADER_H).append("px;z-index:6}")
                .append(".xl th.rh{left:0;z-index:5}.xl [dir=rtl] th.rh{left:auto;right:0}")
                .append(".xl thead th.corner{left:0;z-index:7}.xl [dir=rtl] thead th.corner{left:auto;right:0}")
                .append(".xl td.fz{position:sticky;background-color:#fff}")
                .append(".xl-img{position:absolute;z-index:1;object-fit:fill}")
                .append(".xl-ph{position:absolute;z-index:1;display:flex;align-items:center;justify-content:center;border:1px dashed #AAA;color:#888;font-size:13px;background:rgba(245,245,245,.85)}")
                .append(".xl-msg{padding:56px 12px;text-align:center;color:#8A8A84;font:15px sans-serif}")
                .append(".xl-note{margin:8px;padding:8px 12px;border-radius:8px;background:#FFF4E5;color:#7A4B00;font:13px sans-serif;position:sticky;left:8px}")
                .append(".xl-cm{margin:10px 8px 60px;font:13px sans-serif;color:#333}.xl-cm div{margin:4px 0}.xl-cm span{color:#217346;font-weight:600}")
                .append(".xl-tabs{flex:none;display:flex;overflow-x:auto;background:#EDEBE4;border-top:1px solid #CFCFCF;-webkit-overflow-scrolling:touch}")
                .append(".xl-tabs label{flex:none;padding:10px 18px;font:14px sans-serif;color:#555;cursor:pointer;white-space:nowrap;border-inline-end:1px solid #D8D6CF}")
                .append(tabsCss);
        for (Map.Entry<String, Integer> e : classes.entrySet()) {
            String k = e.getKey();
            if (k.startsWith("t:")) c.append(".xl td.tb").append(e.getValue()).append('{').append(k.substring(2)).append('}');
        }
        for (int i : new java.util.TreeSet<>(usedXf)) {
            if (i < 0 || i >= xfs.size()) continue;
            String x = xfs.get(i).css;
            if (x != null && !x.isEmpty()) c.append(".xl td.x").append(i).append('{').append(x).append('}');
        }
        return c.toString();
    }
}
