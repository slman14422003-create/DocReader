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
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * يستخرج أول ورقة عمل من ملف Excel (.xlsx) كجدول نصوص بسيط،
 * بقراءة xl/sharedStrings.xml و xl/worksheets/sheet1.xml مباشرة.
 */
public class XlsxParser {

    public static class Sheet {
        public String name;
        public List<List<String>> rows = new ArrayList<>();
    }

    public static Sheet parseFirstSheet(InputStream zipStream) throws Exception {
        Map<String, byte[]> entries = new HashMap<>();
        try (ZipInputStream zis = new ZipInputStream(zipStream)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String n = entry.getName();
                if (n.equals("xl/sharedStrings.xml") || n.matches("xl/worksheets/sheet1\\.xml")) {
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    byte[] buf = new byte[4096];
                    int len;
                    while ((len = zis.read(buf)) > 0) bos.write(buf, 0, len);
                    entries.put(n, bos.toByteArray());
                }
            }
        }

        List<String> sharedStrings = new ArrayList<>();
        if (entries.containsKey("xl/sharedStrings.xml")) {
            sharedStrings = parseSharedStrings(new ByteArrayInputStream(entries.get("xl/sharedStrings.xml")));
        }

        Sheet sheet = new Sheet();
        sheet.name = "Sheet1";
        if (entries.containsKey("xl/worksheets/sheet1.xml")) {
            sheet.rows = parseSheet(new ByteArrayInputStream(entries.get("xl/worksheets/sheet1.xml")), sharedStrings);
        }
        return sheet;
    }

    private static List<String> parseSharedStrings(InputStream in) throws Exception {
        List<String> list = new ArrayList<>();
        XmlPullParser parser = Xml.newPullParser();
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
        parser.setInput(in, "UTF-8");
        int event = parser.getEventType();
        StringBuilder sb = new StringBuilder();
        boolean inSi = false;
        while (event != XmlPullParser.END_DOCUMENT) {
            String name = parser.getName();
            if (event == XmlPullParser.START_TAG && "si".equals(name)) {
                inSi = true;
                sb.setLength(0);
            } else if (event == XmlPullParser.START_TAG && "t".equals(name)) {
                if (parser.next() == XmlPullParser.TEXT) sb.append(parser.getText());
                continue;
            } else if (event == XmlPullParser.END_TAG && "si".equals(name)) {
                inSi = false;
                list.add(sb.toString());
            }
            event = parser.next();
        }
        return list;
    }

    private static List<List<String>> parseSheet(InputStream in, List<String> sharedStrings) throws Exception {
        // نستخدم TreeMap للحفاظ على ترتيب الصفوف حسب رقمها
        TreeMap<Integer, Map<Integer, String>> rowMap = new TreeMap<>();

        XmlPullParser parser = Xml.newPullParser();
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
        parser.setInput(in, "UTF-8");

        int event = parser.getEventType();
        int currentRow = -1;
        int currentCol = -1;
        String cellType = null;
        boolean readingValue = false;
        StringBuilder valueBuilder = new StringBuilder();

        while (event != XmlPullParser.END_DOCUMENT) {
            String name = parser.getName();
            if (event == XmlPullParser.START_TAG) {
                if ("row".equals(name)) {
                    String r = parser.getAttributeValue(null, "r");
                    currentRow = r != null ? Integer.parseInt(r) : currentRow + 1;
                    rowMap.putIfAbsent(currentRow, new TreeMap<>());
                } else if ("c".equals(name)) {
                    String ref = parser.getAttributeValue(null, "r");
                    currentCol = columnIndexFromRef(ref);
                    cellType = parser.getAttributeValue(null, "t");
                } else if ("v".equals(name) || "t".equals(name)) {
                    readingValue = true;
                    valueBuilder.setLength(0);
                    if (parser.next() == XmlPullParser.TEXT) {
                        valueBuilder.append(parser.getText());
                    }
                    continue;
                }
            } else if (event == XmlPullParser.END_TAG) {
                if (("v".equals(name) || "t".equals(name)) && readingValue) {
                    readingValue = false;
                    String raw = valueBuilder.toString();
                    String display = raw;
                    if ("s".equals(cellType)) {
                        try {
                            int idx = Integer.parseInt(raw.trim());
                            if (idx >= 0 && idx < sharedStrings.size()) display = sharedStrings.get(idx);
                        } catch (NumberFormatException ignored) {
                        }
                    }
                    if (currentRow >= 0 && currentCol >= 0) {
                        rowMap.get(currentRow).put(currentCol, display);
                    }
                }
            }
            event = parser.next();
        }

        List<List<String>> result = new ArrayList<>();
        for (Map<Integer, String> cols : rowMap.values()) {
            int maxCol = 0;
            for (int c : cols.keySet()) maxCol = Math.max(maxCol, c);
            List<String> rowList = new ArrayList<>();
            for (int i = 0; i <= maxCol; i++) {
                String v = cols.get(i);
                rowList.add(v != null ? v : "");
            }
            result.add(rowList);
        }
        return result;
    }

    /** يحوّل مرجع خلية مثل "C5" إلى رقم عمود صفري (C = 2) */
    private static int columnIndexFromRef(String ref) {
        if (ref == null) return -1;
        int col = 0;
        for (char c : ref.toCharArray()) {
            if (Character.isLetter(c)) {
                col = col * 26 + (Character.toUpperCase(c) - 'A' + 1);
            } else {
                break;
            }
        }
        return col - 1;
    }
}
