package com.docreader.app;

import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.view.View;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import com.docreader.app.ui.DocxBlockAdapter;
import com.docreader.app.ui.PdfPageAdapter;
import com.docreader.app.ui.PptxSlideAdapter;
import com.docreader.app.util.FileTypeUtils;
import com.docreader.app.viewer.DocxParser;
import com.docreader.app.viewer.PptxParser;
import com.docreader.app.viewer.XlsxParser;
import com.google.android.material.appbar.MaterialToolbar;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
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
    private View horizontalTableScroll;
    private TableLayout tableExcel;
    private View layoutError;
    private TextView textError;
    private TextView textPageIndicator;

    private PdfRenderer pdfRenderer;
    private ParcelFileDescriptor pdfFd;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_viewer);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());

        progressLoading = findViewById(R.id.progressLoading);
        pager = findViewById(R.id.pager);
        recyclerContent = findViewById(R.id.recyclerContent);
        horizontalTableScroll = findViewById(R.id.horizontalTableScroll);
        tableExcel = findViewById(R.id.tableExcel);
        layoutError = findViewById(R.id.layoutError);
        textError = findViewById(R.id.textError);
        textPageIndicator = findViewById(R.id.textPageIndicator);

        String uriString = getIntent().getStringExtra(EXTRA_URI);
        String name = getIntent().getStringExtra(EXTRA_NAME);
        toolbar.setTitle(name != null ? name : getString(R.string.viewer_title));

        if (uriString == null) {
            showError(getString(R.string.error_loading));
            return;
        }
        loadDocument(Uri.parse(uriString), name);
    }

    private void loadDocument(Uri uri, String name) {
        FileTypeUtils.DocType type = FileTypeUtils.detect(name);

        executor.execute(() -> {
            try {
                switch (type) {
                    case PDF:
                        loadPdf(uri);
                        break;
                    case DOCX:
                        loadDocx(uri);
                        break;
                    case XLSX:
                        loadXlsx(uri);
                        break;
                    case PPTX:
                        loadPptx(uri);
                        break;
                    case TXT:
                        loadTxt(uri);
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
        mainHandler.post(() -> {
            recyclerContent.setLayoutManager(new LinearLayoutManager(this));
            recyclerContent.setAdapter(new DocxBlockAdapter(blocks));
            showContentView(recyclerContent);
        });
    }

    // ---------- XLSX ----------
    private void loadXlsx(Uri uri) throws Exception {
        XlsxParser.Sheet sheet;
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            sheet = XlsxParser.parseFirstSheet(in);
        }
        List<List<String>> rows = sheet.rows;

        mainHandler.post(() -> {
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
                    if (firstRow) cell.setTypeface(null, android.graphics.Typeface.BOLD);
                    tableRow.addView(cell);
                }
                tableExcel.addView(tableRow);
                firstRow = false;
            }
            showContentView(horizontalTableScroll);
        });
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
        String content = sb.toString();
        mainHandler.post(() -> {
            List<DocxParser.Block> blocks = new java.util.ArrayList<>();
            blocks.add(new DocxParser.Block(DocxParser.Block.Type.PARAGRAPH, content));
            recyclerContent.setLayoutManager(new LinearLayoutManager(this));
            recyclerContent.setAdapter(new DocxBlockAdapter(blocks));
            showContentView(recyclerContent);
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
        horizontalTableScroll.setVisibility(view == horizontalTableScroll ? View.VISIBLE : View.GONE);
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
