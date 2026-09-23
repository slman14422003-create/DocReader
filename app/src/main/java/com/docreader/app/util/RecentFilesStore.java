package com.docreader.app.util;

import android.content.Context;
import android.content.SharedPreferences;

import com.docreader.app.model.RecentFile;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** تخزين بسيط لقائمة آخر الملفات المفتوحة محليًا على الجهاز. */
public class RecentFilesStore {

    private static final String PREFS = "doc_reader_prefs";
    private static final String KEY_RECENTS = "recent_files_json";
    private static final int MAX_ITEMS = 30;

    public static void add(Context context, RecentFile file) {
        List<RecentFile> list = getAll(context);
        // إزالة أي إدخال سابق لنفس الملف
        for (int i = list.size() - 1; i >= 0; i--) {
            if (list.get(i).uri.equals(file.uri)) list.remove(i);
        }
        list.add(0, file);
        while (list.size() > MAX_ITEMS) list.remove(list.size() - 1);
        save(context, list);
    }

    public static void clear(Context context) {
        save(context, new ArrayList<>());
    }

    public static List<RecentFile> getAll(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String json = prefs.getString(KEY_RECENTS, "[]");
        List<RecentFile> result = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                result.add(new RecentFile(
                        o.getString("uri"),
                        o.getString("displayName"),
                        o.optString("mimeType", ""),
                        o.optLong("lastOpened", 0)
                ));
            }
        } catch (JSONException ignored) {
        }
        Collections.sort(result, (a, b) -> Long.compare(b.lastOpened, a.lastOpened));
        return result;
    }

    private static void save(Context context, List<RecentFile> list) {
        JSONArray arr = new JSONArray();
        try {
            for (RecentFile f : list) {
                JSONObject o = new JSONObject();
                o.put("uri", f.uri);
                o.put("displayName", f.displayName);
                o.put("mimeType", f.mimeType);
                o.put("lastOpened", f.lastOpened);
                arr.put(o);
            }
        } catch (JSONException ignored) {
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_RECENTS, arr.toString())
                .apply();
    }
}
