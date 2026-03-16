package com.streamcaster.app;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.streamcaster.app.models.Series;

import java.util.List;

public class HomeAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int TYPE_HERO = 0;
    private static final int TYPE_CARD = 1;

    private final List<Series> seriesList;
    private final SeriesCardAdapter.OnSeriesClickListener clickListener;

    public HomeAdapter(List<Series> seriesList,
                       SeriesCardAdapter.OnSeriesClickListener clickListener) {
        this.seriesList = seriesList;
        this.clickListener = clickListener;
    }

    @Override
    public int getItemViewType(int position) {
        return position == 0 ? TYPE_HERO : TYPE_CARD;
    }

    @Override
    public int getItemCount() {
        return seriesList.isEmpty() ? 0 : 1 + seriesList.size(); // hero + all cards
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == TYPE_HERO) {
            View view = inflater.inflate(R.layout.item_hero_banner, parent, false);
            return new HeroViewHolder(view);
        } else {
            View view = inflater.inflate(R.layout.item_series_card, parent, false);
            return new CardViewHolder(view);
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        if (holder instanceof HeroViewHolder) {
            bindHero((HeroViewHolder) holder);
        } else if (holder instanceof CardViewHolder) {
            bindCard((CardViewHolder) holder, position - 1);
        }
    }

    private void bindHero(HeroViewHolder holder) {
        if (seriesList.isEmpty()) return;
        Series hero = seriesList.get(0);

        holder.title.setText(hero.getTitle());
        holder.category.setText(hero.getCategory());

        Glide.with(holder.itemView.getContext())
                .load(hero.getThumbnailUrl())
                .centerCrop()
                .into(holder.backdrop);

        holder.playBtn.setOnClickListener(v -> {
            if (clickListener != null) clickListener.onSeriesClick(hero);
        });

        holder.detailsBtn.setOnClickListener(v -> {
            if (clickListener != null) clickListener.onSeriesClick(hero);
        });
    }

    private void bindCard(CardViewHolder holder, int index) {
        if (index < 0 || index >= seriesList.size()) return;
        Series series = seriesList.get(index);

        holder.title.setText(series.getTitle());

        Glide.with(holder.itemView.getContext())
                .load(series.getThumbnailUrl())
                .centerCrop()
                .placeholder(R.drawable.default_card_image)
                .into(holder.image);

        holder.itemView.setOnClickListener(v -> {
            if (clickListener != null) clickListener.onSeriesClick(series);
        });

        // Focus animation
        holder.itemView.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                v.animate().scaleX(1.08f).scaleY(1.08f).setDuration(150).start();
                v.setBackgroundResource(R.drawable.card_focus_border);
            } else {
                v.animate().scaleX(1.0f).scaleY(1.0f).setDuration(150).start();
                v.setBackgroundResource(R.drawable.card_default_border);
            }
        });
    }

    static class HeroViewHolder extends RecyclerView.ViewHolder {
        final ImageView backdrop;
        final TextView title;
        final TextView category;
        final Button playBtn;
        final Button detailsBtn;

        HeroViewHolder(View itemView) {
            super(itemView);
            backdrop = itemView.findViewById(R.id.hero_backdrop);
            title = itemView.findViewById(R.id.hero_title);
            category = itemView.findViewById(R.id.hero_category);
            playBtn = itemView.findViewById(R.id.hero_play_btn);
            detailsBtn = itemView.findViewById(R.id.hero_details_btn);
        }
    }

    static class CardViewHolder extends RecyclerView.ViewHolder {
        final ImageView image;
        final TextView title;

        CardViewHolder(View itemView) {
            super(itemView);
            image = itemView.findViewById(R.id.card_image);
            title = itemView.findViewById(R.id.card_title);
        }
    }
}
