package dev.syed.tvpilot.tv;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Which streaming app has a title in India, and the app's own link to it (JustWatch's public GraphQL, the same source
 * the remote-control-tv MCP uses). This turns "play Mirzapur" into Prime Video's title page with no navigation.
 * Unofficial API: any failure just means "no shortcut", and TV Pilot searches inside the app instead.
 */
final class JustWatch {
    private JustWatch() {}

    static final String URL = "https://apis.justwatch.com/graphql";

    static final class Title {
        String title, type, imdb, tmdb;
        int year;
        /** provider technical name -> link, in JustWatch's order. */
        final Map<String, String> offers = new LinkedHashMap<>();
        double score;
        @Override public String toString() { return title + " (" + year + ", " + type + ") " + offers.keySet(); }
    }

    private static final String Q = "query S($country: Country!, $language: Language!, $first: Int!, $filter: TitleFilter) {"
            + " popularTitles(country: $country, first: $first, filter: $filter) { edges { node { objectType"
            + " content(country: $country, language: $language) { title originalReleaseYear externalIds { imdbId tmdbId } }"
            + " offers(country: $country, platform: WEB) { monetizationType standardWebURL package { technicalName } } } } } }";

    static List<Title> search(String query, String country, int timeoutMs) throws Exception {
        JSONObject vars = new JSONObject().put("country", country).put("language", "en").put("first", 5)
                .put("filter", new JSONObject().put("searchQuery", query));
        String body = new JSONObject().put("query", Q).put("variables", vars).toString();
        Net.Response r = Net.postJson(URL, body, new String[][] {{"User-Agent", "Mozilla/5.0 (Linux; Android 11) TVPilot"}}, 4000, timeoutMs, "JustWatch");
        if (r.code != 200) throw Net.http(r.code, r.body, "JustWatch");
        JSONObject d = new JSONObject(r.body);
        List<Title> out = new ArrayList<>();
        JSONObject data = d.optJSONObject("data");
        JSONArray edges = data == null || data.optJSONObject("popularTitles") == null ? null : data.getJSONObject("popularTitles").optJSONArray("edges");
        if (edges == null) return out;
        for (int i = 0; i < edges.length(); i++) {
            JSONObject n = edges.getJSONObject(i).getJSONObject("node");
            JSONObject c = n.optJSONObject("content");
            if (c == null) continue;
            Title t = new Title();
            t.title = c.optString("title");
            t.year = c.optInt("originalReleaseYear");
            t.type = n.optString("objectType");
            JSONObject ex = c.optJSONObject("externalIds");
            if (ex != null) { t.imdb = ex.isNull("imdbId") ? null : ex.optString("imdbId", null); t.tmdb = ex.isNull("tmdbId") ? null : ex.optString("tmdbId", null); }
            JSONArray offers = n.optJSONArray("offers");
            if (offers != null) for (int k = 0; k < offers.length(); k++) {
                JSONObject o = offers.getJSONObject(k);
                String m = o.optString("monetizationType");
                if (!m.equals("FLATRATE") && !m.equals("FREE") && !m.equals("ADS") && !m.equals("FLATRATE_AND_BUY")) continue;   // never rent/buy
                String p = o.getJSONObject("package").optString("technicalName");
                if (!t.offers.containsKey(p)) t.offers.put(p, o.optString("standardWebURL"));
            }
            t.score = Ui.match(t.title, query);
            out.add(t);
        }
        return out;
    }

    /** The best match for the query, or null when nothing matches well enough. Popularity order breaks ties. */
    static Title best(List<Title> ts, String query, Integer year) {
        Title best = null;
        double bestS = 0;
        for (Title t : ts) {
            double s = t.score + (year != null && t.year == year ? 0.2 : 0);
            if (s >= 0.86 && (best == null || s > bestS + 0.05)) { best = t; bestS = s; }
        }
        return best;
    }
}
