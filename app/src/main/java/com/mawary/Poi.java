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
    /** The middle of the thing. What a map is asked to look near. */
    final double lat;
    final double lon;

    /**
     * The outline, when the place is mapped as an area rather than a point:
     * one {@code double[]} per piece of boundary, holding lat, lon, lat, lon…
     * A way arrives as a single closed ring; a relation arrives as its member
     * ways, which join up into rings between them. Null for a point.
     *
     * <p>This is here because a shop is not where its middle is. Measured
     * against real OSM data in Sapporo, standing five metres from the wall,
     * the middle of the building is 18 m away for a convenience store, 18 m
     * for a filling station, 41 m for a supermarket, 82 m for a school and
     * 116 m for a park. Those were the numbers the app used to show.
     */
    final double[][] rings;
    /** Whether {@link #rings} closes, so "am I standing in it" is a fair question. */
    final boolean closed;
    /** One of {@link PlaceRepository#LANDMARKS}: named first when names run short. */
    final boolean landmark;

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
    /** Drawn widths of fieldLabel and distLabel, measured with the data, not per frame. */
    float nameW, distW;
    /** Where the stake's foot landed this frame. Only meaningful while it is in view. */
    float sx, sy;
    /**
     * The spot the label took last frame, or -1. Kept so a label stays put as
     * the view turns, instead of hopping between equally good spots.
     */
    int labelSpot = -1;

    Poi(String name, String kind, double lat, double lon) {
        this(name, "", kind, lat, lon);
    }

    Poi(String name, String key, String kind, double lat, double lon) {
        this(name, key, kind, lat, lon, null, false);
    }

    Poi(String name, String key, String kind, double lat, double lon,
        double[][] rings, boolean closed) {
        this.name = name;
        this.key = key;
        this.kind = kind;
        this.lat = lat;
        this.lon = lon;
        this.rings = rings;
        this.closed = closed;
        this.landmark = isLandmark(key, kind);
    }

    private static boolean isLandmark(String key, String kind) {
        if (key.isEmpty()) return false;
        String tag = key + "=" + kind;
        for (String l : PlaceRepository.LANDMARKS) {
            if (l.equals(tag)) return true;
        }
        return false;
    }

    /** Recomputes distance, bearing and the distance label against a new origin. */
    void relocate(double myLat, double myLon, double mPerDegLon) {
        double dx = (lon - myLon) * mPerDegLon;          // east
        double dy = (lat - myLat) * Geo.M_PER_DEG_LAT;   // north
        if (rings != null && nearestOnOutline(myLat, myLon, mPerDegLon)) {
            // Standing inside it, the nearest wall is behind us and pointing at
            // it would be wrong; the middle is the one direction that still
            // means something. The distance stays zero.
            if (distM == 0f) {
                bearingDeg = Geo.norm360((float) Math.toDegrees(Math.atan2(dx, dy)));
            }
            return;
        }
        distM = (float) Math.hypot(dx, dy);
        bearingDeg = Geo.norm360((float) Math.toDegrees(Math.atan2(dx, dy)));
        distLabel = format(distM);
    }

    /**
     * Distance and bearing to the nearest point of the outline, or zero when we
     * are standing inside it. Everything is worked in metres east and north of
     * us, so we sit at the origin and the sums stay small.
     *
     * <p>The inside test counts how often the boundary crosses the ray running
     * east from us: an odd count means in. It runs over every segment of every
     * ring at once, which is what makes a relation's separate member ways come
     * out right without stitching them back together first, and makes a
     * courtyard punched out of a building count as outside.
     */
    private boolean nearestOnOutline(double myLat, double myLon, double mPerDegLon) {
        double bestD2 = Double.MAX_VALUE, bestX = 0, bestY = 0;
        boolean in = false;
        for (double[] ring : rings) {
            int n = ring.length / 2;
            if (n < 2) continue;
            double px = (ring[1] - myLon) * mPerDegLon;
            double py = (ring[0] - myLat) * Geo.M_PER_DEG_LAT;
            for (int i = 1; i < n; i++) {
                double qx = (ring[2 * i + 1] - myLon) * mPerDegLon;
                double qy = (ring[2 * i] - myLat) * Geo.M_PER_DEG_LAT;
                double vx = qx - px, vy = qy - py;
                double len2 = vx * vx + vy * vy;
                double t = len2 == 0 ? 0 : -(px * vx + py * vy) / len2;
                if (t < 0) t = 0;
                else if (t > 1) t = 1;
                double sxm = px + t * vx, sym = py + t * vy;
                double d2 = sxm * sxm + sym * sym;
                if (d2 < bestD2) {
                    bestD2 = d2;
                    bestX = sxm;
                    bestY = sym;
                }
                if (closed && (py > 0) != (qy > 0) && px + (-py) * vx / vy > 0) in = !in;
                px = qx;
                py = qy;
            }
        }
        if (bestD2 == Double.MAX_VALUE) return false;   // nothing usable; fall back to the middle
        distM = in ? 0f : (float) Math.sqrt(bestD2);
        bearingDeg = Geo.norm360((float) Math.toDegrees(Math.atan2(bestX, bestY)));
        distLabel = format(distM);
        return true;
    }

    static String format(float m) {
        if (m < 1000f) return ((int) (m + 0.5f)) + "m";
        return String.format(java.util.Locale.US, "%.1fkm", m / 1000f);
    }
}
