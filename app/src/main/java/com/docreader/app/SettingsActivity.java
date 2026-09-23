package com.docreader.app;

import android.content.pm.PackageInfo;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.docreader.app.util.RecentFilesStore;
import com.docreader.app.util.ThemeUtils;
import com.google.android.material.appbar.MaterialToolbar;

public class SettingsActivity extends AppCompatActivity {

    private TextView textColorModeValue;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());
        applyWindowInsets(toolbar);

        textColorModeValue = findViewById(R.id.textColorModeValue);
        updateColorModeLabel();

        findViewById(R.id.rowColorMode).setOnClickListener(v -> showColorModeDialog());
        findViewById(R.id.rowClearRecent).setOnClickListener(v -> showClearRecentDialog());
        findViewById(R.id.rowAbout).setOnClickListener(v -> showAboutDialog());

        TextView textAboutValue = findViewById(R.id.textAboutValue);
        textAboutValue.setText(getString(R.string.settings_about_sub, getVersionName()));
    }

    private void applyWindowInsets(MaterialToolbar toolbar) {
        View root = findViewById(R.id.settingsRoot);
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

    private void updateColorModeLabel() {
        String mode = ThemeUtils.getSavedMode(this);
        int resId = R.string.settings_color_mode_system;
        if (ThemeUtils.MODE_LIGHT.equals(mode)) resId = R.string.settings_color_mode_light;
        else if (ThemeUtils.MODE_DARK.equals(mode)) resId = R.string.settings_color_mode_dark;
        textColorModeValue.setText(resId);
    }

    private void showColorModeDialog() {
        String[] labels = {
                getString(R.string.settings_color_mode_system),
                getString(R.string.settings_color_mode_light),
                getString(R.string.settings_color_mode_dark)
        };
        String[] modes = {ThemeUtils.MODE_SYSTEM, ThemeUtils.MODE_LIGHT, ThemeUtils.MODE_DARK};
        String current = ThemeUtils.getSavedMode(this);
        int checked = 0;
        for (int i = 0; i < modes.length; i++) if (modes[i].equals(current)) checked = i;

        new AlertDialog.Builder(this)
                .setTitle(R.string.settings_color_mode)
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    ThemeUtils.setMode(this, modes[which]);
                    updateColorModeLabel();
                    dialog.dismiss();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void showClearRecentDialog() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.settings_clear_recent_confirm_title)
                .setMessage(R.string.settings_clear_recent_confirm_msg)
                .setPositiveButton(R.string.confirm, (dialog, which) -> {
                    RecentFilesStore.clear(this);
                    Toast.makeText(this, R.string.settings_clear_recent_done, Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void showAboutDialog() {
        String message = getString(R.string.app_name) + "\n" +
                getString(R.string.settings_about_sub, getVersionName());
        new AlertDialog.Builder(this)
                .setTitle(R.string.settings_about)
                .setMessage(message)
                .setPositiveButton(R.string.confirm, null)
                .show();
    }

    private String getVersionName() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            return info.versionName != null ? info.versionName : "";
        } catch (Exception e) {
            return "";
        }
    }
}
