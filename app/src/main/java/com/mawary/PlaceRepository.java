package com.mawary;

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
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Fetches the things around us.
 *
 * <p>Primary source is Google Places API (New) Nearby Search, which is what the
 * app is for. It needs an API key, so when none is configured, or when Google
 * turns the request down, we fall back to the Overpass API over OpenStreetMap
 * data, which needs no key. That keeps the app useful on first launch instead
 * of showing an empty ring; the screen always names the source it drew from.
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

    static final String SOURCE_GOOGLE = "GOOGLE PLACES";
    static final String SOURCE_OSM = "OPENSTREETMAP";

    private static final long STALE_MS = 120_000L;
    private static final long RETRY_MS = 15_000L;
    private static final int CONNECT_MS = 8_000;
    /** Overpass asks its own backend for up to 20 s, so the read wait has to outlast that. */
    private static final int READ_MS = 25_000;
    private static final int MAX_RESULTS = 40;

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

    private String apiKey;
    private volatile boolean inFlight;
    private double lastLat = Double.NaN, lastLon = Double.NaN;
    private int lastRadius;
    private long lastFetchMs;

    PlaceRepository(String apiKey, Listener listener) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.listener = listener;
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
            Log.i(TAG, "requestAround: skipped, one already in flight");
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
                    source = SOURCE_GOOGLE;
                } catch (Exception e) {
                    Log.w(TAG, "google places failed", e);
                    status = "Places: " + shortMessage(e);
                }
            }
            if (result == null || result.isEmpty()) {
                try {
                    List<Poi> osm = fetchOverpass(lat, lon, radiusM);
                    if (!osm.isEmpty() || result == null) {
                        result = osm;
                        source = SOURCE_OSM;
                    }
                } catch (Exception e) {
                    Log.w(TAG, "overpass failed", e);
                    if (status == null) status = "OSM: " + shortMessage(e);
                }
            }
            final List<Poi> out = result;
            final String src = source;
            final String msg = status;
            Log.i(TAG, "fetch done: source=" + source
                    + " count=" + (result == null ? -1 : result.size()) + " status=" + status);
            main.post(() -> {
                inFlight = false;
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
                .put("locationRestriction", new JSONObject().put("circle", circle))
                .put("maxResultCount", 20)
                .put("rankPreference", "DISTANCE")
                .put("languageCode", Locale.getDefault().getLanguage());

        HttpURLConnection c = open("https://places.googleapis.com/v1/places:searchNearby");
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
        for (String endpoint : OVERPASS) {
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
        String q = "[out:json][timeout:20];("
                + "node(" + around + ")[name][amenity];"
                + "node(" + around + ")[name][shop];"
                + "node(" + around + ")[name][tourism];"
                + "node(" + around + ")[name][leisure];"
                + "node(" + around + ")[name][railway=station];"
                + "way(" + around + ")[name][amenity];"
                + ");out center " + MAX_RESULTS + ";";

        HttpURLConnection c = open(endpoint);
        c.setRequestMethod("POST");
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");

        byte[] payload = ("data=" + URLEncoder.encode(q, "UTF-8")).getBytes(StandardCharsets.UTF_8);
        JSONObject root = new JSONObject(post(c, payload));
        JSONArray elements = root.optJSONArray("elements");
        List<Poi> out = new ArrayList<>();
        if (elements == null) return out;
        for (int i = 0; i < elements.length() && out.size() < MAX_RESULTS; i++) {
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
        return out;
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
