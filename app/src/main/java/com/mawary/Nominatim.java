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
 * caller to identify itself. A search the user submits costs one request, or a
 * few when a wide range has to be narrowed (see search), always paced at more
 * than a second apart.
 */
final class Nominatim {

    private static final String ENDPOINT = "https://nominatim.openstreetmap.org/search";
    private static final String UA = "mawary/1.0 (Android; github.com/iosxi/mawary)";

    private static final int CONNECT_MS = 6_000;
    private static final int READ_MS = 10_000;
    private static final int LIMIT = 40;
    /**
     * How many times a full answer may send us back with a smaller box. From
     * 10 km that reaches 625 m; each round costs a paced request, about 1.3 s.
     */
    private static final int MAX_ROUNDS = 5;
    private static final int MIN_BOX_M = 500;
    /** Their policy is one a second; leave a margin. */
    private static final long MIN_GAP_MS = 1_200L;

    private static long lastCallMs;

    private Nominatim() {}

    /**
     * Places within the range whose name carries the word, nearest ones
     * guaranteed.
     *
     * <p>Nominatim ranks by its own idea of importance, not by distance, and
     * stops at the limit. So a wide box that holds more matches than the limit
     * comes back full of far ones and none of the near: measured for 窓, the
     * 5 km box gave 8 hits, all within 5 km, the nearest at 772 m; the 10 km
     * box gave 40, the limit, and not one of them within 5 km — the nearest was
     * 5.9 km out. Asking for 50, their ceiling, changed nothing.
     *
     * <p>A full answer is therefore a cut one, and whenever one comes back full
     * the box is halved and asked again, until an answer comes back with room
     * to spare: that one holds everything in its box, so the near end is
     * complete. The answers are merged, and the distance sort downstream takes
     * it from there.
     */
    static List<Poi> search(String word, double lat, double lon, int radiusM) throws Exception {
        List<Poi> out = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        int[] raw = new int[1];
        int r = radiusM;
        for (int round = 0; round < MAX_ROUNDS; round++) {
            List<Poi> got = searchBox(word, lat, lon, r, raw);
            // Full is judged on what the service sent, not on what survived the
            // nameless being dropped: 40 sent with one unnamed is still cut.
            boolean full = raw[0] >= LIMIT;
            android.util.Log.i("mawary", "nominatim: box " + r + "m gave " + raw[0]
                    + (full ? " (full, halving)" : ""));
            for (Poi p : got) {
                // The same place turns up in every box that holds it.
                if (seen.add(p.name + "|" + Math.round(p.lat * 1e5) + "|" + Math.round(p.lon * 1e5))) {
                    out.add(p);
                }
            }
            if (!full || r <= MIN_BOX_M) break;
            r /= 2;
        }
        return out;
    }

    /**
     * One box, the size of the range; a box is what the service takes. How many
     * results the service sent, named or not, goes in raw[0].
     */
    private static synchronized List<Poi> searchBox(String word, double lat, double lon,
                                                    int radiusM, int[] raw) throws Exception {
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
        raw[0] = arr.length();
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
