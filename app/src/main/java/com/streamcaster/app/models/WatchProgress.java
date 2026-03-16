package com.streamcaster.app.models;

import java.io.Serializable;

public class WatchProgress implements Serializable {
    private String seriesUrl;
    private String seriesTitle;
    private String seriesThumbnail;
    private int seasonNumber;
    private int episodeNumber;
    private String episodeTitle;
    private long positionMs;
    private long durationMs;
    private long timestamp; // when last watched

    public WatchProgress() {}

    public WatchProgress(String seriesUrl, String seriesTitle, String seriesThumbnail,
                         int seasonNumber, int episodeNumber, String episodeTitle,
                         long positionMs, long durationMs) {
        this.seriesUrl = seriesUrl;
        this.seriesTitle = seriesTitle;
        this.seriesThumbnail = seriesThumbnail;
        this.seasonNumber = seasonNumber;
        this.episodeNumber = episodeNumber;
        this.episodeTitle = episodeTitle;
        this.positionMs = positionMs;
        this.durationMs = durationMs;
        this.timestamp = System.currentTimeMillis();
    }

    public String getSeriesUrl() { return seriesUrl; }
    public String getSeriesTitle() { return seriesTitle; }
    public String getSeriesThumbnail() { return seriesThumbnail; }
    public int getSeasonNumber() { return seasonNumber; }
    public int getEpisodeNumber() { return episodeNumber; }
    public String getEpisodeTitle() { return episodeTitle; }
    public long getPositionMs() { return positionMs; }
    public long getDurationMs() { return durationMs; }
    public long getTimestamp() { return timestamp; }

    public void setPositionMs(long positionMs) { this.positionMs = positionMs; }
    public void setDurationMs(long durationMs) { this.durationMs = durationMs; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }
    public void setSeasonNumber(int seasonNumber) { this.seasonNumber = seasonNumber; }
    public void setEpisodeNumber(int episodeNumber) { this.episodeNumber = episodeNumber; }
    public void setEpisodeTitle(String episodeTitle) { this.episodeTitle = episodeTitle; }

    /** Returns progress as percentage 0-100 */
    public int getProgressPercent() {
        if (durationMs <= 0) return 0;
        return (int) (positionMs * 100 / durationMs);
    }
}
