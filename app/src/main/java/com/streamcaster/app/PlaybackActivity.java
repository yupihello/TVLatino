package com.streamcaster.app;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.OptIn;
import androidx.fragment.app.FragmentActivity;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.LoadControl;
import androidx.media3.ui.PlayerView;

import com.streamcaster.app.models.Episode;
import com.streamcaster.app.models.WatchProgress;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@OptIn(markerClass = UnstableApi.class)
public class PlaybackActivity extends FragmentActivity {

    private static final String TAG = "PlaybackActivity";
    private static final long SEEK_INCREMENT_MS = 10_000;
    private static final long OVERLAY_HIDE_DELAY_MS = 5000;
    private static final long AUTOPLAY_THRESHOLD_MS = 10_000; // 10s before end
    private static final long PROGRESS_SAVE_INTERVAL_MS = 5000;

    public static final String EXTRA_VIDEO_URL = "extra_video_url";
    public static final String EXTRA_VIDEO_TITLE = "extra_video_title";
    public static final String EXTRA_SERIES_URL = "extra_series_url";
    public static final String EXTRA_SERIES_TITLE = "extra_series_title";
    public static final String EXTRA_SERIES_THUMBNAIL = "extra_series_thumbnail";
    public static final String EXTRA_EPISODE_LIST = "extra_episode_list";
    public static final String EXTRA_CURRENT_INDEX = "extra_current_index";
    public static final String EXTRA_RESUME_POSITION = "extra_resume_position";
    public static final String EXTRA_SHUFFLE = "extra_shuffle";

    private ExoPlayer player;
    private PlayerView playerView;
    private WebView resolverWebView;
    private ProgressBar loadingProgress;
    private TextView loadingText;
    private boolean videoResolved = false;

    // Overlay views
    private View overlayRoot;
    private TextView overlayTitle;
    private TextView timeCurrent;
    private TextView timeTotal;
    private SeekBar seekBar;
    private ImageButton playPauseBtn;
    private ImageButton rewindBtn;
    private ImageButton forwardBtn;
    private boolean overlayVisible = false;
    private final Handler overlayHandler = new Handler(Looper.getMainLooper());
    private final Handler seekUpdateHandler = new Handler(Looper.getMainLooper());

    // Episode data for autoplay
    private String seriesUrl;
    private String seriesTitle;
    private String seriesThumbnail;
    private ArrayList<Episode> episodeList;
    private int currentEpisodeIndex = -1;
    private boolean autoplayTriggered = false;
    private long resumePosition = 0;
    private ContentCache cache;
    private boolean triedFallback = false;
    private boolean shuffleMode = false;
    private final java.util.Random random = new java.util.Random();

    private final Runnable hideOverlayRunnable = () -> hideOverlay();

    // Runs only while overlay is visible — updates seekbar UI
    private final Runnable seekUpdateRunnable = new Runnable() {
        @Override
        public void run() {
            updateSeekBar();
            if (overlayVisible && player != null && player.isPlaying()) {
                seekUpdateHandler.postDelayed(this, 1000);
            }
        }
    };

    // Runs always while playing — checks autoplay and saves progress
    private final Runnable backgroundRunnable = new Runnable() {
        @Override
        public void run() {
            checkAutoplay();
            saveCurrentProgress();
            if (player != null && player.isPlaying()) {
                seekUpdateHandler.postDelayed(this, PROGRESS_SAVE_INTERVAL_MS);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);

        setContentView(R.layout.activity_playback);

        playerView = findViewById(R.id.player_view);
        loadingProgress = findViewById(R.id.loading_progress);
        loadingText = findViewById(R.id.loading_text);

        overlayRoot = findViewById(R.id.player_overlay_root);
        overlayTitle = findViewById(R.id.overlay_title);
        timeCurrent = findViewById(R.id.overlay_time_current);
        timeTotal = findViewById(R.id.overlay_time_total);
        seekBar = findViewById(R.id.overlay_seekbar);
        playPauseBtn = findViewById(R.id.overlay_play_pause);
        rewindBtn = findViewById(R.id.overlay_rewind);
        forwardBtn = findViewById(R.id.overlay_forward);

        cache = new ContentCache(this);

        // Back button in overlay
        findViewById(R.id.overlay_back_btn).setOnClickListener(v -> {
            saveCurrentProgress();
            finish();
        });

        setupOverlayControls();

        // Read intent extras
        Intent intent = getIntent();
        String videoUrl = intent.getStringExtra(EXTRA_VIDEO_URL);
        String videoTitle = intent.getStringExtra(EXTRA_VIDEO_TITLE);
        seriesUrl = intent.getStringExtra(EXTRA_SERIES_URL);
        seriesTitle = intent.getStringExtra(EXTRA_SERIES_TITLE);
        seriesThumbnail = intent.getStringExtra(EXTRA_SERIES_THUMBNAIL);
        resumePosition = intent.getLongExtra(EXTRA_RESUME_POSITION, 0);

        if (intent.hasExtra(EXTRA_EPISODE_LIST)) {
            episodeList = (ArrayList<Episode>) intent.getSerializableExtra(EXTRA_EPISODE_LIST);
            currentEpisodeIndex = intent.getIntExtra(EXTRA_CURRENT_INDEX, -1);
        }
        shuffleMode = intent.getBooleanExtra(EXTRA_SHUFFLE, false);

        if (videoTitle != null) {
            loadingText.setText("Cargando: " + videoTitle);
            overlayTitle.setText(videoTitle);
        }

        if (videoUrl != null) {
            resolveEmbedUrl(videoUrl);
        } else {
            finish();
        }
    }

    private void setupOverlayControls() {
        playPauseBtn.setOnClickListener(v -> togglePlayPause());
        rewindBtn.setOnClickListener(v -> seekRelative(-SEEK_INCREMENT_MS));
        forwardBtn.setOnClickListener(v -> seekRelative(SEEK_INCREMENT_MS));

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser && player != null) {
                    long duration = player.getDuration();
                    if (duration > 0) {
                        player.seekTo((long) progress * duration / 1000);
                    }
                }
                resetOverlayTimer();
            }
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                overlayHandler.removeCallbacks(hideOverlayRunnable);
            }
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                resetOverlayTimer();
            }
        });
    }

    // --- Autoplay logic ---

    private void checkAutoplay() {
        if (autoplayTriggered || player == null || episodeList == null || currentEpisodeIndex < 0)
            return;

        long pos = player.getCurrentPosition();
        long dur = player.getDuration();
        if (dur <= 0) return;

        long remaining = dur - pos;
        if (remaining <= AUTOPLAY_THRESHOLD_MS && remaining > 0) {
            Episode next = getNextEpisode();
            if (next != null) {
                autoplayTriggered = true;
                int nextIdx = shuffleMode ? currentEpisodeIndex : currentEpisodeIndex + 1;
                Log.d(TAG, (shuffleMode ? "Shuffle" : "Autoplay")
                        + ": starting next episode: " + next.getTitle());
                playEpisode(next, nextIdx);
            }
        }
    }

    private Episode getNextEpisode() {
        if (episodeList == null || episodeList.isEmpty()) return null;

        if (shuffleMode) {
            // Pick a random episode different from current
            if (episodeList.size() == 1) return episodeList.get(0);
            int nextIndex;
            do {
                nextIndex = random.nextInt(episodeList.size());
            } while (nextIndex == currentEpisodeIndex);
            currentEpisodeIndex = nextIndex;
            return episodeList.get(nextIndex);
        }

        // Sequential mode
        if (currentEpisodeIndex < 0) return null;
        int nextIndex = currentEpisodeIndex + 1;
        if (nextIndex < episodeList.size()) {
            return episodeList.get(nextIndex);
        }
        return null;
    }

    private void playEpisode(Episode episode, int newIndex) {
        // Save progress of current episode as finished
        saveCurrentProgress();

        // Reset state for new episode
        currentEpisodeIndex = newIndex;
        autoplayTriggered = false;
        videoResolved = false;
        resumePosition = 0;

        String serverUrl = episode.getBestServerUrl();
        if (serverUrl == null || serverUrl.isEmpty()) return;

        // Update UI
        overlayTitle.setText(episode.getTitle());
        loadingText.setText("Cargando: " + episode.getTitle());

        // Stop current player
        if (player != null) {
            player.stop();
            player.release();
            player = null;
        }
        playerView.setVisibility(View.GONE);
        View loadingLayout = findViewById(R.id.loading_layout);
        if (loadingLayout != null) loadingLayout.setVisibility(View.VISIBLE);
        loadingProgress.setVisibility(View.VISIBLE);
        loadingText.setVisibility(View.VISIBLE);

        triedFallback = false;
        resolveEmbedUrl(serverUrl);
    }

    // --- Progress tracking ---

    private void saveCurrentProgress() {
        if (player == null || seriesUrl == null || shuffleMode) return;
        long pos = player.getCurrentPosition();
        long dur = player.getDuration();
        if (dur <= 0) return;

        Episode currentEp = (episodeList != null && currentEpisodeIndex >= 0
                && currentEpisodeIndex < episodeList.size())
                ? episodeList.get(currentEpisodeIndex) : null;

        int season = currentEp != null ? currentEp.getSeasonNumber() : 1;
        int epNum = currentEp != null ? currentEp.getEpisodeNumber() : 1;
        String epTitle = currentEp != null ? currentEp.getTitle() : "";

        WatchProgress progress = new WatchProgress(
                seriesUrl, seriesTitle != null ? seriesTitle : "",
                seriesThumbnail != null ? seriesThumbnail : "",
                season, epNum, epTitle, pos, dur);

        cache.saveWatchProgress(progress);
    }

    // --- Overlay ---

    private void showOverlay() {
        overlayRoot.setVisibility(View.VISIBLE);
        overlayVisible = true;
        updateSeekBar();
        updatePlayPauseIcon();
        seekUpdateHandler.post(seekUpdateRunnable);
        resetOverlayTimer();
    }

    private void hideOverlay() {
        overlayRoot.setVisibility(View.GONE);
        overlayVisible = false;
        seekUpdateHandler.removeCallbacks(seekUpdateRunnable);
    }

    private void resetOverlayTimer() {
        overlayHandler.removeCallbacks(hideOverlayRunnable);
        overlayHandler.postDelayed(hideOverlayRunnable, OVERLAY_HIDE_DELAY_MS);
    }

    private void togglePlayPause() {
        if (player != null) {
            player.setPlayWhenReady(!player.getPlayWhenReady());
            updatePlayPauseIcon();
            resetOverlayTimer();
        }
    }

    private void seekRelative(long deltaMs) {
        if (player != null) {
            long newPos = Math.max(0, Math.min(player.getCurrentPosition() + deltaMs,
                    player.getDuration()));
            player.seekTo(newPos);
            if (!overlayVisible) showOverlay();
            else resetOverlayTimer();
            updateSeekBar();
        }
    }

    private void updatePlayPauseIcon() {
        if (player != null && player.getPlayWhenReady()) {
            playPauseBtn.setImageResource(android.R.drawable.ic_media_pause);
        } else {
            playPauseBtn.setImageResource(android.R.drawable.ic_media_play);
        }
    }

    private void updateSeekBar() {
        if (player == null) return;
        long pos = player.getCurrentPosition();
        long dur = player.getDuration();
        if (dur > 0) {
            seekBar.setProgress((int) (pos * 1000 / dur));
        }
        timeCurrent.setText(formatTime(pos));
        timeTotal.setText(formatTime(dur));
    }

    private String formatTime(long ms) {
        if (ms < 0) ms = 0;
        long totalSec = ms / 1000;
        long hours = totalSec / 3600;
        long minutes = (totalSec % 3600) / 60;
        long seconds = totalSec % 60;
        if (hours > 0) {
            return String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format(Locale.US, "%d:%02d", minutes, seconds);
    }

    // --- URL pre-resolver for .com SPA embeds ---

    /**
     * For .com embed URLs, calls the API to get the f75s.com frame URL
     * (which is a classic player the WebView can handle).
     * For .xyz or other URLs, loads directly in WebView.
     */
    private void resolveEmbedUrl(String embedUrl) {
        if (embedUrl.contains("pokemonlaserielatino.com/e/")) {
            // Extract slug from URL: /e/{slug}/optional_title
            String slug = embedUrl.replaceAll(".*\\.com/e/", "").replaceAll("/.*", "");
            String apiUrl = "https://pokemonlaserielatino.com/api/videos/" + slug + "/embed/details";

            Log.d(TAG, "Resolving .com embed via API: " + apiUrl);
            ExecutorService exec = Executors.newSingleThreadExecutor();
            exec.execute(() -> {
                try {
                    okhttp3.Request request = new okhttp3.Request.Builder()
                            .url(apiUrl)
                            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                                    "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                            .header("Referer", embedUrl)
                            .build();

                    okhttp3.OkHttpClient client = new okhttp3.OkHttpClient();
                    okhttp3.Response response = client.newCall(request).execute();
                    String body = response.body() != null ? response.body().string() : "";
                    response.close();

                    JSONObject json = new JSONObject(body);
                    String frameUrl = json.optString("embed_frame_url", "");

                    // Load the .com SPA directly — it will handle challenge/attest
                    // and eventually load the HLS URL which we intercept
                    Log.d(TAG, "Loading .com SPA directly (frame: " + frameUrl + ")");
                    runOnUiThread(() -> resolveAndPlay(embedUrl));
                } catch (Exception e) {
                    Log.e(TAG, "API resolve failed, loading .com directly", e);
                    runOnUiThread(() -> resolveAndPlay(embedUrl));
                }
            });
            exec.shutdown();
        } else {
            resolveAndPlay(embedUrl);
        }
    }

    // --- WebView resolver + ExoPlayer ---

    @SuppressLint("SetJavaScriptEnabled")
    private void resolveAndPlayHtml(String html, String baseUrl) {
        resolverWebView = new WebView(this);
        resolverWebView.setVisibility(View.GONE);

        FrameLayout container = findViewById(R.id.webview_container);
        container.addView(resolverWebView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        WebSettings settings = resolverWebView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setUserAgentString(
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");

        resolverWebView.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view,
                                                               WebResourceRequest request) {
                String url = request.getUrl().toString();
                String urlLower = url.toLowerCase();

                if (videoResolved) return super.shouldInterceptRequest(view, request);

                if (urlLower.contains(".m3u8") && urlLower.contains("master")) {
                    videoResolved = true;
                    Log.d(TAG, "Resolved HLS master (iframe): " + url);
                    runOnUiThread(() -> startExoPlayer(url));
                } else if (urlLower.contains(".m3u8")) {
                    videoResolved = true;
                    Log.d(TAG, "Resolved HLS (iframe): " + url);
                    runOnUiThread(() -> startExoPlayer(url));
                } else if (urlLower.contains(".mp4") && !urlLower.contains("thumb")
                        && !urlLower.contains("poster") && !urlLower.contains("preview")
                        && (urlLower.contains("cdn") || urlLower.contains("video")
                            || urlLower.contains("stream") || urlLower.contains("media")
                            || urlLower.contains("deliver") || urlLower.contains("edge"))) {
                    videoResolved = true;
                    Log.d(TAG, "Resolved MP4 (iframe): " + url);
                    runOnUiThread(() -> startExoPlayerDirect(url));
                }

                return super.shouldInterceptRequest(view, request);
            }
        });

        resolverWebView.postDelayed(() -> {
            if (!videoResolved) {
                Log.w(TAG, "Timeout (iframe) waiting for video URL");
                Toast.makeText(this, R.string.error_extracting, Toast.LENGTH_LONG).show();
                finish();
            }
        }, 30000);

        Log.d(TAG, "Loading iframe HTML with base: " + baseUrl);
        resolverWebView.loadDataWithBaseURL(baseUrl, html, "text/html", "UTF-8", null);
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void resolveAndPlay(String embedUrl) {
        resolverWebView = new WebView(this);
        resolverWebView.setVisibility(View.INVISIBLE);

        // Use full size so SPAs can render (invisible, not 1x1 which breaks React)
        FrameLayout container = findViewById(R.id.webview_container);
        container.setVisibility(View.INVISIBLE);
        container.addView(resolverWebView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        WebSettings settings = resolverWebView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        settings.setAllowFileAccess(true);
        settings.setAllowContentAccess(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setUserAgentString(
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");

        resolverWebView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                // Auto-submit videok.pro play button form
                if (url != null && url.contains("videok.pro/e/")) {
                    Log.d(TAG, "videok.pro loaded, auto-submitting form");
                    view.evaluateJavascript(
                            "if(document.getElementById('F1')){document.getElementById('F1').submit();}",
                            null);
                }
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view,
                                                               WebResourceRequest request) {
                String url = request.getUrl().toString();
                String urlLower = url.toLowerCase();

                if (videoResolved) return super.shouldInterceptRequest(view, request);

                // Priority 1: HLS master playlist
                if (urlLower.contains(".m3u8") && urlLower.contains("master")) {
                    videoResolved = true;
                    Log.d(TAG, "Resolved HLS master: " + url);
                    runOnUiThread(() -> startExoPlayer(url));
                }
                // Priority 2: Any HLS playlist
                else if (urlLower.contains(".m3u8")) {
                    videoResolved = true;
                    Log.d(TAG, "Resolved HLS: " + url);
                    runOnUiThread(() -> startExoPlayer(url));
                }
                // Priority 3: MP4 video files (from CDN, not thumbnails/images)
                else if (urlLower.contains(".mp4") && !urlLower.contains("thumb")
                        && !urlLower.contains("poster") && !urlLower.contains("preview")
                        && (urlLower.contains("cdn") || urlLower.contains("video")
                            || urlLower.contains("stream") || urlLower.contains("media")
                            || urlLower.contains("deliver") || urlLower.contains("edge"))) {
                    videoResolved = true;
                    Log.d(TAG, "Resolved MP4: " + url);
                    runOnUiThread(() -> startExoPlayerDirect(url));
                }

                return super.shouldInterceptRequest(view, request);
            }
        });

        // Try fallback server after 15s, give up after 30s total
        resolverWebView.postDelayed(() -> {
            if (!videoResolved && !triedFallback) {
                triedFallback = true;
                Episode ep = (episodeList != null && currentEpisodeIndex >= 0
                        && currentEpisodeIndex < episodeList.size())
                        ? episodeList.get(currentEpisodeIndex) : null;
                String fallback = ep != null ? ep.getFallbackServerUrl() : null;
                if (fallback != null && !fallback.isEmpty()) {
                    Log.d(TAG, "Primary timeout, trying fallback: " + fallback);
                    // Clean up current WebView
                    resolverWebView.stopLoading();
                    FrameLayout c = findViewById(R.id.webview_container);
                    c.removeView(resolverWebView);
                    resolverWebView.destroy();
                    resolverWebView = null;
                    resolveAndPlay(fallback);
                }
            }
        }, 15000);

        resolverWebView.postDelayed(() -> {
            if (!videoResolved) {
                Log.w(TAG, "Timeout waiting for video URL");
                Toast.makeText(this, R.string.error_extracting, Toast.LENGTH_LONG).show();
                finish();
            }
        }, 30000);

        Log.d(TAG, "Loading embed URL: " + embedUrl);
        resolverWebView.loadUrl(embedUrl);
    }

    private void startExoPlayer(String hlsUrl) {
        loadingProgress.setVisibility(View.GONE);
        loadingText.setVisibility(View.GONE);
        View loadingLayout = findViewById(R.id.loading_layout);
        if (loadingLayout != null) loadingLayout.setVisibility(View.GONE);

        playerView.setVisibility(View.VISIBLE);

        if (resolverWebView != null) {
            resolverWebView.stopLoading();
            FrameLayout container = findViewById(R.id.webview_container);
            container.removeView(resolverWebView);
            resolverWebView.destroy();
            resolverWebView = null;
        }

        LoadControl loadControl = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(15_000, 60_000, 2_500, 5_000)
                .setPrioritizeTimeOverSizeThresholds(true)
                .setBackBuffer(10_000, false)
                .build();

        player = new ExoPlayer.Builder(this)
                .setLoadControl(loadControl)
                .build();

        playerView.setPlayer(player);
        playerView.setUseController(false);

        player.addListener(new Player.Listener() {
            @Override
            public void onPlayerError(PlaybackException error) {
                Log.e(TAG, "Playback error: " + error.getMessage(), error);
                Toast.makeText(PlaybackActivity.this,
                        R.string.error_playback, Toast.LENGTH_LONG).show();
            }

            @Override
            public void onPlaybackStateChanged(int playbackState) {
                if (playbackState == Player.STATE_ENDED) {
                    saveCurrentProgress();
                    // If autoplay didn't trigger (no next episode), finish
                    if (!autoplayTriggered) {
                        finish();
                    }
                }
            }

            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                updatePlayPauseIcon();
                if (isPlaying) {
                    seekUpdateHandler.post(backgroundRunnable);
                } else {
                    seekUpdateHandler.removeCallbacks(backgroundRunnable);
                }
            }
        });

        Map<String, String> headers = new HashMap<>();
        headers.put("Referer", "https://f75s.com/");
        headers.put("Origin", "https://f75s.com");

        MediaItem mediaItem = new MediaItem.Builder()
                .setUri(Uri.parse(hlsUrl))
                .setMimeType("application/x-mpegurl")
                .build();

        androidx.media3.datasource.DefaultHttpDataSource.Factory httpDataSourceFactory =
                new androidx.media3.datasource.DefaultHttpDataSource.Factory()
                        .setDefaultRequestProperties(headers)
                        .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                                "Chrome/120.0.0.0 Safari/537.36")
                        .setConnectTimeoutMs(15000)
                        .setReadTimeoutMs(15000)
                        .setAllowCrossProtocolRedirects(true);

        androidx.media3.exoplayer.source.MediaSource mediaSource =
                new androidx.media3.exoplayer.hls.HlsMediaSource.Factory(httpDataSourceFactory)
                        .createMediaSource(mediaItem);

        player.setMediaSource(mediaSource);
        player.prepare();

        // Resume from saved position
        if (resumePosition > 0) {
            player.seekTo(resumePosition);
        }

        player.setPlayWhenReady(true);
        showOverlay();

        Log.d(TAG, "ExoPlayer started with HLS: " + hlsUrl);
    }

    private void startExoPlayerDirect(String videoUrl) {
        loadingProgress.setVisibility(View.GONE);
        loadingText.setVisibility(View.GONE);
        View loadingLayout = findViewById(R.id.loading_layout);
        if (loadingLayout != null) loadingLayout.setVisibility(View.GONE);

        playerView.setVisibility(View.VISIBLE);

        if (resolverWebView != null) {
            resolverWebView.stopLoading();
            FrameLayout container = findViewById(R.id.webview_container);
            container.removeView(resolverWebView);
            resolverWebView.destroy();
            resolverWebView = null;
        }

        LoadControl loadControl = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(15_000, 60_000, 2_500, 5_000)
                .setPrioritizeTimeOverSizeThresholds(true)
                .setBackBuffer(10_000, false)
                .build();

        player = new ExoPlayer.Builder(this)
                .setLoadControl(loadControl)
                .build();

        playerView.setPlayer(player);
        playerView.setUseController(false);

        player.addListener(new Player.Listener() {
            @Override
            public void onPlayerError(PlaybackException error) {
                Log.e(TAG, "Playback error: " + error.getMessage(), error);
                Toast.makeText(PlaybackActivity.this,
                        R.string.error_playback, Toast.LENGTH_LONG).show();
            }

            @Override
            public void onPlaybackStateChanged(int playbackState) {
                if (playbackState == Player.STATE_ENDED) {
                    saveCurrentProgress();
                    if (!autoplayTriggered) finish();
                }
            }

            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                updatePlayPauseIcon();
                if (isPlaying) {
                    seekUpdateHandler.post(backgroundRunnable);
                } else {
                    seekUpdateHandler.removeCallbacks(backgroundRunnable);
                }
            }
        });

        // Direct MP4 playback with headers
        Map<String, String> headers = new HashMap<>();
        headers.put("Referer", "https://pokemonlaserielatino.com/");

        androidx.media3.datasource.DefaultHttpDataSource.Factory httpDataSourceFactory =
                new androidx.media3.datasource.DefaultHttpDataSource.Factory()
                        .setDefaultRequestProperties(headers)
                        .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                                "Chrome/120.0.0.0 Safari/537.36")
                        .setConnectTimeoutMs(15000)
                        .setReadTimeoutMs(15000)
                        .setAllowCrossProtocolRedirects(true);

        MediaItem mediaItem = MediaItem.fromUri(Uri.parse(videoUrl));

        androidx.media3.exoplayer.source.MediaSource mediaSource =
                new androidx.media3.exoplayer.source.ProgressiveMediaSource.Factory(httpDataSourceFactory)
                        .createMediaSource(mediaItem);

        player.setMediaSource(mediaSource);
        player.prepare();

        if (resumePosition > 0) player.seekTo(resumePosition);
        player.setPlayWhenReady(true);
        showOverlay();

        Log.d(TAG, "ExoPlayer started with MP4: " + videoUrl);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            saveCurrentProgress();
            finish();
            return true;
        }

        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            if (player != null) {
                if (!overlayVisible) showOverlay();
                else togglePlayPause();
                return true;
            }
        }

        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) {
            if (player != null) { seekRelative(-SEEK_INCREMENT_MS); return true; }
        }

        if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
            if (player != null) { seekRelative(SEEK_INCREMENT_MS); return true; }
        }

        if (player != null && !overlayVisible) showOverlay();
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onPause() {
        super.onPause();
        saveCurrentProgress();
        if (player != null) player.setPlayWhenReady(false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (player != null) player.setPlayWhenReady(true);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        overlayHandler.removeCallbacksAndMessages(null);
        seekUpdateHandler.removeCallbacksAndMessages(null);
        if (player != null) { player.release(); player = null; }
        if (resolverWebView != null) {
            resolverWebView.stopLoading();
            resolverWebView.destroy();
            resolverWebView = null;
        }
    }
}
