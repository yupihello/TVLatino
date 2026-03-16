package com.streamcaster.app;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.leanback.widget.HorizontalGridView;

import com.bumptech.glide.Glide;
import com.streamcaster.app.models.Episode;
import com.streamcaster.app.models.Series;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SeriesDetailFragment extends Fragment
        implements SeasonTabAdapter.OnSeasonSelectedListener,
        EpisodeListAdapter.OnEpisodeClickListener {

    private static final String TAG = "SeriesDetailFragment";

    private Series series;
    private Map<Integer, List<Episode>> seasonEpisodes;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private ImageView backdrop;
    private TextView titleText;
    private TextView categoryText;
    private HorizontalGridView seasonTabsRecycler;
    private HorizontalGridView episodesRecycler;
    private View loadingView;
    private EpisodeListAdapter episodeAdapter;
    private ContentCache cache;
    private Button playBtn;
    private Button liveBtn;
    private android.widget.ImageButton backBtn;
    private int resumeSeason = -1;
    private int resumeEpisodeNum = -1;
    private long resumePositionMs = 0;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Bundle args = getArguments();
        if (args != null) {
            series = (Series) args.getSerializable(DetailsActivity.EXTRA_SERIES);
            resumeSeason = args.getInt(DetailsActivity.EXTRA_RESUME_SEASON, -1);
            resumeEpisodeNum = args.getInt(DetailsActivity.EXTRA_RESUME_EPISODE, -1);
            resumePositionMs = args.getLong(DetailsActivity.EXTRA_RESUME_POSITION, 0);
        }
        if (series == null && getActivity() != null) {
            getActivity().finish();
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_series_detail, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        backdrop = view.findViewById(R.id.detail_backdrop);
        titleText = view.findViewById(R.id.detail_title);
        categoryText = view.findViewById(R.id.detail_category);
        seasonTabsRecycler = view.findViewById(R.id.season_tabs_recycler);
        episodesRecycler = view.findViewById(R.id.episodes_recycler);
        loadingView = view.findViewById(R.id.detail_loading);
        playBtn = view.findViewById(R.id.detail_play_btn);
        liveBtn = view.findViewById(R.id.detail_live_btn);
        backBtn = view.findViewById(R.id.detail_back_btn);

        backBtn.setOnClickListener(v -> {
            if (getActivity() != null) getActivity().onBackPressed();
        });

        cache = new ContentCache(requireContext());

        if (series == null) return;

        titleText.setText(series.getTitle());
        categoryText.setText(series.getCategory());

        Glide.with(requireContext())
                .load(series.getThumbnailUrl())
                .centerCrop()
                .into(backdrop);

        float density = getResources().getDisplayMetrics().density;
        seasonTabsRecycler.setRowHeight((int) (56 * density));
        episodesRecycler.setRowHeight((int) (290 * density));

        episodeAdapter = new EpisodeListAdapter(this);
        episodesRecycler.setAdapter(episodeAdapter);

        // Play button: resume or start first episode
        playBtn.setOnClickListener(v -> playFirstEpisode());

        // Live button: shuffle play random episodes continuously
        liveBtn.setOnClickListener(v -> startLiveMode());

        loadEpisodes();
    }

    private void playFirstEpisode() {
        if (seasonEpisodes == null || seasonEpisodes.isEmpty()) return;
        // Get first episode of first season
        for (List<Episode> episodes : seasonEpisodes.values()) {
            if (!episodes.isEmpty()) {
                launchPlayback(episodes.get(0), 0, false);
                return;
            }
        }
    }

    private void startLiveMode() {
        if (seasonEpisodes == null || seasonEpisodes.isEmpty()) return;

        // Build flat list of all episodes
        ArrayList<Episode> allEpisodes = buildAllEpisodesList();
        if (allEpisodes.isEmpty()) return;

        // Pick a random starting episode
        int randomIndex = new Random().nextInt(allEpisodes.size());
        Episode randomEp = allEpisodes.get(randomIndex);

        String serverUrl = randomEp.getBestServerUrl();
        if (serverUrl == null || serverUrl.isEmpty()) return;

        Intent intent = new Intent(getActivity(), PlaybackActivity.class);
        intent.putExtra(PlaybackActivity.EXTRA_VIDEO_URL, serverUrl);
        intent.putExtra(PlaybackActivity.EXTRA_VIDEO_TITLE, randomEp.getTitle());
        intent.putExtra(PlaybackActivity.EXTRA_SERIES_URL, series.getPageUrl());
        intent.putExtra(PlaybackActivity.EXTRA_SERIES_TITLE, series.getTitle());
        intent.putExtra(PlaybackActivity.EXTRA_SERIES_THUMBNAIL, series.getThumbnailUrl());
        intent.putExtra(PlaybackActivity.EXTRA_EPISODE_LIST, allEpisodes);
        intent.putExtra(PlaybackActivity.EXTRA_CURRENT_INDEX, randomIndex);
        intent.putExtra(PlaybackActivity.EXTRA_SHUFFLE, true);
        startActivity(intent);
    }

    private void loadEpisodes() {
        if (cache.hasSeriesCache(series.getPageUrl())) {
            Map<Integer, List<Episode>> cached = cache.loadSeriesEpisodes(series.getPageUrl());
            if (!cached.isEmpty()) {
                Log.d(TAG, "Using cached episodes for: " + series.getTitle());
                seasonEpisodes = cached;
                showEpisodes();
                return;
            }
        }

        loadingView.setVisibility(View.VISIBLE);

        executor.execute(() -> {
            try {
                TvSeriesLatinoScraper scraper = new TvSeriesLatinoScraper();
                seasonEpisodes = scraper.scrapeSeriesPage(
                        series.getPageUrl(), series.getThumbnailUrl());

                if (!seasonEpisodes.isEmpty()) {
                    cache.saveSeriesEpisodes(series.getPageUrl(), seasonEpisodes);
                }

                mainHandler.post(() -> {
                    if (getActivity() == null || !isAdded()) return;

                    loadingView.setVisibility(View.GONE);

                    if (seasonEpisodes.isEmpty()) {
                        Toast.makeText(getActivity(),
                                R.string.no_episodes, Toast.LENGTH_LONG).show();
                        return;
                    }

                    showEpisodes();
                });

            } catch (Exception e) {
                Log.e(TAG, "Error loading episodes", e);
                mainHandler.post(() -> {
                    if (getActivity() != null && isAdded()) {
                        loadingView.setVisibility(View.GONE);
                        Toast.makeText(getActivity(),
                                R.string.error_loading, Toast.LENGTH_LONG).show();
                    }
                });
            }
        });
    }

    private void showEpisodes() {
        loadingView.setVisibility(View.GONE);

        List<Integer> seasons = new ArrayList<>(seasonEpisodes.keySet());
        SeasonTabAdapter tabAdapter = new SeasonTabAdapter(seasons, this);
        seasonTabsRecycler.setAdapter(tabAdapter);

        if (!seasons.isEmpty()) {
            onSeasonSelected(seasons.get(0));
        }

        // Auto-resume if coming from "Continue watching"
        if (resumeSeason > 0 && resumeEpisodeNum > 0) {
            Episode resumeEp = findEpisode(resumeSeason, resumeEpisodeNum);
            if (resumeEp != null) {
                resumeSeason = -1; // Only auto-resume once
                launchPlayback(resumeEp, resumePositionMs, false);
            }
        }
    }

    private Episode findEpisode(int season, int episodeNum) {
        if (seasonEpisodes == null) return null;
        List<Episode> episodes = seasonEpisodes.get(season);
        if (episodes == null) return null;
        for (Episode ep : episodes) {
            if (ep.getEpisodeNumber() == episodeNum) return ep;
        }
        return null;
    }

    @Override
    public void onSeasonSelected(int seasonNumber) {
        if (seasonEpisodes == null) return;
        List<Episode> episodes = seasonEpisodes.get(seasonNumber);
        if (episodes != null) {
            episodeAdapter.setEpisodes(episodes);
            episodesRecycler.scrollToPosition(0);
        }
    }

    @Override
    public void onEpisodeClick(Episode episode) {
        launchPlayback(episode, 0, false);
    }

    private void launchPlayback(Episode episode, long resumePosition, boolean shuffle) {
        String serverUrl = episode.getBestServerUrl();

        if (serverUrl == null || serverUrl.isEmpty()) {
            Toast.makeText(getActivity(),
                    R.string.error_extracting, Toast.LENGTH_LONG).show();
            return;
        }

        ArrayList<Episode> allEpisodes = buildAllEpisodesList();
        int currentIndex = allEpisodes.indexOf(episode);

        Intent intent = new Intent(getActivity(), PlaybackActivity.class);
        intent.putExtra(PlaybackActivity.EXTRA_VIDEO_URL, serverUrl);
        intent.putExtra(PlaybackActivity.EXTRA_VIDEO_TITLE, episode.getTitle());
        intent.putExtra(PlaybackActivity.EXTRA_SERIES_URL, series.getPageUrl());
        intent.putExtra(PlaybackActivity.EXTRA_SERIES_TITLE, series.getTitle());
        intent.putExtra(PlaybackActivity.EXTRA_SERIES_THUMBNAIL, series.getThumbnailUrl());
        intent.putExtra(PlaybackActivity.EXTRA_EPISODE_LIST, allEpisodes);
        intent.putExtra(PlaybackActivity.EXTRA_CURRENT_INDEX, currentIndex);
        intent.putExtra(PlaybackActivity.EXTRA_RESUME_POSITION, resumePosition);
        intent.putExtra(PlaybackActivity.EXTRA_SHUFFLE, shuffle);
        startActivity(intent);
    }

    private ArrayList<Episode> buildAllEpisodesList() {
        ArrayList<Episode> all = new ArrayList<>();
        if (seasonEpisodes != null) {
            for (List<Episode> seasonList : seasonEpisodes.values()) {
                all.addAll(seasonList);
            }
        }
        return all;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        executor.shutdown();
    }
}
