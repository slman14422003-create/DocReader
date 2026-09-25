package com.docreader.app.viewer;

import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * يستخرج محتوى ملف Word (.docx) بأكبر درجة أمانة ممكنة دون مكتبات ثقيلة:
 * نص منسّق (عريض/مائل/تسطير/لون/حجم)، محاذاة الفقرات، القوائم النقطية،
 * جداول حقيقية، والصور المضمّنة — بقراءة أجزاء حزمة OOXML مباشرة.
 */
public class DocxParser {

    public static class Run {
        public String text = "";
        public boolean bold, italic, underline, strike;
        public String colorHex; // بدون # مثال: "FF0000"
        public Float sizeSp;
    }

    public static class Cell {
        public List<Run> runs = new ArrayList<>();
    }

    public static class Block {
        public enum Type { HEADING, PARAGRAPH, LIST_ITEM, TABLE, IMAGE }
        public static final int ALIGN_START = 0, ALIGN_CENTER = 1, ALIGN_END = 2, ALIGN_JUSTIFY = 3;

        public Type type;
        public List<Run> runs = new ArrayList<>();
        public int align = ALIGN_START;
        public List<List<Cell>> tableRows;
        public byte[] imageBytes;

        public Block(Type type) { this.type = type; }

        /** يبني نصًا بسيطًا (بلا تنسيق) من كل التشغيلات؛ مفيد للتوافق مع كودات قديمة. */
        public String plainText() {
            StringBuilder sb = new StringBuilder();
            for (Run r : runs) sb.append(r.text);
            return sb.toString();
        }
    }

    public static List<Block> parse(InputStream zipStream) throws Exception {
        byte[] documentXml = null;
        byte[] relsXml = null;
        Map<String, byte[]> media = new HashMap<>();

        try (ZipInputStream zis = new ZipInputStream(zipStream)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String n = entry.getName();
                if ("word/document.xml".equals(n)) {
                    documentXml = readAll(zis);
                } else if ("word/_rels/document.xml.rels".equals(n)) {
                    relsXml = readAll(zis);
                } else if (n.startsWith("word/media/")) {
                    media.put(n, readAll(zis));
                }
            }
        }

        Map<String, byte[]> mediaByRelId = new HashMap<>();
        if (relsXml != null) {
            Map<String, String> rels = parseRels(new ByteArrayInputStream(relsXml));
            for (Map.Entry<String, String> e : rels.entrySet()) {
                String target = e.getValue();
                if (target == null) continue;
                String full = target.startsWith("media/") ? "word/" + target : "word/" + target;
                byte[] bytes = media.get(full);
                if (bytes != null) mediaByRelId.put(e.getKey(), bytes);
            }
        }

        List<Block> blocks = new ArrayList<>();
        if (documentXml != null) {
            parseDocumentXml(new ByteArrayInputStream(documentXml), mediaByRelId, blocks);
        }
        return blocks;
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int len;
        while ((len = in.read(buf)) > 0) bos.write(buf, 0, len);
        return bos.toByteArray();
    }

    private static Map<String, String> parseRels(InputStream in) throws Exception {
        Map<String, String> rels = new HashMap<>();
        XmlPullParser parser = Xml.newPullParser();
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
        parser.setInput(in, "UTF-8");
        int event = parser.getEventType();
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && tagIs(parser.getName(), "Relationship")) {
                String id = parser.getAttributeValue(null, "Id");
                String target = parser.getAttributeValue(null, "Target");
                if (id != null && target != null) rels.put(id, target);
            }
            event = parser.next();
        }
        return rels;
    }

    private static boolean tagIs(String name, String local) {
        return name != null && (name.equals(local) || name.endsWith(":" + local));
    }

    private static String attr(XmlPullParser parser, String... names) {
        for (String n : names) {
            String v = parser.getAttributeValue(null, n);
            if (v != null) return v;
        }
        return null;
    }

    private static void parseDocumentXml(InputStream in, Map<String, byte[]> mediaByRelId, List<Block> blocks) throws Exception {
        XmlPullParser parser = Xml.newPullParser();
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
        parser.setInput(in, "UTF-8");

        int event = parser.getEventType();

        // حالة الفقرة الحالية
        List<Run> currentRuns = new ArrayList<>();
        Run currentRun = null;
        boolean paragraphIsHeading = false;
        boolean paragraphIsList = false;
        int paragraphAlign = Block.ALIGN_START;

        // حالة الجدول
        int tableDepth = 0;
        List<List<Cell>> tableRows = null;
        List<Cell> currentRowCells = null;
        Cell currentCell = null;
        int cellDepthInsideTable = 0; // لتمييز فقرات داخل خلية عن فقرات عامة

        while (event != XmlPullParser.END_DOCUMENT) {
            String name = parser.getName();

            if (event == XmlPullParser.START_TAG) {
                if (tagIs(name, "tbl")) {
                    tableDepth++;
                    if (tableDepth == 1) tableRows = new ArrayList<>();
                } else if (tagIs(name, "tr") && tableDepth > 0) {
                    currentRowCells = new ArrayList<>();
                } else if (tagIs(name, "tc") && tableDepth > 0) {
                    currentCell = new Cell();
                    cellDepthInsideTable++;
                } else if (tagIs(name, "p")) {
                    currentRuns = new ArrayList<>();
                    paragraphIsHeading = false;
                    paragraphIsList = false;
                    paragraphAlign = Block.ALIGN_START;
                } else if (tagIs(name, "pStyle")) {
                    String val = attr(parser, "w:val", "val");
                    if (val != null && val.toLowerCase().contains("heading")) paragraphIsHeading = true;
                } else if (tagIs(name, "numPr")) {
                    paragraphIsList = true;
                } else if (tagIs(name, "jc")) {
                    String val = attr(parser, "w:val", "val");
                    if (val != null) {
                        switch (val) {
                            case "center": paragraphAlign = Block.ALIGN_CENTER; break;
                            case "right": case "end": paragraphAlign = Block.ALIGN_END; break;
                            case "both": case "distribute": paragraphAlign = Block.ALIGN_JUSTIFY; break;
                            default: paragraphAlign = Block.ALIGN_START;
                        }
                    }
                } else if (tagIs(name, "r")) {
                    currentRun = new Run();
                } else if (tagIs(name, "b") && currentRun != null) {
                    currentRun.bold = !"false".equals(attr(parser, "w:val", "val")) && !"0".equals(attr(parser, "w:val", "val"));
                } else if (tagIs(name, "i") && currentRun != null) {
                    currentRun.italic = !"false".equals(attr(parser, "w:val", "val")) && !"0".equals(attr(parser, "w:val", "val"));
                } else if (tagIs(name, "u") && currentRun != null) {
                    String val = attr(parser, "w:val", "val");
                    currentRun.underline = val == null || !"none".equals(val);
                } else if (tagIs(name, "strike") && currentRun != null) {
                    currentRun.strike = true;
                } else if (tagIs(name, "color") && currentRun != null) {
                    String val = attr(parser, "w:val", "val");
                    if (val != null && !"auto".equalsIgnoreCase(val)) currentRun.colorHex = val;
                } else if (tagIs(name, "sz") && currentRun != null) {
                    String val = attr(parser, "w:val", "val");
                    if (val != null) {
                        try { currentRun.sizeSp = Integer.parseInt(val) / 2f; } catch (NumberFormatException ignored) { }
                    }
                } else if (tagIs(name, "t")) {
                    if (currentRun == null) currentRun = new Run();
                    event = parser.next();
                    if (event == XmlPullParser.TEXT) {
                        currentRun.text += parser.getText();
                    }
                    continue;
                } else if (tagIs(name, "tab")) {
                    if (currentRun != null) currentRun.text += "\t";
                } else if (tagIs(name, "br")) {
                    if (currentRun != null) currentRun.text += "\n";
                } else if (tagIs(name, "blip")) {
                    String rId = attr(parser, "r:embed", "embed");
                    if (rId != null) {
                        byte[] img = mediaByRelId.get(rId);
                        if (img != null && tableDepth == 0) {
                            if (!currentRuns.isEmpty()) {
                                Block textBlock = new Block(paragraphIsHeading ? Block.Type.HEADING
                                        : paragraphIsList ? Block.Type.LIST_ITEM : Block.Type.PARAGRAPH);
                                textBlock.align = paragraphAlign;
                                textBlock.runs = currentRuns;
                                if (hasVisibleText(textBlock)) blocks.add(textBlock);
                                currentRuns = new ArrayList<>();
                            }
                            Block imageBlock = new Block(Block.Type.IMAGE);
                            imageBlock.imageBytes = img;
                            blocks.add(imageBlock);
                        }
                    }
                }
            } else if (event == XmlPullParser.END_TAG) {
                if (tagIs(name, "r")) {
                    if (currentRun != null && !currentRun.text.isEmpty()) currentRuns.add(currentRun);
                    currentRun = null;
                } else if (tagIs(name, "p")) {
                    if (cellDepthInsideTable > 0 && currentCell != null) {
                        if (!currentCell.runs.isEmpty()) {
                            Run br = new Run(); br.text = "\n";
                            currentCell.runs.add(br);
                        }
                        currentCell.runs.addAll(currentRuns);
                    } else {
                        Block.Type type = paragraphIsHeading ? Block.Type.HEADING
                                : paragraphIsList ? Block.Type.LIST_ITEM : Block.Type.PARAGRAPH;
                        Block block = new Block(type);
                        block.align = paragraphAlign;
                        block.runs = currentRuns;
                        if (hasVisibleText(block)) blocks.add(block);
                    }
                    currentRuns = new ArrayList<>();
                } else if (tagIs(name, "tc") && tableDepth > 0) {
                    if (currentRowCells != null && currentCell != null) currentRowCells.add(currentCell);
                    currentCell = null;
                    cellDepthInsideTable = Math.max(0, cellDepthInsideTable - 1);
                } else if (tagIs(name, "tr") && tableDepth > 0) {
                    if (tableRows != null && currentRowCells != null) tableRows.add(currentRowCells);
                    currentRowCells = null;
                } else if (tagIs(name, "tbl")) {
                    tableDepth--;
                    if (tableDepth == 0 && tableRows != null && !tableRows.isEmpty()) {
                        Block tableBlock = new Block(Block.Type.TABLE);
                        tableBlock.tableRows = tableRows;
                        blocks.add(tableBlock);
                    }
                    tableRows = null;
                }
            }
            event = parser.next();
        }
    }

    private static boolean hasVisibleText(Block block) {
        for (Run r : block.runs) if (!r.text.trim().isEmpty()) return true;
        return false;
    }
}
