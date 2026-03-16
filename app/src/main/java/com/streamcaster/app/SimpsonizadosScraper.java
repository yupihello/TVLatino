package com.streamcaster.app;

import android.util.Log;

import com.streamcaster.app.models.Episode;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.FormBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Scraper for simpsonizados.me — Los Simpsons in Spanish Latino.
 */
public class SimpsonizadosScraper {

    private static final String TAG = "SimpsonizadosScraper";
    private static final String BASE_URL = "https://simpsonizados.me";
    private static final int TIMEOUT_MS = 15000;
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    private final OkHttpClient httpClient = new OkHttpClient();

    /**
     * Scrapes all seasons and episodes from simpsonizados.me.
     * Returns episodes organized by season number.
     */
    public Map<Integer, List<Episode>> scrapeAllEpisodes(String seriesThumbnail) throws IOException {
        Map<Integer, List<Episode>> seasonEpisodes = new LinkedHashMap<>();

        // Get homepage to find all season URLs
        Document doc = Jsoup.connect(BASE_URL)
                .userAgent(USER_AGENT)
                .timeout(TIMEOUT_MS)
                .get();

        // Find season links: /temp/los-simpson-season-N/
        Elements seasonLinks = doc.select("a[href*=simpson-season-]");
        List<int[]> seasons = new ArrayList<>(); // [seasonNum, dummy]
        List<String> seasonUrls = new ArrayList<>();

        for (Element link : seasonLinks) {
            String href = link.absUrl("href");
            Matcher m = Pattern.compile("season-(\\d+)").matcher(href);
            if (m.find()) {
                int num = Integer.parseInt(m.group(1));
                if (!seasonUrls.contains(href)) {
                    seasonUrls.add(href);
                    seasons.add(new int[]{num});
                }
            }
        }

        Log.d(TAG, "Found " + seasonUrls.size() + " seasons");

        // Scrape each season page for episode links
        for (int i = 0; i < seasonUrls.size(); i++) {
            int seasonNum = seasons.get(i)[0];
            String seasonUrl = seasonUrls.get(i);

            try {
                List<Episode> episodes = scrapeSeason(seasonUrl, seasonNum, seriesThumbnail);
                if (!episodes.isEmpty()) {
                    seasonEpisodes.put(seasonNum, episodes);
                    Log.d(TAG, "Season " + seasonNum + ": " + episodes.size() + " episodes");
                }
            } catch (Exception e) {
                Log.e(TAG, "Error scraping season " + seasonNum, e);
            }
        }

        return seasonEpisodes;
    }

    private List<Episode> scrapeSeason(String seasonUrl, int seasonNum, String thumbnail)
            throws IOException {
        List<Episode> episodes = new ArrayList<>();

        Document doc = Jsoup.connect(seasonUrl)
                .userAgent(USER_AGENT)
                .timeout(TIMEOUT_MS)
                .get();

        // Episode links: /cap/los-simpson-NxM/
        Elements epLinks = doc.select("a[href*=los-simpson-" + seasonNum + "x]");
        int epNum = 0;

        for (Element link : epLinks) {
            String href = link.absUrl("href");
            if (!href.contains("/cap/")) continue;

            String title = link.text().trim();
            if (title.isEmpty() || title.equalsIgnoreCase("season")
                    || title.matches("\\d+")) continue;

            epNum++;

            // Extract post ID from the episode page to build the embed URL
            // We'll get it lazily — store the episode page URL as serverUrl
            // The PlaybackActivity will load this page, which loads the videok.pro iframe
            // But we need the direct embed URL for our WebView resolver

            // For now, store the episode page URL — we'll resolve via AJAX
            String embedUrl = resolveEmbedUrl(href);

            if (embedUrl != null && !embedUrl.isEmpty()) {
                Episode ep = new Episode();
                ep.setTitle(title);
                ep.setEpisodeNumber(epNum);
                ep.setSeasonNumber(seasonNum);
                ep.addServerUrl(embedUrl);
                ep.setThumbnailUrl(thumbnail);
                episodes.add(ep);
            }
        }

        return episodes;
    }

    /**
     * Loads an episode page, extracts the post ID, calls the AJAX endpoint
     * to get the videok.pro embed URL.
     */
    private String resolveEmbedUrl(String episodeUrl) {
        try {
            Document doc = Jsoup.connect(episodeUrl)
                    .userAgent(USER_AGENT)
                    .timeout(TIMEOUT_MS)
                    .get();

            // Extract post ID from body class "postid-XXXX"
            String bodyClass = doc.selectFirst("body") != null
                    ? doc.selectFirst("body").className() : "";
            Matcher m = Pattern.compile("postid-(\\d+)").matcher(bodyClass);
            if (!m.find()) return null;

            String postId = m.group(1);

            // Call AJAX to get embed URL
            FormBody form = new FormBody.Builder()
                    .add("action", "doo_player_ajax")
                    .add("post", postId)
                    .add("nume", "1")
                    .add("type", "tv")
                    .build();

            Request request = new Request.Builder()
                    .url(BASE_URL + "/wp-admin/admin-ajax.php")
                    .post(form)
                    .header("User-Agent", USER_AGENT)
                    .header("Referer", episodeUrl)
                    .header("X-Requested-With", "XMLHttpRequest")
                    .build();

            Response response = httpClient.newCall(request).execute();
            String body = response.body() != null ? response.body().string() : "";
            response.close();

            // Parse {"embed_url":"https://videok.pro/e/xxx.html","type":"iframe"}
            Matcher urlMatcher = Pattern.compile("\"embed_url\":\"([^\"]+)\"").matcher(body);
            if (urlMatcher.find()) {
                String embedUrl = urlMatcher.group(1).replace("\\/", "/");
                Log.d(TAG, "Resolved: " + episodeUrl + " → " + embedUrl);
                return embedUrl;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error resolving embed for: " + episodeUrl, e);
        }
        return null;
    }
}
