package com.docreader.app.viewer;

import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * يستخرج كل شريحة من ملف PowerPoint (.pptx): العنوان منفصلاً عن النص،
 * والصور المضمّنة، بقراءة ppt/slides/slideN.xml وعلاقاتها مباشرة.
 */
public class PptxParser {

    public static class SlideItem {
        public enum Type { TITLE, BODY, IMAGE }
        public Type type;
        public String text;
        public byte[] imageBytes;
    }

    public static class Slide {
        public int index;
        public List<SlideItem> items = new ArrayList<>();
    }

    public static List<Slide> parse(InputStream zipStream) throws Exception {
        Map<Integer, byte[]> slideXml = new HashMap<>();
        Map<Integer, byte[]> slideRels = new HashMap<>();
        Map<String, byte[]> media = new HashMap<>();

        try (ZipInputStream zis = new ZipInputStream(zipStream)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String n = entry.getName();
                if (n.matches("ppt/slides/slide\\d+\\.xml")) {
                    slideXml.put(extractSlideNumber(n), readAll(zis));
                } else if (n.matches("ppt/slides/_rels/slide\\d+\\.xml\\.rels")) {
                    slideRels.put(extractSlideNumber(n), readAll(zis));
                } else if (n.startsWith("ppt/media/")) {
                    media.put(n, readAll(zis));
                }
            }
        }

        List<Slide> slides = new ArrayList<>();
        for (Map.Entry<Integer, byte[]> e : slideXml.entrySet()) {
            int idx = e.getKey();
            Map<String, String> rels = new HashMap<>();
            byte[] relsBytes = slideRels.get(idx);
            if (relsBytes != null) rels = parseRels(new ByteArrayInputStream(relsBytes));

            Map<String, byte[]> mediaByRelId = new HashMap<>();
            for (Map.Entry<String, String> r : rels.entrySet()) {
                String target = r.getValue();
                if (target == null) continue;
                String full = target.startsWith("../media/") ? "ppt/media/" + target.substring(9) : "ppt/" + target;
                byte[] bytes = media.get(full);
                if (bytes != null) mediaByRelId.put(r.getKey(), bytes);
            }

            Slide slide = new Slide();
            slide.index = idx;
            slide.items = parseSlideXml(new ByteArrayInputStream(e.getValue()), mediaByRelId);
            slides.add(slide);
        }
        slides.sort(Comparator.comparingInt(s -> s.index));
        return slides;
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
            if (event == XmlPullParser.START_TAG && "Relationship".equals(parser.getName())) {
                String id = parser.getAttributeValue(null, "Id");
                String target = parser.getAttributeValue(null, "Target");
                if (id != null && target != null) rels.put(id, target);
            }
            event = parser.next();
        }
        return rels;
    }

    private static int extractSlideNumber(String path) {
        try {
            String digits = path.replaceAll("\\D+", "");
            return Integer.parseInt(digits);
        } catch (Exception e) {
            return 0;
        }
    }

    private static boolean tagIs(String name, String local) {
        return name != null && (name.equals(local) || name.endsWith(":" + local));
    }

    private static List<SlideItem> parseSlideXml(InputStream in, Map<String, byte[]> mediaByRelId) throws Exception {
        List<SlideItem> items = new ArrayList<>();
        XmlPullParser parser = Xml.newPullParser();
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
        parser.setInput(in, "UTF-8");

        int event = parser.getEventType();
        StringBuilder paragraph = new StringBuilder();
        boolean inShape = false;
        boolean shapeIsTitle = false;

        while (event != XmlPullParser.END_DOCUMENT) {
            String name = parser.getName();
            if (event == XmlPullParser.START_TAG) {
                if (tagIs(name, "sp")) {
                    inShape = true;
                    shapeIsTitle = false;
                } else if (tagIs(name, "ph") && inShape) {
                    String type = parser.getAttributeValue(null, "type");
                    if ("title".equals(type) || "ctrTitle".equals(type)) shapeIsTitle = true;
                } else if (tagIs(name, "p")) {
                    paragraph.setLength(0);
                } else if (tagIs(name, "t")) {
                    event = parser.next();
                    if (event == XmlPullParser.TEXT) paragraph.append(parser.getText());
                    continue;
                } else if (tagIs(name, "blip")) {
                    String rId = parser.getAttributeValue(null, "r:embed");
                    if (rId == null) rId = parser.getAttributeValue(null, "embed");
                    if (rId != null) {
                        byte[] img = mediaByRelId.get(rId);
                        if (img != null) {
                            SlideItem item = new SlideItem();
                            item.type = SlideItem.Type.IMAGE;
                            item.imageBytes = img;
                            items.add(item);
                        }
                    }
                }
            } else if (event == XmlPullParser.END_TAG) {
                if (tagIs(name, "p")) {
                    String text = paragraph.toString().trim();
                    if (!text.isEmpty()) {
                        SlideItem item = new SlideItem();
                        item.type = shapeIsTitle ? SlideItem.Type.TITLE : SlideItem.Type.BODY;
                        item.text = text;
                        items.add(item);
                    }
                } else if (tagIs(name, "sp")) {
                    inShape = false;
                    shapeIsTitle = false;
                }
            }
            event = parser.next();
        }
        return items;
    }
}
