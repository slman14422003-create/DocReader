package com.docreader.app.pdf;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.view.View;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import com.docreader.app.R;
import com.google.android.material.textfield.TextInputEditText;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * إدارة "مكتبات الكلمات" (قاموس التشكيل/النطق) للقراءة الصوتية: تفعيل/إيقاف، استيراد من ملف أو رابط،
 * حذف، وتنبؤ الذكاء المحلي. منقولة كما هي من شاشة إعدادات تطبيق الفيزيو.
 *
 * الاستخدام من أي AppCompatActivity: أنشئ الكائن داخل onCreate (قبل STARTED لأنه يسجّل ActivityResult)،
 * ثم استدعِ show() عند الضغط على الصف، وsummary(context) لنص الحالة.
 */
public final class DictionaryPacksUi {

    private final AppCompatActivity act;
    private final Runnable onChanged;
    private final ActivityResultLauncher<String[]> importPicker;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    /** @param onChanged يُستدعى بعد أي تغيير لتحديث سطر الحالة (يمكن أن يكون null). */
    public DictionaryPacksUi(AppCompatActivity act, Runnable onChanged) {
        this.act = act;
        this.onChanged = onChanged;
        this.importPicker = act.registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
            if (uri != null) importFromUri(uri);
        });
    }

    public static String summary(Context context) {
        return DictionaryPacks.summary(context);
    }

    /** يُستدعى من onDestroy للتطبيق المضيف. */
    public void release() {
        executor.shutdownNow();
    }

    private boolean uiAlive() {
        return !act.isFinishing() && !act.isDestroyed();
    }

    private void notifyChanged() {
        if (onChanged != null) onChanged.run();
    }

    public void show() {
        final List<DictionaryPacks.Pack> packs = DictionaryPacks.list(act);
        final int n = packs.size();
        final String[] items = new String[n + 1];
        for (int i = 0; i < n; i++) {
            DictionaryPacks.Pack p = packs.get(i);
            String count = p.accepted > 0 ? "  (" + p.accepted + " كلمة)" : "";
            items[i] = (p.enabled ? "\u2713  " : "\u25CB  ") + p.title + count;
        }
        final boolean predict = DictionaryPacks.isPredictEnabled(act);
        items[n] = (predict ? "\u2713  " : "\u25CB  ") + "تنبؤ الذكاء المحلي بالكلمات غير الموجودة (تجريبي)";

        new ClaudeDialog(act)
                .setTitle("مكتبات الكلمات")
                .setMessage("اضغط على مكتبة لتفعيلها أو إيقافها ويُعاد دمج القاموس تلقائيًا. الأعلى في القائمة يتقدّم عند تعارض كلمة بين مكتبتين. "
                        + "التنبؤ يشكّل كلمات ليست في أي مكتبة بحسب أنماط الحروف؛ دقته نحو 87% فقط، فهو متوقف ما لم تفعّله.")
                .setItems(items, (dialog, which) -> {
                    if (which == n) {
                        DictionaryPacks.setPredictEnabled(act, !predict);
                    } else {
                        DictionaryPacks.Pack p = packs.get(which);
                        DictionaryPacks.setEnabled(act, p.id, !p.enabled);
                    }
                    applyChanges(null);
                    show();
                })
                .setPositiveButton("استيراد مكتبة", (dialog, which) -> showImportChoiceDialog())
                .setNeutralButton("حذف مكتبة", (dialog, which) -> showDeleteDialog())
                .setNegativeButton("إغلاق", null)
                .show();
    }

    /** يعيد دمج القاموس في الخلفية بعد أي تغيير، ثم يحدّث سطر الإعدادات. */
    private void applyChanges(final String toastWhenDone) {
        TashkeelDict.reloadAsync(act.getApplicationContext(), () -> act.runOnUiThread(() -> {
            if (!uiAlive()) return;
            notifyChanged();
            if (toastWhenDone != null) Toast.makeText(act, toastWhenDone, Toast.LENGTH_LONG).show();
        }));
    }

    private void showImportChoiceDialog() {
        final String[] items = {"من ملف على الجهاز", "من رابط (https)"};
        new ClaudeDialog(act)
                .setTitle("استيراد مكتبة كلمات")
                .setMessage("ملف نصي UTF-8 فيه كلمات عربية مشكولة. الصيغة المفضلة: في كل سطر الكلمة بلا تشكيل ثم Tab ثم الكلمة مشكولة. "
                        + "الأسطر الفاسدة (حروف مختلفة أو تشكيل خاطئ) تُتجاهل تلقائيًا.")
                .setItems(items, (dialog, which) -> {
                    if (which == 0) importPicker.launch(new String[]{"*/*"});
                    else showImportUrlDialog();
                })
                .setNegativeButton("إلغاء", null)
                .show();
    }

    private void showImportUrlDialog() {
        TextInputEditText input = new TextInputEditText(act);
        input.setBackgroundResource(R.drawable.bg_input_field);
        input.setPadding(Ui.dp(act, 16), Ui.dp(act, 14), Ui.dp(act, 16), Ui.dp(act, 14));
        input.setSingleLine(true);
        input.setTextDirection(View.TEXT_DIRECTION_LTR);
        input.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        input.setTextSize(15f);
        input.setTextColor(act.getColor(R.color.pdf_text_primary));
        input.setHintTextColor(act.getColor(R.color.pdf_text_tertiary));
        input.setHint("https://example.com/words.txt");
        new ClaudeDialog(act)
                .setTitle("استيراد من رابط")
                .setMessage("الرابط لازم يبدأ بـ https وحجم الملف حتى 60 ميجابايت.")
                .setView(input)
                .setPositiveButton("تحميل", (dialog, which) -> {
                    String url = input.getText() == null ? "" : input.getText().toString().trim();
                    if (url.isEmpty()) return;
                    importFromUrl(url);
                })
                .setNegativeButton("إلغاء", null)
                .show();
    }

    private String displayNameOf(Uri uri) {
        try (Cursor c = act.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                String n = c.getString(0);
                if (n != null && !n.trim().isEmpty()) return n;
            }
        } catch (RuntimeException ignored) {
        }
        String last = uri.getLastPathSegment();
        return last == null ? "مكتبة مستوردة" : last;
    }

    private void importFromUri(final Uri uri) {
        final String name = displayNameOf(uri);
        Toast.makeText(act, "جارٍ استيراد المكتبة...", Toast.LENGTH_SHORT).show();
        final Context app = act.getApplicationContext();
        executor.execute(() -> {
            try {
                DictionaryPacks.Pack p = DictionaryPacks.importFromUri(app, uri, name);
                TashkeelDict.reload(app);
                act.runOnUiThread(() -> onImported(p));
            } catch (IOException | RuntimeException e) {
                act.runOnUiThread(() -> onImportFailed(e.getMessage()));
            }
        });
    }

    private void importFromUrl(final String url) {
        Toast.makeText(act, "جارٍ تحميل المكتبة...", Toast.LENGTH_SHORT).show();
        final Context app = act.getApplicationContext();
        executor.execute(() -> {
            try {
                DictionaryPacks.Pack p = DictionaryPacks.importFromUrl(app, url, null);
                TashkeelDict.reload(app);
                act.runOnUiThread(() -> onImported(p));
            } catch (IOException | RuntimeException e) {
                act.runOnUiThread(() -> onImportFailed(e.getMessage()));
            }
        });
    }

    private void onImported(DictionaryPacks.Pack p) {
        if (!uiAlive()) return;
        notifyChanged();
        String rejected = p.rejected > 0 ? " وتم تجاهل " + p.rejected + " سطرًا فاسدًا" : "";
        Toast.makeText(act, "تمت إضافة \"" + p.title + "\": " + p.accepted + " كلمة" + rejected + ".", Toast.LENGTH_LONG).show();
        show();
    }

    private void onImportFailed(String message) {
        if (!uiAlive()) return;
        new ClaudeDialog(act)
                .setTitle("تعذّر الاستيراد")
                .setMessage(message == null || message.isEmpty() ? "حدث خطأ غير متوقع." : message)
                .setPositiveButton("حسنًا", null)
                .show();
    }

    private void showDeleteDialog() {
        final List<DictionaryPacks.Pack> mine = new ArrayList<>();
        for (DictionaryPacks.Pack p : DictionaryPacks.list(act)) if (p.isUser()) mine.add(p);
        if (mine.isEmpty()) {
            new ClaudeDialog(act)
                    .setTitle("حذف مكتبة")
                    .setMessage("لا توجد مكتبات مستوردة لحذفها. المكتبات المدمجة مع التطبيق لا تُحذف، لكن يمكنك إيقافها من القائمة.")
                    .setPositiveButton("حسنًا", null)
                    .show();
            return;
        }
        final String[] items = new String[mine.size()];
        for (int i = 0; i < items.length; i++) items[i] = mine.get(i).title;
        new ClaudeDialog(act)
                .setTitle("حذف مكتبة مستوردة")
                .setItems(items, (dialog, which) -> {
                    final DictionaryPacks.Pack p = mine.get(which);
                    new ClaudeDialog(act)
                            .setTitle("حذف المكتبة")
                            .setMessage("حذف \"" + p.title + "\" نهائيًا من الجهاز؟")
                            .setPositiveButton("حذف", (d2, w2) -> {
                                DictionaryPacks.remove(act, p.id);
                                applyChanges("تم حذف المكتبة.");
                                notifyChanged();
                            })
                            .setNegativeButton("إلغاء", null)
                            .show();
                })
                .setNegativeButton("إغلاق", null)
                .show();
    }
}
