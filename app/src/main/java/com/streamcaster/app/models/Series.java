package com.streamcaster.app.models;

import java.io.Serializable;

public class Series implements Serializable {
    private String title;
    private String thumbnailUrl;
    private String pageUrl;
    private String category;

    public Series() {
    }

    public Series(String title, String thumbnailUrl, String pageUrl, String category) {
        this.title = title;
        this.thumbnailUrl = thumbnailUrl;
        this.pageUrl = pageUrl;
        this.category = category;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getThumbnailUrl() {
        return thumbnailUrl;
    }

    public void setThumbnailUrl(String thumbnailUrl) {
        this.thumbnailUrl = thumbnailUrl;
    }

    public String getPageUrl() {
        return pageUrl;
    }

    public void setPageUrl(String pageUrl) {
        this.pageUrl = pageUrl;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    @Override
    public String toString() {
        return "Series{" +
                "title='" + title + '\'' +
                ", category='" + category + '\'' +
                '}';
    }
}
