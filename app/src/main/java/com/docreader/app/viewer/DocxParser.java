package com.docreader.app.viewer;

import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * يستخرج الفقرات (والجداول كنص مبسّط) من ملف Word (.docx)
 * عبر قراءة word/document.xml مباشرة من داخل حزمة ZIP، دون الاعتماد
 * على مكتبات ثقيلة قد تسبب مشاكل توافق على أندرويد.
 */
public class DocxParser {

    public static class Block {
        public enum Type { HEADING, PARAGRAPH, TABLE_ROW }
        public Type type;
        public String text;
        public Block(Type type, String text) { this.type = type; this.text = text; }
    }

    public static List<Block> parse(InputStream zipStream) throws Exception {
        List<Block> blocks = new ArrayList<>();
        try (ZipInputStream zis = new ZipInputStream(zipStream)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if ("word/document.xml".equals(entry.getName())) {
                    parseDocumentXml(zis, blocks);
                    break;
                }
            }
        }
        return blocks;
    }

    private static void parseDocumentXml(InputStream in, List<Block> blocks) throws Exception {
        XmlPullParser parser = Xml.newPullParser();
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
        parser.setInput(in, "UTF-8");

        StringBuilder currentText = new StringBuilder();
        boolean insideTableCell = false;
        boolean paragraphIsHeading = false;
        int event = parser.getEventType();

        while (event != XmlPullParser.END_DOCUMENT) {
            String name = parser.getName();
            if (event == XmlPullParser.START_TAG) {
                if (name != null && name.endsWith(":p") || "p".equals(name)) {
                    currentText.setLength(0);
                    paragraphIsHeading = false;
                } else if (name != null && (name.endsWith(":pStyle") || "pStyle".equals(name))) {
                    String val = parser.getAttributeValue(null, "w:val");
                    if (val == null) val = parser.getAttributeValue(null, "val");
                    if (val != null && val.toLowerCase().contains("heading")) {
                        paragraphIsHeading = true;
                    }
                } else if (name != null && (name.endsWith(":t") || "t".equals(name))) {
                    if (parser.next() == XmlPullParser.TEXT) {
                        currentText.append(parser.getText());
                    }
                    continue;
                } else if (name != null && (name.endsWith(":tc") || "tc".equals(name))) {
                    insideTableCell = true;
                } else if (name != null && (name.endsWith(":br") || "br".equals(name))) {
                    currentText.append("\n");
                }
            } else if (event == XmlPullParser.END_TAG) {
                if (name != null && (name.endsWith(":p") || "p".equals(name))) {
                    String text = currentText.toString().trim();
                    if (!text.isEmpty()) {
                        Block.Type type = insideTableCell ? Block.Type.TABLE_ROW
                                : (paragraphIsHeading ? Block.Type.HEADING : Block.Type.PARAGRAPH);
                        blocks.add(new Block(type, text));
                    }
                    currentText.setLength(0);
                } else if (name != null && (name.endsWith(":tbl") || "tbl".equals(name))) {
                    insideTableCell = false;
                }
            }
            event = parser.next();
        }
    }
}
