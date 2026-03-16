package com.streamcaster.app;

import android.util.Log;

import com.streamcaster.app.models.Episode;
import com.streamcaster.app.models.Series;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.select.Elements;

import java.io.IOException;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Scrapes tvserieslatino.com for series and episode data.
 *
 * Site structure (WordPress):
 * - Homepage has h2 headings for categories followed by series with images
 * - Series pages use TWO formats for video URLs:
 *   1) Servidor JS: function Servidor1(id) { var urls = [...] }
 *   2) Link-based: <a href="...?UrlPok=EMBED_URL">Capítulo XX. TÍTULO</a>
 *      with <button onclick="toggleDiv('sectionN')">Temporada N</button>
 */
public class TvSeriesLatinoScraper {

    private static final String TAG = "TvSeriesLatinoScraper";
    private static final String BASE_URL = "https://www.tvserieslatino.com";
    private static final int TIMEOUT_MS = 15000;
    private static final String USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    /**
     * Scrapes the homepage and returns a flat deduplicated list of all series.
     */
    public List<Series> scrapeHomePage() throws IOException {
        Set<String> seenUrls = new LinkedHashSet<>();
        List<Series> allSeries = new ArrayList<>();

        Document doc = Jsoup.connect(BASE_URL)
                .userAgent(USER_AGENT)
                .timeout(TIMEOUT_MS)
                .get();

        // Scan all links with images on the page
        Elements links = doc.select("a[href*=tvserieslatino.com]");
        for (Element link : links) {
            Element img = link.selectFirst("img");
            if (img == null) continue;

            String href = link.absUrl("href");
            if (href.isEmpty() || href.equals(BASE_URL) || href.equals(BASE_URL + "/")) continue;
            if (href.contains("/category/") || href.contains("/tag/")
                    || href.contains("/author/") || href.contains("/page/")
                    || href.contains("#") || href.contains("wp-content")
                    || href.contains("wp-admin") || href.contains("?UrlPok")) continue;

            // Deduplicate by URL
            if (!seenUrls.add(href)) continue;

            String title = img.attr("alt");
            if (title.isEmpty()) title = link.attr("title");
            if (title.isEmpty()) title = link.text().trim();
            if (title.isEmpty()) continue;

            String thumbnailUrl = img.absUrl("src");
            if (thumbnailUrl.isEmpty()) thumbnailUrl = img.absUrl("data-src");
            if (thumbnailUrl.isEmpty()) thumbnailUrl = img.absUrl("data-lazy-src");

            allSeries.add(new Series(title, thumbnailUrl, href, ""));
        }

        // Expand Dragon Ball: replace single entry with individual sub-series
        allSeries = expandDragonBall(allSeries);

        // Replace Los Simpsons from tvserieslatino with simpsonizados.me (español latino)
        allSeries = replaceSimpsons(allSeries);

        Log.d(TAG, "Found " + allSeries.size() + " unique series");
        return allSeries;
    }

    private List<Series> replaceSimpsons(List<Series> series) {
        List<Series> result = new ArrayList<>();
        for (Series s : series) {
            if (s.getPageUrl().contains("simpsons") || s.getPageUrl().contains("simpson")) {
                // Replace with simpsonizados.me source (español latino)
                result.add(new Series("Los Simpsons", s.getThumbnailUrl(),
                        "https://simpsonizados.me/", ""));
                Log.d(TAG, "Replaced Simpsons with simpsonizados.me");
            } else {
                result.add(s);
            }
        }
        return result;
    }

    private List<Series> expandDragonBall(List<Series> series) {
        List<Series> result = new ArrayList<>();
        for (Series s : series) {
            if (s.getPageUrl().contains("dragon-ball") && s.getPageUrl().contains("especiales")) {
                // Replace with individual sub-series from dragondelasesferas.com
                Log.d(TAG, "Expanding Dragon Ball into sub-series");
                try {
                    Document dbDoc = Jsoup.connect("https://dragondelasesferas.com/")
                            .userAgent(USER_AGENT)
                            .timeout(TIMEOUT_MS)
                            .get();

                    Elements links = dbDoc.select("a[href*=dragondelasesferas.com]");
                    Set<String> seen = new LinkedHashSet<>();
                    for (Element link : links) {
                        String href = link.absUrl("href");
                        if (href.isEmpty() || href.endsWith(".com/")
                                || href.contains("/category/") || href.contains("/author/")
                                || href.contains("/page/") || href.contains("#")
                                || href.contains("manga") || href.contains("ovas")
                                || href.contains("especiales") || href.contains("peliculas")) continue;

                        String text = link.text().trim();
                        if (!text.startsWith("Ver ")) continue;
                        if (!seen.add(href)) continue;

                        // Find thumbnail image
                        Element img = link.selectFirst("img");
                        String thumb = img != null ? img.absUrl("src") : s.getThumbnailUrl();

                        String title = text.replace("Ver ", "");
                        result.add(new Series(title, thumb, href, "Dragon Ball"));
                        Log.d(TAG, "  Sub-series: " + title + " → " + href);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Failed to expand Dragon Ball", e);
                    result.add(s); // Keep original if expansion fails
                }
            } else {
                result.add(s);
            }
        }
        return result;
    }

    /**
     * Scrapes a series page to extract episodes organized by season.
     * Tries Servidor JS format first, falls back to link-based format.
     */
    public Map<Integer, List<Episode>> scrapeSeriesPage(String seriesUrl, String seriesThumbnail)
            throws IOException {
        // Simpsonizados.me has its own scraper
        if (seriesUrl.contains("simpsonizados.me")) {
            return new SimpsonizadosScraper().scrapeAllEpisodes(seriesThumbnail);
        }

        Document doc = Jsoup.connect(seriesUrl)
                .userAgent(USER_AGENT)
                .timeout(TIMEOUT_MS)
                .get();

        // Check for JS redirect to another domain (e.g. Dragon Ball → dragondelasesferas.com)
        String jsRedirect = detectJsRedirect(doc);
        if (jsRedirect != null) {
            Log.d(TAG, "Following JS redirect: " + jsRedirect);
            doc = Jsoup.connect(jsRedirect)
                    .userAgent(USER_AGENT)
                    .timeout(TIMEOUT_MS)
                    .get();
        }

        // Try Servidor JS format first
        Map<Integer, List<Episode>> result = scrapeServidorFormat(doc, seriesThumbnail);

        // Fallback: link-based format
        if (result.isEmpty()) {
            Log.d(TAG, "No Servidor format, trying link-based format for: " + seriesUrl);
            result = scrapeLinkFormat(doc, seriesThumbnail);
        }

        // If still empty and we followed a redirect, try sub-pages of the redirected site
        if (result.isEmpty() && jsRedirect != null) {
            result = scrapeSubPages(doc, jsRedirect, seriesThumbnail);
        }

        return result;
    }

    /**
     * When a JS redirect leads to a site homepage (e.g. dragondelasesferas.com),
     * scan for sub-page links and scrape the first one that has episodes.
     */
    private Map<Integer, List<Episode>> scrapeSubPages(Document doc, String baseUrl,
                                                        String seriesThumbnail) {
        String baseDomain = baseUrl.replaceAll("https?://", "").replaceAll("/.*", "");
        Elements links = doc.select("a[href*=" + baseDomain + "]");

        for (Element link : links) {
            String href = link.absUrl("href");
            if (href.isEmpty() || href.equals(baseUrl) || href.equals(baseUrl + "/")) continue;
            if (href.contains("/category/") || href.contains("/tag/")
                    || href.contains("/author/") || href.contains("/page/")
                    || href.contains("#")) continue;

            // Only try pages that look like series pages (have keywords)
            String text = link.text().toLowerCase();
            if (!text.contains("ver ") && !text.contains("capitulo")
                    && !text.contains("latino") && !text.contains("online")) continue;

            try {
                Log.d(TAG, "Trying sub-page: " + href);
                Document subDoc = Jsoup.connect(href)
                        .userAgent(USER_AGENT)
                        .timeout(TIMEOUT_MS)
                        .get();

                Map<Integer, List<Episode>> result = scrapeServidorFormat(subDoc, seriesThumbnail);
                if (!result.isEmpty()) {
                    Log.d(TAG, "Found episodes on sub-page: " + href);
                    return result;
                }
            } catch (Exception e) {
                Log.d(TAG, "Sub-page failed: " + href);
            }
        }
        return new LinkedHashMap<>();
    }

    private String detectJsRedirect(Document doc) {
        for (Element script : doc.select("script")) {
            String content = script.html();
            // Match: window.location.href = 'https://...'
            Matcher m = Pattern.compile("window\\.location(?:\\.href)?\\s*=\\s*['\"]"
                    + "(https?://[^'\"]+)['\"]").matcher(content);
            if (m.find()) {
                String target = m.group(1);
                // Only follow if it's a different domain
                if (!target.contains("tvserieslatino.com")) {
                    return target;
                }
            }
        }
        return null;
    }

    // --- Format 1: Servidor JS arrays ---

    private Map<Integer, List<Episode>> scrapeServidorFormat(Document doc, String seriesThumbnail) {
        Map<Integer, List<Episode>> seasonEpisodes = new LinkedHashMap<>();

        // Concatenate ALL script tags that have video functions
        Elements scripts = doc.select("script");
        StringBuilder sb = new StringBuilder();
        for (Element script : scripts) {
            String content = script.html();
            if (content.contains("var urls") || content.contains("destino")) {
                sb.append(content).append("\n");
            }
        }
        String fullScript = sb.toString();

        if (fullScript.isEmpty()) return seasonEpisodes;

        // Find all function names that take (id) parameter
        List<String> funcNames = new ArrayList<>();
        Pattern funcP = Pattern.compile("function\\s+(\\w+)\\s*\\(\\s*id\\s*\\)");
        Matcher funcM = funcP.matcher(fullScript);
        while (funcM.find()) {
            funcNames.add(funcM.group(1));
        }
        Log.d(TAG, "Found functions with (id): " + funcNames);

        // Extract URL arrays from each function, keep first two that have URLs
        List<String> server1Urls = new ArrayList<>();
        List<String> server2Urls = new ArrayList<>();

        for (String funcName : funcNames) {
            List<String> urls = extractUrlArray(fullScript, funcName);
            if (!urls.isEmpty()) {
                if (server1Urls.isEmpty()) {
                    server1Urls = urls;
                } else if (server2Urls.isEmpty()) {
                    server2Urls = urls;
                    break; // Got two, enough
                }
            }
        }

        // Use the server with more URLs as primary
        List<String> primaryUrls = server1Urls.size() >= server2Urls.size()
                ? server1Urls : server2Urls;
        List<String> secondaryUrls = server1Urls.size() >= server2Urls.size()
                ? server2Urls : server1Urls;

        Log.d(TAG, "Servidor format: " + primaryUrls.size() + " primary, "
                + secondaryUrls.size() + " secondary URLs");

        // Extract episode titles from HTML text (e.g. "Capítulo 1 Vacaciones de verano")
        List<String> htmlTitles = extractHtmlEpisodeTitles(doc);
        int htmlTitleIndex = 0;

        int currentSeason = 1;
        int episodeInSeason = 0;

        for (int i = 0; i < primaryUrls.size(); i++) {
            String url1 = primaryUrls.get(i);

            if (isSeasonSeparator(url1)) {
                int seasonNum = extractSeasonNumber(url1);
                if (seasonNum > 0) {
                    currentSeason = seasonNum;
                    episodeInSeason = 0;
                }
                continue;
            }

            if (url1.isEmpty() || url1.equals("#") || url1.equals("about:blank")) continue;

            // Skip entries that aren't URLs (section titles like "1. Digimon Adventure")
            if (!url1.startsWith("http")) {
                Log.d(TAG, "Skipping non-URL entry: " + url1);
                continue;
            }

            episodeInSeason++;

            String url2 = "";
            if (i < secondaryUrls.size()) {
                String candidate = secondaryUrls.get(i);
                if (!isSeasonSeparator(candidate) && !candidate.isEmpty()
                        && candidate.startsWith("http")) {
                    url2 = candidate;
                }
            }

            // Prefer HTML title (has accents) over URL-derived title
            String episodeTitle;
            if (htmlTitleIndex < htmlTitles.size()) {
                episodeTitle = htmlTitles.get(htmlTitleIndex);
                htmlTitleIndex++;
            } else {
                episodeTitle = buildEpisodeTitle(url1, currentSeason, episodeInSeason);
            }

            Episode episode = new Episode(
                    episodeTitle, episodeInSeason, currentSeason,
                    url1, url2, seriesThumbnail);

            seasonEpisodes.computeIfAbsent(currentSeason, k -> new ArrayList<>()).add(episode);
        }

        return seasonEpisodes;
    }

    // --- Format 2: HTML links with toggleDiv season buttons ---

    private Map<Integer, List<Episode>> scrapeLinkFormat(Document doc, String seriesThumbnail) {
        Map<Integer, List<Episode>> seasonEpisodes = new LinkedHashMap<>();

        int currentSeason = 1;
        int episodeInSeason = 0;

        // Find season buttons: <button onclick="toggleDiv('sectionN')">Temporada N</button>
        Elements buttons = doc.select("button[onclick*=toggleDiv]");
        // Find all episode links with UrlPok
        Elements episodeLinks = doc.select("a[href*=UrlPok]");

        if (episodeLinks.isEmpty()) {
            Log.d(TAG, "Link format: no UrlPok links found");
            return seasonEpisodes;
        }

        // Build a list of season boundaries by their position in the document
        // We'll assign each link to its preceding season button
        List<int[]> seasonPositions = new ArrayList<>(); // [seasonNum, sourceIndex]
        for (Element btn : buttons) {
            int seasonNum = extractSeasonFromButtonText(btn.text());
            if (seasonNum > 0) {
                seasonPositions.add(new int[]{seasonNum, btn.siblingIndex()});
            }
        }

        // If no season buttons, everything is season 1
        if (seasonPositions.isEmpty()) {
            for (Element link : episodeLinks) {
                episodeInSeason++;
                String embedUrl = extractUrlPokParam(link.attr("href"));
                if (embedUrl.isEmpty()) continue;
                String title = link.text().trim();
                if (title.isEmpty()) title = "T1 - Episodio " + episodeInSeason;
                seasonEpisodes.computeIfAbsent(1, k -> new ArrayList<>())
                        .add(new Episode(title, episodeInSeason, 1, embedUrl, "", seriesThumbnail));
            }
        } else {
            // Walk through all elements in document order using select results
            // Simple approach: assign each UrlPok link to the season of the
            // most recent preceding toggle button
            Element body = doc.body();
            String bodyHtml = body.html();

            currentSeason = 1;
            episodeInSeason = 0;

            // Use a simple approach: iterate all elements in order
            for (Element el : body.getAllElements()) {
                if (el.tagName().equals("button") && el.attr("onclick").contains("toggleDiv")) {
                    int sn = extractSeasonFromButtonText(el.text());
                    if (sn > 0) {
                        currentSeason = sn;
                        episodeInSeason = 0;
                    }
                } else if (el.tagName().equals("a") && el.attr("href").contains("UrlPok")) {
                    String embedUrl = extractUrlPokParam(el.attr("href"));
                    if (embedUrl.isEmpty()) continue;
                    episodeInSeason++;
                    String title = el.text().trim();
                    if (title.isEmpty()) title = "T" + currentSeason + " - Episodio " + episodeInSeason;
                    seasonEpisodes.computeIfAbsent(currentSeason, k -> new ArrayList<>())
                            .add(new Episode(title, episodeInSeason, currentSeason,
                                    embedUrl, "", seriesThumbnail));
                }
            }
        }

        Log.d(TAG, "Link format: found " + seasonEpisodes.size() + " seasons, "
                + episodeLinks.size() + " total links");
        return seasonEpisodes;
    }

    private String extractUrlPokParam(String href) {
        try {
            int idx = href.indexOf("UrlPok=");
            if (idx < 0) return "";
            String encoded = href.substring(idx + 7);
            // Remove any trailing parameters
            int amp = encoded.indexOf('&');
            if (amp > 0) encoded = encoded.substring(0, amp);
            return URLDecoder.decode(encoded, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    private int extractSeasonFromButtonText(String text) {
        Pattern p = Pattern.compile("(?:temporada|season)\\s*(\\d+)", Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(text);
        if (m.find()) {
            try { return Integer.parseInt(m.group(1)); }
            catch (NumberFormatException e) { return -1; }
        }
        return -1;
    }

    /**
     * Extracts episode titles from the HTML text of the page.
     * Looks for patterns like "Capítulo 1 Título" or "Capitulo 1: Título" in the page body.
     */
    private List<String> extractHtmlEpisodeTitles(Document doc) {
        List<String> titles = new ArrayList<>();
        // Use text() to decode HTML entities (& nbsp; → space, etc.)
        String text = doc.body().text();

        // Match: "Capítulo/Capitulo N Title" from plain text
        Pattern p = Pattern.compile(
                "(?:Cap[ií]tulo|Cap\\.?)\\s*(\\d+)[.:;\\s-]*\\s*(.{2,80}?)(?=\\s*(?:Cap[ií]tulo|Cap\\.|⮕|Opci[oó]n|$))",
                Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(text);
        while (m.find()) {
            String num = m.group(1);
            String name = m.group(2).trim();
            name = name.trim();
            if (!name.isEmpty()) {
                titles.add("Capítulo " + num + " - " + name);
            } else {
                titles.add("Capítulo " + num);
            }
        }

        if (!titles.isEmpty()) {
            Log.d(TAG, "Extracted " + titles.size() + " episode titles from HTML");
        }
        return titles;
    }

    // --- Shared helpers ---

    private List<String> findUrlArrayFunctions(String script) {
        List<String> names = new ArrayList<>();
        Pattern p = Pattern.compile("function\\s+(\\w+)\\s*\\([^)]*\\)\\s*\\{");
        Matcher m = p.matcher(script);
        while (m.find()) {
            String funcName = m.group(1);
            // Check if this function body contains a urls array
            int start = m.end();
            int braceCount = 1;
            int end = start;
            for (int i = start; i < script.length() && braceCount > 0; i++) {
                if (script.charAt(i) == '{') braceCount++;
                else if (script.charAt(i) == '}') braceCount--;
                end = i;
            }
            String body = script.substring(start, end);
            if (body.contains("urls") && body.contains("destino")) {
                names.add(funcName);
            }
        }
        return names;
    }

    private List<String> extractUrlArray(String script, String functionName) {
        List<String> urls = new ArrayList<>();

        // Find the function declaration
        String funcPattern = "function\\s+" + functionName + "\\s*\\([^)]*\\)\\s*\\{";
        Pattern funcRegex = Pattern.compile(funcPattern);
        Matcher funcMatcher = funcRegex.matcher(script);

        if (!funcMatcher.find()) return urls;

        // Find "var urls = [" after the function start
        int searchFrom = funcMatcher.end();
        int urlsIdx = script.indexOf("var urls", searchFrom);
        if (urlsIdx < 0 || urlsIdx > searchFrom + 200) return urls; // urls decl should be near start

        int bracketStart = script.indexOf('[', urlsIdx);
        if (bracketStart < 0) return urls;

        // Find matching closing bracket - skip brackets inside strings
        int bracketEnd = -1;
        boolean inString = false;
        char stringChar = 0;
        for (int i = bracketStart + 1; i < script.length(); i++) {
            char c = script.charAt(i);
            if (inString) {
                if (c == stringChar && script.charAt(i - 1) != '\\') {
                    inString = false;
                }
            } else {
                if (c == '"' || c == '\'') {
                    inString = true;
                    stringChar = c;
                } else if (c == ']') {
                    bracketEnd = i;
                    break;
                }
            }
        }

        if (bracketEnd < 0) return urls;

        String arrayContent = script.substring(bracketStart + 1, bracketEnd);
        Pattern urlPattern = Pattern.compile("[\"']([^\"']*)[\"']");
        Matcher urlMatcher = urlPattern.matcher(arrayContent);

        while (urlMatcher.find()) {
            urls.add(urlMatcher.group(1).trim());
        }

        return urls;
    }

    private boolean isSeasonSeparator(String url) {
        return url != null && url.toUpperCase().contains("TEMPORADA");
    }

    private int extractSeasonNumber(String url) {
        Pattern pattern = Pattern.compile("TEMPORADA[^0-9]*(\\d+)", Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(url);
        if (matcher.find()) {
            try { return Integer.parseInt(matcher.group(1)); }
            catch (NumberFormatException e) { return -1; }
        }
        return -1;
    }

    private String buildEpisodeTitle(String url, int season, int episode) {
        try {
            String path = url;
            if (path.contains("/")) {
                path = path.substring(path.lastIndexOf('/') + 1);
            }
            // URL-decode to restore accented characters (%C3%A1 → á, etc.)
            path = URLDecoder.decode(path, "UTF-8");
            path = path.replaceAll("\\.[a-zA-Z0-9]+$", "");
            path = path.replace("_-_", " - ").replace("_", " ").trim();

            if (!path.isEmpty() && path.length() > 3) {
                return path;
            }
        } catch (Exception ignored) {}

        return "T" + season + " - Episodio " + episode;
    }
}
