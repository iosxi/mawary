package com.mawary;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Fetches the things around us.
 *
 * <p>The normal way to run this app is with no API key at all, so the keyless
 * source — the Overpass API over OpenStreetMap data — is the one that has to
 * work properly, not a token fallback. If a Google Places key <em>is</em>
 * configured it takes precedence, since it matches what the user would see in
 * Google Maps; without one, or when Google turns the request down, Overpass
 * carries the app on its own. The screen always names the source it drew from.
 *
 * <p>All network work runs on one low-priority background thread. Requests are
 * coalesced: we refetch only after moving a meaningful fraction of the search
 * radius, or once the cached answer has gone stale.
 */
final class PlaceRepository {

    interface Listener {
        void onPlaces(List<Poi> places, String source);
        void onStatus(String message);
    }

    private static final String TAG = "mawary";

    private static final long STALE_MS = 120_000L;
    private static final long RETRY_MS = 15_000L;
    private static final int CONNECT_MS = 8_000;
    /** Overpass asks its own backend for up to 20 s, so the read wait has to outlast that. */
    private static final int READ_MS = 20_000;
    /** How many places the view is given, nearest first. */
    private static final int MAX_RESULTS = 40;
    /**
     * How many Overpass may return before we pick. Overpass answers in no
     * particular order, so asking it for MAX_RESULTS would handed us an
     * arbitrary forty of the matches and quietly drop nearer ones. We take a
     * wide net and sort it ourselves; a few hundred elements is a few tens of
     * kilobytes.
     */
    private static final int OVERPASS_LIMIT = 250;

    /**
     * Overpass mirrors, tried in order. The main instance rate-limits and times
     * out under load often enough that a single endpoint is not dependable, and
     * it is the only source that works without an API key.
     */
    private static final String[] OVERPASS = {
            "https://overpass-api.de/api/interpreter",
            "https://overpass.kumi.systems/api/interpreter",
            "https://overpass.private.coffee/api/interpreter",
    };

    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "mawary-net");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Listener listener;
    private final AtomicInteger generation = new AtomicInteger();

    private final Runnable retry = this::retryLast;

    /**
     * Beyond this radius the unfiltered search stops asking for every shop and
     * asks only for things you could see from that far: a corner shop 8 km away
     * is not a landmark, and a city's worth of them is a query Overpass will
     * refuse to finish.
     */
    private static final int WIDE_RADIUS = 3000;

    /**
     * What a search word means in map tags. A search that only matched names
     * would be useless for the obvious cases: nobody types the name of the
     * mountain they are trying to find. Anything not listed here falls through
     * to a regular-expression search over names, which Overpass does natively.
     */
    private static final String[][] TOPICS = {
            {"\u5c71\u5cb3|\u5c71|\u5cf0|peak|mountain", "natural=peak", "natural=volcano"},
            {"\u99c5|\u9244\u9053|station", "railway=station", "railway=halt"},
            {"\u30b3\u30f3\u30d3\u30cb|konbini", "shop=convenience"},
            {"\u5e97|\u8cb7\u3044\u7269|\u30b7\u30e7\u30c3\u30d7|shop", "shop"},
            {"\u98df\u4e8b|\u30ec\u30b9\u30c8\u30e9\u30f3|\u98ef|\u98df\u5802|restaurant",
                    "amenity=restaurant", "amenity=fast_food"},
            {"\u30ab\u30d5\u30a7|\u55ab\u8336|cafe", "amenity=cafe"},
            {"\u516c\u5712|park", "leisure=park"},
            {"\u5b66\u6821|\u5927\u5b66|school", "amenity=school", "amenity=university"},
            {"\u75c5\u9662|\u533b\u9662|\u30af\u30ea\u30cb\u30c3\u30af|hospital",
                    "amenity=hospital", "amenity=clinic"},
            {"\u5bfa|\u795e\u793e|\u6559\u4f1a|temple|shrine", "amenity=place_of_worship"},
            {"\u6e29\u6cc9|\u9280\u6e6f|onsen", "natural=hot_spring", "amenity=public_bath"},
            {"\u30db\u30c6\u30eb|\u5bbf|\u65c5\u9928|hotel", "tourism=hotel", "tourism=guest_house"},
            {"\u9280\u884c|bank", "amenity=bank"},
            {"\u90f5\u4fbf|post", "amenity=post_office"},
            {"\u30c8\u30a4\u30ec|toilet", "amenity=toilets"},
            {"\u5ddd|\u6cb3|river", "waterway=river"},
            {"\u6e56|\u6c60|lake", "natural=water"},
            {"\u57ce|castle", "historic=castle"},
            {"\u5cf6|island", "place=island"},
            {"\u89b3\u5149|\u540d\u6240|sight", "tourism=attraction", "tourism=viewpoint"},
    };

    /** What a wide, unfiltered sweep looks for. */
    private static final String[] LANDMARKS = {
            "natural=peak", "natural=volcano", "railway=station",
            "tourism=attraction", "tourism=viewpoint", "historic=castle",
            "amenity=hospital", "amenity=university",
    };

    private final Context ctx;
    private final String sourceGoogle, sourceOsm;

    private String apiKey;
    private String query = "";

    /**
     * A request that arrived while another was still out. Typing a search word
     * during a slow Overpass round trip used to drop the search on the floor,
     * leaving the old, unfiltered answer on screen with the new word in the
     * title bar. Hold it and run it the moment the line is free.
     */
    private boolean pending;
    private double pendLat, pendLon;
    private int pendRadius;
    private volatile boolean inFlight;
    private double lastLat = Double.NaN, lastLon = Double.NaN;
    private int lastRadius;
    private long lastFetchMs;

    PlaceRepository(Context context, String apiKey, Listener listener) {
        this.ctx = context.getApplicationContext();
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.listener = listener;
        this.sourceGoogle = ctx.getString(R.string.source_google);
        this.sourceOsm = ctx.getString(R.string.source_osm);
    }

    /** The word the user is looking for, or empty for "whatever is around". */
    void setQuery(String q) {
        query = q == null ? "" : q.trim();
        invalidate();
    }

    String getQuery() {
        return query;
    }

    void setApiKey(String key) {
        apiKey = key == null ? "" : key.trim();
        invalidate();
    }

    /** Forces the next request through, ignoring the coalescing rules. */
    void invalidate() {
        lastLat = Double.NaN;
        lastFetchMs = 0L;
    }

    /** Lower case, so a topic is found whether it was typed Station or station. */
    private static String fold(String q) {
        return q.toLowerCase(Locale.US);
    }

    void shutdown() {
        main.removeCallbacks(retry);
        io.shutdownNow();
    }

    /** Re-runs the last request after a failure, so a flaky mirror is not fatal. */
    private void retryLast() {
        if (Double.isNaN(lastLat)) return;
        double lat = lastLat, lon = lastLon;
        int radius = lastRadius;
        invalidate();
        requestAround(lat, lon, radius);
    }

    /**
     * Asks for places around a point. Cheap to call often: it decides for
     * itself whether anything actually needs fetching.
     */
    void requestAround(double lat, double lon, int radiusM) {
        if (inFlight) {
            pending = true;
            pendLat = lat;
            pendLon = lon;
            pendRadius = radiusM;
            Log.i(TAG, "requestAround: held until the one in flight finishes");
            return;
        }
        long now = System.currentTimeMillis();
        if (!Double.isNaN(lastLat)) {
            double moved = Math.hypot(
                    (lon - lastLon) * Geo.metersPerDegLon(lat),
                    (lat - lastLat) * Geo.M_PER_DEG_LAT);
            boolean stale = now - lastFetchMs > STALE_MS;
            if (moved < radiusM * 0.25 && radiusM == lastRadius && !stale) {
                Log.i(TAG, "requestAround: skipped, moved " + (int) moved + "m");
                return;
            }
        }
        inFlight = true;
        main.removeCallbacks(retry);
        lastLat = lat;
        lastLon = lon;
        lastRadius = radiusM;
        lastFetchMs = now;

        final int gen = generation.incrementAndGet();
        Log.i(TAG, "requestAround: fetching r=" + radiusM + "m key=" + !apiKey.isEmpty());
        io.execute(() -> {
            List<Poi> result = null;
            String source = null;
            String status = null;
            if (!apiKey.isEmpty()) {
                try {
                    result = fetchGooglePlaces(lat, lon, radiusM);
                    source = sourceGoogle;
                } catch (Exception e) {
                    Log.w(TAG, "google places failed", e);
                    status = ctx.getString(R.string.err_google, shortMessage(e));
                }
            }
            if (result == null || result.isEmpty()) {
                try {
                    List<Poi> osm = fetchOverpass(lat, lon, radiusM);
                    if (!osm.isEmpty() || result == null) {
                        result = osm;
                        source = sourceOsm;
                    }
                } catch (Exception e) {
                    Log.w(TAG, "overpass failed", e);
                    if (status == null) status = ctx.getString(R.string.err_osm, shortMessage(e));
                }
            }
            final List<Poi> out = result;
            final String src = source;
            final String msg = status;
            Log.i(TAG, "fetch done: source=" + source
                    + " count=" + (result == null ? -1 : result.size()) + " status=" + status);
            main.post(() -> {
                inFlight = false;
                if (pending) {
                    pending = false;
                    invalidate();
                    requestAround(pendLat, pendLon, pendRadius);
                }
                if (gen != generation.get()) return;    // superseded by a newer request
                if (msg != null) listener.onStatus(msg);
                if (out != null) {
                    listener.onPlaces(out, src);
                } else {
                    // Every source refused. Come back to it rather than sitting
                    // on an error until the user happens to walk far enough.
                    lastFetchMs = 0L;
                    main.postDelayed(retry, RETRY_MS);
                }
            });
        });
    }

    private static String shortMessage(Exception e) {
        String m = e.getMessage();
        if (m == null || m.isEmpty()) return e.getClass().getSimpleName();
        return m.length() > 60 ? m.substring(0, 60) : m;
    }

    // ---------------------------------------------------------------- Google

    private List<Poi> fetchGooglePlaces(double lat, double lon, int radiusM) throws Exception {
        JSONObject center = new JSONObject().put("latitude", lat).put("longitude", lon);
        JSONObject circle = new JSONObject().put("center", center).put("radius", (double) radiusM);
        JSONObject body = new JSONObject()
                .put("languageCode", Locale.getDefault().getLanguage());

        // Nearby Search cannot take a word, so a search goes to Text Search and
        // leans on the same circle as a bias rather than a hard restriction.
        String url;
        if (query.isEmpty()) {
            url = "https://places.googleapis.com/v1/places:searchNearby";
            body.put("locationRestriction", new JSONObject().put("circle", circle))
                    .put("maxResultCount", 20)
                    .put("rankPreference", "DISTANCE");
        } else {
            url = "https://places.googleapis.com/v1/places:searchText";
            body.put("textQuery", query)
                    .put("locationBias", new JSONObject().put("circle", circle))
                    .put("maxResultCount", 20);
        }

        HttpURLConnection c = open(url);
        c.setRequestMethod("POST");
        c.setRequestProperty("Content-Type", "application/json");
        c.setRequestProperty("X-Goog-Api-Key", apiKey);
        c.setRequestProperty("X-Goog-FieldMask",
                "places.displayName,places.location,places.primaryTypeDisplayName");

        String json = post(c, body.toString().getBytes(StandardCharsets.UTF_8));
        JSONObject root = new JSONObject(json);
        if (root.has("error")) {
            throw new IllegalStateException(root.getJSONObject("error").optString("message", "denied"));
        }
        JSONArray places = root.optJSONArray("places");
        List<Poi> out = new ArrayList<>();
        if (places == null) return out;
        for (int i = 0; i < places.length() && out.size() < MAX_RESULTS; i++) {
            JSONObject p = places.optJSONObject(i);
            if (p == null) continue;
            JSONObject loc = p.optJSONObject("location");
            JSONObject name = p.optJSONObject("displayName");
            if (loc == null || name == null) continue;
            String text = name.optString("text", "");
            if (text.isEmpty()) continue;
            double plat = loc.optDouble("latitude", Double.NaN);
            double plon = loc.optDouble("longitude", Double.NaN);
            if (Double.isNaN(plat) || Double.isNaN(plon)) continue;
            JSONObject type = p.optJSONObject("primaryTypeDisplayName");
            out.add(new Poi(text, type == null ? "" : type.optString("text", ""), plat, plon));
        }
        return out;
    }

    // -------------------------------------------------------------- Overpass

    private List<Poi> fetchOverpass(double lat, double lon, int radiusM) throws Exception {
        Exception last = null;
        for (int i = 0; i < OVERPASS.length; i++) {
            String endpoint = OVERPASS[i];
            // Say which mirror we are on. Three of them, each allowed 20 s,
            // is a long time to leave the screen saying nothing at all.
            final String note = ctx.getString(R.string.searching_fmt, i + 1, OVERPASS.length);
            main.post(() -> listener.onStatus(note));
            try {
                return fetchOverpass(endpoint, lat, lon, radiusM);
            } catch (Exception e) {
                Log.w(TAG, "overpass mirror failed: " + endpoint + " (" + shortMessage(e) + ")");
                last = e;
            }
        }
        throw last == null ? new IllegalStateException("no mirror") : last;
    }

    private List<Poi> fetchOverpass(String endpoint, double lat, double lon, int radiusM)
            throws Exception {
        String around = String.format(Locale.US, "around:%d,%.6f,%.6f", radiusM, lat, lon);
        String q = "[out:json][timeout:20];(" + overpassBody(around, radiusM)
                + ");out center " + OVERPASS_LIMIT + ";";

        HttpURLConnection c = open(endpoint);
        c.setRequestMethod("POST");
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");

        byte[] payload = ("data=" + URLEncoder.encode(q, "UTF-8")).getBytes(StandardCharsets.UTF_8);
        JSONObject root = new JSONObject(post(c, payload));
        JSONArray elements = root.optJSONArray("elements");
        List<Poi> out = new ArrayList<>();
        if (elements == null) return out;
        for (int i = 0; i < elements.length(); i++) {
            JSONObject el = elements.optJSONObject(i);
            if (el == null) continue;
            JSONObject tags = el.optJSONObject("tags");
            if (tags == null) continue;
            String name = tags.optString("name", "");
            if (name.isEmpty()) continue;

            double elat, elon;
            if (el.has("lat")) {
                elat = el.optDouble("lat", Double.NaN);
                elon = el.optDouble("lon", Double.NaN);
            } else {
                JSONObject ctr = el.optJSONObject("center");
                if (ctr == null) continue;
                elat = ctr.optDouble("lat", Double.NaN);
                elon = ctr.optDouble("lon", Double.NaN);
            }
            if (Double.isNaN(elat) || Double.isNaN(elon)) continue;

            String kind = tags.optString("amenity",
                    tags.optString("shop", tags.optString("tourism",
                            tags.optString("leisure", ""))));
            out.add(new Poi(name, kind, elat, elon));
        }
        return nearest(out, lat, lon);
    }

    /** The statements that go inside the Overpass union, given what was asked for. */
    private String overpassBody(String around, int radiusM) {
        StringBuilder b = new StringBuilder(512);
        if (!query.isEmpty()) {
            String[] tags = topicFor(query);
            if (tags != null) {
                for (String tag : tags) both(b, around, tag);
            } else {
                // Not a topic we know, so search the names themselves. Overpass
                // matches these as regular expressions, which means the word has
                // to be handed over with its metacharacters defused.
                String re = escapeRegex(query);
                b.append("node(").append(around).append(")[name~\"").append(re)
                        .append("\"];");
                b.append("way(").append(around).append(")[name~\"").append(re)
                        .append("\"];");
            }
            return b.toString();
        }

        if (radiusM > WIDE_RADIUS) {
            for (String tag : LANDMARKS) both(b, around, tag);
            return b.toString();
        }

        for (String key : new String[]{"amenity", "shop", "tourism", "leisure", "office"}) {
            b.append("node(").append(around).append(")[name][").append(key).append("];");
        }
        b.append("node(").append(around).append(")[name][railway=station];");
        for (String key : new String[]{"amenity", "shop", "tourism", "leisure"}) {
            b.append("way(").append(around).append(")[name][").append(key).append("];");
        }
        return b.toString();
    }

    /** The same filter asked of both nodes and ways; a shop can be either. */
    private static void both(StringBuilder b, String around, String tag) {
        b.append("node(").append(around).append(")[name][").append(tag).append("];");
        b.append("way(").append(around).append(")[name][").append(tag).append("];");
    }

    /** The tags a search word stands for, or null if it is just a word. */
    private static String[] topicFor(String raw) {
        String q = fold(raw);
        for (String[] row : TOPICS) {
            for (String word : row[0].split("\\|")) {
                if (q.contains(word)) {
                    String[] tags = new String[row.length - 1];
                    System.arraycopy(row, 1, tags, 0, tags.length);
                    return tags;
                }
            }
        }
        return null;
    }

    private static final String SPECIALS = "\\^$.|?*+()[]{}";

    private static String escapeRegex(String s) {
        StringBuilder b = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (SPECIALS.indexOf(c) >= 0 || c == '\"') b.append('\\');
            b.append(c);
        }
        return b.toString();
    }

    /**
     * Keeps the MAX_RESULTS closest, since Overpass hands its matches back in
     * whatever order it found them.
     */
    private static List<Poi> nearest(List<Poi> all, double lat, double lon) {
        if (all.size() <= MAX_RESULTS) return all;
        final double mPerDegLon = Geo.metersPerDegLon(lat);
        for (int i = 0; i < all.size(); i++) {
            all.get(i).relocate(lat, lon, mPerDegLon);
        }
        Collections.sort(all, (a, b) -> Float.compare(a.distM, b.distM));
        return new ArrayList<>(all.subList(0, MAX_RESULTS));
    }

    // ------------------------------------------------------------------ HTTP

    private static HttpURLConnection open(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(CONNECT_MS);
        c.setReadTimeout(READ_MS);
        c.setDoOutput(true);
        c.setUseCaches(false);
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("Accept-Encoding", "gzip");
        c.setRequestProperty("User-Agent", "mawary/1.0 (Android)");
        return c;
    }

    private static String post(HttpURLConnection c, byte[] payload) throws Exception {
        try {
            c.setFixedLengthStreamingMode(payload.length);
            try (OutputStream os = c.getOutputStream()) {
                os.write(payload);
            }
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            if (in == null) throw new IllegalStateException("HTTP " + code);
            if ("gzip".equalsIgnoreCase(c.getContentEncoding())) {
                in = new java.util.zip.GZIPInputStream(in);
            }
            String text = readAll(in);
            if (code >= 400 && !text.startsWith("{")) {
                throw new IllegalStateException("HTTP " + code);
            }
            return text;
        } finally {
            c.disconnect();
        }
    }

    private static String readAll(InputStream in) throws Exception {
        try (InputStream src = in) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(8192);
            byte[] buf = new byte[8192];
            int n;
            while ((n = src.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toString("UTF-8");
        }
    }
}
