package com.docreader.app.pdf;

import com.docreader.app.R;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/**
 * شاشة "قاموس الحروف": كل حروف العربية من الألف إلى الياء بتفصيل نطقها (المخرج، الصفات، النوع، الأشكال،
 * الحركات، أمثلة، وتنبيه لمنع الخلط)، ثم الحركات والعلامات والحالات الخاصة (شدّة، تنوين، مدود، تاء مربوطة...).
 * زرّا "استمع" و"اقرأ الشرح" يقرآن الدرس بنفس صوت القارئ (الصوت العصبي الأونلاين).
 * البيانات كلها من {@link ArabicLetters}، وهو نفسه ما تعتمد عليه القراءة الصوتية لنطق الحروف المنفردة.
 */
final class LetterDictionaryDialog {

    private final Activity act;
    private final PdfSpeaker speaker;
    private Dialog current;

    private LetterDictionaryDialog(Activity act, PdfSpeaker speaker) {
        this.act = act;
        this.speaker = speaker;
    }

    static LetterDictionaryDialog show(Activity act, PdfSpeaker speaker) {
        LetterDictionaryDialog d = new LetterDictionaryDialog(act, speaker);
        d.showIndex();
        return d;
    }

    void dismiss() {
        try {
            if (current != null && current.isShowing()) current.dismiss();
        } catch (Throwable ignored) {
        }
        current = null;
    }

    // ------------------------------------------------------------------ القائمة

    private void showIndex() {
        if (act.isFinishing() || act.isDestroyed()) return;
        final List<ArabicLetters.Letter> letters = ArabicLetters.all();
        final List<ArabicLetters.Special> specials = ArabicLetters.specials();

        LinearLayout content = vbox();
        content.addView(text("اضغط على أي حرف لتفاصيل مخرجه وصفاته وطريقة نطقه الصحيحة، ثم استمع إليه.",
                13f, R.color.pdf_text_secondary, false));

        content.addView(sectionTitle("الحروف من الألف إلى الياء"));
        final int perRow = 4;
        LinearLayout row = null;
        for (int i = 0; i < letters.size(); i++) {
            if (i % perRow == 0) {
                row = new LinearLayout(act);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
                LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                rp.topMargin = dp(8);
                content.addView(row, rp);
            }
            final int idx = i;
            row.addView(letterTile(letters.get(i), () -> showLetter(idx)));
        }
        // إكمال آخر صف بخانات فارغة كي يبقى عرض الخانات متساويًا
        int rem = letters.size() % perRow;
        if (rem != 0 && row != null) {
            for (int k = rem; k < perRow; k++) {
                View pad = new View(act);
                row.addView(pad, new LinearLayout.LayoutParams(0, 1, 1f));
            }
        }

        content.addView(sectionTitle("الحركات والعلامات والحالات الخاصة"));
        for (int i = 0; i < specials.size(); i++) {
            final int idx = i;
            final ArabicLetters.Special sp = specials.get(i);
            content.addView(specialRow(sp, () -> showSpecial(idx)));
        }

        present("قاموس الحروف ونطقها", wrapScroll(content));
    }

    // ------------------------------------------------------------------ تفاصيل حرف

    private void showLetter(final int index) {
        if (act.isFinishing() || act.isDestroyed()) return;
        final List<ArabicLetters.Letter> all = ArabicLetters.all();
        final int n = all.size();
        final int i = ((index % n) + n) % n;
        final ArabicLetters.Letter l = all.get(i);

        LinearLayout c = vbox();
        TextView glyph = text(String.valueOf(l.ch), 64f, R.color.primary_cyan, true);
        glyph.setGravity(Gravity.CENTER);
        glyph.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        c.addView(glyph);
        if (l.ch != '\u0621') {
            TextView forms = text(ArabicLetters.forms(l), 24f, R.color.pdf_text_primary, false);
            forms.setGravity(Gravity.CENTER);
            forms.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
            forms.setTextDirection(View.TEXT_DIRECTION_LTR);
            c.addView(forms);
            TextView cap = text("منفصل  ·  أول الكلمة  ·  وسطها  ·  آخرها", 11f, R.color.pdf_text_tertiary, false);
            cap.setGravity(Gravity.CENTER);
            cap.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
            c.addView(cap);
        }

        LinearLayout btns = new LinearLayout(act);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        btns.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bp.leftMargin = dp(6);
        bp.rightMargin = dp(6);
        btns.addView(pill("استمع للنطق", true, () -> play(ArabicLetters.lessonText(l))), bp);
        btns.addView(pill("اقرأ الشرح", false, () -> play(ArabicLetters.explainText(l))), bp);
        LinearLayout.LayoutParams btnsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        btnsLp.topMargin = dp(12);
        c.addView(btns, btnsLp);

        field(c, "الاسم", l.name);
        field(c, "النطق التقريبي", l.latin + "   [" + l.ipa + "]");
        field(c, "المخرج (" + l.group + ")", l.makhraj);
        field(c, "الصفات", l.sifat);
        field(c, "نوع الحرف", ArabicLetters.kindLine(l));
        TextView hk = field(c, "مع الحركات", ArabicLetters.withHarakat(l));
        if (hk != null) hk.setTextSize(22f);
        StringBuilder ex = new StringBuilder();
        String[] lab = {"أول الكلمة", "وسطها", "آخرها"};
        for (int k = 0; k < 3; k++) {
            String e = l.examples[k];
            if (e == null || e.isEmpty()) continue;
            if (ex.length() > 0) ex.append('\n');
            ex.append(lab[k]).append(":  ").append(e);
        }
        field(c, "أمثلة", ex.toString());
        field(c, "تنبيه للنطق الصحيح", l.tip);

        present("حرف " + stripMarks(l.name), wrapScroll(c),
                "التالي", () -> showLetter(i + 1),
                "السابق", () -> showLetter(i - 1), "القائمة", this::showIndex);
    }

    // ------------------------------------------------------------------ تفاصيل علامة/حالة خاصة

    private void showSpecial(final int index) {
        if (act.isFinishing() || act.isDestroyed()) return;
        final List<ArabicLetters.Special> all = ArabicLetters.specials();
        final int n = all.size();
        final int i = ((index % n) + n) % n;
        final ArabicLetters.Special s = all.get(i);

        LinearLayout c = vbox();
        TextView glyph = text(displaySymbol(s.symbol), 56f, R.color.primary_cyan, true);
        glyph.setGravity(Gravity.CENTER);
        glyph.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        c.addView(glyph);

        LinearLayout btns = new LinearLayout(act);
        btns.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams btnsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        btnsLp.topMargin = dp(12);
        btns.addView(pill("استمع", true, () -> play(ArabicLetters.specialText(s))));
        c.addView(btns, btnsLp);

        field(c, "الشرح", s.how);
        TextView ex = field(c, "أمثلة", s.example);
        if (ex != null) ex.setTextSize(20f);

        present(s.title, wrapScroll(c),
                "التالي", () -> showSpecial(i + 1),
                "السابق", () -> showSpecial(i - 1), "القائمة", this::showIndex);
    }

    // ------------------------------------------------------------------ الصوت

    private void play(String text) {
        Toast.makeText(act, "جارٍ التجهيز...", Toast.LENGTH_SHORT).show();
        speaker.previewSample(text, new PdfSpeaker.PreviewListener() {
            @Override
            public void onPrepared(String spokenText) {
                // يبدأ الصوت تلقائيًا
            }

            @Override
            public void onFailed(String message) {
                if (!act.isFinishing() && !act.isDestroyed()) {
                    Toast.makeText(act, message, Toast.LENGTH_LONG).show();
                }
            }
        });
    }

    // ------------------------------------------------------------------ عرض

    private interface Action {
        void run();
    }

    /** يعرض مربع الحوار: next/prev/list اختيارية (تنقّل بين الحروف). */
    private void present(String title, View content, String nextLabel, Action next,
                         String prevLabel, Action prev, String listLabel, Action list) {
        speaker.stopPreview();
        Dialog old = current;
        ClaudeDialog b = new ClaudeDialog(act).setTitle(title).setView(content);
        if (nextLabel != null) b.setPositiveButton(nextLabel, (d, w) -> next.run());
        if (prevLabel != null) b.setNegativeButton(prevLabel, (d, w) -> prev.run());
        if (listLabel != null) b.setNeutralButton(listLabel, (d, w) -> list.run());
        if (nextLabel == null && prevLabel == null && listLabel == null) {
            b.setPositiveButton("إغلاق", null);
        }
        Dialog d = b.show();
        d.setOnDismissListener(x -> {
            if (current == d) speaker.stopPreview();
        });
        current = d;
        if (old != null && old != d) {
            try {
                old.dismiss();
            } catch (Throwable ignored) {
            }
        }
    }

    private void present(String title, View content) {
        present(title, content, null, null, null, null, null, null);
    }

    /** ClaudeDialog يعرض العرض المخصّص بارتفاع WRAP_CONTENT، فنضع التمرير داخل حاوية بارتفاع ثابت (58% من الشاشة). */
    private View wrapScroll(View content) {
        ScrollView sv = new ScrollView(act);
        sv.setVerticalScrollBarEnabled(false);
        sv.addView(content, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout box = new LinearLayout(act);
        box.setOrientation(LinearLayout.VERTICAL);
        int h = (int) (act.getResources().getDisplayMetrics().heightPixels * 0.58f);
        box.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h));
        return box;
    }

    // ------------------------------------------------------------------ مكوّنات

    private LinearLayout vbox() {
        LinearLayout l = new LinearLayout(act);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setPadding(0, dp(4), 0, dp(4));
        return l;
    }

    private TextView text(CharSequence s, float sp, int colorRes, boolean bold) {
        TextView t = new TextView(act);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(act.getColor(colorRes));
        t.setTextDirection(View.TEXT_DIRECTION_RTL);
        t.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private View sectionTitle(String s) {
        TextView t = text(s, 15f, R.color.pdf_text_primary, true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(18);
        t.setLayoutParams(lp);
        return t;
    }

    /** سطر "عنوان صغير + قيمة" ويعيد TextView القيمة لو أردنا تكبيرها. */
    private TextView field(LinearLayout parent, String label, String value) {
        if (value == null || value.trim().isEmpty()) return null;
        TextView l = text(label, 12f, R.color.pdf_text_secondary, true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(14);
        parent.addView(l, lp);
        TextView v = text(value, 15f, R.color.pdf_text_primary, false);
        v.setLineSpacing(0f, 1.2f);
        LinearLayout.LayoutParams vp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        vp.topMargin = dp(2);
        parent.addView(v, vp);
        return v;
    }

    private View letterTile(ArabicLetters.Letter l, final Action click) {
        LinearLayout tile = new LinearLayout(act);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setGravity(Gravity.CENTER);
        tile.setPadding(dp(4), dp(8), dp(4), dp(8));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(14));
        bg.setColor(act.getColor(R.color.glass_fill));
        bg.setStroke(dp(1), act.getColor(R.color.glass_border_soft));
        tile.setBackground(bg);
        TextView g = text(String.valueOf(l.ch), 26f, R.color.pdf_text_primary, true);
        g.setGravity(Gravity.CENTER);
        g.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        tile.addView(g);
        TextView n = text(stripMarks(l.name), 11f, R.color.pdf_text_secondary, false);
        n.setGravity(Gravity.CENTER);
        n.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        tile.addView(n);
        tile.setContentDescription("حرف " + stripMarks(l.name));
        tile.setClickable(true);
        tile.setOnClickListener(v -> click.run());
        Ui.applyPressFeedback(tile);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.leftMargin = dp(4);
        lp.rightMargin = dp(4);
        tile.setLayoutParams(lp);
        return tile;
    }

    private View specialRow(ArabicLetters.Special s, final Action click) {
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(6), 0, dp(6));
        TextView sym = text(displaySymbol(s.symbol), 24f, R.color.primary_cyan, true);
        sym.setGravity(Gravity.CENTER);
        sym.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
        sym.setMinWidth(dp(56));
        row.addView(sym);
        TextView t = text(s.title, 15f, R.color.pdf_text_primary, false);
        row.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.setMinimumHeight(dp(48));
        row.setClickable(true);
        row.setOnClickListener(v -> click.run());
        android.util.TypedValue tv = new android.util.TypedValue();
        if (act.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true)) {
            row.setBackgroundResource(tv.resourceId);
        }
        return row;
    }

    private View pill(String label, boolean filled, final Action click) {
        TextView t = new TextView(act);
        t.setText(label);
        t.setTextSize(14f);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(18), dp(9), dp(18), dp(9));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(100));
        if (filled) {
            bg.setColor(act.getColor(R.color.primary_cyan));
            t.setTextColor(act.getColor(R.color.white));
        } else {
            bg.setColor(0x00000000);
            bg.setStroke(dp(1), act.getColor(R.color.glass_border_soft));
            t.setTextColor(act.getColor(R.color.pdf_text_primary));
        }
        t.setBackground(bg);
        t.setClickable(true);
        t.setOnClickListener(v -> click.run());
        Ui.applyPressFeedback(t);
        return t;
    }

    /** العلامات المركّبة (فتحة، شدّة...) تُعرض مع تطويل كي تظهر بوضوح على حرف. */
    private static String displaySymbol(String sym) {
        if (sym.length() == 1 && ArabicPhonetics.isMark(sym.charAt(0))) return "\u0640" + sym;
        return sym;
    }

    private static String stripMarks(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!ArabicPhonetics.isMark(c)) sb.append(c);
        }
        return sb.toString();
    }

    private int dp(float v) {
        return Ui.dp(act, v);
    }
}
