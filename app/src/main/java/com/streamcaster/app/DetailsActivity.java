package com.streamcaster.app;

import android.os.Bundle;

import androidx.fragment.app.FragmentActivity;

/**
 * Activity that shows series details with seasons and episodes.
 */
public class DetailsActivity extends FragmentActivity {

    public static final String EXTRA_SERIES = "extra_series";
    public static final String EXTRA_RESUME_SEASON = "extra_resume_season";
    public static final String EXTRA_RESUME_EPISODE = "extra_resume_episode";
    public static final String EXTRA_RESUME_POSITION = "extra_resume_position";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_details);

        if (savedInstanceState == null) {
            SeriesDetailFragment fragment = new SeriesDetailFragment();
            fragment.setArguments(getIntent().getExtras());
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.details_fragment_container, fragment)
                    .commit();
        }
    }
}
