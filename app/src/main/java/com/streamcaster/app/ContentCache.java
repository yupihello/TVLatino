package com.streamcaster.app;

import android.content.Context;
import android.util.Log;

import com.streamcaster.app.models.Episode;
import com.streamcaster.app.models.Series;
import com.streamcaster.app.models.WatchProgress;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Persists scraping results to JSON files in internal storage.
 * Homepage data and each series' episodes are cached separately.
 * Data is loaded from cache on subsequent app opens without re-scraping.
 */
public class ContentCache {

    private static final String TAG = "ContentCache";
    private static final String CACHE_DIR = "content_cache";
    private static final String HOME_FILE = "homepage.json";
    private static final String SERIES_DIR = "series";
    private static final String PROGRESS_FILE = "watch_progress.json";

    private final File cacheDir;
    private final File seriesDir;

    public ContentCache(Context context) {
        cacheDir = new File(context.getFilesDir(), CACHE_DIR);
        seriesDir = new File(cacheDir, SERIES_DIR);
        if (!cacheDir.exists()) cacheDir.mkdirs();
        if (!seriesDir.exists()) seriesDir.mkdirs();
    }

    // --- Homepage cache ---

    public boolean hasHomepageCache() {
        return new File(cacheDir, HOME_FILE).exists();
    }

    public List<Series> loadHomepage() {
        List<Series> result = new ArrayList<>();
        try {
            String json = readFile(new File(cacheDir, HOME_FILE));
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                result.add(new Series(
                        obj.getString("title"),
                        obj.getString("thumbnailUrl"),
                        obj.getString("pageUrl"),
                        obj.optString("category", "")
                ));
            }
            Log.d(TAG, "Loaded homepage from cache: " + result.size() + " series");
        } catch (Exception e) {
            Log.e(TAG, "Error loading homepage cache", e);
        }
        return result;
    }

    public void saveHomepage(List<Series> data) {
        try {
            JSONArray arr = new JSONArray();
            for (Series s : data) {
                JSONObject obj = new JSONObject();
                obj.put("title", s.getTitle());
                obj.put("thumbnailUrl", s.getThumbnailUrl());
                obj.put("pageUrl", s.getPageUrl());
                obj.put("category", s.getCategory());
                arr.put(obj);
            }
            writeFile(new File(cacheDir, HOME_FILE), arr.toString());
            Log.d(TAG, "Saved homepage cache: " + data.size() + " series");
        } catch (Exception e) {
            Log.e(TAG, "Error saving homepage cache", e);
        }
    }

    // --- Series episodes cache ---

    public boolean hasSeriesCache(String seriesUrl) {
        return new File(seriesDir, urlToFilename(seriesUrl)).exists();
    }

    public Map<Integer, List<Episode>> loadSeriesEpisodes(String seriesUrl) {
        Map<Integer, List<Episode>> result = new LinkedHashMap<>();
        try {
            String json = readFile(new File(seriesDir, urlToFilename(seriesUrl)));
            JSONObject root = new JSONObject(json);
            Iterator<String> keys = root.keys();
            while (keys.hasNext()) {
                String seasonKey = keys.next();
                int season = Integer.parseInt(seasonKey);
                JSONArray arr = root.getJSONArray(seasonKey);
                List<Episode> list = new ArrayList<>();
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject obj = arr.getJSONObject(i);
                    // Support both old (serverUrl1/2) and new (serverUrls array) format
                    Episode ep;
                    if (obj.has("serverUrls")) {
                        JSONArray urlsArr = obj.getJSONArray("serverUrls");
                        ep = new Episode();
                        ep.setTitle(obj.getString("title"));
                        ep.setEpisodeNumber(obj.getInt("episodeNumber"));
                        ep.setSeasonNumber(obj.getInt("seasonNumber"));
                        ep.setThumbnailUrl(obj.optString("thumbnailUrl", ""));
                        List<String> urls = new ArrayList<>();
                        for (int j = 0; j < urlsArr.length(); j++) urls.add(urlsArr.getString(j));
                        ep.setServerUrls(urls);
                    } else {
                        ep = new Episode(
                                obj.getString("title"),
                                obj.getInt("episodeNumber"),
                                obj.getInt("seasonNumber"),
                                obj.optString("serverUrl1", ""),
                                obj.optString("serverUrl2", ""),
                                obj.optString("thumbnailUrl", ""));
                    }
                    ep.setSynopsis(obj.optString("synopsis", ""));
                    ep.setTmdbStillUrl(obj.optString("tmdbStillUrl", ""));
                    ep.setRating(obj.optDouble("rating", 0));
                    ep.setAirDate(obj.optString("airDate", ""));
                    list.add(ep);
                }
                result.put(season, list);
            }
            Log.d(TAG, "Loaded series cache: " + result.size() + " seasons for " + seriesUrl);
        } catch (Exception e) {
            Log.e(TAG, "Error loading series cache", e);
        }
        return result;
    }

    public void saveSeriesEpisodes(String seriesUrl, Map<Integer, List<Episode>> data) {
        try {
            JSONObject root = new JSONObject();
            for (Map.Entry<Integer, List<Episode>> entry : data.entrySet()) {
                JSONArray arr = new JSONArray();
                for (Episode ep : entry.getValue()) {
                    JSONObject obj = new JSONObject();
                    obj.put("title", ep.getTitle());
                    obj.put("episodeNumber", ep.getEpisodeNumber());
                    obj.put("seasonNumber", ep.getSeasonNumber());
                    JSONArray urlsArr = new JSONArray();
                    for (String url : ep.getServerUrls()) urlsArr.put(url);
                    obj.put("serverUrls", urlsArr);
                    obj.put("thumbnailUrl", ep.getThumbnailUrl() != null ? ep.getThumbnailUrl() : "");
                    obj.put("synopsis", ep.getSynopsis() != null ? ep.getSynopsis() : "");
                    obj.put("tmdbStillUrl", ep.getTmdbStillUrl() != null ? ep.getTmdbStillUrl() : "");
                    obj.put("rating", ep.getRating());
                    obj.put("airDate", ep.getAirDate() != null ? ep.getAirDate() : "");
                    arr.put(obj);
                }
                root.put(String.valueOf(entry.getKey()), arr);
            }
            writeFile(new File(seriesDir, urlToFilename(seriesUrl)), root.toString());
            Log.d(TAG, "Saved series cache: " + data.size() + " seasons for " + seriesUrl);
        } catch (Exception e) {
            Log.e(TAG, "Error saving series cache", e);
        }
    }

    // --- Watch progress ---

    public void saveWatchProgress(WatchProgress progress) {
        try {
            // Load existing progress map
            Map<String, WatchProgress> all = loadAllWatchProgress();
            all.put(progress.getSeriesUrl(), progress);

            JSONArray arr = new JSONArray();
            for (WatchProgress wp : all.values()) {
                JSONObject obj = new JSONObject();
                obj.put("seriesUrl", wp.getSeriesUrl());
                obj.put("seriesTitle", wp.getSeriesTitle());
                obj.put("seriesThumbnail", wp.getSeriesThumbnail());
                obj.put("seasonNumber", wp.getSeasonNumber());
                obj.put("episodeNumber", wp.getEpisodeNumber());
                obj.put("episodeTitle", wp.getEpisodeTitle());
                obj.put("positionMs", wp.getPositionMs());
                obj.put("durationMs", wp.getDurationMs());
                obj.put("timestamp", wp.getTimestamp());
                arr.put(obj);
            }
            writeFile(new File(cacheDir, PROGRESS_FILE), arr.toString());
        } catch (Exception e) {
            Log.e(TAG, "Error saving watch progress", e);
        }
    }

    public Map<String, WatchProgress> loadAllWatchProgress() {
        Map<String, WatchProgress> result = new LinkedHashMap<>();
        File file = new File(cacheDir, PROGRESS_FILE);
        if (!file.exists()) return result;
        try {
            String json = readFile(file);
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                WatchProgress wp = new WatchProgress(
                        obj.getString("seriesUrl"),
                        obj.getString("seriesTitle"),
                        obj.getString("seriesThumbnail"),
                        obj.getInt("seasonNumber"),
                        obj.getInt("episodeNumber"),
                        obj.getString("episodeTitle"),
                        obj.getLong("positionMs"),
                        obj.getLong("durationMs")
                );
                wp.setTimestamp(obj.getLong("timestamp"));
                result.put(wp.getSeriesUrl(), wp);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error loading watch progress", e);
        }
        return result;
    }

    /** Returns recently watched series sorted by most recent first. */
    public List<WatchProgress> getRecentlyWatched() {
        List<WatchProgress> list = new ArrayList<>(loadAllWatchProgress().values());
        list.sort((a, b) -> Long.compare(b.getTimestamp(), a.getTimestamp()));
        return list;
    }

    /** Deletes all cached data, forcing a fresh scrape on next load. */
    public void clearAll() {
        deleteRecursive(cacheDir);
        cacheDir.mkdirs();
        seriesDir.mkdirs();
        Log.d(TAG, "Cache cleared");
    }

    // --- Helpers ---

    private String urlToFilename(String url) {
        // Deterministic filename from URL — strip scheme, replace non-alphanum
        return url.replaceAll("https?://", "")
                  .replaceAll("[^a-zA-Z0-9]", "_")
                  + ".json";
    }

    private String readFile(File file) throws IOException {
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] bytes = new byte[(int) file.length()];
            fis.read(bytes);
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    private void writeFile(File file, String content) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }

    private void deleteRecursive(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) deleteRecursive(child);
            }
        }
        file.delete();
    }
}
