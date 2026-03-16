package com.streamcaster.app;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.streamcaster.app.models.Episode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class EpisodeListAdapter extends RecyclerView.Adapter<EpisodeListAdapter.ViewHolder> {

    private List<Episode> episodes = new ArrayList<>();
    private OnEpisodeClickListener listener;

    public interface OnEpisodeClickListener {
        void onEpisodeClick(Episode episode);
    }

    public EpisodeListAdapter(OnEpisodeClickListener listener) {
        this.listener = listener;
    }

    public void setEpisodes(List<Episode> episodes) {
        this.episodes = episodes;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_episode, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Episode episode = episodes.get(position);

        holder.title.setText(episode.getTitle());

        // Build info line: "T1 E3 · ★7.8"
        String info = "T" + episode.getSeasonNumber() + " E" + episode.getEpisodeNumber();
        if (episode.getRating() > 0) {
            info += String.format(Locale.US, " · ★%.1f", episode.getRating());
        }
        holder.info.setText(info);

        // Synopsis
        String synopsis = episode.getSynopsis();
        if (synopsis != null && !synopsis.isEmpty()) {
            holder.synopsis.setText(synopsis);
            holder.synopsis.setVisibility(View.VISIBLE);
        } else {
            holder.synopsis.setVisibility(View.GONE);
        }

        // Use TMDB still if available, fallback to scraped thumbnail
        Glide.with(holder.itemView.getContext())
                .load(episode.getBestThumbnail())
                .centerCrop()
                .placeholder(R.drawable.default_card_image)
                .into(holder.image);

        holder.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onEpisodeClick(episode);
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
        return episodes.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final ImageView image;
        final TextView title;
        final TextView info;
        final TextView synopsis;

        ViewHolder(View itemView) {
            super(itemView);
            image = itemView.findViewById(R.id.episode_image);
            title = itemView.findViewById(R.id.episode_title);
            info = itemView.findViewById(R.id.episode_info);
            synopsis = itemView.findViewById(R.id.episode_synopsis);
        }
    }
}
