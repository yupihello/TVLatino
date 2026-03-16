package com.streamcaster.app.models;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

public class Episode implements Serializable {
    private String title;
    private int episodeNumber;
    private int seasonNumber;
    private List<String> serverUrls; // Multiple video source URLs
    private String thumbnailUrl;

    // TMDB metadata (optional, enriched after scraping)
    private String synopsis;
    private String tmdbStillUrl;
    private double rating;
    private String airDate;

    public Episode() {
        serverUrls = new ArrayList<>();
    }

    public Episode(String title, int episodeNumber, int seasonNumber,
                   String serverUrl1, String serverUrl2, String thumbnailUrl) {
        this.title = title;
        this.episodeNumber = episodeNumber;
        this.seasonNumber = seasonNumber;
        this.thumbnailUrl = thumbnailUrl;
        this.serverUrls = new ArrayList<>();
        if (serverUrl1 != null && !serverUrl1.isEmpty()) serverUrls.add(serverUrl1);
        if (serverUrl2 != null && !serverUrl2.isEmpty()) serverUrls.add(serverUrl2);
    }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public int getEpisodeNumber() { return episodeNumber; }
    public void setEpisodeNumber(int episodeNumber) { this.episodeNumber = episodeNumber; }

    public int getSeasonNumber() { return seasonNumber; }
    public void setSeasonNumber(int seasonNumber) { this.seasonNumber = seasonNumber; }

    public List<String> getServerUrls() { return serverUrls; }
    public void setServerUrls(List<String> serverUrls) { this.serverUrls = serverUrls; }
    public void addServerUrl(String url) {
        if (url != null && !url.isEmpty() && !serverUrls.contains(url)) {
            serverUrls.add(url);
        }
    }

    public String getThumbnailUrl() { return thumbnailUrl; }
    public void setThumbnailUrl(String thumbnailUrl) { this.thumbnailUrl = thumbnailUrl; }

    public String getSynopsis() { return synopsis; }
    public void setSynopsis(String synopsis) { this.synopsis = synopsis; }

    public String getTmdbStillUrl() { return tmdbStillUrl; }
    public void setTmdbStillUrl(String tmdbStillUrl) { this.tmdbStillUrl = tmdbStillUrl; }

    public double getRating() { return rating; }
    public void setRating(double rating) { this.rating = rating; }

    public String getAirDate() { return airDate; }
    public void setAirDate(String airDate) { this.airDate = airDate; }

    // --- Backward compat for serialized cache ---
    public String getServerUrl1() {
        return serverUrls.size() > 0 ? serverUrls.get(0) : null;
    }
    public String getServerUrl2() {
        return serverUrls.size() > 1 ? serverUrls.get(1) : null;
    }

    /**
     * Returns the best available server URL.
     * Prefers .xyz URLs (classic HLS player) over .com URLs (SPA, less compatible).
     */
    public String getBestServerUrl() {
        // Prefer .xyz
        for (String url : serverUrls) {
            if (url.contains(".xyz/e/")) return url;
        }
        // Fallback to first available
        return serverUrls.isEmpty() ? null : serverUrls.get(0);
    }

    /**
     * Returns the fallback server URL (first one that isn't getBestServerUrl).
     */
    public String getFallbackServerUrl() {
        String best = getBestServerUrl();
        for (String url : serverUrls) {
            if (!url.equals(best)) return url;
        }
        return null;
    }

    /** Returns the best thumbnail: TMDB still > scraped thumbnail */
    public String getBestThumbnail() {
        if (tmdbStillUrl != null && !tmdbStillUrl.isEmpty()) return tmdbStillUrl;
        return thumbnailUrl;
    }

    @Override
    public String toString() {
        return "Episode{" +
                "title='" + title + '\'' +
                ", S" + seasonNumber + "E" + episodeNumber +
                ", servers=" + serverUrls.size() +
                '}';
    }
}
