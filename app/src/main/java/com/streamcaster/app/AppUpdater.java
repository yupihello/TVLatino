package com.streamcaster.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Checks for updates from GitHub Releases and installs new APK.
 *
 * Setup: Create a GitHub release with a tag like "v1.0" and upload the APK.
 * The updater checks the latest release via GitHub API and compares versionCode.
 */
public class AppUpdater {

    private static final String TAG = "AppUpdater";

    // CHANGE THIS to your GitHub repo (owner/repo)
    private static final String GITHUB_REPO = "yupihello/TVLatino"; // e.g. "user/TVLatino"
    private static final String API_URL = "https://api.github.com/repos/%s/releases/latest";

    private final Activity activity;
    private final OkHttpClient client = new OkHttpClient();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    public AppUpdater(Activity activity) {
        this.activity = activity;
    }

    /**
     * Checks GitHub for a newer release. Shows dialog if update available.
     * Call this from MainActivity.onCreate() or onResume().
     */
    public void checkForUpdate() {
        if (GITHUB_REPO.isEmpty()) return;

        executor.execute(() -> {
            try {
                String url = String.format(API_URL, GITHUB_REPO);
                Request request = new Request.Builder()
                        .url(url)
                        .header("Accept", "application/vnd.github+json")
                        .build();

                Response response = client.newCall(request).execute();
                String body = response.body() != null ? response.body().string() : "";
                response.close();

                JSONObject release = new JSONObject(body);
                String tagName = release.optString("tag_name", "");
                int remoteVersion = parseVersionCode(tagName);
                int localVersion = getLocalVersionCode();

                if (remoteVersion > localVersion) {
                    // Find APK asset in release
                    String apkUrl = null;
                    var assets = release.optJSONArray("assets");
                    if (assets != null) {
                        for (int i = 0; i < assets.length(); i++) {
                            String name = assets.getJSONObject(i).optString("name", "");
                            if (name.endsWith(".apk")) {
                                apkUrl = assets.getJSONObject(i).optString(
                                        "browser_download_url", "");
                                break;
                            }
                        }
                    }

                    String changelog = release.optString("body", "");
                    String finalApkUrl = apkUrl;

                    mainHandler.post(() -> showUpdateDialog(tagName, changelog, finalApkUrl));
                } else {
                    Log.d(TAG, "App is up to date (local=" + localVersion
                            + " remote=" + remoteVersion + ")");
                }
            } catch (Exception e) {
                Log.d(TAG, "Update check skipped: " + e.getMessage());
            }
        });
    }

    private void showUpdateDialog(String version, String changelog, String apkUrl) {
        if (activity.isFinishing() || apkUrl == null) return;

        new AlertDialog.Builder(activity)
                .setTitle("Nueva versión disponible: " + version)
                .setMessage(changelog.isEmpty() ? "Hay una actualización disponible." : changelog)
                .setPositiveButton("Actualizar", (d, w) -> downloadAndInstall(apkUrl))
                .setNegativeButton("Después", null)
                .show();
    }

    private void downloadAndInstall(String apkUrl) {
        Toast.makeText(activity, "Descargando actualización...", Toast.LENGTH_LONG).show();

        executor.execute(() -> {
            try {
                Request request = new Request.Builder().url(apkUrl).build();
                Response response = client.newCall(request).execute();

                if (!response.isSuccessful() || response.body() == null) {
                    mainHandler.post(() -> Toast.makeText(activity,
                            "Error al descargar", Toast.LENGTH_LONG).show());
                    return;
                }

                // Save to external files dir
                File apkFile = new File(activity.getExternalFilesDir(null), "update.apk");
                try (InputStream in = response.body().byteStream();
                     FileOutputStream out = new FileOutputStream(apkFile)) {
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                    }
                }
                response.close();

                mainHandler.post(() -> installApk(apkFile));

            } catch (Exception e) {
                Log.e(TAG, "Download failed", e);
                mainHandler.post(() -> Toast.makeText(activity,
                        "Error: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    private void installApk(File apkFile) {
        try {
            Uri apkUri = FileProvider.getUriForFile(activity,
                    activity.getPackageName() + ".fileprovider", apkFile);

            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(apkUri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(intent);
        } catch (Exception e) {
            Log.e(TAG, "Install failed", e);
            Toast.makeText(activity, "Error al instalar: " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    private int getLocalVersionCode() {
        try {
            return activity.getPackageManager()
                    .getPackageInfo(activity.getPackageName(), 0).versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    /** Parses "v2" or "v1.2" to integer versionCode (2, 12) */
    private int parseVersionCode(String tag) {
        try {
            String num = tag.replaceAll("[^0-9.]", "");
            if (num.contains(".")) {
                String[] parts = num.split("\\.");
                return Integer.parseInt(parts[0]) * 10 + Integer.parseInt(parts[1]);
            }
            return Integer.parseInt(num);
        } catch (Exception e) {
            return 0;
        }
    }
}
