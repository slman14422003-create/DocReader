package com.docreader.app.viewer;

import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * يستخرج كل أوراق ملف Excel (.xlsx) بأسمائها الحقيقية وترتيبها،
 * بقراءة xl/workbook.xml وعلاقاته وأوراق العمل و sharedStrings مباشرة.
 */
public class XlsxParser {

    public static class Sheet {
        public String name;
        public List<List<String>> rows = new ArrayList<>();
    }

    public static List<Sheet> parseAll(InputStream zipStream) throws Exception {
        byte[] workbookXml = null;
        byte[] relsXml = null;
        byte[] sharedStringsXml = null;
        Map<String, byte[]> worksheetFiles = new HashMap<>();

        try (ZipInputStream zis = new ZipInputStream(zipStream)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String n = entry.getName();
                if ("xl/workbook.xml".equals(n)) {
                    workbookXml = readAll(zis);
                } else if ("xl/_rels/workbook.xml.rels".equals(n)) {
                    relsXml = readAll(zis);
                } else if ("xl/sharedStrings.xml".equals(n)) {
                    sharedStringsXml = readAll(zis);
                } else if (n.matches("xl/worksheets/sheet\\d+\\.xml")) {
                    worksheetFiles.put(n, readAll(zis));
                }
            }
        }

        List<String> sharedStrings = new ArrayList<>();
        if (sharedStringsXml != null) {
            sharedStrings = parseSharedStrings(new ByteArrayInputStream(sharedStringsXml));
        }

        Map<String, String> relIdToTarget = new HashMap<>();
        if (relsXml != null) relIdToTarget = parseRels(new ByteArrayInputStream(relsXml));

        List<Sheet> sheets = new ArrayList<>();
        if (workbookXml != null) {
            LinkedHashMap<String, String> nameToRelId = parseWorkbookSheetList(new ByteArrayInputStream(workbookXml));
            for (Map.Entry<String, String> e : nameToRelId.entrySet()) {
                String target = relIdToTarget.get(e.getValue());
                if (target == null) continue;
                String fullPath = target.startsWith("worksheets/") ? "xl/" + target : "xl/" + target;
                byte[] sheetXml = worksheetFiles.get(fullPath);
                if (sheetXml == null) continue;
                Sheet sheet = new Sheet();
                sheet.name = e.getKey();
                sheet.rows = parseSheet(new ByteArrayInputStream(sheetXml), sharedStrings);
                sheets.add(sheet);
            }
        }

        // خيار احتياطي إن تعذّرت قراءة workbook.xml لأي سبب
        if (sheets.isEmpty() && !worksheetFiles.isEmpty()) {
            List<String> keys = new ArrayList<>(worksheetFiles.keySet());
            java.util.Collections.sort(keys);
            int i = 1;
            for (String key : keys) {
                Sheet sheet = new Sheet();
                sheet.name = "Sheet" + i++;
                sheet.rows = parseSheet(new ByteArrayInputStream(worksheetFiles.get(key)), sharedStrings);
                sheets.add(sheet);
            }
        }
        return sheets;
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int len;
        while ((len = in.read(buf)) > 0) bos.write(buf, 0, len);
        return bos.toByteArray();
    }

    private static LinkedHashMap<String, String> parseWorkbookSheetList(InputStream in) throws Exception {
        LinkedHashMap<String, String> nameToRelId = new LinkedHashMap<>();
        XmlPullParser parser = Xml.newPullParser();
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
        parser.setInput(in, "UTF-8");
        int event = parser.getEventType();
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && "sheet".equals(parser.getName())) {
                String name = parser.getAttributeValue(null, "name");
                String relId = parser.getAttributeValue(null, "r:id");
                if (relId == null) relId = parser.getAttributeValue(null, "id");
                if (name != null && relId != null) nameToRelId.put(name, relId);
            }
            event = parser.next();
        }
        return nameToRelId;
    }

    private static Map<String, String> parseRels(InputStream in) throws Exception {
        Map<String, String> rels = new HashMap<>();
        XmlPullParser parser = Xml.newPullParser();
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
        parser.setInput(in, "UTF-8");
        int event = parser.getEventType();
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && "Relationship".equals(parser.getName())) {
                String id = parser.getAttributeValue(null, "Id");
                String target = parser.getAttributeValue(null, "Target");
                if (id != null && target != null) rels.put(id, target);
            }
            event = parser.next();
        }
        return rels;
    }

    private static List<String> parseSharedStrings(InputStream in) throws Exception {
        List<String> list = new ArrayList<>();
        XmlPullParser parser = Xml.newPullParser();
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
        parser.setInput(in, "UTF-8");
        int event = parser.getEventType();
        StringBuilder sb = new StringBuilder();
        while (event != XmlPullParser.END_DOCUMENT) {
            String name = parser.getName();
            if (event == XmlPullParser.START_TAG && "si".equals(name)) {
                sb.setLength(0);
            } else if (event == XmlPullParser.START_TAG && "t".equals(name)) {
                event = parser.next();
                if (event == XmlPullParser.TEXT) sb.append(parser.getText());
                continue;
            } else if (event == XmlPullParser.END_TAG && "si".equals(name)) {
                list.add(sb.toString());
            }
            event = parser.next();
        }
        return list;
    }

    private static List<List<String>> parseSheet(InputStream in, List<String> sharedStrings) throws Exception {
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
                    event = parser.next();
                    if (event == XmlPullParser.TEXT) {
                        valueBuilder.append(parser.getText());
                    }
                    continue;
                }
            } else if (event == XmlPullParser.END_TAG) {
                if (("v".equals(name) || "t".equals(name)) && readingValue) {
                    readingValue = false;
                    String raw = valueBuilder.toString();
                    String display = formatCell(raw, cellType, sharedStrings);
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

    private static String formatCell(String raw, String cellType, List<String> sharedStrings) {
        if ("s".equals(cellType)) {
            try {
                int idx = Integer.parseInt(raw.trim());
                if (idx >= 0 && idx < sharedStrings.size()) return sharedStrings.get(idx);
            } catch (NumberFormatException ignored) {
            }
            return raw;
        }
        if ("b".equals(cellType)) {
            return "1".equals(raw.trim()) ? "TRUE" : "FALSE";
        }
        if ("str".equals(cellType) || "e".equals(cellType)) {
            return raw;
        }
        // رقم: أزل الأصفار العشرية الزائدة (123.0 -> 123)
        try {
            double d = Double.parseDouble(raw.trim());
            if (d == Math.rint(d) && !Double.isInfinite(d) && Math.abs(d) < 1e15) {
                return String.valueOf((long) d);
            }
            return raw;
        } catch (NumberFormatException ignored) {
            return raw;
        }
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

    /** محلل CSV بسيط (يدعم الفواصل المقتبسة بعلامتي تنصيص). */
    public static List<List<String>> parseCsv(InputStream in) throws Exception {
        java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(in, "UTF-8"));
        List<List<String>> rows = new ArrayList<>();
        String line;
        while ((line = reader.readLine()) != null) {
            List<String> row = new ArrayList<>();
            StringBuilder cur = new StringBuilder();
            boolean inQuotes = false;
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                if (c == '"') {
                    inQuotes = !inQuotes;
                } else if (c == ',' && !inQuotes) {
                    row.add(cur.toString());
                    cur.setLength(0);
                } else {
                    cur.append(c);
                }
            }
            row.add(cur.toString());
            rows.add(row);
        }
        return rows;
    }
}
