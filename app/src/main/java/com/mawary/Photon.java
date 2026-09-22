package com.mawary;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Photon: OpenStreetMap data served by a search index instead of a database.
 *
 * <p>It is the keyless source that actually behaves. Measured against the same
 * spot and the same question: Overpass answered in 2 to 12 seconds and refused
 * outright a good share of the time, taking nine to eleven seconds to say so;
 * Photon answered every one of five requests fired back to back, each in about
 * two seconds, and refused none. For a screen a person is standing outdoors
 * waiting on, being dependable is worth more than being occasionally quicker.
 *
 * <p>Two things about it shape how it is used here.
 *
 * <p><b>It returns fifty results and no more</b>, whatever limit is asked for.
 * One sweep for everything therefore reaches only as far as the fiftieth
 * nearest thing — about 580 m in a built-up area, which leaves the far half of
 * the view empty. Asking once per kind of place instead gives each kind its own
 * fifty, and the sweep reaches about 2 km. The requests are independent, so
 * they go out together.
 *
 * <p><b>Its text search matches from the start of a word</b>, not anywhere
 * inside it. Searching マート finds マート島桝店 but not セイコーマート, which
 * is the wrong way round for the way people actually search. Word searches are
 * therefore not sent here; they are answered from what is already held and
 * refined by Overpass, which matches on a regular expression.
 */
final class Photon {

    private static final String TAG = "mawary";
    private static final String ENDPOINT = "https://photon.komoot.io/reverse";
    private static final String UA = "mawary/1.0 (Android; github.com/iosxi/mawary)";

    private static final int CONNECT_MS = 6_000;
    private static final int READ_MS = 12_000;
    /** Photon caps this itself; asking for more is harmless and documents the intent. */
    private static final int LIMIT = 50;
    private static final long BUDGET_MS = 14_000L;

    private Photon() {}

    /**
     * Asks for everything of each given kind around a point, all at once, and
     * returns them merged. A group is a set of OSM tags that go in one request,
     * where {@code "shop"} means any shop and {@code "amenity:cafe"} means that
     * one value.
     */
    static List<Poi> nearby(String[][] groups, double lat, double lon, int radiusM,
                            ExecutorService pool) throws Exception {
        final double radiusKm = Math.max(0.1, radiusM / 1000.0);
        List<Future<List<Poi>>> futures = new ArrayList<>(groups.length);
        for (String[] group : groups) {
            final String url = url(group, lat, lon, radiusKm);
            futures.add(pool.submit(new Callable<List<Poi>>() {
                @Override
                public List<Poi> call() throws Exception {
                    return fetch(url);
                }
            }));
        }

        List<Poi> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Exception last = null;
        long deadline = System.currentTimeMillis() + BUDGET_MS;
        for (Future<List<Poi>> f : futures) {
            long left = deadline - System.currentTimeMillis();
            try {
                for (Poi p : f.get(Math.max(1, left), TimeUnit.MILLISECONDS)) {
                    // The groups overlap at the edges, and a place tagged twice
                    // would otherwise be drawn twice on the same spot.
                    String id = p.name + "@" + Math.round(p.lat * 1e5)
                            + "," + Math.round(p.lon * 1e5);
                    if (seen.add(id)) out.add(p);
                }
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                last = cause instanceof Exception ? (Exception) cause
                        : new IllegalStateException(String.valueOf(cause));
                Log.w(TAG, "photon group failed (" + last.getMessage() + ")");
            } catch (Exception e) {
                last = e;
                f.cancel(true);
            }
        }
        if (out.isEmpty() && last != null) throw last;
        return out;
    }

    private static String url(String[] tags, double lat, double lon, double radiusKm)
            throws Exception {
        StringBuilder b = new StringBuilder(ENDPOINT);
        b.append(String.format(Locale.US, "?lat=%.6f&lon=%.6f&radius=%.3f&limit=%d",
                lat, lon, radiusKm, LIMIT));
        for (String tag : tags) {
            b.append("&osm_tag=").append(URLEncoder.encode(tag, "UTF-8"));
        }
        // Several osm_tag values read as "any of these", which is what a sweep
        // for places of several kinds wants.
        return b.toString();
    }

    private static List<Poi> fetch(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(CONNECT_MS);
        c.setReadTimeout(READ_MS);
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("Accept-Encoding", "gzip");
        c.setRequestProperty("User-Agent", UA);
        String text;
        try {
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            if (in == null) throw new IllegalStateException("HTTP " + code);
            if ("gzip".equalsIgnoreCase(c.getContentEncoding())) {
                in = new java.util.zip.GZIPInputStream(in);
            }
            text = readAll(in);
            if (code >= 400) throw new IllegalStateException("HTTP " + code);
        } finally {
            c.disconnect();
        }

        JSONArray features = new JSONObject(text).optJSONArray("features");
        List<Poi> out = new ArrayList<>();
        if (features == null) return out;
        for (int i = 0; i < features.length(); i++) {
            JSONObject f = features.optJSONObject(i);
            if (f == null) continue;
            JSONObject props = f.optJSONObject("properties");
            JSONObject geom = f.optJSONObject("geometry");
            if (props == null || geom == null) continue;
            String name = props.optString("name", "");
            if (name.isEmpty()) continue;
            JSONArray coords = geom.optJSONArray("coordinates");
            if (coords == null || coords.length() < 2) continue;
            double lon = coords.optDouble(0, Double.NaN);
            double lat = coords.optDouble(1, Double.NaN);
            if (Double.isNaN(lat) || Double.isNaN(lon)) continue;
            out.add(new Poi(name, props.optString("osm_key", ""),
                    props.optString("osm_value", ""), lat, lon));
        }
        return out;
    }

    private static String readAll(InputStream in) throws Exception {
        try (InputStream src = in) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(16384);
            byte[] buf = new byte[8192];
            int n;
            while ((n = src.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toString("UTF-8");
        }
    }
}
