package com.docreader.app.pdf;

import android.content.Context;
import android.graphics.RectF;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * بحث نصي داخل ملف PDF: يمرّ على الصفحات صفحةً صفحة (عبر PdfSpeechText الذي يعطي كل كلمة
 * بموضعها كنسبة من الصفحة) ويُرجع كل تطابق كمستطيلات جاهزة للتظليل فوق الصفحة.
 * البحث يتجاهل التشكيل وفروق الهمزات (أ/إ/آ) والتاء المربوطة والألف المقصورة وحالة الأحرف اللاتينية.
 * يُنادى على خيط خلفي؛ الإلغاء عبر تغيير قيمة token.
 */
final class PdfSearchEngine {

    private PdfSearchEngine() {
    }

    static final int MAX_HITS = 500;

    static final class Hit {
        final int page;
        final List<RectF> rects;

        Hit(int page, List<RectF> rects) {
            this.page = page;
            this.rects = rects;
        }
    }

    interface Listener {
        void onHit(Hit hit);

        void onProgress(int donePages, int totalPages);

        void onDone(int totalHits, boolean truncated);

        void onError(String message);
    }

    static void run(Context ctx, File file, String query, AtomicInteger token, int myToken, Listener l) {
        String[] tokens = tokenize(query);
        if (tokens.length == 0) {
            l.onDone(0, false);
            return;
        }
        PdfSpeechText.Source src = null;
        try {
            src = PdfSpeechText.Source.open(ctx, file);
            int total = src.pageCount();
            int hits = 0;
            boolean truncated = false;
            for (int p = 0; p < total; p++) {
                if (token.get() != myToken) return;
                PdfSpeechText.PageText pt = src.page(p);
                List<PdfSpeechText.Word> words = pt.words;
                int n = words.size();
                String[] nw = new String[n];
                for (int i = 0; i < n; i++) nw[i] = normalize(words.get(i).text);
                for (int i = 0; i + tokens.length <= n; i++) {
                    boolean ok = true;
                    for (int k = 0; k < tokens.length; k++) {
                        if (!nw[i + k].contains(tokens[k])) {
                            ok = false;
                            break;
                        }
                    }
                    if (!ok) continue;
                    List<RectF> rects = new ArrayList<>();
                    for (int k = 0; k < tokens.length; k++) rects.add(new RectF(words.get(i + k).box));
                    if (token.get() != myToken) return;
                    l.onHit(new Hit(p, rects));
                    hits++;
                    i += tokens.length - 1;
                    if (hits >= MAX_HITS) {
                        truncated = true;
                        break;
                    }
                }
                l.onProgress(p + 1, total);
                if (truncated) break;
            }
            if (token.get() == myToken) l.onDone(hits, truncated);
        } catch (Throwable t) {
            if (token.get() == myToken) {
                String m = t.getMessage();
                l.onError(m == null || m.trim().isEmpty() ? t.getClass().getSimpleName() : m);
            }
        } finally {
            if (src != null) src.close();
        }
    }

    private static String[] tokenize(String query) {
        String q = normalize(query == null ? "" : query).trim();
        if (q.isEmpty()) return new String[0];
        String[] parts = q.split("\\s+");
        List<String> out = new ArrayList<>();
        for (String s : parts) if (!s.isEmpty()) out.add(s);
        return out.toArray(new String[0]);
    }

    static String normalize(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= '\u064B' && c <= '\u065F') || c == '\u0670' || c == '\u0640'
                    || c == '\u200E' || c == '\u200F' || c == '\u200B' || c == '\u202A'
                    || c == '\u202B' || c == '\u202C') {
                continue;
            }
            switch (c) {
                case '\u0623':
                case '\u0625':
                case '\u0622':
                case '\u0671':
                    c = '\u0627';
                    break;
                case '\u0629':
                    c = '\u0647';
                    break;
                case '\u0649':
                    c = '\u064A';
                    break;
                default:
                    break;
            }
            sb.append(Character.toLowerCase(c));
        }
        return sb.toString();
    }
}
