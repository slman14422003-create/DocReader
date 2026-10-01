package com.docreader.app.pdf;

import com.docreader.app.R;

import android.app.Dialog;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import android.content.SharedPreferences;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.animation.DecelerateInterpolator;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.SeekBar;
import androidx.activity.OnBackPressedCallback;
import java.util.TreeSet;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.ColorDrawable;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;
import android.text.InputType;
import android.util.LruCache;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.textfield.TextInputEditText;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * قارئ PDF داخل التطبيق (بدون أي مكتبة خارجية - يعتمد على android.graphics.pdf.PdfRenderer):
 * صفحات كبطاقات فوق خلفية التطبيق، مؤشر صفحة عائم، تكبير/تصغير مع تمرير جانبي،
 * انتقال لصفحة معيّنة، وفتح الملف بتطبيق آخر.
 *
 * طرق الفتح:
 *  1) من الملفات السحابية: EXTRA_PATH (مسار ملف محلي داخل الكاش) + EXTRA_TITLE.
 *  2) من تطبيق آخر: ACTION_VIEW برابط content:// لملف PDF (يُنسخ للكاش أولًا).
 *  3) بدون أي بيانات (زر "قارئ PDF" في الشاشة الرئيسية): يفتح منتقي الملفات مباشرة.
 */
public class PdfViewerActivity extends AppCompatActivity {

    public static final String EXTRA_PATH = "pdf_path";
    public static final String EXTRA_TITLE = "pdf_title";

    /** حدود التكبير المتاحة (% من عرض الشاشة) - أدنى 60% وأقصى 300% لتجنّب
     *  استهلاك الذاكرة، وخطوة أزرار +/- الثابتة 50% لكل ضغطة. التكبير نفسه
     *  أصبح قيمة مستمرة (float) بدل درجات ثابتة (100/150/200) حتى يسمح
     *  بالتكبير/التصغير بحركة إصبعين (Pinch) بأي نسبة بينهم، مش قفزات فقط. */
    private static final float MIN_ZOOM_PERCENT = 60f;
    private static final float MAX_ZOOM_PERCENT = 300f;
    private static final float ZOOM_STEP_PERCENT = 50f;
    private static final int MAX_BITMAP_WIDTH = 2400;
    private static final long INDICATOR_HIDE_DELAY_MS = 1400;
    /** مجلد النسخ المؤقتة داخل الكاش (مغطّى بـ pdf_share_paths.xml حتى تعمل المشاركة عبر FileProvider). */
    private static final String CACHE_DIR = "pdf_reader";

    private TextView titleView;
    private TextView subtitleView;
    private HorizontalScrollView hScroll;
    private RecyclerView pages;
    private LinearLayoutManager layoutManager;
    private View loadingBox;
    private TextView loadingText;
    private View emptyBox;
    private TextView pageIndicator;
    private ImageButton zoomOutBtn;
    private ImageButton zoomInBtn;

    // ---- الشريط العلوي/السفلي، البحث، الإشارات المرجعية، حفظ موضع القراءة
    private View topChrome;
    private View searchBar;
    private EditText searchInput;
    private TextView searchCount;
    private View navBar;
    private SeekBar navSeek;
    private TextView navLabel;
    private boolean seekDragging = false;
    private boolean chromeVisible = true;
    private OnBackPressedCallback backCallback;
    private SharedPreferences prefs;
    private String docKey = "";
    private final TreeSet<Integer> bookmarks = new TreeSet<>();
    private final AtomicInteger searchToken = new AtomicInteger();
    private final List<PdfSearchEngine.Hit> hits = new ArrayList<>();
    private int hitIndex = -1;
    private GestureDetector tapDetector;
    private boolean gestureTracking = false;
    private boolean tapWasScrolling = false;
    private final Runnable savePositionRunnable = this::saveReadingPosition;

    private final ExecutorService loadExecutor = Executors.newSingleThreadExecutor();
    /** طابور LIFO: آخر صفحة ظهرت على الشاشة تُرسم أولًا (بدل انتظار الصفحات التي تجاوزها المستخدم). */
    private final ExecutorService renderExecutor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
            new LinkedBlockingDeque<Runnable>() {
                @Override
                public boolean offer(Runnable r) {
                    return offerFirst(r);
                }
            });
    /** استخراج نص الصفحة (PdfBox) قبل إرسالها لخدمة الترجمة - منفصل عن renderExecutor
     *  حتى لا تنتظر الترجمة دورها خلف رسم الصفحات. */
    private final ExecutorService textExecutor = Executors.newSingleThreadExecutor();
    /** PdfRenderer لا يدعم فتح أكثر من صفحة في نفس الوقت ولا الوصول من عدة خيوط. */
    private final Object renderLock = new Object();
    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    private PdfRenderer renderer;
    private ParcelFileDescriptor descriptor;
    private float[] ratios = new float[0];
    private File currentFile;
    private float zoomPercent = 100f;
    /** آخر نسبة تكبير فعلية اترسمت بيها الصفحات (تتحدّث فقط لما applyZoom
     *  فعليًا يعيد الرسم) - بيُستخدم أثناء حركة القرص (Pinch) لحساب مقياس
     *  عرض مؤقت (scaleX/scaleY) بدون إعادة رسم الـ Bitmaps مع كل حركة
     *  إصبع، فقط عند توقف الحركة. */
    private float renderedZoomPercent = 100f;
    private ScaleGestureDetector pinchDetector;
    /** الوضع الليلي لصفحات الـ PDF: يُفعَّل تلقائياً حسب مظهر النظام، ويمكن للمستخدم تبديله يدوياً من القائمة. */
    private static final int MODE_LIGHT = 0;
    private static final int MODE_NIGHT = 1;
    private static final int MODE_SEPIA = 2;
    private int pageMode = MODE_LIGHT;
    private boolean nightPagesUserOverride = false;
    /** يزيد مع كل تغيير للتكبير؛ أي رسم قديم بجيل مختلف يُتجاهل. */
    private volatile int generation = 0;
    private LruCache<Long, Bitmap> cache;
    private PageAdapter adapter;
    private ActivityResultLauncher<String[]> pickLauncher;
    private boolean translateInProgress = false;
    private static volatile boolean pdfBoxReadyForText = false;

    private static final String[] TRANSLATE_LANG_LABELS = {"العربية", "English", "Français", "Türkçe"};
    private static final String[] TRANSLATE_LANG_CODES = {"ar", "en", "fr", "tr"};

    /** ترجمة الملف كاملاً (كل الصفحات) إلى PDF منسّق - بخلاف "ترجمة الصفحة" أعلاه
     *  التي تعرض نص صفحة واحدة فقط داخل نافذة عابرة. */
    private boolean fullTranslateInProgress = false;
    private volatile boolean fullTranslateCancelled = false;
    private Dialog fullTranslateDialog;
    private TextView fullTranslateStatus;
    private ProgressBar fullTranslateBar;

    /** حفظ نسخة من أي PDF مفتوح حاليًا (الأصلي أو ناتج الترجمة) على الجهاز عبر منتقي حفظ النظام. */
    private ActivityResultLauncher<String> createDocumentLauncher;
    private File pendingSaveSource;

    private final Runnable hideIndicatorRunnable = () -> {
        if (pageIndicator == null) return;
        pageIndicator.animate().alpha(0f).setDuration(250)
                .withEndAction(() -> pageIndicator.setVisibility(View.GONE)).start();
    };

    // ------------------------------------------------------------------ دورة الحياة

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_pdf_viewer);

        // DocReader يستهدف API 36 فيُفرض عرض الشاشة كاملة خلف شريطي النظام؛ نُبقي القارئ داخل الحدود الآمنة.
        final View pdfRoot = findViewById(R.id.pdf_root);
        ViewCompat.setOnApplyWindowInsetsListener(pdfRoot, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            v.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, ime.bottom));
            return WindowInsetsCompat.CONSUMED;
        });

        // تفعيل الوضع الليلي للصفحات تلقائياً إذا كان النظام/التطبيق بالوضع الداكن.
        prefs = getSharedPreferences("pdf_reader", MODE_PRIVATE);
        int savedMode = prefs.getInt("page_mode", -1);
        if (savedMode >= MODE_LIGHT && savedMode <= MODE_SEPIA) {
            pageMode = savedMode;
            nightPagesUserOverride = true;
        } else {
            pageMode = isSystemNightMode() ? MODE_NIGHT : MODE_LIGHT;
        }

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());
        toolbar.inflateMenu(R.menu.menu_pdf_viewer);
        // القائمتان مبنيتان بالكامل كـ actionLayout مخصّص (بِسمة التكبير/
        // التصغير وزر "المزيد") بدل عناصر قائمة Toolbar الافتراضية، فمفيش
        // داعي لـ onMenuItemClickListener هنا - كل زر بيوصّل حدثه مباشرة.
        Menu menu = toolbar.getMenu();
        View zoomAction = menu.findItem(R.id.action_pdf_zoom).getActionView();
        if (zoomAction != null) {
            zoomOutBtn = zoomAction.findViewById(R.id.btn_pdf_zoom_out);
            zoomInBtn = zoomAction.findViewById(R.id.btn_pdf_zoom_in);
            if (zoomOutBtn != null) zoomOutBtn.setOnClickListener(v -> changeZoom(-1));
            if (zoomInBtn != null) zoomInBtn.setOnClickListener(v -> changeZoom(+1));
            updateZoomButtonsState();
        }
        View moreAction = menu.findItem(R.id.action_pdf_more).getActionView();
        if (moreAction != null) {
            moreAction.setOnClickListener(this::showMoreMenu);
        }

        titleView = findViewById(R.id.pdf_title);
        subtitleView = findViewById(R.id.pdf_subtitle);
        hScroll = findViewById(R.id.pdf_hscroll);
        pages = findViewById(R.id.pdf_pages);
        loadingBox = findViewById(R.id.pdf_loading);
        loadingText = findViewById(R.id.pdf_loading_text);
        emptyBox = findViewById(R.id.pdf_empty);
        pageIndicator = findViewById(R.id.pdf_page_indicator);
        bindTtsBar();
        bindChrome();

        // ذاكرة مؤقتة للصفحات المرسومة: سدس الذاكرة المتاحة للتطبيق (حد أدنى 24 ميجا)
        int maxKb = (int) (Runtime.getRuntime().maxMemory() / 1024);
        cache = new LruCache<Long, Bitmap>(Math.max(24 * 1024, maxKb / 6)) {
            @Override
            protected int sizeOf(Long key, Bitmap value) {
                return value.getByteCount() / 1024;
            }
        };

        layoutManager = new LinearLayoutManager(this);
        pages.setLayoutManager(layoutManager);
        pages.setItemAnimator(null);
        pages.setItemViewCacheSize(4);
        adapter = new PageAdapter();
        pages.setAdapter(adapter);
        setupPinchZoom();
        pages.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                if (dy != 0) updateIndicator();
            }

            @Override
            public void onScrollStateChanged(@NonNull RecyclerView rv, int newState) {
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) userScrolled = true;
                else if (newState == RecyclerView.SCROLL_STATE_IDLE && userScrolled) {
                    userScrolled = false;
                    reseekReadingToVisiblePage();
                }
            }
        });
        pageIndicator.setOnClickListener(v -> showGoToPageDialog());
        Ui.applyPressFeedback(pageIndicator);

        pickLauncher = registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
            if (uri != null) {
                importAndLoad(uri);
            } else if (renderer == null) {
                finish(); // ألغى الاختيار ولا يوجد ملف معروض
            }
        });

        createDocumentLauncher = registerForActivityResult(
                new ActivityResultContracts.CreateDocument("application/pdf"), this::onSaveDestinationChosen);

        handleIntent(getIntent());
    }

    /** نقطة الدخول للتطبيق المضيف: يفتح القارئ لملف PDF عبر Uri (content://) ويمنحه صلاحية القراءة. */
    public static void open(android.content.Context context, Uri uri) {
        Intent i = new Intent(context, PdfViewerActivity.class)
                .setAction(Intent.ACTION_VIEW)
                .setData(uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        if (!(context instanceof android.app.Activity)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(i);
    }

    private void handleIntent(Intent intent) {
        String path = intent.getStringExtra(EXTRA_PATH);
        if (path != null) {
            File f = new File(path);
            String title = intent.getStringExtra(EXTRA_TITLE);
            loadFile(f, title != null && !title.trim().isEmpty() ? title : f.getName());
            return;
        }
        Uri data = intent.getData();
        if (Intent.ACTION_VIEW.equals(intent.getAction()) && data != null) {
            importAndLoad(data);
            return;
        }
        launchPicker();
    }

    private void launchPicker() {
        try {
            pickLauncher.launch(new String[]{"application/pdf"});
        } catch (ActivityNotFoundException e) {
            showError("تعذّر اختيار الملف", "لا يوجد تطبيق على جهازك لاختيار الملفات.");
        }
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // تدوير الشاشة: نعيد حساب عرض الصفحات حسب العرض الجديد
        if (ratios.length > 0) hScroll.post(this::applyZoom);
        // إذا لم يتدخّل المستخدم يدوياً، نتابع تلقائياً أي تغيير بمظهر النظام (فاتح/داكن).
        if (!nightPagesUserOverride) {
            int want = isSystemNightMode() ? MODE_NIGHT : MODE_LIGHT;
            if (want != pageMode) {
                pageMode = want;
                refreshRenderedPages();
            }
        }
    }

    private boolean isSystemNightMode() {
        int mode = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return mode == Configuration.UI_MODE_NIGHT_YES;
    }

    /** يعيد رسم كل الصفحات الظاهرة بعد تبديل الوضع الليلي (يُبطل الذاكرة المؤقتة فقط، بدون تغيير التكبير). */
    private void refreshRenderedPages() {
        if (ratios.length == 0) return;
        generation++;
        cache.evictAll();
        adapter.notifyDataSetChanged();
    }

    @Override
    public void finish() {
        super.finish();
        overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right);
    }

    @Override
    protected void onPause() {
        super.onPause();
        saveReadingPosition();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        searchToken.incrementAndGet();
        uiHandler.removeCallbacksAndMessages(null);
        loadExecutor.shutdownNow();
        renderExecutor.shutdownNow();
        textExecutor.shutdownNow();
        if (voiceDialog != null) voiceDialog.dismiss();
        if (letterDialog != null) letterDialog.dismiss();
        if (speaker != null) {
            speaker.shutdown();
            speaker = null;
        }
        releaseRenderer();
        cache.evictAll();
    }

    // ------------------------------------------------------------------ القائمة

    private void changeZoom(int direction) {
        if (ratios.length == 0) return;
        setZoomPercent(zoomPercent + direction * ZOOM_STEP_PERCENT);
    }

    /** يضبط نسبة التكبير الفعلية (تُستخدم من أزرار +/- وحركة القرص بإصبعين
     *  الاتنين) - تُحصر بين MIN/MAX_ZOOM_PERCENT ثم يُعاد رسم الصفحات فورًا. */
    private void setZoomPercent(float percent) {
        float clamped = Math.max(MIN_ZOOM_PERCENT, Math.min(MAX_ZOOM_PERCENT, percent));
        if (Math.abs(clamped - zoomPercent) < 0.01f) return;
        zoomPercent = clamped;
        applyZoom();
        updateZoomButtonsState();
    }

    /** يعكس حدود التكبير/التصغير (أقصى/أدنى نسبة) بإطفاء الزر المعني بدل
     *  تركه يبدو فعّالًا وهو بلا تأثير. */
    private void updateZoomButtonsState() {
        if (zoomOutBtn != null) {
            boolean enabled = zoomPercent > MIN_ZOOM_PERCENT + 0.5f;
            zoomOutBtn.setEnabled(enabled);
            zoomOutBtn.setAlpha(enabled ? 1f : 0.35f);
        }
        if (zoomInBtn != null) {
            boolean enabled = zoomPercent < MAX_ZOOM_PERCENT - 0.5f;
            zoomInBtn.setEnabled(enabled);
            zoomInBtn.setAlpha(enabled ? 1f : 0.35f);
        }
    }

    /** يُنشئ كاشف حركة القرص بإصبعين (Pinch-to-zoom) على منطقة عرض الصفحات:
     *  أثناء الحركة نطبّق مقياس عرض مؤقت (scaleX/scaleY) على pages مباشرة
     *  للاستجابة الفورية الناعمة بدون أي إعادة رسم فعلي (رخيص جدًا)، وبس
     *  لما تنتهي حركة الإصبعين (onScaleEnd) نحوّل المقياس المؤقت لنسبة
     *  تكبير حقيقية عبر setZoomPercent() اللي بتعيد رسم الـ Bitmaps بدقة
     *  مناسبة للعرض الجديد - بدل ما نعيد الرسم مع كل حدث لمس (بطيء جدًا
     *  ومكلف للذاكرة مع صفحات PDF عالية الدقة). */
    private void setupPinchZoom() {
        pinchDetector = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScaleBegin(@NonNull ScaleGestureDetector detector) {
                return ratios.length > 0;
            }

            @Override
            public boolean onScale(@NonNull ScaleGestureDetector detector) {
                float liveScale = pages.getScaleX() * detector.getScaleFactor();
                float minLive = MIN_ZOOM_PERCENT / renderedZoomPercent;
                float maxLive = MAX_ZOOM_PERCENT / renderedZoomPercent;
                liveScale = Math.max(minLive, Math.min(maxLive, liveScale));
                pages.setPivotX(detector.getFocusX());
                pages.setPivotY(detector.getFocusY());
                pages.setScaleX(liveScale);
                pages.setScaleY(liveScale);
                return true;
            }

            @Override
            public void onScaleEnd(@NonNull ScaleGestureDetector detector) {
                float finalPercent = renderedZoomPercent * pages.getScaleX();
                pages.setScaleX(1f);
                pages.setScaleY(1f);
                setZoomPercent(finalPercent);
            }
        });
        tapDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onSingleTapConfirmed(@NonNull MotionEvent e) {
                if (!tapWasScrolling) toggleChrome();
                return true;
            }

            @Override
            public boolean onDoubleTap(@NonNull MotionEvent e) {
                if (ratios.length == 0) return false;
                setZoomPercent(zoomPercent >= 150f ? 100f : 200f);
                return true;
            }
        });
    }

    /**
     * كان كاشف الـ Pinch مربوطًا بـ hScroll.setOnTouchListener، لكن RecyclerView (الابن) يستهلك
     * اللمسات فلا تصل للأب - فكان التكبير بإصبعين لا يعمل بثبات. الحل: نغذّي الكاشفين من
     * dispatchTouchEvent للنشاط نفسه (يرى كل اللمسات) مع استثناء الأشرطة العائمة.
     */
    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (pinchDetector != null && tapDetector != null && hScroll != null) {
            int a = ev.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN) {
                gestureTracking = hScroll.getVisibility() == View.VISIBLE && ratios.length > 0
                        && hitView(hScroll, ev) && !hitView(topChrome, ev) && !hitView(navBar, ev)
                        && !hitView(ttsBar, ev) && !hitView(pageIndicator, ev);
                tapWasScrolling = pages.getScrollState() != RecyclerView.SCROLL_STATE_IDLE;
            }
            if (gestureTracking) {
                pinchDetector.onTouchEvent(ev);
                tapDetector.onTouchEvent(ev);
            }
            if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) gestureTracking = false;
        }
        return super.dispatchTouchEvent(ev);
    }

    private static boolean hitView(View v, MotionEvent ev) {
        if (v == null || v.getVisibility() != View.VISIBLE) return false;
        int[] loc = new int[2];
        v.getLocationOnScreen(loc);
        float x = ev.getRawX();
        float y = ev.getRawY();
        return x >= loc[0] && x < loc[0] + v.getWidth() && y >= loc[1] && y < loc[1] + v.getHeight();
    }

    /** يعرض قائمة "المزيد" كـ PopupWindow مخصّص (popup_pdf_more_menu) بنفس
     *  أنماط صفوف الإعدادات (Settings.Row) بدل قائمة Toolbar الافتراضية -
     *  انظر تعليق menu_pdf_viewer.xml. */
    private void showMoreMenu(View anchor) {
        View content = LayoutInflater.from(this).inflate(R.layout.popup_pdf_more_menu, null);

        TextView modeLabel = content.findViewById(R.id.txt_pdf_night_pages);
        if (modeLabel != null) modeLabel.setText("وضع الصفحة: " + modeName(pageMode) + " (اضغط للتغيير)");
        TextView bmLabel = content.findViewById(R.id.txt_pdf_bookmark);
        if (bmLabel != null && ratios.length > 0) {
            bmLabel.setText(bookmarks.contains(currentPageIndex())
                    ? "إزالة الإشارة المرجعية من هذه الصفحة" : "إضافة إشارة مرجعية لهذه الصفحة");
        }

        int widthPx = Ui.dp(this, 264);
        content.measure(View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int maxH = Math.round(getResources().getDisplayMetrics().heightPixels * 0.70f);
        int height = Math.min(content.getMeasuredHeight(), maxH);

        PopupWindow popup = new PopupWindow(content, widthPx, height, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popup.setOutsideTouchable(true);
        popup.setElevation(Ui.dp(this, 8));

        bindMoreMenuRow(content, popup, R.id.row_pdf_read_aloud, this::startReadAloud);
        bindMoreMenuRow(content, popup, R.id.row_pdf_search, this::showSearchBar);
        bindMoreMenuRow(content, popup, R.id.row_pdf_bookmark, this::toggleBookmark);
        bindMoreMenuRow(content, popup, R.id.row_pdf_bookmarks, this::showBookmarksDialog);
        bindMoreMenuRow(content, popup, R.id.row_pdf_translate_page, this::showTranslateLanguageDialog);
        bindMoreMenuRow(content, popup, R.id.row_pdf_translate_full, this::showFullTranslateLanguageDialog);
        bindMoreMenuRow(content, popup, R.id.row_pdf_save_copy, this::saveCurrentFileCopy);
        bindMoreMenuRow(content, popup, R.id.row_pdf_night_pages, this::cyclePageMode);
        bindMoreMenuRow(content, popup, R.id.row_pdf_goto, this::showGoToPageDialog);
        bindMoreMenuRow(content, popup, R.id.row_pdf_open_external, this::openExternally);
        bindMoreMenuRow(content, popup, R.id.row_pdf_pick_another, this::launchPicker);

        int xOff = anchor.getWidth() - widthPx;
        popup.showAsDropDown(anchor, xOff, Ui.dp(this, 4));
        // دخول ناعم للقائمة
        content.setAlpha(0f);
        content.setScaleX(0.94f);
        content.setScaleY(0.94f);
        content.setPivotX(0f);
        content.setPivotY(0f);
        content.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(160)
                .setInterpolator(new DecelerateInterpolator()).start();
    }

    private static String modeName(int mode) {
        if (mode == MODE_NIGHT) return "ليلي";
        if (mode == MODE_SEPIA) return "دافئ";
        return "عادي";
    }

    /** عادي ← ليلي ← دافئ (لون ورق مريح للعين) ثم يعود، ويُحفظ اختيار المستخدم. */
    private void cyclePageMode() {
        pageMode = (pageMode + 1) % 3;
        nightPagesUserOverride = true;
        prefs.edit().putInt("page_mode", pageMode).apply();
        refreshRenderedPages();
        Toast.makeText(this, "وضع الصفحة: " + modeName(pageMode), Toast.LENGTH_SHORT).show();
    }

    private void bindMoreMenuRow(View root, PopupWindow popup, int rowId, Runnable action) {
        View row = root.findViewById(rowId);
        if (row == null) return;
        row.setOnClickListener(v -> {
            popup.dismiss();
            action.run();
        });
    }

    private void openExternally() {
        if (currentFile == null || !currentFile.exists()) {
            Toast.makeText(this, "لا يوجد ملف مفتوح.", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".pdfshare", currentFile);
            Intent i = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/pdf")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "لا يوجد تطبيق آخر لفتح ملفات PDF.", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "تعذّر فتح الملف بتطبيق آخر.", Toast.LENGTH_SHORT).show();
        }
    }

    // ------------------------------------------------------------------ تحميل الملف

    private void importAndLoad(Uri uri) {
        showLoading("جارٍ تجهيز الملف...");
        loadExecutor.execute(() -> {
            try {
                String name = queryDisplayName(uri);
                File dir = new File(getCacheDir(), CACHE_DIR);
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
                // ننظّف النسخ المستوردة القديمة حتى لا تتراكم في الكاش
                File[] old = dir.listFiles((d, n) -> n.startsWith("imported_"));
                if (old != null) for (File f : old) //noinspection ResultOfMethodCallIgnored
                    f.delete();
                File dest = new File(dir, "imported_" + System.currentTimeMillis() + ".pdf");
                try (InputStream in = getContentResolver().openInputStream(uri);
                     OutputStream out = new FileOutputStream(dest)) {
                    if (in == null) throw new IOException("تعذّر قراءة الملف المختار.");
                    byte[] buf = new byte[16 * 1024];
                    int n;
                    while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                }
                runOnUiThread(() -> loadFile(dest, name));
            } catch (Exception e) {
                runOnUiThread(() -> showError("تعذّر فتح الملف", errorText(e)));
            }
        });
    }

    private void loadFile(File file, String title) {
        showLoading("جارٍ فتح الملف...");
        titleView.setText(title);
        subtitleView.setVisibility(View.GONE);
        loadExecutor.execute(() -> {
            ParcelFileDescriptor pfd = null;
            PdfRenderer r = null;
            try {
                pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
                r = new PdfRenderer(pfd);
                int n = r.getPageCount();
                if (n <= 0) throw new IOException("الملف لا يحتوي على أي صفحات.");
                // نسبة ارتفاع/عرض كل صفحة مسبقًا حتى لا تقفز المواضع أثناء الرسم
                float[] rt = new float[n];
                for (int i = 0; i < n; i++) {
                    PdfRenderer.Page p = r.openPage(i);
                    try {
                        rt[i] = p.getWidth() > 0 ? p.getHeight() / (float) p.getWidth() : 1.414f;
                    } finally {
                        p.close();
                    }
                }
                final PdfRenderer fr = r;
                final ParcelFileDescriptor fpfd = pfd;
                runOnUiThread(() -> onLoaded(fr, fpfd, rt, file));
            } catch (SecurityException e) {
                closeQuietly(r, pfd);
                runOnUiThread(() -> showError("الملف محمي بكلمة مرور",
                        "لا يمكن عرض ملفات PDF المحمية بكلمة مرور داخل التطبيق."));
            } catch (Throwable t) {
                closeQuietly(r, pfd);
                runOnUiThread(() -> showError("تعذّر عرض الملف",
                        "الملف تالف أو ليس PDF صالحًا.\n" + errorText(t)));
            }
        });
    }

    private void onLoaded(PdfRenderer r, ParcelFileDescriptor pfd, float[] rt, File file) {
        if (isFinishing() || isDestroyed()) {
            closeQuietly(r, pfd);
            return;
        }
        stopReading();
        releaseRenderer();
        synchronized (renderLock) {
            renderer = r;
            descriptor = pfd;
        }
        currentFile = file;
        resetSearchState();
        docKey = String.valueOf(titleView.getText()) + "|" + file.length();
        loadBookmarks();
        ratios = rt;
        zoomPercent = 100f;
        renderedZoomPercent = 100f;
        generation++;
        cache.evictAll();

        subtitleView.setText(BidiText.fix(rt.length == 1 ? "صفحة واحدة" : rt.length + " صفحة"));
        subtitleView.setVisibility(View.VISIBLE);
        loadingBox.setVisibility(View.GONE);
        emptyBox.setVisibility(View.GONE);
        hScroll.setVisibility(View.VISIBLE);
        hScroll.scrollTo(0, 0);
        layoutManager.scrollToPositionWithOffset(0, 0);
        // ننتظر اكتمال قياس الواجهة قبل حساب عرض الصفحات، ثم نكمل من آخر صفحة قُرئت
        final int resume = prefs.getInt("pg_" + docKey, 0);
        hScroll.post(this::applyZoom);
        hScroll.post(() -> {
            if (resume > 0 && resume < ratios.length) {
                layoutManager.scrollToPositionWithOffset(resume, 0);
                updateIndicator();
                Toast.makeText(this, "تم الاستئناف من الصفحة " + (resume + 1), Toast.LENGTH_SHORT).show();
            }
        });
        chromeVisible = true;
        applyChrome();
    }

    // ------------------------------------------------------------------ التكبير والرسم

    private void applyZoom() {
        if (isFinishing() || isDestroyed() || ratios.length == 0) return;
        int base = hScroll.getWidth();
        if (base <= 0) {
            hScroll.post(this::applyZoom);
            return;
        }
        int first = layoutManager.findFirstVisibleItemPosition();
        ViewGroup.LayoutParams lp = pages.getLayoutParams();
        lp.width = Math.round(base * zoomPercent / 100f);
        pages.setLayoutParams(lp);
        if (zoomPercent <= 100f) hScroll.scrollTo(0, 0);

        renderedZoomPercent = zoomPercent;
        generation++;
        adapter.notifyDataSetChanged();
        if (first > 0) layoutManager.scrollToPositionWithOffset(first, 0);
        updateIndicator();
    }

    /** يجمّع نسبة التكبير المستمرة في "دُرجة" صحيحة لاستخدامها كجزء من
     *  مفتاح ذاكرة تخزين الصفحات المؤقتة (cacheKey) - يمنع مفاتيح لا نهائية
     *  مختلفة عند أي تغيّر طفيف، مع بقاء دقة كافية (كل 5%) لإعادة رسم واضحة. */
    private int zoomBucket() {
        return Math.round(zoomPercent / 5f);
    }

    /**
     * عرض الصفحة بالبكسل = عرض المنطقة بعد التكبير - هوامش بطاقة الصفحة الفعلية.
     * إصلاح: كانت القيمة المطروحة 24dp فقط بينما بطاقة item_pdf_page.xml تستهلك
     * فعليًا 48dp (14dp هامش + 10dp حشوة من كل جهة) - الفرق (24dp) كان يخلي عرض
     * البتمَاب المرسوم أوسع من عرض الصورة المعروضة الحقيقي، فيتمدّد/يتشوّه ارتفاع
     * كل صفحة (scaleType="fitXY") بدل ما تناسق حجم الشاشة بشكل سليم.
     */
    private static final int PAGE_CARD_HORIZONTAL_CHROME_DP = 26;

    private int pageWidthPx() {
        int w = Math.round(hScroll.getWidth() * zoomPercent / 100f) - Ui.dp(this, PAGE_CARD_HORIZONTAL_CHROME_DP);
        return Math.max(1, w);
    }

    /** المفتاح يعتمد على عرض الرسم الفعلي (لا على درجة التكبير) فيبقى صحيحًا بعد تدوير الشاشة. */
    private static long cacheKey(int page, int widthPx) {
        return ((long) page << 32) | (widthPx & 0xFFFFFFFFL);
    }

    /** عرض التصدير الأقصى بالبكسل لخلفية كل صفحة في ملف الترجمة الناتج - أعلى
     *  من عرض شاشة العرض العادي لجودة قراءة أفضل، لكن مقيّد حتى لا يتضخّم
     *  حجم الملف الناتج مع الملفات الكبيرة (كل صفحة بتتضمّن كصورة). */
    private static final int TRANSLATE_EXPORT_WIDTH = 1400;

    /** يرسم صفحة أصلية بجودة تصدير (بدون تحسين/انعكاس الوضع الليلي، فهي
     *  خلفية داخل ملف PDF ناتج ثابت وليست عرضًا حيًا) - تُستخدم فقط أثناء
     *  بناء "ترجمة الملف بالكامل" حتى يحافظ الملف الناتج على تصميم كل صفحة
     *  أصلية (راجع تعليق TranslatedPdfBuilder). يرجّع null لو تعذّر الرسم
     *  (الملف اتقفل أو الصفحة غير موجودة) - النداء المستدعي بيكمل بدونها. */
    private Bitmap renderPageForExportBackground(int page) {
        synchronized (renderLock) {
            if (renderer == null || page < 0 || page >= renderer.getPageCount()) return null;
            PdfRenderer.Page p = null;
            try {
                p = renderer.openPage(page);
                int pw = p.getWidth();
                int ph = p.getHeight();
                if (pw <= 0 || ph <= 0) return null;
                int bw = Math.min(TRANSLATE_EXPORT_WIDTH, MAX_BITMAP_WIDTH);
                int bh = Math.max(1, Math.round(bw * (ph / (float) pw)));
                Bitmap bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
                bmp.eraseColor(Color.WHITE);
                p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                return bmp;
            } catch (OutOfMemoryError oom) {
                return null;
            } catch (Exception e) {
                return null;
            } finally {
                if (p != null) p.close();
            }
        }
    }

    /** أبعاد صفحة أصلية بوحدة نقطة PDF (72dpi) - نفس الوحدة اللي PdfDocument
     *  بيستخدمها لحجم الصفحة الناتجة، حتى تطابق أبعاد الصفحة الناتجة الأصل
     *  تمامًا بدل حجم A4 ثابت بغض النظر عن حجم/اتجاه الصفحة الأصلية الفعلي. */
    private float[] pagePointSize(int page) {
        synchronized (renderLock) {
            if (renderer == null || page < 0 || page >= renderer.getPageCount()) return null;
            PdfRenderer.Page p = null;
            try {
                p = renderer.openPage(page);
                return new float[]{p.getWidth(), p.getHeight()};
            } catch (Exception e) {
                return null;
            } finally {
                if (p != null) p.close();
            }
        }
    }

    private Bitmap renderPage(int page, int w, int h) {
        int bw = w;
        int bh = h;
        if (bw > MAX_BITMAP_WIDTH) {
            bh = Math.max(1, (int) ((long) bh * MAX_BITMAP_WIDTH / bw));
            bw = MAX_BITMAP_WIDTH;
        }
        synchronized (renderLock) {
            if (renderer == null || page < 0 || page >= renderer.getPageCount()) return null;
            PdfRenderer.Page p = null;
            try {
                p = renderer.openPage(page);
                Bitmap bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
                bmp.eraseColor(Color.WHITE); // صفحات PDF شفافة افتراضيًا
                p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                return enhancePage(bmp, pageMode);
            } catch (OutOfMemoryError oom) {
                cache.evictAll();
                return null;
            } catch (Exception e) {
                return null;
            } finally {
                if (p != null) p.close();
            }
        }
    }

    /**
     * تحسين تلقائي لجودة النص (تباين أوضح للحروف الرفيعة عند التصغير)، مع قلب الألوان
     * اختيارياً لعرض الصفحة بالوضع الليلي (خلفية داكنة ونص فاتح) بدل الورقة البيضاء الأصلية.
     */
    private static Bitmap enhancePage(Bitmap src, int mode) {
        Bitmap out = Bitmap.createBitmap(src.getWidth(), src.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        paint.setColorFilter(new ColorMatrixColorFilter(buildEnhanceMatrix(mode)));
        canvas.drawBitmap(src, 0, 0, paint);
        src.recycle();
        return out;
    }

    private static ColorMatrix buildEnhanceMatrix(int mode) {
        // تباين أعلى قليلاً وتغميق نقطة الأسود: يبرز حروف النص الرفيعة بعد تصغير الصفحة لعرض الشاشة.
        float c = 1.12f;
        float t = -18f * c;
        ColorMatrix matrix = new ColorMatrix(new float[]{
                c, 0, 0, 0, t,
                0, c, 0, 0, t,
                0, 0, c, 0, t,
                0, 0, 0, 1, 0
        });
        if (mode == MODE_SEPIA) {
            // ورق دافئ: تخفيف الأزرق والأخضر قليلًا فيصير الأبيض بلون كريمي مريح للعين
            matrix.postConcat(new ColorMatrix(new float[]{
                    0.97f, 0, 0, 0, 0,
                    0, 0.92f, 0, 0, 0,
                    0, 0, 0.78f, 0, 0,
                    0, 0, 0, 1, 0
            }));
        }
        if (mode == MODE_NIGHT) {
            ColorMatrix invert = new ColorMatrix(new float[]{
                    -1, 0, 0, 0, 255,
                    0, -1, 0, 0, 255,
                    0, 0, -1, 0, 255,
                    0, 0, 0, 1, 0
            });
            matrix.postConcat(invert);
        }
        return matrix;
    }

    // ------------------------------------------------------------------ مؤشر الصفحة والانتقال

    private boolean userScrolled = false;

    /** لو المستخدم سحب الملف لصفحات بعيدة عن الصفحة المقروءة، نبدأ القراءة من الصفحة الظاهرة. */
    private void reseekReadingToVisiblePage() {
        if (speaker == null || !speaker.isActive() || layoutManager == null) return;
        int first = layoutManager.findFirstVisibleItemPosition();
        int last = layoutManager.findLastVisibleItemPosition();
        int reading = speaker.getCurrentPage();
        if (first < 0 || last < 0 || reading < 0) return;
        if (reading >= first && reading <= last) return; // ما زالت الصفحة المقروءة على الشاشة
        int target = layoutManager.findFirstCompletelyVisibleItemPosition();
        if (target < 0) target = first;
        speaker.seekToPage(target);
    }

    private int currentPageIndex() {
        if (layoutManager == null) return 0;
        int pos = layoutManager.findFirstCompletelyVisibleItemPosition();
        if (pos < 0) pos = layoutManager.findFirstVisibleItemPosition();
        return Math.max(0, pos);
    }

    private void updateIndicator() {
        if (ratios.length == 0) return;
        int pos = layoutManager.findFirstCompletelyVisibleItemPosition();
        if (pos < 0) pos = layoutManager.findFirstVisibleItemPosition();
        if (pos < 0) return;
        String label = (pos + 1) + " / " + ratios.length;
        if (navLabel != null) navLabel.setText(BidiText.fix(label));
        if (navSeek != null && !seekDragging) {
            navSeek.setMax(Math.max(1, ratios.length - 1));
            navSeek.setProgress(pos);
        }
        uiHandler.removeCallbacks(savePositionRunnable);
        uiHandler.postDelayed(savePositionRunnable, 900);
        if (navBar != null && navBar.getVisibility() == View.VISIBLE) {
            // شريط التنقل يعرض الرقم، فلا داعي للكبسولة العائمة
            pageIndicator.animate().cancel();
            pageIndicator.setVisibility(View.GONE);
            uiHandler.removeCallbacks(hideIndicatorRunnable);
            return;
        }
        pageIndicator.setText(BidiText.fix(label));
        if (pageIndicator.getVisibility() != View.VISIBLE) {
            pageIndicator.setAlpha(0f);
            pageIndicator.setVisibility(View.VISIBLE);
        }
        pageIndicator.animate().cancel();
        pageIndicator.animate().alpha(1f).setDuration(120).start();
        uiHandler.removeCallbacks(hideIndicatorRunnable);
        uiHandler.postDelayed(hideIndicatorRunnable, INDICATOR_HIDE_DELAY_MS);
    }

    private void showGoToPageDialog() {
        final int total = ratios.length;
        if (total == 0) return;
        TextInputEditText input = new TextInputEditText(this);
        input.setBackgroundResource(R.drawable.bg_input_field);
        input.setPadding(Ui.dp(this, 16), Ui.dp(this, 14), Ui.dp(this, 16), Ui.dp(this, 14));
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setGravity(Gravity.START);
        input.setTextDirection(View.TEXT_DIRECTION_LTR);
        input.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        input.setTextSize(15.5f);
        input.setTextColor(getColor(R.color.pdf_text_primary));
        input.setHintTextColor(getColor(R.color.pdf_text_tertiary));
        input.setHint("1 - " + total);

        new ClaudeDialog(this)
                .setTitle("الانتقال إلى صفحة")
                .setView(input)
                .setPositiveButton("انتقال", (dialog, which) -> {
                    String s = input.getText() == null ? "" : input.getText().toString().trim();
                    try {
                        int p = Math.max(1, Math.min(total, Integer.parseInt(s)));
                        layoutManager.scrollToPositionWithOffset(p - 1, 0);
                        updateIndicator();
                        if (speaker != null && speaker.isActive()) speaker.seekToPage(p - 1);
                    } catch (NumberFormatException ignored) {
                    }
                })
                .setNegativeButton("إلغاء", null)
                .show();
    }

    // ------------------------------------------------------------------ ترجمة الصفحة (Google، مجانًا)

    /** يعرض اختيار اللغة الهدف، ثم يترجم الصفحة المرئية حاليًا إليها. */
    private void showTranslateLanguageDialog() {
        if (ratios.length == 0 || currentFile == null) {
            Toast.makeText(this, "افتح ملف PDF أولًا.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (translateInProgress) {
            Toast.makeText(this, "جارٍ ترجمة صفحة سابقة، يرجى الانتظار...", Toast.LENGTH_SHORT).show();
            return;
        }
        new ClaudeDialog(this)
                .setTitle("ترجمة الصفحة إلى")
                .setItems(TRANSLATE_LANG_LABELS, (dialog, which) ->
                        translateCurrentPage(TRANSLATE_LANG_CODES[which], TRANSLATE_LANG_LABELS[which]))
                .show();
    }

    private void translateCurrentPage(String targetLangCode, String targetLangLabel) {
        int pos = layoutManager.findFirstCompletelyVisibleItemPosition();
        if (pos < 0) pos = layoutManager.findFirstVisibleItemPosition();
        if (pos < 0) pos = 0;
        final int pageIndex = pos;
        final File file = currentFile;

        translateInProgress = true;
        Toast.makeText(this, "جارٍ استخراج نص الصفحة " + (pageIndex + 1) + "...", Toast.LENGTH_SHORT).show();

        textExecutor.execute(() -> {
            String text = extractPageText(file, pageIndex);
            if (text == null || text.trim().isEmpty()) {
                runOnUiThread(() -> {
                    translateInProgress = false;
                    Toast.makeText(this, "لا يوجد نص قابل للاستخراج في هذه الصفحة (قد تكون صورة ممسوحة ضوئيًا).",
                            Toast.LENGTH_LONG).show();
                });
                return;
            }
            GoogleTranslateClient.translateAsync(text, targetLangCode, (translated, error) -> {
                translateInProgress = false;
                if (isFinishing() || isDestroyed()) return;
                if (error != null || translated == null || translated.trim().isEmpty()) {
                    Toast.makeText(this, "تعذّر الاتصال بخدمة الترجمة، حاول مرة أخرى بعد قليل.",
                            Toast.LENGTH_LONG).show();
                    return;
                }
                showTranslationResult(pageIndex + 1, targetLangLabel, translated, "ar".equals(targetLangCode));
            });
        });
    }

    /** استخراج نص صفحة واحدة فعليًا (PdfBox) - وليس OCR، فيعتمد على وجود طبقة نص
     *  حقيقية بالملف (وهذا حال أغلب ملفات PDF المُصدَّرة من Word/برامج التصميم). */
    private String extractPageText(File file, int pageIndex) {
        try {
            if (!pdfBoxReadyForText) {
                com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(getApplicationContext());
                pdfBoxReadyForText = true;
            }
            try (com.tom_roush.pdfbox.pdmodel.PDDocument doc = com.tom_roush.pdfbox.pdmodel.PDDocument.load(file)) {
                com.tom_roush.pdfbox.text.PDFTextStripper stripper = new com.tom_roush.pdfbox.text.PDFTextStripper();
                stripper.setStartPage(pageIndex + 1);
                stripper.setEndPage(pageIndex + 1);
                return stripper.getText(doc);
            }
        } catch (Throwable t) {
            return null;
        }
    }

    private void showTranslationResult(int pageNumber, String targetLangLabel, String translatedText, boolean rtl) {
        if (isFinishing() || isDestroyed()) return;

        TextView tv = new TextView(this);
        tv.setText(translatedText);
        tv.setTextColor(getColor(R.color.pdf_text_primary));
        tv.setTextSize(15f);
        tv.setLineSpacing(Ui.dp(this, 4), 1f);
        tv.setTextIsSelectable(true);
        tv.setTextDirection(rtl ? View.TEXT_DIRECTION_RTL : View.TEXT_DIRECTION_LTR);
        tv.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);

        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        int maxHeight = (int) (getResources().getDisplayMetrics().heightPixels * 0.5f);
        scroll.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, maxHeight));
        scroll.addView(tv);

        new ClaudeDialog(this)
                .setTitle("ترجمة الصفحة " + pageNumber + " · " + targetLangLabel)
                .setView(scroll)
                .setPositiveButton("نسخ النص", (d, w) -> {
                    android.content.ClipboardManager cm =
                            (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    if (cm != null) {
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("pdf_translation", translatedText));
                        Toast.makeText(this, "تم نسخ النص المترجم.", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("إغلاق", null)
                .show();
    }

    // ------------------------------------------------------------------ ترجمة الملف بالكامل (PDF منسّق)

    /** يعرض اختيار اللغة الهدف، ثم يترجم كل صفحات الملف (وليس صفحة واحدة فقط)
     *  ويبني منها ملف PDF منسّق جاهز للعرض والحفظ. */
    private void showFullTranslateLanguageDialog() {
        if (ratios.length == 0 || currentFile == null) {
            Toast.makeText(this, "افتح ملف PDF أولًا.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (translateInProgress || fullTranslateInProgress) {
            Toast.makeText(this, "يوجد عملية ترجمة جارية بالفعل، يرجى الانتظار...", Toast.LENGTH_SHORT).show();
            return;
        }
        new ClaudeDialog(this)
                .setTitle("ترجمة الملف بالكامل إلى")
                .setItems(TRANSLATE_LANG_LABELS, (dialog, which) ->
                        startFullTranslation(TRANSLATE_LANG_CODES[which], TRANSLATE_LANG_LABELS[which]))
                .show();
    }

    private void startFullTranslation(String targetLangCode, String targetLangLabel) {
        final int total = ratios.length;
        final File sourceFile = currentFile;
        final String sourceTitle = titleView.getText() != null ? titleView.getText().toString() : "مستند PDF";

        fullTranslateInProgress = true;
        fullTranslateCancelled = false;

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);

        fullTranslateStatus = new TextView(this);
        fullTranslateStatus.setTextColor(getColor(R.color.pdf_text_primary));
        fullTranslateStatus.setTextSize(14f);
        fullTranslateStatus.setTextDirection(View.TEXT_DIRECTION_RTL);
        fullTranslateStatus.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        fullTranslateStatus.setText(BidiText.fix("جارٍ التحضير..."));
        box.addView(fullTranslateStatus, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        fullTranslateBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 6));
        barLp.topMargin = Ui.dp(this, 14);
        fullTranslateBar.setLayoutParams(barLp);
        fullTranslateBar.setMax(100);
        fullTranslateBar.setProgress(0);
        fullTranslateBar.setProgressDrawable(getDrawable(R.drawable.bg_upload_progress));
        box.addView(fullTranslateBar);

        fullTranslateDialog = new ClaudeDialog(this)
                .setTitle("ترجمة الملف بالكامل")
                .setView(box)
                .setNegativeButton("إلغاء", (d, w) -> fullTranslateCancelled = true)
                .create();
        fullTranslateDialog.setCancelable(false);
        fullTranslateDialog.show();

        textExecutor.execute(() -> {
            String base = sourceTitle.replaceAll("(?i)\\.pdf$", "").trim();
            boolean rtlOut = "ar".equals(targetLangCode);
            File outFile;
            // إصلاح مهم (طلب المستخدم + خطر تعطّل بالذاكرة): النسخة القديمة
            // كانت بتستخرج النص بس وتبني لوحة ترجمة منفصلة فوق الصفحة (أو
            // صفحة نص بديلة بالكامل في نسخة أقدم)، فتصميم الصفحة الأصلية
            // (صور، ألوان، تخطيط) يفضل زي ما هو لكن الترجمة نفسها بتظهر في
            // لوحة مجمّعة بدل مكان كل سطر أصلي بالظبط. دلوقتي: كل صفحة أصلية
            // بترتسم كخلفية (زي PdfRenderer في القارئ العادي) بأبعادها
            // الحقيقية، وكل سطر نص بيتستخرج بصندوق إحاطة (PdfLineExtractor)
            // ويتترجم مع الحفاظ على محاذاته سطرًا-بسطر (GoogleTranslateClient
            // .translateLinesAsync)، فـ TranslatedPdfBuilder يقدر يمسح كل سطر
            // أصلي ويرسم ترجمته في نفس مكانه بالضبط - استبدال حقيقي بدل لوحة
            // منفصلة. الصفحة بتتضاف فورًا (streaming) لملف الترجمة الناتج
            // بدل ما تتجمّع كل صور الصفحات في الذاكرة أولًا - كان ده هيسبب
            // تعطّل (OutOfMemoryError) على ملفات كبيرة زي كتب العلاج
            // الطبيعي (100+ صفحة). صفحة اتفشل استخراج/ترجمة نصها هتفضل
            // خلفيتها زي ما هي بدون أي إضافة، بدل ما توقف ترجمة باقي الملف.
            try (TranslatedPdfBuilder builder = new TranslatedPdfBuilder(base, targetLangLabel, total, rtlOut)) {
                for (int i = 0; i < total; i++) {
                    if (fullTranslateCancelled) {
                        finishFullTranslateCancelled();
                        return;
                    }
                    int pageNum = i + 1;
                    updateFullTranslateProgress(pageNum, total, "جارٍ تجهيز الصفحة " + pageNum + " من " + total);
                    Bitmap background = renderPageForExportBackground(i);
                    float[] pointSize = pagePointSize(i);
                    float pageWidthPt = pointSize != null ? pointSize[0] : 0f;
                    float pageHeightPt = pointSize != null ? pointSize[1] : 0f;

                    updateFullTranslateProgress(pageNum, total, "جارٍ استخراج نص الصفحة " + pageNum + " من " + total);
                    List<PdfLineExtractor.Line> lines = PdfLineExtractor.extractLines(getApplicationContext(), sourceFile, i);
                    List<String> translatedLines = null;
                    if (!lines.isEmpty()) {
                        if (fullTranslateCancelled) {
                            finishFullTranslateCancelled();
                            return;
                        }
                        updateFullTranslateProgress(pageNum, total, "جارٍ ترجمة الصفحة " + pageNum + " من " + total);
                        List<String> originals = new ArrayList<>(lines.size());
                        for (PdfLineExtractor.Line ln : lines) originals.add(ln.text);
                        // لو فشلت ترجمة هذه الصفحة تحديدًا (بترجع null) نسيبها
                        // null هنا - يعني addPage هتسيب خلفية هذه الصفحة "متل
                        // ما هي" بدون أي إضافة.
                        translatedLines = translateLinesBlockingSync(originals, targetLangCode);
                    }
                    builder.addPage(pageNum, background, pageWidthPt, pageHeightPt, lines, translatedLines);
                }
                if (fullTranslateCancelled) {
                    finishFullTranslateCancelled();
                    return;
                }

                updateFullTranslateProgress(total, total, "جارٍ إنشاء ملف PDF المترجم");
                File dir = new File(getCacheDir(), CACHE_DIR);
                //noinspection ResultOfMethodCallIgnored
                dir.mkdirs();
                outFile = new File(dir, "translated_" + System.currentTimeMillis() + ".pdf");
                builder.writeTo(outFile);
            } catch (Exception e) {
                runOnUiThread(() -> {
                    fullTranslateInProgress = false;
                    if (isFinishing() || isDestroyed()) return;
                    if (fullTranslateDialog != null) fullTranslateDialog.dismiss();
                    Toast.makeText(this, "تعذّر إنشاء ملف الترجمة: " + errorText(e), Toast.LENGTH_LONG).show();
                });
                return;
            }
            final File finalOutFile = outFile;
            final String finalBase = base;
            runOnUiThread(() -> {
                fullTranslateInProgress = false;
                if (isFinishing() || isDestroyed()) return;
                if (fullTranslateDialog != null) fullTranslateDialog.dismiss();
                openTranslatedPdf(finalOutFile, "ترجمة - " + finalBase);
            });
        });
    }

    private void updateFullTranslateProgress(int current, int total, String message) {
        int pct = total > 0 ? Math.min(100, current * 100 / total) : 0;
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            if (fullTranslateStatus != null) fullTranslateStatus.setText(BidiText.fix(message + " · " + pct + "%"));
            if (fullTranslateBar != null) fullTranslateBar.setProgress(pct);
        });
    }

    private void finishFullTranslateCancelled() {
        runOnUiThread(() -> {
            fullTranslateInProgress = false;
            if (isFinishing() || isDestroyed()) return;
            if (fullTranslateDialog != null) fullTranslateDialog.dismiss();
            Toast.makeText(this, "تم إلغاء ترجمة الملف.", Toast.LENGTH_SHORT).show();
        });
    }

    /** نسخة متزامنة (تحجب خيط الاستدعاء فقط، وليس الخيط الرئيسي) من GoogleTranslateClient
     *  حتى يمكن ترجمة الصفحات الواحدة تلو الأخرى داخل حلقة، مع الاستفادة من نفس
     *  آليات التسلسل/الفاصل الزمني/إعادة المحاولة الموجودة أصلًا في العميل. */
    private String translateBlockingSync(String text, String targetLangCode) {
        final String[] holder = {null};
        CountDownLatch latch = new CountDownLatch(1);
        GoogleTranslateClient.translateAsync(text, targetLangCode, (translated, error) -> {
            holder[0] = (error == null) ? translated : null;
            latch.countDown();
        });
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return holder[0];
    }

    /** نفس فكرة translateBlockingSync لكن لقائمة أسطر مع الحفاظ على محاذاتها
     *  (GoogleTranslateClient.translateLinesAsync) - تُستخدم في بناء ملف
     *  الترجمة الكامل حتى يُستبدل كل سطر بترجمته في مكانه بالضبط. */
    private List<String> translateLinesBlockingSync(List<String> lines, String targetLangCode) {
        final List<String>[] holder = new List[]{null};
        CountDownLatch latch = new CountDownLatch(1);
        GoogleTranslateClient.translateLinesAsync(lines, targetLangCode, (translated, error) -> {
            holder[0] = (error == null) ? translated : null;
            latch.countDown();
        });
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return holder[0];
    }

    /** يفتح ملف الترجمة الناتج مباشرة في نافذة قارئ جديدة (فوق الملف الأصلي)
     *  حتى يظهر للمستخدم فور انتهاء الترجمة، ومنها يمكنه "حفظ نسخة على الجهاز". */
    private void openTranslatedPdf(File file, String title) {
        Intent i = new Intent(this, PdfViewerActivity.class)
                .putExtra(EXTRA_PATH, file.getAbsolutePath())
                .putExtra(EXTRA_TITLE, title);
        startActivity(i);
        overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left);
    }

    // ------------------------------------------------------------------ حفظ/تنزيل نسخة على الجهاز

    /** يفتح منتقي حفظ النظام (Storage Access Framework) ليختار المستخدم مكان
     *  واسم الحفظ بنفسه - يعمل مع أي PDF مفتوح حاليًا، الأصلي أو ناتج الترجمة. */
    private void saveCurrentFileCopy() {
        if (currentFile == null || !currentFile.exists()) {
            Toast.makeText(this, "لا يوجد ملف مفتوح لحفظه.", Toast.LENGTH_SHORT).show();
            return;
        }
        pendingSaveSource = currentFile;
        CharSequence titleText = titleView.getText();
        String suggested = titleText != null && titleText.length() > 0 ? titleText.toString() : currentFile.getName();
        if (!suggested.toLowerCase(Locale.ROOT).endsWith(".pdf")) suggested = suggested + ".pdf";
        try {
            createDocumentLauncher.launch(suggested);
        } catch (Exception e) {
            pendingSaveSource = null;
            Toast.makeText(this, "تعذّر فتح نافذة الحفظ.", Toast.LENGTH_SHORT).show();
        }
    }

    private void onSaveDestinationChosen(Uri destination) {
        if (destination == null || pendingSaveSource == null) return;
        final File src = pendingSaveSource;
        pendingSaveSource = null;
        loadExecutor.execute(() -> {
            try (InputStream in = new FileInputStream(src);
                 OutputStream out = getContentResolver().openOutputStream(destination)) {
                if (out == null) throw new IOException("تعذّر فتح الوجهة للكتابة.");
                byte[] buf = new byte[16 * 1024];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                runOnUiThread(() -> Toast.makeText(this, "تم حفظ الملف بنجاح.", Toast.LENGTH_SHORT).show());
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "تعذّر حفظ الملف: " + errorText(e), Toast.LENGTH_LONG).show());
            }
        });
    }

    // ------------------------------------------------------------------ القراءة الصوتية (TTS مجاني)

    /**
     * قراءة الملف بصوت عالٍ (مجانًا): صوت عصبي أونلاين افتراضيًا، مع رجوع تلقائي لمحرك النطق
     * المدمج في أندرويد (بدون إنترنت) - التفاصيل في PdfSpeaker / EdgeTtsClient / PdfSpeechText. أثناء القراءة تُظلَّل الجملة الحالية والكلمة
     * المنطوقة فوق الصفحة (PdfHighlightView)، وتنقلب الصفحات تلقائيًا.
     */
    private static final float[] TTS_SPEEDS = {0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f};
    private static final String[] TTS_LANG_LABELS = {"العربية", "English", "Français", "Türkçe"};
    private static final String[] TTS_LANG_CODES = {"ar", "en", "fr", "tr"};

    private PdfSpeaker speaker;
    private View ttsBar;
    private TextView ttsStatus;
    private TextView ttsSpeed;
    private ImageButton ttsPlay;
    private VoiceWaveView ttsWave;
    private int speakingPage = -1;
    private List<RectF> speakingSentence = new ArrayList<>();
    private RectF speakingWord;
    private Dialog voiceDialog;
    private LetterDictionaryDialog letterDialog;

    private void bindTtsBar() {
        ttsBar = findViewById(R.id.tts_bar);
        ttsStatus = findViewById(R.id.tts_status);
        ttsSpeed = findViewById(R.id.tts_speed);
        ttsPlay = findViewById(R.id.tts_play);
        ttsWave = findViewById(R.id.tts_wave);
        // الموجة تقرأ مستوى الصوت الحقيقي من القارئ في كل إطار (الصوت العصبي: من غلاف الصوت، الجهاز: تقديري)
        ttsWave.setLevelSource(() -> speaker != null ? speaker.getLevel() : 0f);
        // ارتفاع المشغّل يتغيّر بتغيّر الخط/الشاشة: نُبقي هوامش الصفحات ومؤشر الصفحة مطابقة له
        ttsBar.addOnLayoutChangeListener((v, left, top, right, bottom, oldL, oldT, oldR, oldB) -> {
            if (ttsBar.getVisibility() == View.VISIBLE && (bottom - top) != (oldB - oldT)) applyTtsInsets(true);
        });
        ttsPlay.setOnClickListener(v -> {
            if (speaker != null) speaker.togglePlayPause();
        });
        findViewById(R.id.tts_prev).setOnClickListener(v -> {
            if (speaker != null) speaker.previousPage();
        });
        findViewById(R.id.tts_next).setOnClickListener(v -> {
            if (speaker != null) speaker.nextPage();
        });
        findViewById(R.id.tts_close).setOnClickListener(v -> stopReading());
        ttsSpeed.setOnClickListener(v -> cycleSpeed());
        findViewById(R.id.tts_settings).setOnClickListener(v -> showVoiceSettings());
    }

    private void startReadAloud() {
        if (ratios.length == 0 || currentFile == null) {
            Toast.makeText(this, "افتح ملف PDF أولًا.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (speaker == null) speaker = new PdfSpeaker(this, ttsListener);
        int pos = layoutManager.findFirstCompletelyVisibleItemPosition();
        if (pos < 0) pos = layoutManager.findFirstVisibleItemPosition();
        if (pos < 0) pos = 0;
        showTtsBar(true);
        ttsWave.setPaused(false);
        ttsStatus.setText(BidiText.fix("جارٍ تجهيز القراءة..."));
        updateSpeedLabel();
        speaker.play(currentFile, pos);
    }

    private void stopReading() {
        if (speaker != null) speaker.stop();
        showTtsBar(false);
        clearSpeakingHighlight();
    }

    private void showTtsBar(boolean show) {
        if (ttsBar == null) return;
        final boolean was = ttsBar.getVisibility() == View.VISIBLE;
        ttsBar.animate().cancel();
        ttsBar.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show && !was) {
            // دخول ناعم: ينزلق المشغّل من الأسفل مع ظهور تدريجي
            ttsBar.setAlpha(0f);
            ttsBar.setTranslationY(Ui.dp(this, 28));
            ttsBar.animate().alpha(1f).translationY(0f).setDuration(260)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
        } else {
            ttsBar.setAlpha(1f);
            ttsBar.setTranslationY(0f);
        }
        applyTtsInsets(show);
        applyChrome();
        if (show) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    /** يترك مساحة أسفل الصفحات ومؤشر الصفحة بحسب ارتفاع المشغّل الفعلي. */
    private void applyTtsInsets(boolean show) {
        if (pages == null || ttsBar == null) return;
        int barH = 0;
        if (show) {
            int h = ttsBar.getHeight();
            barH = (h > 0 ? h : Ui.dp(this, 148)) + Ui.dp(this, 14);
        }
        pages.setPadding(pages.getPaddingLeft(), pages.getPaddingTop(), pages.getPaddingRight(),
                Ui.dp(this, show ? 28 : 92) + barH);
        pageIndicator.setTranslationY(show ? -(barH + Ui.dp(this, 4)) : 0f);
    }

    /** سطر حالة المشغّل: (جارٍ القراءة | متوقف مؤقتًا | جارٍ التحضير) + رقم الصفحة. */
    private void updateTtsStatus(int page) {
        if (ttsStatus == null) return;
        PdfSpeaker.State st = speaker != null ? speaker.getState() : PdfSpeaker.State.LOADING;
        String label;
        if (st == PdfSpeaker.State.PAUSED) label = "متوقف مؤقتًا";
        else if (st == PdfSpeaker.State.LOADING) label = "جارٍ التحضير";
        else label = "جارٍ القراءة";
        String text = page >= 0 ? label + " · الصفحة " + (page + 1) + " / " + ratios.length : label + "...";
        ttsStatus.setText(BidiText.fix(text));
    }

    private void cycleSpeed() {
        if (speaker == null) return;
        float cur = speaker.getRate();
        float next = TTS_SPEEDS[0];
        for (float sp : TTS_SPEEDS) {
            if (sp > cur + 0.01f) {
                next = sp;
                break;
            }
        }
        speaker.setRate(next);
        updateSpeedLabel();
    }

    private void updateSpeedLabel() {
        float r = speaker != null ? speaker.getRate() : 1f;
        String t = String.format(Locale.US, "%.2f", r).replaceAll("0+$", "").replaceAll("\\.$", "");
        ttsSpeed.setText(t + "x");
    }

    private final PdfSpeaker.Listener ttsListener = new PdfSpeaker.Listener() {
        @Override
        public void onStateChanged(PdfSpeaker.State state) {
            if (isFinishing() || isDestroyed()) return;
            switch (state) {
                case IDLE:
                    showTtsBar(false);
                    clearSpeakingHighlight();
                    break;
                case PAUSED:
                    ttsPlay.setImageResource(R.drawable.ic_tts_play);
                    ttsWave.setPaused(true);
                    updateTtsStatus(speakingPage >= 0 ? speakingPage : (speaker != null ? speaker.getCurrentPage() : -1));
                    getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    break;
                default:
                    ttsPlay.setImageResource(R.drawable.ic_tts_pause);
                    ttsWave.setPaused(false);
                    updateTtsStatus(speakingPage >= 0 ? speakingPage : (speaker != null ? speaker.getCurrentPage() : -1));
                    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    break;
            }
        }

        @Override
        public void onPageStarted(int page, PdfSpeechText.PageText text) {
            if (isFinishing() || isDestroyed()) return;
            clearSpeakingHighlight();
            speakingPage = page;
            layoutManager.scrollToPositionWithOffset(page, 0);
            updateIndicator();
            updateTtsStatus(page);
        }

        @Override
        public void onSpeaking(int page, PdfSpeechText.PageText text, int chunkIndex, int wordIndex) {
            if (isFinishing() || isDestroyed()) return;
            showSpeaking(page, text, chunkIndex, wordIndex);
        }

        @Override
        public void onFinished() {
            if (isFinishing() || isDestroyed()) return;
            Toast.makeText(PdfViewerActivity.this, "انتهت القراءة.", Toast.LENGTH_SHORT).show();
        }

        @Override
        public void onStatus(String message) {
            if (isFinishing() || isDestroyed() || ttsStatus == null) return;
            ttsStatus.setText(BidiText.fix(message));
        }

        @Override
        public void onError(String message) {
            if (isFinishing() || isDestroyed()) return;
            if (message != null && message.length() > 110) {
                showFullError(message); // الرسالة الطويلة (تشخيص الصوت العصبي) لا تتسع في Toast: نافذة كاملة قابلة للنسخ
            } else {
                Toast.makeText(PdfViewerActivity.this, message, Toast.LENGTH_LONG).show();
            }
        }

        @Override
        public void onVoiceMissing(String lang) {
            if (isFinishing() || isDestroyed()) return;
            offerInstallVoice(lang, true);
        }

        @Override
        public void onEngineUnavailable() {
            if (isFinishing() || isDestroyed()) return;
            showTtsBar(false);
            showEngineUnavailableDialog();
        }
    };

    private void showFullError(final String message) {
        try {
            if (voiceDialog != null && voiceDialog.isShowing()) voiceDialog.dismiss();
        } catch (Throwable ignored) {
        }
        voiceDialog = new ClaudeDialog(this)
                .setTitle("تفاصيل خطأ الصوت العصبي")
                .setMessage(message)
                .setPositiveButton("نسخ الخطأ", (d, w) -> {
                    try {
                        android.content.ClipboardManager cm =
                                (android.content.ClipboardManager) getSystemService(android.content.Context.CLIPBOARD_SERVICE);
                        if (cm != null) {
                            cm.setPrimaryClip(android.content.ClipData.newPlainText("tts_error", message));
                            Toast.makeText(this, "تم نسخ الخطأ.", Toast.LENGTH_SHORT).show();
                        }
                    } catch (Throwable ignored) {
                    }
                })
                .setNegativeButton("إغلاق", null)
                .show();
    }

    /** يبني تظليل الجملة (مستطيل لكل سطر) والكلمة الحالية ويدفعهما لبطاقة الصفحة ثم يتابع بالتمرير. */
    private void showSpeaking(int page, PdfSpeechText.PageText t, int chunkIdx, int wordIdx) {
        List<RectF> sentence = new ArrayList<>();
        if (chunkIdx >= 0 && chunkIdx < t.chunks.size()) {
            PdfSpeechText.Chunk c = t.chunks.get(chunkIdx);
            RectF cur = null;
            int curLine = -1;
            // المقطع قد يكون الصفحة كلها: نظلّل الجملة الجارية فقط (بحسب الكلمة المنطوقة)
            int anchor = (wordIdx >= c.firstWord && wordIdx <= c.lastWord && wordIdx < t.words.size())
                    ? wordIdx : c.firstWord;
            int sentNo = anchor < t.words.size() ? t.words.get(anchor).sent : -1;
            for (int i = c.firstWord; i <= c.lastWord && i < t.words.size(); i++) {
                PdfSpeechText.Word w = t.words.get(i);
                if (w.sent != sentNo) continue;
                if (cur == null || w.line != curLine) {
                    if (cur != null) sentence.add(cur);
                    cur = new RectF(w.box);
                    curLine = w.line;
                } else {
                    cur.union(w.box);
                }
            }
            if (cur != null) sentence.add(cur);
        }
        RectF word = (wordIdx >= 0 && wordIdx < t.words.size()) ? new RectF(t.words.get(wordIdx).box) : null;
        speakingPage = page;
        speakingSentence = sentence;
        speakingWord = word;

        RecyclerView.ViewHolder vh = pages.findViewHolderForAdapterPosition(page);
        if (vh instanceof PageAdapter.PageHolder) {
            PageAdapter.PageHolder ph = (PageAdapter.PageHolder) vh;
            ph.highlight.setHighlight(sentence, word);
            followSpeaker(ph, word);
        } else {
            layoutManager.scrollToPositionWithOffset(page, 0);
        }
    }

    /** يبقي الكلمة المنطوقة ظاهرة فوق شريط التحكم بدون تدخّل أثناء سحب المستخدم للصفحة. */
    private void followSpeaker(PageAdapter.PageHolder ph, RectF word) {
        if (word == null || pages.getScrollState() != RecyclerView.SCROLL_STATE_IDLE) return;
        int viewH = pages.getHeight();
        if (viewH <= 0 || ph.image.getHeight() <= 0) return;
        float y = ph.itemView.getTop() + ph.image.getTop() + word.centerY() * ph.image.getHeight();
        int barH = ttsBar.getVisibility() == View.VISIBLE ? ttsBar.getHeight() + Ui.dp(this, 14) : 0;
        float topLimit = viewH * 0.10f;
        float bottomLimit = viewH - barH - Ui.dp(this, 36);
        if (y < topLimit || y > bottomLimit) {
            pages.smoothScrollBy(0, Math.round(y - viewH * 0.28f));
        }
    }

    private void clearSpeakingHighlight() {
        speakingPage = -1;
        speakingSentence = new ArrayList<>();
        speakingWord = null;
        if (pages == null) return;
        for (int i = 0; i < pages.getChildCount(); i++) {
            RecyclerView.ViewHolder vh = pages.getChildViewHolder(pages.getChildAt(i));
            if (vh instanceof PageAdapter.PageHolder) ((PageAdapter.PageHolder) vh).highlight.clearHighlight();
        }
    }

    // ---- إعدادات الصوت

    // ---- مؤقت النوم + تجربة النطق

    private static final int[] SLEEP_MINUTES = {0, 15, 30, 45, 60};
    private int sleepIdx = 0;
    private long sleepEndAt = 0L;
    private final Runnable sleepStopRunnable = () -> {
        sleepIdx = 0;
        sleepEndAt = 0L;
        if (speaker != null && speaker.getState() != PdfSpeaker.State.IDLE) {
            stopReading();
            Toast.makeText(this, "انتهى مؤقت النوم - تم إيقاف القراءة.", Toast.LENGTH_LONG).show();
        }
    };

    private String sleepLabel() {
        if (SLEEP_MINUTES[sleepIdx] == 0) return "متوقف";
        long left = Math.max(0L, sleepEndAt - android.os.SystemClock.uptimeMillis());
        int minLeft = (int) Math.max(1L, (left + 59_999L) / 60_000L);
        return "بعد " + SLEEP_MINUTES[sleepIdx] + " دقيقة (متبقي نحو " + minLeft + " د)";
    }

    private void cycleSleepTimer() {
        sleepIdx = (sleepIdx + 1) % SLEEP_MINUTES.length;
        uiHandler.removeCallbacks(sleepStopRunnable);
        if (SLEEP_MINUTES[sleepIdx] == 0) {
            sleepEndAt = 0L;
        } else {
            long ms = SLEEP_MINUTES[sleepIdx] * 60_000L;
            sleepEndAt = android.os.SystemClock.uptimeMillis() + ms;
            uiHandler.postDelayed(sleepStopRunnable, ms);
        }
    }

    /** جملة تجريبية فيها ة/ه في مواضع مختلفة (داخل الجملة، عند الوقف، ضمير متصل) لسماع الفرق. */
    private static final String VOICE_SAMPLE =
            "العضلة القوية تحمي المفصل، وله وظيفة مهمة. هذه رقبة الطفل ورقبته سليمة. أ) الإحماء ب) التمرين ج) الإطالة.";

    private void previewVoice() {
        Toast.makeText(this, "جارٍ تجهيز التجربة...", Toast.LENGTH_SHORT).show();
        speaker.previewSample(VOICE_SAMPLE, new PdfSpeaker.PreviewListener() {
            @Override
            public void onPrepared(String spokenText) {
                if (isFinishing() || isDestroyed()) return;
                voiceDialog = new ClaudeDialog(PdfViewerActivity.this)
                        .setTitle("تجربة النطق")
                        .setMessage("النص الأصلي:\n" + VOICE_SAMPLE
                                + "\n\nما يُرسل للمحرك (بعد التشكيل وضبط ة/ه):\n" + spokenText)
                        .setPositiveButton("تمام", null)
                        .show();
            }

            @Override
            public void onFailed(String message) {
                if (!isFinishing() && !isDestroyed()) Toast.makeText(PdfViewerActivity.this, message, Toast.LENGTH_LONG).show();
            }
        });
    }

    // ---- إعدادات الصوت

    private void showVoiceSettings() {
        if (speaker == null) return;
        if (voiceDialog != null && voiceDialog.isShowing()) voiceDialog.dismiss();

        final LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        final Runnable[] rebuild = new Runnable[1];
        rebuild[0] = () -> buildVoiceSettingsContent(root, rebuild[0]);
        rebuild[0].run();

        voiceDialog = new ClaudeDialog(this)
                .setTitle("إعدادات القراءة الصوتية")
                .setView(root)
                .setPositiveButton("تم", null)
                .show();
    }

    /** يبني محتوى مربع إعدادات الصوت: أقسام مرتبة بنفس أسلوب صفوف الإعدادات
     *  (عنوان + وصف + قيمة/مفتاح) بدل قائمة نصية طويلة، ويُحدَّث في مكانه بدون إغلاق. */
    private void buildVoiceSettingsContent(LinearLayout root, Runnable refresh) {
        root.removeAllViews();
        final boolean cloud = speaker.isCloudEngine();

        // ---- المحرك والأصوات
        LinearLayout g1 = voiceGroup(root, "المحرك والأصوات");
        addVoiceRow(g1, "محرك القراءة",
                cloud ? "صوت عصبي أونلاين مجاني" : "صوت الجهاز يعمل بدون إنترنت",
                cloud ? "عصبي" : "الجهاز", false, () -> {
                    speaker.setCloudEngine(!cloud);
                    Toast.makeText(this, cloud ? "تم التحويل لصوت الجهاز." : "تم التحويل للصوت العصبي الأونلاين.",
                            Toast.LENGTH_SHORT).show();
                    refresh.run();
                });
        for (int i = 0; i < TTS_LANG_LABELS.length; i++) {
            final int idx = i;
            addVoiceRow(g1, "صوت " + TTS_LANG_LABELS[i], null, null, true, () -> {
                if (voiceDialog != null) voiceDialog.dismiss();
                showVoicePicker(idx);
            });
        }

        // ---- التحكم بالصوت المحلي (محرك الجهاز - يعمل بدون إنترنت)
        LinearLayout gLocal = voiceGroup(root, "الصوت المحلي (بدون إنترنت)");
        addVoiceRow(gLocal, "محرك النطق المحلي", "المحرك الذي يقرأ به الجهاز عند عدم استخدام العصبي",
                localEngineLabel(), true, () -> {
                    if (voiceDialog != null) voiceDialog.dismiss();
                    showDeviceEnginePicker();
                });
        addVoiceSwitch(gLocal, "أصوات بلا إنترنت فقط", "يتجاهل أصوات المحرك التي تحتاج اتصالًا",
                speaker.isDeviceOfflineOnly(), () -> {
                    speaker.setDeviceOfflineOnly(!speaker.isDeviceOfflineOnly());
                    refresh.run();
                });
        addVoiceRow(gLocal, "سرعة صوت الجهاز", "تلقائي: يطابق إيقاع العصبي بعد أن يتعلّمه. أو معايرة ثابتة",
                speaker.getDeviceSpeedLabel(), false, () -> { speaker.cycleDeviceSpeed(); refresh.run(); });
        addVoiceSwitch(gLocal, "التعلّم الذاتي للنموذج المحلي", "يتعلّم إيقاع العصبي وصحة أصوات الجهاز ويحسّن اختيارها",
                speaker.isVoiceLearning(), () -> {
                    speaker.setVoiceLearning(!speaker.isVoiceLearning());
                    refresh.run();
                });
        addVoiceRow(gLocal, "ما تعلّمه النموذج", "إحصاءات التعلّم وإعادة التصفير", null, true, () -> {
            if (voiceDialog != null) voiceDialog.dismiss();
            voiceDialog = new ClaudeDialog(this)
                    .setTitle("النموذج الصوتي المحلي")
                    .setMessage(speaker.voiceModelStats()
                            + "\n\nيتحسّن النموذج تلقائيًا كلما قرأت: يقيس سرعة الصوت العصبي وصوت الجهاز فيطابق إيقاعهما عند الرجوع بينهما، ويقدّم الأصوات الأوثق.")
                    .setPositiveButton("تم", null)
                    .setNegativeButton("إعادة التعلّم من الصفر", (d, w) -> {
                        speaker.resetVoiceModel();
                        Toast.makeText(this, "تم تصفير النموذج.", Toast.LENGTH_SHORT).show();
                    })
                    .show();
        });
        addVoiceSwitch(gLocal, "الرجوع التلقائي لصوت الجهاز", "عند انقطاع الإنترنت أو تعطل الصوت العصبي",
                speaker.isDeviceFallback(), () -> {
                    speaker.setDeviceFallback(!speaker.isDeviceFallback());
                    refresh.run();
                });

        // ---- جودة الصوت
        LinearLayout g2 = voiceGroup(root, "جودة الصوت");
        addVoiceRow(g2, "صفاء الصوت", null, speaker.getEqLabel(), false, () -> { speaker.cycleEq(); refresh.run(); });
        addVoiceRow(g2, "تعزيز مستوى الصوت", null, speaker.getGainLabel(), false, () -> { speaker.cycleGain(); refresh.run(); });
        addVoiceRow(g2, "طبقة الصوت", null, speaker.getPitchLabel(), false, () -> { speaker.cyclePitch(); refresh.run(); });
        addVoiceRow(g2, "أسلوب النطق", null, speaker.getProfileLabel(), false, () -> { speaker.cycleProfile(); refresh.run(); });

        // ---- النطق العربي
        LinearLayout g3 = voiceGroup(root, "النطق العربي");
        addVoiceRow(g3, "نطق التاء المربوطة (ة)", null, speaker.getTaaLabel(), false, () -> { speaker.cycleTaaMode(); refresh.run(); });
        addVoiceSwitch(g3, "تصحيح إملاء ة/ه تلقائيًا", "الحركه ← الحركة", speaker.isTaaFix(),
                () -> { speaker.setTaaFix(!speaker.isTaaFix()); refresh.run(); });
        addVoiceSwitch(g3, "تشكيل ذكي للعربي", "شدّة وحركات وتنوين ومصطلحات", speaker.isArabicAssist(),
                () -> { speaker.setArabicAssist(!speaker.isArabicAssist()); refresh.run(); });
        addVoiceSwitch(g3, "قراءة بلا إعراب", "تسكين أواخر الكلمات (تجريبي)", speaker.isNoIrab(),
                () -> { speaker.setNoIrab(!speaker.isNoIrab()); refresh.run(); });
        addVoiceSwitch(g3, "نطق الحروف المنفردة باسمها", null, speaker.isLetterNames(),
                () -> { speaker.setLetterNames(!speaker.isLetterNames()); refresh.run(); });

        // ---- الكلمات الأجنبية والاختصارات
        LinearLayout g4 = voiceGroup(root, "الكلمات الأجنبية والاختصارات");
        addVoiceSwitch(g4, "تبديل الصوت للكلمات الأجنبية", "داخل الجملة الواحدة", speaker.isMixedVoices(),
                () -> { speaker.setMixedVoices(!speaker.isMixedVoices()); refresh.run(); });
        addVoiceSwitch(g4, "نطق الاختصارات حرفًا حرفًا", "EMG ، MRI ...", speaker.isSpellAcronyms(),
                () -> { speaker.setSpellAcronyms(!speaker.isSpellAcronyms()); refresh.run(); });

        // ---- الملفات الممسوحة ضوئيًا
        LinearLayout gOcr = voiceGroup(root, "الملفات الممسوحة ضوئيًا");
        addVoiceRow(gOcr, "التعرّف على النص (OCR)",
                "تلقائي: للصفحات الصور. دائمًا: يتجاهل النص المدمج. يُنزَّل النموذج مرة واحدة",
                PdfOcr.modeLabel(this), false, () -> { PdfOcr.cycleMode(this); refresh.run(); });

        // ---- أدوات
        LinearLayout g5 = voiceGroup(root, "أدوات");
        addVoiceRow(g5, "تجربة النطق", "جملة فيها ة وه", null, true, () -> {
            if (voiceDialog != null) voiceDialog.dismiss();
            previewVoice();
        });
        addVoiceRow(g5, "قاموس النطق الخاص", "تصحيح كلمات بعينها", null, true, () -> {
            if (voiceDialog != null) voiceDialog.dismiss();
            showLexiconEditor();
        });
        addVoiceRow(g5, "قاموس الحروف ونطقها", "المخارج والصفات والحركات", null, true, () -> {
            if (voiceDialog != null) voiceDialog.dismiss();
            if (letterDialog != null) letterDialog.dismiss();
            letterDialog = LetterDictionaryDialog.show(this, speaker);
        });
        addVoiceRow(g5, "مؤقت النوم", null, sleepLabel(), false, () -> { cycleSleepTimer(); refresh.run(); });
        addVoiceRow(g5, "إعدادات محرك النطق في النظام", null, null, true, () -> {
            if (voiceDialog != null) voiceDialog.dismiss();
            openSystemTtsSettings();
        });
    }

    /** يضيف عنوان قسم صغير + بطاقة مجموعة (Settings.Group) ويُرجع البطاقة لإضافة الصفوف فيها. */
    private LinearLayout voiceGroup(LinearLayout root, String label) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextColor(getColor(R.color.pdf_text_tertiary));
        tv.setTextSize(13f);
        tv.setTextDirection(View.TEXT_DIRECTION_RTL);
        tv.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        tv.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.setMargins(Ui.dp(this, 4), root.getChildCount() == 0 ? 0 : Ui.dp(this, 6), Ui.dp(this, 4), Ui.dp(this, 8));
        root.addView(tv, tlp);

        LinearLayout group = new LinearLayout(this);
        group.setOrientation(LinearLayout.VERTICAL);
        group.setBackgroundResource(R.drawable.bg_settings_group);
        group.setClipToOutline(true);
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        glp.bottomMargin = Ui.dp(this, 12);
        root.addView(group, glp);
        return group;
    }

    private LinearLayout voiceRowShell(LinearLayout group, String title, String subtitle) {
        if (group.getChildCount() > 0) {
            View divider = new View(this);
            divider.setBackgroundColor(getColor(R.color.glass_border_soft));
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 1));
            dlp.setMarginStart(Ui.dp(this, 16));
            group.addView(divider, dlp);
        }
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(Ui.dp(this, 56));
        row.setPadding(Ui.dp(this, 16), Ui.dp(this, 10), Ui.dp(this, 14), Ui.dp(this, 10));
        row.setClickable(true);
        row.setFocusable(true);
        android.util.TypedValue tv = new android.util.TypedValue();
        if (getTheme().resolveAttribute(android.R.attr.selectableItemBackground, tv, true)) {
            row.setForeground(getDrawable(tv.resourceId));
        }

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(getColor(R.color.pdf_text_primary));
        t.setTextSize(15f);
        t.setTextDirection(View.TEXT_DIRECTION_RTL);
        t.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        texts.addView(t);
        if (subtitle != null && !subtitle.isEmpty()) {
            TextView st = new TextView(this);
            st.setText(subtitle);
            st.setTextColor(getColor(R.color.pdf_text_secondary));
            st.setTextSize(12.5f);
            st.setTextDirection(View.TEXT_DIRECTION_RTL);
            st.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            slp.topMargin = Ui.dp(this, 2);
            texts.addView(st, slp);
        }
        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        group.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return row;
    }

    /** صف بقيمة (شريحة كبسولة) أو سهم للانتقال. */
    private void addVoiceRow(LinearLayout group, String title, String subtitle, String value,
                             boolean chevron, Runnable onClick) {
        LinearLayout row = voiceRowShell(group, title, subtitle);
        if (value != null && !value.isEmpty()) {
            TextView chip = new TextView(this);
            chip.setText(value);
            chip.setTextColor(getColor(R.color.primary_cyan));
            chip.setTextSize(12.5f);
            chip.setTypeface(null, android.graphics.Typeface.BOLD);
            chip.setSingleLine(true);
            chip.setEllipsize(android.text.TextUtils.TruncateAt.END);
            chip.setMaxWidth(Ui.dp(this, 150));
            chip.setGravity(Gravity.CENTER);
            chip.setBackgroundResource(R.drawable.bg_glass_chip);
            chip.setPadding(Ui.dp(this, 12), Ui.dp(this, 6), Ui.dp(this, 12), Ui.dp(this, 6));
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            clp.setMarginStart(Ui.dp(this, 10));
            row.addView(chip, clp);
        }
        if (chevron) {
            ImageView arrow = new ImageView(this);
            arrow.setImageResource(R.drawable.ic_chevron_end);
            arrow.setImageTintList(android.content.res.ColorStateList.valueOf(getColor(R.color.pdf_text_tertiary)));
            LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(Ui.dp(this, 20), Ui.dp(this, 20));
            alp.setMarginStart(Ui.dp(this, 8));
            row.addView(arrow, alp);
        }
        row.setOnClickListener(v -> onClick.run());
    }

    /** صف بمفتاح تشغيل/إيقاف؛ الضغط على الصف كله يبدّل الحالة. */
    private void addVoiceSwitch(LinearLayout group, String title, String subtitle, boolean checked, Runnable onToggle) {
        LinearLayout row = voiceRowShell(group, title, subtitle);
        com.google.android.material.materialswitch.MaterialSwitch sw =
                new com.google.android.material.materialswitch.MaterialSwitch(this);
        sw.setChecked(checked);
        sw.setClickable(false);
        sw.setFocusable(false);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.setMarginStart(Ui.dp(this, 8));
        row.addView(sw, slp);
        row.setOnClickListener(v -> onToggle.run());
    }

    /** قاموس نطق خاص: سطر لكل كلمة بصيغة  كلمة=نطقها  (مثال: Piriformis=بيريفورميس). */
    private void showLexiconEditor() {
        final EditText input = new EditText(this);
        input.setText(speaker.getUserLexicon());
        input.setHint("Piriformis=بيريفورميس\nمفصل=مَفْصِل");
        input.setMinLines(5);
        input.setGravity(Gravity.TOP | Gravity.START);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 8);
        box.setPadding(pad, pad, pad, 0);
        box.addView(input, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        voiceDialog = new ClaudeDialog(this)
                .setTitle("قاموس النطق الخاص")
                .setMessage("اكتب كل كلمة في سطر بصيغة: كلمة=نطقها. يمكن كتابة نطق الكلمة الأجنبية بحروف عربية.")
                .setView(box)
                .setPositiveButton("حفظ", (d, w) -> {
                    speaker.setUserLexicon(input.getText().toString());
                    Toast.makeText(this, "تم حفظ القاموس.", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("إلغاء", null)
                .show();
    }

    /** اسم المحرك المحلي الظاهر في الإعدادات (الافتراضي أو المختار). */
    private String localEngineLabel() {
        String pkg = speaker.getDeviceEnginePackage();
        if (pkg == null) return "افتراضي النظام";
        for (PdfSpeaker.VoiceOption o : speaker.listDeviceEngines()) {
            if (o.name.equals(pkg)) return o.label;
        }
        return pkg;
    }

    /** قائمة محركات النطق المثبّتة لاختيار محرك الصوت المحلي. */
    private void showDeviceEnginePicker() {
        final List<PdfSpeaker.VoiceOption> engines = speaker.listDeviceEngines();
        if (engines.isEmpty()) {
            Toast.makeText(this, "لا توجد محركات نطق أخرى ظاهرة (أو المحرك قيد التجهيز). يمكنك إدارتها من إعدادات النظام.",
                    Toast.LENGTH_LONG).show();
            openSystemTtsSettings();
            return;
        }
        String[] labels = new String[engines.size() + 1];
        labels[0] = "افتراضي النظام";
        int checked = 0;
        String saved = speaker.getDeviceEnginePackage();
        for (int i = 0; i < engines.size(); i++) {
            labels[i + 1] = engines.get(i).label;
            if (engines.get(i).name.equals(saved)) checked = i + 1;
        }
        voiceDialog = new ClaudeDialog(this)
                .setTitle("محرك النطق المحلي")
                .setSingleChoiceItems(labels, checked, (d, which) -> {
                    speaker.setDeviceEngine(which == 0 ? null : engines.get(which - 1).name);
                    Toast.makeText(this, "تم تغيير محرك النطق المحلي", Toast.LENGTH_SHORT).show();
                    if (voiceDialog != null) voiceDialog.dismiss();
                })
                .show();
    }

    private void showVoicePicker(int langIdx) {
        final String lang = TTS_LANG_CODES[langIdx];
        if (speaker.isCloudEngine()) {
            // أصوات عصبية أونلاين (مجانية) - قائمة ثابتة لكل لغة
            final List<PdfSpeaker.VoiceOption> cloudOpts = speaker.listCloudVoices(lang);
            String[] cloudLabels = new String[cloudOpts.size() + 1];
            cloudLabels[0] = "تلقائي (الصوت الافتراضي)";
            int cloudChecked = 0;
            String cloudSaved = speaker.getPreferredCloudVoice(lang);
            for (int i = 0; i < cloudOpts.size(); i++) {
                cloudLabels[i + 1] = cloudOpts.get(i).label;
                if (cloudOpts.get(i).name.equals(cloudSaved)) cloudChecked = i + 1;
            }
            voiceDialog = new ClaudeDialog(this)
                    .setTitle("صوت " + TTS_LANG_LABELS[langIdx] + " (أونلاين)")
                    .setSingleChoiceItems(cloudLabels, cloudChecked, (d, which) -> {
                        speaker.setPreferredCloudVoice(lang, which == 0 ? null : cloudOpts.get(which - 1).name);
                        Toast.makeText(this, "تم تغيير الصوت - سيُطبَّق فورًا على القراءة", Toast.LENGTH_SHORT).show();
                    })
                    .show();
            return;
        }
        final List<PdfSpeaker.VoiceOption> opts = speaker.listVoices(lang);
        if (opts.isEmpty()) {
            if (speaker.isDeviceOfflineOnly()) {
                Toast.makeText(this, "لا يوجد صوت " + TTS_LANG_LABELS[langIdx]
                        + " يعمل بدون إنترنت. ثبّته أو أوقف خيار \"أصوات بلا إنترنت فقط\".", Toast.LENGTH_LONG).show();
            }
            offerInstallVoice(lang, false);
            return;
        }
        String[] labels = new String[opts.size() + 1];
        labels[0] = "تلقائي (أعلى جودة متاحة)";
        int checked = 0;
        String saved = speaker.getPreferredVoice(lang);
        for (int i = 0; i < opts.size(); i++) {
            labels[i + 1] = opts.get(i).label;
            if (opts.get(i).name.equals(saved)) checked = i + 1;
        }
        voiceDialog = new ClaudeDialog(this)
                .setTitle("صوت " + TTS_LANG_LABELS[langIdx])
                .setSingleChoiceItems(labels, checked, (d, which) -> {
                    speaker.setPreferredVoice(lang, which == 0 ? null : opts.get(which - 1).name);
                    Toast.makeText(this, "تم تغيير الصوت - سيُطبَّق فورًا على القراءة", Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    private String langLabel(String lang) {
        for (int i = 0; i < TTS_LANG_CODES.length; i++) {
            if (TTS_LANG_CODES[i].equals(lang)) return TTS_LANG_LABELS[i];
        }
        return lang;
    }

    /** لا يوجد صوت مثبّت للغة: نعرض تثبيت بيانات الصوت (مجانًا) من محرك النطق. */
    private void offerInstallVoice(String lang, boolean skippedSentences) {
        if (voiceDialog != null && voiceDialog.isShowing()) return;
        String msg = skippedSentences
                ? "لا يوجد صوت مثبّت للغة " + langLabel(lang) + " على جهازك، فتم تخطّي الجمل بهذه اللغة. "
                : "لا توجد أصوات مثبّتة للغة " + langLabel(lang) + ". ";
        voiceDialog = new ClaudeDialog(this)
                .setTitle("صوت غير مثبّت")
                .setMessage(msg + "يمكنك تثبيت بيانات الصوت مجانًا من إعدادات محرك النطق (يُفضَّل \"خدمات Google للكلام\" لأفضل جودة).")
                .setPositiveButton("تثبيت الصوت", (d, w) -> {
                    try {
                        startActivity(new Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA));
                    } catch (Exception e) {
                        openSystemTtsSettings();
                    }
                })
                .setNegativeButton("لاحقًا", null)
                .show();
    }

    private void showEngineUnavailableDialog() {
        voiceDialog = new ClaudeDialog(this)
                .setTitle("محرك النطق غير متاح")
                .setMessage("لا يوجد محرك نطق يعمل على جهازك. ثبّت \"خدمات Google للكلام\" مجانًا من متجر Play ثم أعد المحاولة.")
                .setPositiveButton("فتح المتجر", (d, w) -> {
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW,
                                Uri.parse("market://details?id=com.google.android.tts")));
                    } catch (Exception e) {
                        try {
                            startActivity(new Intent(Intent.ACTION_VIEW,
                                    Uri.parse("https://play.google.com/store/apps/details?id=com.google.android.tts")));
                        } catch (Exception ignored) {
                            openSystemTtsSettings();
                        }
                    }
                })
                .setNegativeButton("إغلاق", null)
                .show();
    }

    private void openSystemTtsSettings() {
        try {
            startActivity(new Intent("com.android.settings.TTS_SETTINGS"));
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            } catch (Exception ignored) {
                Toast.makeText(this, "تعذّر فتح إعدادات النظام.", Toast.LENGTH_SHORT).show();
            }
        }
    }

    // ------------------------------------------------------------------ الحالات (تحميل/خطأ)

    private void showLoading(String text) {
        loadingText.setText(text);
        loadingBox.setVisibility(View.VISIBLE);
        emptyBox.setVisibility(View.GONE);
        hScroll.setVisibility(View.GONE);
        pageIndicator.setVisibility(View.GONE);
        applyChrome();
    }

    private void showError(String title, String body) {
        if (isFinishing() || isDestroyed()) return;
        loadingBox.setVisibility(View.GONE);
        hScroll.setVisibility(View.GONE);
        pageIndicator.setVisibility(View.GONE);
        emptyBox.setVisibility(View.VISIBLE);
        ((ImageView) emptyBox.findViewById(R.id.empty_icon)).setImageResource(R.drawable.ic_pdf);
        ((TextView) emptyBox.findViewById(R.id.empty_title)).setText(title);
        ((TextView) emptyBox.findViewById(R.id.empty_body)).setText(body);
        TextView action = emptyBox.findViewById(R.id.empty_action);
        action.setText("اختيار ملف آخر");
        action.setOnClickListener(v -> launchPicker());
        applyChrome();
    }

    // ------------------------------------------------------------------ أدوات

    private String queryDisplayName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) {
                    String name = c.getString(idx);
                    if (name != null && !name.trim().isEmpty()) return name.trim();
                }
            }
        } catch (Exception ignored) {
        }
        String path = uri.getLastPathSegment();
        return path != null ? path : "ملف PDF";
    }

    private void releaseRenderer() {
        synchronized (renderLock) {
            closeQuietly(renderer, descriptor);
            renderer = null;
            descriptor = null;
        }
    }

    private static void closeQuietly(PdfRenderer r, ParcelFileDescriptor pfd) {
        if (r != null) {
            try {
                r.close();
            } catch (Exception ignored) {
            }
        }
        if (pfd != null) {
            try {
                pfd.close();
            } catch (Exception ignored) {
            }
        }
    }

    private static String errorText(Throwable t) {
        String m = t.getMessage();
        return m == null || m.trim().isEmpty() ? t.getClass().getSimpleName() : m;
    }

    // ------------------------------------------------------------------ المحوّل

    // ------------------------------------------------------------------ الشريط العلوي/السفلي + البحث + الإشارات

    private int paperColor() {
        if (pageMode == MODE_NIGHT) return 0xFF161616;
        if (pageMode == MODE_SEPIA) return 0xFFF4ECD8;
        return Color.WHITE;
    }

    private void bindChrome() {
        topChrome = findViewById(R.id.top_chrome);
        searchBar = findViewById(R.id.pdf_search_bar);
        searchInput = findViewById(R.id.search_input);
        searchCount = findViewById(R.id.search_count);
        navBar = findViewById(R.id.pdf_nav_bar);
        navSeek = findViewById(R.id.nav_seek);
        navLabel = findViewById(R.id.nav_label);

        findViewById(R.id.search_close).setOnClickListener(v -> hideSearchBar());
        findViewById(R.id.search_prev).setOnClickListener(v -> stepHit(-1));
        findViewById(R.id.search_next).setOnClickListener(v -> stepHit(+1));
        searchInput.setOnEditorActionListener((tv, actionId, event) -> {
            boolean enter = event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_DOWN;
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE || enter) {
                startSearch(tv.getText() == null ? "" : tv.getText().toString());
                return true;
            }
            return false;
        });

        navSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (!fromUser || ratios.length == 0) return;
                int p = Math.max(0, Math.min(ratios.length - 1, progress));
                layoutManager.scrollToPositionWithOffset(p, 0);
                navLabel.setText(BidiText.fix((p + 1) + " / " + ratios.length));
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {
                seekDragging = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
                seekDragging = false;
                if (speaker != null && speaker.isActive() && ratios.length > 0) speaker.seekToPage(sb.getProgress());
                updateIndicator();
            }
        });

        backCallback = new OnBackPressedCallback(false) {
            @Override
            public void handleOnBackPressed() {
                hideSearchBar();
            }
        };
        getOnBackPressedDispatcher().addCallback(this, backCallback);
    }

    private void toggleChrome() {
        chromeVisible = !chromeVisible;
        if (searchBar.getVisibility() == View.VISIBLE) chromeVisible = true;
        applyChrome();
    }

    /** يحسب ظهور الشريط العلوي والسفلي حسب الحالة (وضع القراءة الهادئ، البحث، القراءة الصوتية). */
    private void applyChrome() {
        if (topChrome == null || navBar == null) return;
        boolean docShown = hScroll.getVisibility() == View.VISIBLE && ratios.length > 0;
        boolean searchOn = searchBar.getVisibility() == View.VISIBLE;
        boolean topShow = chromeVisible || searchOn || !docShown;
        boolean navShow = docShown && chromeVisible && !searchOn
                && (ttsBar == null || ttsBar.getVisibility() != View.VISIBLE);
        fadeSlide(topChrome, topShow, -Ui.dp(this, 22));
        fadeSlide(navBar, navShow, Ui.dp(this, 22));
    }

    private void fadeSlide(final View v, boolean show, float dy) {
        v.animate().cancel();
        if (show) {
            if (v.getVisibility() != View.VISIBLE) {
                v.setAlpha(0f);
                v.setTranslationY(dy);
                v.setVisibility(View.VISIBLE);
            }
            v.animate().alpha(1f).translationY(0f).setDuration(200)
                    .setInterpolator(new DecelerateInterpolator()).start();
        } else {
            if (v.getVisibility() != View.VISIBLE) return;
            v.animate().alpha(0f).translationY(dy).setDuration(170).withEndAction(() -> {
                if (v.getAlpha() <= 0.01f) v.setVisibility(View.GONE);
            }).start();
        }
    }

    // ---- موضع القراءة + الإشارات المرجعية

    private void saveReadingPosition() {
        if (prefs == null || docKey.isEmpty() || ratios.length == 0 || layoutManager == null) return;
        int pos = currentPageIndex();
        prefs.edit().putInt("pg_" + docKey, pos >= ratios.length - 1 ? 0 : pos).apply();
    }

    private void loadBookmarks() {
        bookmarks.clear();
        String raw = prefs.getString("bm_" + docKey, "");
        if (raw == null) return;
        for (String part : raw.split(",")) {
            try {
                if (!part.trim().isEmpty()) bookmarks.add(Integer.parseInt(part.trim()));
            } catch (NumberFormatException ignored) {
            }
        }
    }

    private void saveBookmarks() {
        StringBuilder sb = new StringBuilder();
        for (int p : bookmarks) {
            if (sb.length() > 0) sb.append(',');
            sb.append(p);
        }
        prefs.edit().putString("bm_" + docKey, sb.toString()).apply();
    }

    private void toggleBookmark() {
        if (ratios.length == 0) {
            Toast.makeText(this, "افتح ملف PDF أولًا.", Toast.LENGTH_SHORT).show();
            return;
        }
        int page = currentPageIndex();
        if (bookmarks.contains(page)) {
            bookmarks.remove(page);
            Toast.makeText(this, "أُزيلت الإشارة المرجعية من الصفحة " + (page + 1), Toast.LENGTH_SHORT).show();
        } else {
            bookmarks.add(page);
            Toast.makeText(this, "تمت إضافة إشارة مرجعية للصفحة " + (page + 1), Toast.LENGTH_SHORT).show();
        }
        saveBookmarks();
        adapter.notifyItemChanged(page);
    }

    private void showBookmarksDialog() {
        if (ratios.length == 0) {
            Toast.makeText(this, "افتح ملف PDF أولًا.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (bookmarks.isEmpty()) {
            Toast.makeText(this, "لا توجد إشارات مرجعية في هذا الملف بعد.", Toast.LENGTH_LONG).show();
            return;
        }
        final List<Integer> pagesList = new ArrayList<>(bookmarks);
        String[] labels = new String[pagesList.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = "الصفحة " + (pagesList.get(i) + 1);
        new ClaudeDialog(this)
                .setTitle("الإشارات المرجعية")
                .setItems(labels, (d, which) -> {
                    int p = pagesList.get(which);
                    layoutManager.scrollToPositionWithOffset(p, 0);
                    updateIndicator();
                    if (speaker != null && speaker.isActive()) speaker.seekToPage(p);
                })
                .setNegativeButton("إغلاق", null)
                .show();
    }

    // ---- البحث داخل الملف

    private void showSearchBar() {
        if (ratios.length == 0 || currentFile == null) {
            Toast.makeText(this, "افتح ملف PDF أولًا.", Toast.LENGTH_SHORT).show();
            return;
        }
        chromeVisible = true;
        searchBar.setVisibility(View.VISIBLE);
        backCallback.setEnabled(true);
        applyChrome();
        searchInput.requestFocus();
        searchInput.postDelayed(() -> {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(searchInput, InputMethodManager.SHOW_IMPLICIT);
        }, 150);
    }

    private void hideSearchBar() {
        searchBar.setVisibility(View.GONE);
        backCallback.setEnabled(false);
        resetSearchState();
        hideKeyboard();
        applyChrome();
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null && searchInput != null) imm.hideSoftInputFromWindow(searchInput.getWindowToken(), 0);
    }

    private void resetSearchState() {
        searchToken.incrementAndGet();
        hits.clear();
        hitIndex = -1;
        if (searchCount != null) searchCount.setText("");
        refreshSearchHighlights();
    }

    private void startSearch(String raw) {
        final String q = raw == null ? "" : raw.trim();
        if (q.length() < 2) {
            Toast.makeText(this, "اكتب كلمتين أو حرفين على الأقل للبحث.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (currentFile == null) return;
        hideKeyboard();
        resetSearchState();
        final int my = searchToken.get();
        final File file = currentFile;
        searchCount.setText("...");
        textExecutor.execute(() -> PdfSearchEngine.run(getApplicationContext(), file, q, searchToken, my,
                new PdfSearchEngine.Listener() {
                    @Override
                    public void onHit(PdfSearchEngine.Hit hit) {
                        runOnUiThread(() -> {
                            if (my != searchToken.get() || isFinishing() || isDestroyed()) return;
                            hits.add(hit);
                            if (hitIndex < 0) goToHit(0);
                            else {
                                updateSearchCount(false);
                                refreshSearchHighlights();
                            }
                        });
                    }

                    @Override
                    public void onProgress(int done, int total) {
                        runOnUiThread(() -> {
                            if (my != searchToken.get() || isFinishing() || isDestroyed()) return;
                            if (hits.isEmpty()) searchCount.setText(BidiText.fix(done + "/" + total));
                        });
                    }

                    @Override
                    public void onDone(int totalHits, boolean truncated) {
                        runOnUiThread(() -> {
                            if (my != searchToken.get() || isFinishing() || isDestroyed()) return;
                            if (hits.isEmpty()) {
                                searchCount.setText("لا نتائج");
                                Toast.makeText(PdfViewerActivity.this,
                                        "لم يُعثر على «" + q + "». الملفات الممسوحة ضوئيًا (صور) لا يمكن البحث فيها.",
                                        Toast.LENGTH_LONG).show();
                            } else {
                                updateSearchCount(true);
                            }
                        });
                    }

                    @Override
                    public void onError(String message) {
                        runOnUiThread(() -> {
                            if (my != searchToken.get() || isFinishing() || isDestroyed()) return;
                            searchCount.setText("");
                            Toast.makeText(PdfViewerActivity.this, "تعذّر البحث: " + message, Toast.LENGTH_LONG).show();
                        });
                    }
                }));
    }

    private void updateSearchCount(boolean finished) {
        if (hits.isEmpty()) return;
        String suffix = (!finished && hits.size() >= PdfSearchEngine.MAX_HITS) ? "+" : "";
        searchCount.setText(BidiText.fix((hitIndex + 1) + " / " + hits.size() + suffix));
    }

    private void stepHit(int dir) {
        if (hits.isEmpty()) {
            String q = searchInput.getText() == null ? "" : searchInput.getText().toString();
            if (!q.trim().isEmpty()) startSearch(q);
            return;
        }
        int n = hits.size();
        goToHit(((hitIndex + dir) % n + n) % n);
    }

    /** ينتقل للنتيجة رقم i: يمرّر الصفحة بحيث يظهر التطابق في أعلى ثلث الشاشة ويلوّنه بالبرتقالي. */
    private void goToHit(int i) {
        if (i < 0 || i >= hits.size()) return;
        hitIndex = i;
        PdfSearchEngine.Hit h = hits.get(i);
        RectF r = h.rects.get(0);
        int viewH = Math.max(1, pages.getHeight());
        float pageH = pageWidthPx() * ratios[h.page];
        float yIn = r.centerY() * pageH;
        int offset = Math.round(Math.min(Ui.dp(this, 8), viewH * 0.32f - yIn - pages.getPaddingTop()));
        layoutManager.scrollToPositionWithOffset(h.page, offset);
        if (zoomPercent > 105f) {
            int px = Math.round(r.centerX() * pages.getWidth()) - hScroll.getWidth() / 2;
            hScroll.smoothScrollTo(Math.max(0, px), 0);
        }
        updateSearchCount(false);
        refreshSearchHighlights();
        updateIndicator();
    }

    private void applySearchHighlight(PageAdapter.PageHolder holder, int page) {
        if (hits.isEmpty()) {
            holder.highlight.setSearchHits(null, null);
            return;
        }
        List<RectF> others = new ArrayList<>();
        List<RectF> current = new ArrayList<>();
        for (int i = 0; i < hits.size(); i++) {
            PdfSearchEngine.Hit h = hits.get(i);
            if (h.page != page) continue;
            if (i == hitIndex) current.addAll(h.rects);
            else others.addAll(h.rects);
        }
        holder.highlight.setSearchHits(others, current);
    }

    private void refreshSearchHighlights() {
        if (pages == null) return;
        for (int i = 0; i < pages.getChildCount(); i++) {
            RecyclerView.ViewHolder vh = pages.getChildViewHolder(pages.getChildAt(i));
            if (vh instanceof PageAdapter.PageHolder) {
                PageAdapter.PageHolder ph = (PageAdapter.PageHolder) vh;
                applySearchHighlight(ph, ph.page);
            }
        }
    }

    private final class PageAdapter extends RecyclerView.Adapter<PageAdapter.PageHolder> {

        @NonNull
        @Override
        public PageHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_pdf_page, parent, false);
            return new PageHolder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull PageHolder holder, int position) {
            // لو نفس البطاقة كانت تعرض نفس الصفحة نُبقي صورتها القديمة (حتى وإن كانت بدقة أخرى)
            // كعنصر نائب إلى أن تجهز الصورة الحادّة - بدل وميض أبيض عند كل تكبير/تغيير وضع.
            boolean samePage = holder.page == position;
            holder.page = position;
            int w = pageWidthPx();
            int h = Math.max(1, Math.round(w * ratios[position]));
            ViewGroup.LayoutParams lp = holder.image.getLayoutParams();
            if (lp.height != h) {
                lp.height = h;
                holder.image.setLayoutParams(lp);
            }
            holder.image.setBackgroundColor(paperColor());
            boolean marked = bookmarks.contains(position);
            holder.number.setText(marked ? "\u2605  " + (position + 1) : String.valueOf(position + 1));
            holder.number.setTextColor(getColor(marked ? R.color.primary_cyan : R.color.pdf_text_tertiary));
            if (position == speakingPage) holder.highlight.setHighlight(speakingSentence, speakingWord);
            else holder.highlight.clearHighlight();
            applySearchHighlight(holder, position);
            Bitmap cached = cache.get(cacheKey(position, w));
            if (cached != null) {
                holder.image.setImageBitmap(cached);
            } else {
                if (!samePage) holder.image.setImageDrawable(null);
                requestRender(holder, position, w, h);
            }
        }

        private void requestRender(PageHolder holder, int page, int w, int h) {
            final int gen = generation;
            renderExecutor.execute(() -> {
                // الصفحة خرجت من الشاشة أو تغيّر التكبير قبل دورها: نتجاهلها
                if (gen != generation || holder.page != page) return;
                Bitmap bmp = renderPage(page, w, h);
                if (bmp == null) return;
                if (gen == generation) cache.put(cacheKey(page, w), bmp);
                runOnUiThread(() -> {
                    if (gen == generation && holder.page == page) holder.image.setImageBitmap(bmp);
                });
            });
        }

        @Override
        public int getItemCount() {
            return ratios.length;
        }

        final class PageHolder extends RecyclerView.ViewHolder {
            final ImageView image;
            final PdfHighlightView highlight;
            final TextView number;
            volatile int page = -1;

            PageHolder(@NonNull View itemView) {
                super(itemView);
                image = itemView.findViewById(R.id.pdf_page_image);
                highlight = itemView.findViewById(R.id.pdf_page_highlight);
                number = itemView.findViewById(R.id.pdf_page_number);
            }
        }
    }
}
