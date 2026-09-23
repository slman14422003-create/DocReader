package com.docreader.app.viewer;

import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * يستخرج نص كل شريحة من ملف PowerPoint (.pptx) بقراءة
 * ppt/slides/slideN.xml مباشرة من داخل حزمة ZIP.
 */
public class PptxParser {

    public static class Slide {
        public int index;
        public List<String> textLines = new ArrayList<>();
    }

    public static List<Slide> parse(InputStream zipStream) throws Exception {
        List<Slide> slides = new ArrayList<>();
        try (ZipInputStream zis = new ZipInputStream(zipStream)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String n = entry.getName();
                if (n.matches("ppt/slides/slide\\d+\\.xml")) {
                    int idx = extractSlideNumber(n);
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    byte[] buf = new byte[4096];
                    int len;
                    while ((len = zis.read(buf)) > 0) bos.write(buf, 0, len);

                    Slide slide = new Slide();
                    slide.index = idx;
                    slide.textLines = parseSlideXml(new ByteArrayInputStream(bos.toByteArray()));
                    slides.add(slide);
                }
            }
        }
        slides.sort(Comparator.comparingInt(s -> s.index));
        return slides;
    }

    private static int extractSlideNumber(String path) {
        try {
            String digits = path.replaceAll("\\D+", "");
            return Integer.parseInt(digits);
        } catch (Exception e) {
            return 0;
        }
    }

    private static List<String> parseSlideXml(InputStream in) throws Exception {
        List<String> lines = new ArrayList<>();
        XmlPullParser parser = Xml.newPullParser();
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
        parser.setInput(in, "UTF-8");

        int event = parser.getEventType();
        StringBuilder paragraph = new StringBuilder();
        while (event != XmlPullParser.END_DOCUMENT) {
            String name = parser.getName();
            if (event == XmlPullParser.START_TAG && "p".equals(name)) {
                paragraph.setLength(0);
            } else if (event == XmlPullParser.START_TAG && "t".equals(name)) {
                if (parser.next() == XmlPullParser.TEXT) {
                    paragraph.append(parser.getText());
                }
                continue;
            } else if (event == XmlPullParser.END_TAG && "p".equals(name)) {
                String text = paragraph.toString().trim();
                if (!text.isEmpty()) lines.add(text);
            }
            event = parser.next();
        }
        return lines;
    }
}
