package com.docreader.app;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.docreader.app.model.RecentFile;
import com.docreader.app.ui.RecentFilesAdapter;
import com.docreader.app.util.FileTypeUtils;
import com.docreader.app.util.RecentFilesStore;

import java.util.List;

public class MainActivity extends AppCompatActivity {

    private RecyclerView recyclerRecentFiles;
    private TextView textEmptyState;

    private final ActivityResultLauncher<String[]> openDocumentLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri != null) openViewer(uri);
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        recyclerRecentFiles = findViewById(R.id.recyclerRecentFiles);
        textEmptyState = findViewById(R.id.textEmptyState);
        recyclerRecentFiles.setLayoutManager(new LinearLayoutManager(this));

        findViewById(R.id.cardOpenFile).setOnClickListener(v -> pickDocument());
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshRecentFiles();
    }

    private void pickDocument() {
        openDocumentLauncher.launch(new String[]{
                "application/pdf",
                "application/msword",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "application/vnd.ms-excel",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "application/vnd.ms-powerpoint",
                "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                "text/plain",
                "text/csv",
                "*/*"
        });
    }

    private void openViewer(Uri uri) {
        try {
            getContentResolver().takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException ignored) {
            // بعض المزودين لا يدعمون صلاحيات دائمة؛ نتابع بصلاحية مؤقتة
        }

        String displayName = FileTypeUtils.queryDisplayName(this, uri);
        RecentFilesStore.add(this, new RecentFile(
                uri.toString(), displayName, "", System.currentTimeMillis()));

        Intent intent = new Intent(this, DocumentViewerActivity.class);
        intent.putExtra(DocumentViewerActivity.EXTRA_URI, uri.toString());
        intent.putExtra(DocumentViewerActivity.EXTRA_NAME, displayName);
        startActivity(intent);
    }

    private void refreshRecentFiles() {
        List<RecentFile> files = RecentFilesStore.getAll(this);
        textEmptyState.setVisibility(files.isEmpty() ? View.VISIBLE : View.GONE);
        recyclerRecentFiles.setAdapter(new RecentFilesAdapter(files, file -> {
            try {
                openViewer(Uri.parse(file.uri));
            } catch (Exception e) {
                // الملف لم يعد متاحًا
            }
        }));
    }
}
