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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
    private static final int CONNECT_MS = 6_000;
    /** Overpass asks its own backend for up to 20 s, so the read wait has to outlast that. */
    /** A refusal arrives in about ten seconds, so waiting twenty buys nothing. */
    private static final int READ_MS = 14_000;
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
     * Overpass endpoints, tried in order.
     *
     * <p>These three were measured, not guessed. Of the public instances,
     * overpass-api.de and its two aliases were the only ones that answered at
     * all from a Japanese connection with Japanese data; kumi.systems,
     * private.coffee and monicz.dev accepted the TCP connection and then never
     * replied, and overpass.osm.ch answers in a second but only holds Swiss
     * data. Keeping dead names in this list was worse than having no fallback:
     * each one burned the full read timeout before the next was tried, which is
     * what turned a rate-limited request into a minute of "searching".
     *
     * <p>The three share a rate limit, but not their load: the main name has
     * returned 504 while lz4 answered in 1.4 s.
     */
    private static final String[] OVERPASS = {
            "https://overpass-api.de/api/interpreter",
            "https://lz4.overpass-api.de/api/interpreter",
            "https://z.overpass-api.de/api/interpreter",
    };

    /**
     * Overpass allows a couple of queries in quick succession and then answers
     * 429 — and takes about ten seconds to say so. Measured: two back-to-back
     * queries are fine, the third is refused. So we pace ourselves rather than
     * being paced.
     */
    private static final long MIN_GAP_MS = 1_500L;

    /**
     * How long one endpoint gets to itself before a second is asked in
     * parallel. Measured: a healthy answer comes back in 2 to 3 seconds, while
     * a refusal takes nine to eleven. Waiting for the refusal before trying
     * anywhere else is what made a search feel like it took a minute. Two at a
     * time is the most Overpass asks callers to run, so a third only starts
     * once one of the first two has given up.
     */
    private static final long HEDGE_MS = 2_500L;

    /** Total time all endpoints together get before the attempt is a failure. */
    private static final long BUDGET_MS = 18_000L;

    /** How long a fetched answer stays good enough to hand back without asking again. */
    private static final long CACHE_TTL_MS = 180_000L;
    private static final int CACHE_MAX = 8;

    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "mawary-net");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });
    /**
     * Endpoint attempts, at most two at once. Separate from the single-threaded
     * queue above, which owns one search from start to finish.
     */
    private final ExecutorService net = Executors.newFixedThreadPool(3, r -> {
        Thread t = new Thread(r, "mawary-net-ep");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });
    /** Which endpoint leads next time, so no one of them takes every request. */
    private int rotation;

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
            {"\u89b3\u5149|\u540d\u6240|\u307f\u3069\u3053\u308d|\u898b\u3069\u3053\u308d|sight",
                    "tourism=attraction", "tourism=viewpoint", "tourism=museum", "tourism=theme_park", "tourism=zoo", "tourism=aquarium"},
            {"\u666f\u52dd|\u7d76\u666f|\u5c55\u671b|\u773a\u3081|scenic",
                    "tourism=viewpoint", "natural=peak", "waterway=waterfall", "tourism=attraction"},
            {"\u53f2\u8de1|\u65e7\u8de1|\u907a\u8de1|\u6b74\u53f2|historic", "historic"},
            {"\u535a\u7269\u9928|\u7f8e\u8853\u9928|\u8cc7\u6599\u9928|museum",
                    "tourism=museum", "tourism=artwork"},
            {"\u904a\u5712\u5730|theme", "tourism=theme_park"},
            {"\u52d5\u7269\u5712|zoo", "tourism=zoo"},
            {"\u6c34\u65cf\u9928|aquarium", "tourism=aquarium"},
            {"\u6edd|waterfall", "waterway=waterfall"},
            {"\u6d77\u6c34\u6d74|\u30d3\u30fc\u30c1|\u7802\u6d5c|beach", "natural=beach"},
            {"\u5c71\u5cb3|\u5cf0|\u767b\u5c71|peak|mountain",
                    "natural=peak", "natural=volcano"},
            {"\u99c5|\u9244\u9053|station", "railway=station", "railway=halt"},
            {"\u30b3\u30f3\u30d3\u30cb|konbini", "shop=convenience"},
            {"\u98df\u4e8b|\u30ec\u30b9\u30c8\u30e9\u30f3|\u98ef|\u98df\u5802|restaurant",
                    "amenity=restaurant", "amenity=fast_food"},
            {"\u30ab\u30d5\u30a7|\u55ab\u8336|cafe", "amenity=cafe"},
            {"\u516c\u5712|park", "leisure=park"},
            {"\u5b66\u6821|\u5927\u5b66|school", "amenity=school", "amenity=university"},
            {"\u75c5\u9662|\u533b\u9662|\u30af\u30ea\u30cb\u30c3\u30af|hospital",
                    "amenity=hospital", "amenity=clinic"},
            {"\u5bfa|\u795e\u793e|\u6559\u4f1a|temple|shrine", "amenity=place_of_worship"},
            {"\u6e29\u6cc9|\u92ad\u6e6f|\u98a8\u5442|onsen", "amenity=public_bath"},
            {"\u30db\u30c6\u30eb|\u5bbf|\u65c5\u9928|hotel",
                    "tourism=hotel", "tourism=guest_house"},
            {"\u9280\u884c|bank", "amenity=bank"},
            {"\u90f5\u4fbf|post", "amenity=post_office"},
            {"\u30c8\u30a4\u30ec|toilet", "amenity=toilets"},
            {"\u5ddd|\u6cb3|river", "waterway=river"},
            {"\u6e56|\u6c60|lake", "natural=water"},
            {"\u57ce|castle", "historic=castle"},
            {"\u5cf6|island", "place=island"},
            {"\u8cb7\u3044\u7269|\u30b7\u30e7\u30c3\u30d7|\u5e97|shop", "shop"},
            {"\u5c71|yama", "natural=peak", "natural=volcano"},
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

    /**
     * Answers we already have, keyed by what was asked and roughly where from.
     * Clearing a search word puts back exactly the question that was asked a
     * moment ago, and asking Overpass again for something we are still holding
     * is both slow and the quickest way to be rate limited.
     */
    private final LinkedHashMap<String, Cached> cache =
            new LinkedHashMap<String, Cached>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Cached> eldest) {
                    return size() > CACHE_MAX;
                }
            };

    /** When the last Overpass round trip finished, for pacing. */
    private long lastNetworkMs;
    /**
     * What the view is already showing. Position updates keep arriving while
     * standing still, and handing the same cached answer over every few seconds
     * would redraw the screen and throw away the user's place in the list.
     */
    private String deliveredKey = "";
    /** The key we last answered out of the sweep we were holding, so we do it once. */
    private String localKey = "";
    /** How long to wait before trying again after everything refused. */
    private long backoffMs = RETRY_MS;

    private static final class Cached {
        final List<Poi> places;
        final String source;
        final long at;

        Cached(List<Poi> places, String source, long at) {
            this.places = places;
            this.source = source;
            this.at = at;
        }
    }
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

    /**
     * What was asked, and roughly from where. The rounding is about 110 m,
     * which is the same order as the distance that makes us refetch anyway
     * (a quarter of the range). Rounding to 11 m meant that standing still with
     * a drifting GPS fix produced a different key every few seconds, and the
     * cache never hit the one case it exists for: taking a search word back
     * off again.
     */
    private String cacheKey(double lat, double lon, int radiusM) {
        return cacheKey(query, lat, lon, radiusM);
    }

    private static String cacheKey(String q, double lat, double lon, int radiusM) {
        return q + "|" + radiusM + "|"
                + Math.round(lat * 1000d) + "," + Math.round(lon * 1000d);
    }

    /** Forces the next request through, ignoring the coalescing rules. */
    void invalidate() {
        lastLat = Double.NaN;
        lastFetchMs = 0L;
    }

    /** The places already in hand whose name contains the word. */
    private static List<Poi> matching(List<Poi> all, String word) {
        String needle = fold(word);
        List<Poi> out = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) {
            Poi p = all.get(i);
            if (fold(p.name).contains(needle)) out.add(p);
        }
        return out;
    }

    /**
     * The places already in hand that carry one of a topic's tags. "shop" on
     * its own means any shop; "amenity=cafe" means that one value.
     */
    private static List<Poi> carrying(List<Poi> all, String[] tags) {
        List<Poi> out = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) {
            Poi p = all.get(i);
            for (String tag : tags) {
                int eq = tag.indexOf('=');
                boolean hit = eq < 0
                        ? p.key.equals(tag)
                        : p.key.equals(tag.substring(0, eq))
                                && p.kind.equals(tag.substring(eq + 1));
                if (hit) {
                    out.add(p);
                    break;
                }
            }
        }
        return out;
    }

    /** Lower case, so a topic is found whether it was typed Station or station. */
    private static String fold(String q) {
        return q.toLowerCase(Locale.US);
    }

    void shutdown() {
        main.removeCallbacks(retry);
        io.shutdownNow();
        net.shutdownNow();
    }

    /** Re-runs the last request after a failure, so a flaky mirror is not fatal. */
    private void retryLast() {
        double lat, lon;
        int radius;
        if (pending) {
            lat = pendLat;
            lon = pendLon;
            radius = pendRadius;
            pending = false;
        } else if (!Double.isNaN(lastLat)) {
            lat = lastLat;
            lon = lastLon;
            radius = lastRadius;
        } else {
            return;
        }
        invalidate();
        requestAround(lat, lon, radius);
    }

    /**
     * Asks for places around a point. Cheap to call often: it decides for
     * itself whether anything actually needs fetching.
     */
    void requestAround(double lat, double lon, int radiusM) {
        String key = cacheKey(lat, lon, radiusM);
        Cached hit = cache.get(key);
        if (hit != null && System.currentTimeMillis() - hit.at < CACHE_TTL_MS) {
            if (key.equals(deliveredKey)) return;   // already on screen
            // Supersede anything still in the air: it is answering an older
            // question than the one we are about to satisfy from memory.
            generation.incrementAndGet();
            pending = false;
            main.removeCallbacks(retry);
            lastLat = lat;
            lastLon = lon;
            lastRadius = radiusM;
            lastFetchMs = hit.at;
            Log.i(TAG, "requestAround: answered from cache, " + hit.places.size() + " held");
            deliveredKey = key;
            listener.onStatus("");
            listener.onPlaces(nearest(hit.places, lat, lon), hit.source);
            return;
        }

        // A word we have to ask Overpass about costs seconds even when it
        // works, and ten of them when it does not. But the unfiltered sweep for
        // this same spot is usually already in hand, and the answer is very
        // often sitting inside it. Show that straight away and let the real
        // query correct it when it lands.
        if (!query.isEmpty() && !key.equals(deliveredKey) && !key.equals(localKey)) {
            Cached base = cache.get(cacheKey("", lat, lon, radiusM));
            if (base != null && System.currentTimeMillis() - base.at < CACHE_TTL_MS) {
                String[] tags = topicFor(query);
                List<Poi> found = tags == null
                        ? matching(base.places, query)
                        : carrying(base.places, tags);
                if (!found.isEmpty()) {
                    localKey = key;
                    Log.i(TAG, "requestAround: " + found.size()
                            + " from what we already hold, asking anyway");
                    listener.onStatus(ctx.getString(R.string.from_hand));
                    listener.onPlaces(nearest(found, lat, lon), base.source);
                }
            }
        }

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
        long sinceNetwork = now - lastNetworkMs;
        if (lastNetworkMs != 0L && sinceNetwork < MIN_GAP_MS) {
            // Too soon. Hold it rather than spend it on a 429 that will take ten
            // seconds to arrive.
            pending = true;
            pendLat = lat;
            pendLon = lon;
            pendRadius = radiusM;
            main.removeCallbacks(retry);
            main.postDelayed(retry, MIN_GAP_MS - sinceNetwork);
            Log.i(TAG, "requestAround: pacing, " + (MIN_GAP_MS - sinceNetwork) + "ms to go");
            return;
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
            // A word the user typed is Nominatim's job: it answers in well
            // under a second and does not refuse, where Overpass is slow and
            // often will not answer at all. It matches by token rather than by
            // substring, so a miss here still falls through to Overpass.
            if ((result == null || result.isEmpty())
                    && !query.isEmpty() && topicFor(query) == null) {
                try {
                    List<Poi> hits = Nominatim.search(query, lat, lon, radiusM);
                    if (!hits.isEmpty()) {
                        result = hits;
                        source = sourceOsm;
                    }
                } catch (Exception e) {
                    Log.w(TAG, "nominatim failed (" + shortMessage(e) + ")");
                }
            }

            // Photon for everything else: the keyless source that answers in
            // about two seconds and does not refuse.
            if (result == null || result.isEmpty()) {
                String[][] groups = photonGroups(radiusM);
                if (groups != null) {
                    try {
                        List<Poi> got = Photon.nearby(groups, lat, lon, radiusM, net);
                        if (!got.isEmpty()) {
                            result = nearest(got, lat, lon, OVERPASS_LIMIT);
                            source = sourceOsm;
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "photon failed", e);
                    }
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
                    if (status == null) {
                        String m = shortMessage(e);
                        // 429 is Overpass saying "you are asking too often". It
                        // is not a fault to report as one.
                        status = m.contains("429")
                                ? ctx.getString(R.string.err_busy)
                                : ctx.getString(R.string.err_osm, m);
                    }
                }
            }
            final List<Poi> out = result;
            final String src = source;
            final String msg = status;
            Log.i(TAG, "fetch done: source=" + source
                    + " count=" + (result == null ? -1 : result.size()) + " status=" + status);
            main.post(() -> {
                inFlight = false;
                lastNetworkMs = System.currentTimeMillis();
                if (out != null && !out.isEmpty()) {
                    // The whole answer goes in, not the trimmed one: a later
                    // word search reads it to answer without asking again.
                    cache.put(key, new Cached(out, src, lastNetworkMs));
                    backoffMs = RETRY_MS;
                }
                if (pending) {
                    pending = false;
                    invalidate();
                    requestAround(pendLat, pendLon, pendRadius);
                }
                if (gen != generation.get()) return;    // superseded by a newer request
                if (msg != null) listener.onStatus(msg);
                if (out != null) {
                    deliveredKey = key;
                    listener.onPlaces(nearest(out, lat, lon), src);
                } else {
                    // Every endpoint refused. Come back to it rather than sitting
                    // on an error until the user happens to walk far enough, but
                    // back off: retrying hard is how the rate limit was earned.
                    lastFetchMs = 0L;
                    main.postDelayed(retry, backoffMs);
                    backoffMs = Math.min(backoffMs * 2, 60_000L);
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

    /**
     * Asks the endpoints for the same thing, one at a time but overlapping, and
     * returns whichever answers first.
     *
     * <p>The endpoints do not share a rate limit — measured: the main name
     * answered 429 while lz4 returned in 5.1 s and z in 3.0 s — so a refusal
     * from one says nothing about the others. Trying them strictly in turn
     * meant paying ten seconds for each refusal before learning that, which is
     * where the minute went.
     */
    private List<Poi> fetchOverpass(double lat, double lon, int radiusM) throws Exception {
        final String body = overpassBody(
                String.format(Locale.US, "around:%d,%.6f,%.6f", radiusM, lat, lon), radiusM);
        final int start = rotation++;
        final long deadline = System.currentTimeMillis() + BUDGET_MS;

        CompletionService<List<Poi>> cs = new ExecutorCompletionService<>(net);
        List<Future<List<Poi>>> live = new ArrayList<>(OVERPASS.length);
        Exception last = null;
        int submitted = 0, failed = 0;
        try {
            for (int i = 0; i < OVERPASS.length; i++) {
                final String endpoint = OVERPASS[(start + i) % OVERPASS.length];
                final int attempt = i + 1;
                main.post(() -> listener.onStatus(
                        ctx.getString(R.string.searching_fmt, attempt, OVERPASS.length)));
                live.add(cs.submit(new Callable<List<Poi>>() {
                    @Override
                    public List<Poi> call() throws Exception {
                        return fetchOverpass(endpoint, body);
                    }
                }));

                submitted++;
                boolean lastOne = i == OVERPASS.length - 1;
                long until = lastOne ? deadline
                        : Math.min(deadline, System.currentTimeMillis() + HEDGE_MS);
                while (failed < submitted) {
                    long left = until - System.currentTimeMillis();
                    if (left <= 0) break;
                    Future<List<Poi>> done = cs.poll(left, TimeUnit.MILLISECONDS);
                    if (done == null) break;
                    try {
                        return done.get();
                    } catch (ExecutionException e) {
                        Throwable cause = e.getCause();
                        last = cause instanceof Exception ? (Exception) cause
                                : new IllegalStateException(String.valueOf(cause));
                        failed++;
                        Log.w(TAG, "overpass endpoint failed (" + shortMessage(last) + ")");
                        // One refusal says nothing about the others, which are
                        // still out. Only once every attempt so far has come
                        // back empty is there any point starting another.
                    }
                }
            }
        } finally {
            for (Future<List<Poi>> f : live) f.cancel(true);
        }
        throw last == null ? new IllegalStateException("no endpoint") : last;
    }

    private List<Poi> fetchOverpass(String endpoint, String body) throws Exception {
        String q = "[out:json][timeout:18];(" + body + ");out center " + OVERPASS_LIMIT + ";";

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

            String tagKey = "", kind = "";
            for (String k : new String[]{"amenity", "shop", "tourism", "leisure",
                    "office", "railway", "natural", "historic", "place", "waterway"}) {
                String v = tags.optString(k, "");
                if (!v.isEmpty()) {
                    tagKey = k;
                    kind = v;
                    break;
                }
            }
            out.add(new Poi(name, tagKey, kind, elat, elon));
        }
        return out;
    }

    /**
     * What to ask Photon for, or null when Photon is the wrong tool.
     *
     * <p>Each entry is one request. Photon returns fifty results per request
     * whatever is asked of it, so splitting the sweep by kind of place is what
     * makes it reach past the nearest few hundred metres.
     */
    private String[][] photonGroups(int radiusM) {
        if (!query.isEmpty()) {
            String[] tags = topicFor(query);
            // A bare word needs a substring match, and Photon matches prefixes.
            if (tags == null) return null;
            // Photon gives fifty per request, so a topic with several tags is
            // split up rather than having them compete for one fifty.
            return chunk(photonTags(tags), 2);
        }
        if (radiusM > WIDE_RADIUS) {
            return new String[][]{
                    {"natural:peak", "natural:volcano"},
                    {"railway:station", "tourism:attraction", "tourism:viewpoint"},
                    {"historic:castle", "amenity:hospital", "amenity:university"},
            };
        }
        return new String[][]{
                {"amenity"},
                {"shop"},
                {"leisure", "tourism", "office", "railway:station"},
        };
    }

    /** Breaks a tag list into requests of at most {@code per} tags each. */
    private static String[][] chunk(String[] tags, int per) {
        int groups = (tags.length + per - 1) / per;
        String[][] out = new String[groups][];
        for (int g = 0; g < groups; g++) {
            int from = g * per, to = Math.min(tags.length, from + per);
            out[g] = new String[to - from];
            System.arraycopy(tags, from, out[g], 0, to - from);
        }
        return out;
    }

    /** Overpass writes a tag as key=value; Photon writes it as key:value. */
    private static String[] photonTags(String[] tags) {
        String[] out = new String[tags.length];
        for (int i = 0; i < tags.length; i++) out[i] = tags[i].replace('=', ':');
        return out;
    }

    /** The keys that make something a place worth showing. */
    private static final String[] POI_KEYS =
            {"amenity", "shop", "tourism", "leisure", "office"};

    /**
     * The statements that go inside the Overpass union, given what was asked for.
     *
     * <p>Every one of them is an {@code nwr}: one statement that covers nodes,
     * ways and relations at once. Asking for nodes and ways separately doubles
     * the statement count and costs far more than it looks — measured on the
     * same data, the plain sweep took 12.0 s written out as ten node and way
     * statements and 3.2 s written as six {@code nwr} ones.
     */
    private String overpassBody(String around, int radiusM) {
        StringBuilder b = new StringBuilder(512);
        if (!query.isEmpty()) {
            String[] tags = topicFor(query);
            if (tags != null) {
                for (String tag : tags) one(b, around, tag);
                return b.toString();
            }
            // Not a topic we know, so search the names themselves. Overpass
            // matches these as regular expressions, which means the word has to
            // be handed over with its metacharacters defused. The search is
            // pinned to the same keys the rest of the app uses, which keeps it
            // selective and stops a named kerb or road segment turning up as
            // though it were a place.
            String re = escapeRegex(query);
            for (String key : POI_KEYS) {
                b.append("nwr(").append(around).append(")[").append(key)
                        .append("][name~\"").append(re).append("\"];");
            }
            b.append("nwr(").append(around).append(")[name~\"").append(re)
                    .append("\"][railway];");
            return b.toString();
        }

        if (radiusM > WIDE_RADIUS) {
            for (String tag : LANDMARKS) one(b, around, tag);
            return b.toString();
        }

        for (String key : POI_KEYS) one(b, around, key);
        one(b, around, "railway=station");
        return b.toString();
    }

    /** One filter, asked of nodes, ways and relations together. */
    private static void one(StringBuilder b, String around, String tag) {
        b.append("nwr(").append(around).append(")[name][").append(tag).append("];");
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
        return nearest(all, lat, lon, MAX_RESULTS);
    }

    private static List<Poi> nearest(List<Poi> all, double lat, double lon, int keep) {
        if (all.size() <= keep) return all;
        final double mPerDegLon = Geo.metersPerDegLon(lat);
        for (int i = 0; i < all.size(); i++) {
            all.get(i).relocate(lat, lon, mPerDegLon);
        }
        Collections.sort(all, (a, b) -> Float.compare(a.distM, b.distM));
        return new ArrayList<>(all.subList(0, keep));
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
