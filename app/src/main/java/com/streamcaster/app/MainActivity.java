package com.streamcaster.app;

import android.content.Intent;
import android.os.Bundle;

import androidx.fragment.app.FragmentActivity;

/**
 * Main activity for the Android TV app.
 * Hosts the MainFragment (BrowseSupportFragment) which displays
 * the series catalog organized by categories.
 */
public class MainActivity extends FragmentActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.main_fragment_container, new HomeFragment())
                    .commit();
        }

        // Check for updates from GitHub
        new AppUpdater(this).checkForUpdate();
    }
}
