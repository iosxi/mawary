package com.mawary;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Nominatim: OpenStreetMap's own search, used here for one job only — finding a
 * word the user typed inside the names of places nearby.
 *
 * <p>Measured against the alternatives for that job. Overpass can match a real
 * regular expression, which is the only way to find セイコーマート by typing
 * マート, but it took 2 to 12 seconds and refused outright a good share of the
 * time. Photon answers in two seconds and never refuses, but matches from the
 * start of a word, so マート finds マート島桝店 and nothing else. Nominatim
 * answered in 0.2 to 1.6 seconds, returned 200 to four requests fired with no
 * pause at all, and found ファミリーマート for マート.
 *
 * <p>It is not a substitute for the others. Its matching is by token, so
 * セイコー finds nothing at all where マート finds five, and it has no way to
 * ask "what is around me" — its reverse lookup returns a single feature. So it
 * sits in the middle: tried for a word before Overpass, never for the sweep.
 *
 * <p>Their usage policy asks for no more than one request a second and for the
 * caller to identify itself. One request per search the user actually submits
 * is well inside that, and the pacing below keeps it so.
 */
final class Nominatim {

    private static final String ENDPOINT = "https://nominatim.openstreetmap.org/search";
    private static final String UA = "mawary/1.0 (Android; github.com/iosxi/mawary)";

    private static final int CONNECT_MS = 6_000;
    private static final int READ_MS = 10_000;
    private static final int LIMIT = 40;
    /** Their policy is one a second; leave a margin. */
    private static final long MIN_GAP_MS = 1_200L;

    private static long lastCallMs;

    private Nominatim() {}

    /**
     * Places within a box around us whose name carries the word. The box is the
     * search range, not a circle; anything outside the range is dropped later
     * by the distance sort, and a box is what the service takes.
     */
    static synchronized List<Poi> search(String word, double lat, double lon, int radiusM)
            throws Exception {
        long since = System.currentTimeMillis() - lastCallMs;
        if (lastCallMs != 0L && since < MIN_GAP_MS) {
            Thread.sleep(MIN_GAP_MS - since);
        }
        lastCallMs = System.currentTimeMillis();

        double km = Math.max(0.2, radiusM / 1000.0);
        double dLat = km / 110.574;
        double dLon = km / (111.320 * Math.cos(Math.toRadians(lat)));
        String viewbox = String.format(Locale.US, "%.5f,%.5f,%.5f,%.5f",
                lon - dLon, lat + dLat, lon + dLon, lat - dLat);

        String url = ENDPOINT
                + "?q=" + URLEncoder.encode(word, "UTF-8")
                + "&format=jsonv2&limit=" + LIMIT
                + "&bounded=1&viewbox=" + viewbox
                + "&accept-language=ja";

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

        JSONArray arr = new JSONArray(text);
        List<Poi> out = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.optJSONObject(i);
            if (o == null) continue;
            String name = o.optString("name", "");
            if (name.isEmpty()) continue;
            double plat = o.optDouble("lat", Double.NaN);
            double plon = o.optDouble("lon", Double.NaN);
            if (Double.isNaN(plat) || Double.isNaN(plon)) continue;
            // jsonv2 calls the OSM key "category" and the value "type", which is
            // the same pair Photon returns as osm_key and osm_value.
            out.add(new Poi(name, o.optString("category", ""), o.optString("type", ""),
                    plat, plon));
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
