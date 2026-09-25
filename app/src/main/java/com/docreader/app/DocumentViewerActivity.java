package com.docreader.app;

import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.view.Gravity;
import android.view.View;
import android.webkit.WebView;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import com.docreader.app.ui.DocxBlockAdapter;
import com.docreader.app.ui.PdfPageAdapter;
import com.docreader.app.ui.PptxSlideAdapter;
import com.docreader.app.util.FileTypeUtils;
import com.docreader.app.viewer.DocxParser;
import com.docreader.app.viewer.PptxParser;
import com.docreader.app.viewer.RtfParser;
import com.docreader.app.viewer.XlsxParser;
import com.google.android.material.appbar.MaterialToolbar;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class DocumentViewerActivity extends AppCompatActivity {

    public static final String EXTRA_URI = "extra_uri";
    public static final String EXTRA_NAME = "extra_name";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private View progressLoading;
    private ViewPager2 pager;
    private RecyclerView recyclerContent;
    private View xlsxContainer;
    private HorizontalScrollView sheetTabsScroll;
    private LinearLayout sheetTabsRow;
    private TableLayout tableExcel;
    private View imageScroll;
    private ImageView imageSingle;
    private WebView webViewContent;
    private View layoutError;
    private TextView textError;
    private TextView textPageIndicator;

    private PdfRenderer pdfRenderer;
    private ParcelFileDescriptor pdfFd;

    private List<XlsxParser.Sheet> loadedSheets;
    private int activeSheetIndex = 0;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_viewer);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());
        applyWindowInsets(toolbar);

        progressLoading = findViewById(R.id.progressLoading);
        pager = findViewById(R.id.pager);
        recyclerContent = findViewById(R.id.recyclerContent);
        xlsxContainer = findViewById(R.id.xlsxContainer);
        sheetTabsScroll = findViewById(R.id.sheetTabsScroll);
        sheetTabsRow = findViewById(R.id.sheetTabsRow);
        tableExcel = findViewById(R.id.tableExcel);
        imageScroll = findViewById(R.id.imageScroll);
        imageSingle = findViewById(R.id.imageSingle);
        webViewContent = findViewById(R.id.webViewContent);
        layoutError = findViewById(R.id.layoutError);
        textError = findViewById(R.id.textError);
        textPageIndicator = findViewById(R.id.textPageIndicator);
        webViewContent.getSettings().setLoadWithOverviewMode(true);
        webViewContent.getSettings().setUseWideViewPort(true);

        String uriString = getIntent().getStringExtra(EXTRA_URI);
        String name = getIntent().getStringExtra(EXTRA_NAME);
        toolbar.setTitle(name != null ? name : getString(R.string.viewer_title));

        if (uriString == null) {
            showError(getString(R.string.error_loading));
            return;
        }
        loadDocument(Uri.parse(uriString), name);
    }

    private void applyWindowInsets(MaterialToolbar toolbar) {
        View root = findViewById(R.id.viewerRoot);
        final int toolbarPaddingTop = toolbar.getPaddingTop();
        final int rootPaddingBottom = root.getPaddingBottom();

        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            toolbar.setPadding(toolbar.getPaddingLeft(), toolbarPaddingTop + bars.top,
                    toolbar.getPaddingRight(), toolbar.getPaddingBottom());
            root.setPadding(root.getPaddingLeft(), root.getPaddingTop(),
                    root.getPaddingRight(), rootPaddingBottom + bars.bottom);
            return insets;
        });
    }

    private void loadDocument(Uri uri, String name) {
        FileTypeUtils.DocType type = FileTypeUtils.detect(name);

        if (FileTypeUtils.isLegacyBinary(type)) {
            showError(getString(R.string.legacy_format_unsupported));
            return;
        }

        executor.execute(() -> {
            try {
                switch (type) {
                    case PDF:
                        loadPdf(uri);
                        break;
                    case DOCX:
                        loadDocx(uri);
                        break;
                    case RTF:
                        loadRtf(uri);
                        break;
                    case XLSX:
                        loadXlsx(uri);
                        break;
                    case CSV:
                        loadCsv(uri);
                        break;
                    case PPTX:
                        loadPptx(uri);
                        break;
                    case TXT:
                        loadTxt(uri);
                        break;
                    case HTML:
                        loadHtml(uri);
                        break;
                    case IMAGE:
                        loadImage(uri);
                        break;
                    default:
                        mainHandler.post(() -> showError(getString(R.string.unsupported_format)));
                }
            } catch (Exception e) {
                mainHandler.post(() -> showError(getString(R.string.error_loading)));
            }
        });
    }

    // ---------- PDF ----------
    private void loadPdf(Uri uri) throws Exception {
        // PdfRenderer يحتاج ملف قابل للـ seek، لذلك ننسخه إلى ملف مؤقت أولاً
        File temp = new File(getCacheDir(), "temp_view.pdf");
        try (InputStream in = getContentResolver().openInputStream(uri);
             FileOutputStream out = new FileOutputStream(temp)) {
            byte[] buffer = new byte[8192];
            int len;
            while (in != null && (len = in.read(buffer)) > 0) out.write(buffer, 0, len);
        }
        pdfFd = ParcelFileDescriptor.open(temp, ParcelFileDescriptor.MODE_READ_ONLY);
        pdfRenderer = new PdfRenderer(pdfFd);
        int pageCount = pdfRenderer.getPageCount();

        mainHandler.post(() -> {
            pager.setAdapter(new PdfPageAdapter(pdfRenderer));
            showContentView(pager);
            updateIndicator(pageCount, true);
            pager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
                @Override
                public void onPageSelected(int position) {
                    textPageIndicator.setText(getString(R.string.page_indicator, position + 1, pageCount));
                }
            });
        });
    }

    // ---------- DOCX ----------
    private void loadDocx(Uri uri) throws Exception {
        List<DocxParser.Block> blocks;
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            blocks = DocxParser.parse(in);
        }
        showBlocks(blocks);
    }

    // ---------- RTF ----------
    private void loadRtf(Uri uri) throws Exception {
        List<DocxParser.Block> blocks;
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            blocks = RtfParser.parse(in);
        }
        showBlocks(blocks);
    }

    // ---------- TXT ----------
    private void loadTxt(Uri uri) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in != null) {
                byte[] buffer = new byte[8192];
                int len;
                while ((len = in.read(buffer)) > 0) sb.append(new String(buffer, 0, len, "UTF-8"));
            }
        }
        DocxParser.Block block = new DocxParser.Block(DocxParser.Block.Type.PARAGRAPH);
        DocxParser.Run run = new DocxParser.Run();
        run.text = sb.toString();
        block.runs.add(run);
        List<DocxParser.Block> blocks = new ArrayList<>();
        blocks.add(block);
        showBlocks(blocks);
    }

    private void showBlocks(List<DocxParser.Block> blocks) {
        mainHandler.post(() -> {
            recyclerContent.setLayoutManager(new LinearLayoutManager(this));
            recyclerContent.setAdapter(new DocxBlockAdapter(blocks));
            showContentView(recyclerContent);
        });
    }

    // ---------- XLSX ----------
    private void loadXlsx(Uri uri) throws Exception {
        List<XlsxParser.Sheet> sheets;
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            sheets = XlsxParser.parseAll(in);
        }
        showSheets(sheets);
    }

    // ---------- CSV ----------
    private void loadCsv(Uri uri) throws Exception {
        List<List<String>> rows;
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            rows = XlsxParser.parseCsv(in);
        }
        XlsxParser.Sheet sheet = new XlsxParser.Sheet();
        sheet.name = "CSV";
        sheet.rows = rows;
        List<XlsxParser.Sheet> sheets = new ArrayList<>();
        sheets.add(sheet);
        showSheets(sheets);
    }

    private void showSheets(List<XlsxParser.Sheet> sheets) {
        loadedSheets = sheets;
        activeSheetIndex = 0;
        mainHandler.post(() -> {
            sheetTabsRow.removeAllViews();
            boolean showTabs = sheets.size() > 1;
            sheetTabsScroll.setVisibility(showTabs ? View.VISIBLE : View.GONE);
            if (showTabs) {
                for (int i = 0; i < sheets.size(); i++) {
                    int index = i;
                    TextView tab = new TextView(this);
                    tab.setText(sheets.get(i).name);
                    tab.setTextSize(13f);
                    tab.setPadding(36, 16, 36, 16);
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                    lp.marginEnd = 8;
                    tab.setLayoutParams(lp);
                    tab.setOnClickListener(v -> selectSheet(index));
                    sheetTabsRow.addView(tab);
                }
            }
            selectSheet(0);
            showContentView(xlsxContainer);
        });
    }

    private void selectSheet(int index) {
        if (loadedSheets == null || index < 0 || index >= loadedSheets.size()) return;
        activeSheetIndex = index;
        for (int i = 0; i < sheetTabsRow.getChildCount(); i++) {
            TextView tab = (TextView) sheetTabsRow.getChildAt(i);
            boolean selected = i == index;
            tab.setBackgroundResource(selected ? R.drawable.bg_button_filled_pill : R.drawable.bg_button_outline_pill);
            tab.setTextColor(getColor(selected ? R.color.text_on_accent : R.color.text_secondary));
            tab.setTypeface(null, selected ? Typeface.BOLD : Typeface.NORMAL);
        }
        renderTable(loadedSheets.get(index).rows);
    }

    private void renderTable(List<List<String>> rows) {
        tableExcel.removeAllViews();
        boolean firstRow = true;
        for (List<String> row : rows) {
            TableRow tableRow = new TableRow(this);
            for (String cellText : row) {
                TextView cell = new TextView(this);
                cell.setText(cellText);
                cell.setPadding(24, 20, 24, 20);
                cell.setTextColor(getColor(R.color.text_primary));
                cell.setTextSize(13f);
                cell.setBackgroundResource(R.drawable.bg_table_cell);
                boolean numeric = isNumeric(cellText);
                cell.setGravity(numeric ? Gravity.END : Gravity.START);
                if (firstRow) cell.setTypeface(null, Typeface.BOLD);
                tableRow.addView(cell);
            }
            tableExcel.addView(tableRow);
            firstRow = false;
        }
    }

    private boolean isNumeric(String s) {
        if (s == null || s.trim().isEmpty()) return false;
        try {
            Double.parseDouble(s.trim());
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    // ---------- PPTX ----------
    private void loadPptx(Uri uri) throws Exception {
        List<PptxParser.Slide> slides;
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            slides = PptxParser.parse(in);
        }
        int total = slides.size();

        mainHandler.post(() -> {
            pager.setAdapter(new PptxSlideAdapter(slides));
            showContentView(pager);
            updateIndicator(total, false);
            pager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
                @Override
                public void onPageSelected(int position) {
                    textPageIndicator.setText(getString(R.string.slide_indicator, position + 1, total));
                }
            });
        });
    }

    // ---------- HTML ----------
    private void loadHtml(Uri uri) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in != null) {
                byte[] buffer = new byte[8192];
                int len;
                while ((len = in.read(buffer)) > 0) sb.append(new String(buffer, 0, len, "UTF-8"));
            }
        }
        String html = sb.toString();
        mainHandler.post(() -> {
            webViewContent.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null);
            showContentView(webViewContent);
        });
    }

    // ---------- صور مستقلة ----------
    private void loadImage(Uri uri) throws Exception {
        mainHandler.post(() -> {
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                imageSingle.setImageBitmap(BitmapFactory.decodeStream(in));
                showContentView(imageScroll);
            } catch (Exception e) {
                showError(getString(R.string.error_loading));
            }
        });
    }

    private void updateIndicator(int total, boolean isPdf) {
        textPageIndicator.setVisibility(View.VISIBLE);
        textPageIndicator.setText(isPdf
                ? getString(R.string.page_indicator, 1, total)
                : getString(R.string.slide_indicator, 1, total));
    }

    private void showContentView(View view) {
        progressLoading.setVisibility(View.GONE);
        pager.setVisibility(view == pager ? View.VISIBLE : View.GONE);
        recyclerContent.setVisibility(view == recyclerContent ? View.VISIBLE : View.GONE);
        xlsxContainer.setVisibility(view == xlsxContainer ? View.VISIBLE : View.GONE);
        imageScroll.setVisibility(view == imageScroll ? View.VISIBLE : View.GONE);
        webViewContent.setVisibility(view == webViewContent ? View.VISIBLE : View.GONE);
        layoutError.setVisibility(View.GONE);
    }

    private void showError(String message) {
        progressLoading.setVisibility(View.GONE);
        layoutError.setVisibility(View.VISIBLE);
        textError.setText(message);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdownNow();
        try {
            if (pdfRenderer != null) pdfRenderer.close();
            if (pdfFd != null) pdfFd.close();
        } catch (Exception ignored) {
        }
    }
}
