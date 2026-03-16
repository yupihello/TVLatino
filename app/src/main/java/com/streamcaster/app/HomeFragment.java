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
import com.streamcaster.app.models.Series;
import com.streamcaster.app.models.WatchProgress;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class HomeFragment extends Fragment implements SeriesCardAdapter.OnSeriesClickListener,
        ContinueWatchingAdapter.OnContinueClickListener {

    private static final String TAG = "HomeFragment";

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private View homeContent;
    private View loadingContainer;
    private HorizontalGridView seriesRow;
    private HorizontalGridView continueRow;
    private TextView continueLabel;
    private ImageView heroBackdrop;
    private TextView heroTitle;
    private TextView heroCategory;
    private Button heroPlayBtn;
    private Button heroDetailsBtn;
    private ContentCache cache;

    private List<Series> seriesList;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_home, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        homeContent = view.findViewById(R.id.home_content);
        loadingContainer = view.findViewById(R.id.loading_container);
        seriesRow = view.findViewById(R.id.series_row);
        continueRow = view.findViewById(R.id.continue_row);
        continueLabel = view.findViewById(R.id.continue_label);

        View heroBanner = view.findViewById(R.id.hero_banner);
        heroBackdrop = heroBanner.findViewById(R.id.hero_backdrop);
        heroTitle = heroBanner.findViewById(R.id.hero_title);
        heroCategory = heroBanner.findViewById(R.id.hero_category);
        heroPlayBtn = heroBanner.findViewById(R.id.hero_play_btn);
        heroDetailsBtn = heroBanner.findViewById(R.id.hero_details_btn);

        float density = getResources().getDisplayMetrics().density;
        seriesRow.setRowHeight((int) (240 * density));
        continueRow.setRowHeight((int) (310 * density));

        cache = new ContentCache(requireContext());
        loadContent();
    }

    @Override
    public void onResume() {
        super.onResume();
        // Refresh "Continue watching" when returning from playback
        refreshContinueWatching();
    }

    private void refreshContinueWatching() {
        if (cache == null) return;
        List<WatchProgress> recent = cache.getRecentlyWatched();
        if (recent.isEmpty()) {
            continueLabel.setVisibility(View.GONE);
            continueRow.setVisibility(View.GONE);
        } else {
            continueLabel.setVisibility(View.VISIBLE);
            continueRow.setVisibility(View.VISIBLE);
            ContinueWatchingAdapter adapter = new ContinueWatchingAdapter(recent, this);
            continueRow.setAdapter(adapter);

            // Update hero to show the most recently watched series
            WatchProgress latest = recent.get(0);
            heroTitle.setText(latest.getSeriesTitle());
            heroCategory.setText(String.format("T%d E%d · %s",
                    latest.getSeasonNumber(), latest.getEpisodeNumber(),
                    latest.getEpisodeTitle()));

            Glide.with(requireContext())
                    .load(latest.getSeriesThumbnail())
                    .centerCrop()
                    .into(heroBackdrop);

            // Hero buttons go to the series detail
            heroPlayBtn.setOnClickListener(v -> {
                Series s = new Series(latest.getSeriesTitle(), latest.getSeriesThumbnail(),
                        latest.getSeriesUrl(), "");
                onSeriesClick(s);
            });
            heroDetailsBtn.setOnClickListener(v -> {
                Series s = new Series(latest.getSeriesTitle(), latest.getSeriesThumbnail(),
                        latest.getSeriesUrl(), "");
                onSeriesClick(s);
            });
        }
    }

    private void loadContent() {
        if (cache.hasHomepageCache()) {
            List<Series> cached = cache.loadHomepage();
            if (!cached.isEmpty()) {
                Log.d(TAG, "Using cached homepage data");
                showData(cached);
                return;
            }
        }

        loadingContainer.setVisibility(View.VISIBLE);
        homeContent.setVisibility(View.GONE);

        executor.execute(() -> {
            try {
                TvSeriesLatinoScraper scraper = new TvSeriesLatinoScraper();
                List<Series> result = scraper.scrapeHomePage();

                if (!result.isEmpty()) {
                    cache.saveHomepage(result);
                }

                mainHandler.post(() -> {
                    if (getActivity() == null || !isAdded()) return;

                    if (result.isEmpty()) {
                        loadingContainer.setVisibility(View.GONE);
                        Toast.makeText(getActivity(),
                                R.string.error_loading, Toast.LENGTH_LONG).show();
                        return;
                    }

                    showData(result);
                });

            } catch (Exception e) {
                Log.e(TAG, "Error loading content", e);
                mainHandler.post(() -> {
                    if (getActivity() != null && isAdded()) {
                        loadingContainer.setVisibility(View.GONE);
                        Toast.makeText(getActivity(),
                                R.string.error_loading, Toast.LENGTH_LONG).show();
                    }
                });
            }
        });
    }

    private void showData(List<Series> data) {
        this.seriesList = data;
        loadingContainer.setVisibility(View.GONE);
        homeContent.setVisibility(View.VISIBLE);

        // Setup hero with first series (will be overridden if there's watch history)
        if (!data.isEmpty()) {
            Series hero = data.get(0);
            heroTitle.setText(hero.getTitle());
            heroCategory.setText(hero.getCategory());

            Glide.with(requireContext())
                    .load(hero.getThumbnailUrl())
                    .centerCrop()
                    .into(heroBackdrop);

            heroPlayBtn.setOnClickListener(v -> onSeriesClick(hero));
            heroDetailsBtn.setOnClickListener(v -> onSeriesClick(hero));
        }

        SeriesCardAdapter adapter = new SeriesCardAdapter(data, this);
        seriesRow.setAdapter(adapter);

        // Show continue watching row if there's history
        refreshContinueWatching();
    }

    @Override
    public void onSeriesClick(Series series) {
        Intent intent = new Intent(getActivity(), DetailsActivity.class);
        intent.putExtra(DetailsActivity.EXTRA_SERIES, series);
        startActivity(intent);
    }

    @Override
    public void onContinueClick(WatchProgress progress) {
        // Open series detail with resume info so it can auto-play from where user left off
        Series s = new Series(progress.getSeriesTitle(), progress.getSeriesThumbnail(),
                progress.getSeriesUrl(), "");
        Intent intent = new Intent(getActivity(), DetailsActivity.class);
        intent.putExtra(DetailsActivity.EXTRA_SERIES, s);
        intent.putExtra(DetailsActivity.EXTRA_RESUME_SEASON, progress.getSeasonNumber());
        intent.putExtra(DetailsActivity.EXTRA_RESUME_EPISODE, progress.getEpisodeNumber());
        intent.putExtra(DetailsActivity.EXTRA_RESUME_POSITION, progress.getPositionMs());
        startActivity(intent);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        executor.shutdown();
    }
}
