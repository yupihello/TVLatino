package com.streamcaster.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.streamcaster.app.models.Series;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class FavoritesStore {
    private static final String TAG = "FavoritesStore";
    private static final String PREFS = "streamcaster_favorites";
    private static final String KEY = "items";

    private final SharedPreferences prefs;

    public FavoritesStore(Context ctx) {
        this.prefs = ctx.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public List<Series> getAll() {
        List<Series> result = new ArrayList<>();
        String raw = prefs.getString(KEY, "[]");
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                result.add(new Series(
                        o.optString("title"),
                        o.optString("thumbnailUrl"),
                        o.optString("pageUrl"),
                        o.optString("category")));
            }
        } catch (Exception e) {
            Log.e(TAG, "load error", e);
        }
        return result;
    }

    public boolean isFavorite(String pageUrl) {
        if (pageUrl == null) return false;
        for (Series s : getAll()) {
            if (pageUrl.equals(s.getPageUrl())) return true;
        }
        return false;
    }

    public boolean toggle(Series series) {
        if (series == null || series.getPageUrl() == null) return false;
        Map<String, Series> map = new LinkedHashMap<>();
        for (Series s : getAll()) map.put(s.getPageUrl(), s);

        boolean nowFavorite;
        if (map.containsKey(series.getPageUrl())) {
            map.remove(series.getPageUrl());
            nowFavorite = false;
        } else {
            map.put(series.getPageUrl(), series);
            nowFavorite = true;
        }
        save(new ArrayList<>(map.values()));
        return nowFavorite;
    }

    private void save(List<Series> list) {
        try {
            JSONArray arr = new JSONArray();
            for (Series s : list) {
                JSONObject o = new JSONObject();
                o.put("title", s.getTitle() == null ? "" : s.getTitle());
                o.put("thumbnailUrl", s.getThumbnailUrl() == null ? "" : s.getThumbnailUrl());
                o.put("pageUrl", s.getPageUrl() == null ? "" : s.getPageUrl());
                o.put("category", s.getCategory() == null ? "" : s.getCategory());
                arr.put(o);
            }
            prefs.edit().putString(KEY, arr.toString()).apply();
        } catch (Exception e) {
            Log.e(TAG, "save error", e);
        }
    }
}
