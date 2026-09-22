package com.mawary;

/**
 * One "thing" out there. Distance and bearing are cached rather than recomputed
 * per frame: they only change when our own position does, which is rare
 * compared to the ~50 Hz the compass ticks at.
 */
final class Poi {

    final String name;
    /** The OSM tag value, e.g. "convenience"; or Google's type name. */
    final String kind;
    /** The OSM tag key, e.g. "shop". Empty when the place came from Google. */
    final String key;
    final double lat;
    final double lon;

    /** Great-circle-ish distance from us, in metres. */
    float distM;
    /** True bearing from us, degrees clockwise from north. */
    float bearingDeg;
    /** Pre-formatted "84m" / "1.2km" so onDraw() never allocates. */
    String distLabel = "";
    /** Name trimmed to the width it is drawn at, out in the field. */
    String fieldLabel = "";
    /** Name trimmed to the width it is drawn at, in the list. */
    String listLabel = "";
    /** Type size the list row needs so this name fits without being cut. */
    float listSize;

    Poi(String name, String kind, double lat, double lon) {
        this(name, "", kind, lat, lon);
    }

    Poi(String name, String key, String kind, double lat, double lon) {
        this.name = name;
        this.key = key;
        this.kind = kind;
        this.lat = lat;
        this.lon = lon;
    }

    /** Recomputes distance, bearing and the distance label against a new origin. */
    void relocate(double myLat, double myLon, double mPerDegLon) {
        double dx = (lon - myLon) * mPerDegLon;          // east
        double dy = (lat - myLat) * Geo.M_PER_DEG_LAT;   // north
        distM = (float) Math.hypot(dx, dy);
        bearingDeg = Geo.norm360((float) Math.toDegrees(Math.atan2(dx, dy)));
        distLabel = format(distM);
    }

    static String format(float m) {
        if (m < 1000f) return ((int) (m + 0.5f)) + "m";
        return String.format(java.util.Locale.US, "%.1fkm", m / 1000f);
    }
}
