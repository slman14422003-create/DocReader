package com.docreader.app.util;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.appcompat.app.AppCompatDelegate;

/** يحفظ وضع الألوان المختار (فاتح / داكن / حسب النظام) ويطبّقه على التطبيق كاملًا. */
public class ThemeUtils {

    private static final String PREFS = "doc_reader_prefs";
    private static final String KEY_THEME_MODE = "theme_mode";

    public static final String MODE_SYSTEM = "system";
    public static final String MODE_LIGHT = "light";
    public static final String MODE_DARK = "dark";

    public static String getSavedMode(Context context) {
        return prefs(context).getString(KEY_THEME_MODE, MODE_SYSTEM);
    }

    public static void setMode(Context context, String mode) {
        prefs(context).edit().putString(KEY_THEME_MODE, mode).apply();
        applyMode(mode);
    }

    public static void applySavedTheme(Context context) {
        applyMode(getSavedMode(context));
    }

    private static void applyMode(String mode) {
        switch (mode) {
            case MODE_LIGHT:
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
                break;
            case MODE_DARK:
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
                break;
            default:
                AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
