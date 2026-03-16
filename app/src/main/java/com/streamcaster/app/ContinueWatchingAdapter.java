package com.streamcaster.app;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.streamcaster.app.models.WatchProgress;

import java.util.List;
import java.util.Locale;

public class ContinueWatchingAdapter extends RecyclerView.Adapter<ContinueWatchingAdapter.ViewHolder> {

    private final List<WatchProgress> items;
    private final OnContinueClickListener listener;

    public interface OnContinueClickListener {
        void onContinueClick(WatchProgress progress);
    }

    public ContinueWatchingAdapter(List<WatchProgress> items, OnContinueClickListener listener) {
        this.items = items;
        this.listener = listener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_continue_watching, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        WatchProgress wp = items.get(position);

        holder.seriesTitle.setText(wp.getSeriesTitle());
        holder.episodeInfo.setText(String.format(Locale.US,
                "T%d E%d · %s · %s",
                wp.getSeasonNumber(), wp.getEpisodeNumber(),
                wp.getEpisodeTitle(), formatTime(wp.getPositionMs())));

        holder.progressBar.setProgress(wp.getProgressPercent());

        Glide.with(holder.itemView.getContext())
                .load(wp.getSeriesThumbnail())
                .centerCrop()
                .placeholder(R.drawable.default_card_image)
                .into(holder.image);

        holder.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onContinueClick(wp);
        });

        holder.itemView.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                v.animate().scaleX(1.05f).scaleY(1.05f).setDuration(150).start();
                v.setBackgroundResource(R.drawable.card_focus_border);
            } else {
                v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(150).start();
                v.setBackgroundResource(R.drawable.card_default_border);
            }
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    private String formatTime(long ms) {
        long totalSec = ms / 1000;
        long min = totalSec / 60;
        long sec = totalSec % 60;
        return String.format(Locale.US, "%d:%02d", min, sec);
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final ImageView image;
        final ProgressBar progressBar;
        final TextView seriesTitle;
        final TextView episodeInfo;

        ViewHolder(View itemView) {
            super(itemView);
            image = itemView.findViewById(R.id.continue_image);
            progressBar = itemView.findViewById(R.id.continue_progress);
            seriesTitle = itemView.findViewById(R.id.continue_series_title);
            episodeInfo = itemView.findViewById(R.id.continue_episode_info);
        }
    }
}
