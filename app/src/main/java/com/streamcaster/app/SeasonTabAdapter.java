package com.streamcaster.app;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

public class SeasonTabAdapter extends RecyclerView.Adapter<SeasonTabAdapter.ViewHolder> {

    private final List<Integer> seasons;
    private int selectedPosition = 0;
    private OnSeasonSelectedListener listener;

    public interface OnSeasonSelectedListener {
        void onSeasonSelected(int seasonNumber);
    }

    public SeasonTabAdapter(List<Integer> seasons, OnSeasonSelectedListener listener) {
        this.seasons = seasons;
        this.listener = listener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_season_tab, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        int season = seasons.get(position);
        holder.text.setText(holder.itemView.getContext()
                .getString(R.string.season_label, season));

        // Selected state
        if (position == selectedPosition) {
            holder.text.setBackgroundColor(
                    holder.itemView.getContext().getColor(R.color.prime_accent));
        } else {
            holder.text.setBackgroundColor(
                    holder.itemView.getContext().getColor(R.color.prime_surface));
        }

        holder.itemView.setOnClickListener(v -> {
            int oldPos = selectedPosition;
            selectedPosition = holder.getAdapterPosition();
            notifyItemChanged(oldPos);
            notifyItemChanged(selectedPosition);
            if (listener != null) listener.onSeasonSelected(season);
        });

        // Focus animation
        holder.itemView.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                v.animate().scaleX(1.1f).scaleY(1.1f).setDuration(100).start();
            } else {
                v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(100).start();
            }
        });
    }

    @Override
    public int getItemCount() {
        return seasons.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final TextView text;

        ViewHolder(View itemView) {
            super(itemView);
            text = (TextView) itemView;
        }
    }
}
