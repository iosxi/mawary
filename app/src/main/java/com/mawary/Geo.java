package com.mawary;

/**
 * Local-scale geodesy. Everything here is an equirectangular approximation:
 * within a few kilometres the error against the haversine formula stays well
 * under a metre, and it costs one cosine instead of four trig calls.
 */
final class Geo {

    static final double M_PER_DEG_LAT = 110574.0;

    private Geo() {}

    /** Metres per degree of longitude at the given latitude. */
    static double metersPerDegLon(double lat) {
        return 111320.0 * Math.cos(Math.toRadians(lat));
    }

    /** Normalises an angle in degrees to [0, 360). */
    static float norm360(float deg) {
        deg = deg % 360f;
        return deg < 0f ? deg + 360f : deg;
    }

    /** Signed difference a - b, folded into (-180, 180]. */
    static float delta180(float a, float b) {
        float d = (a - b) % 360f;
        if (d > 180f) d -= 360f;
        if (d <= -180f) d += 360f;
        return d;
    }

    static final String[] CARDINAL_16 = {
            "N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
            "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW"
    };

    static String cardinal(float azimuthDeg) {
        int i = (int) ((norm360(azimuthDeg) + 11.25f) / 22.5f) & 15;
        return CARDINAL_16[i];
    }

    /**
     * True bearing, in degrees clockwise from north, that the back of the phone
     * is aimed at. {@code r} is a 3x3 row-major rotation matrix mapping device
     * coordinates to world coordinates (x=east, y=north, z=up), i.e. what
     * {@code SensorManager.getRotationMatrixFromVector} produces.
     *
     * <p>Taking the rear camera axis (device -Z) alone breaks down when the
     * phone lies flat: -Z then points at the ground and its horizontal
     * projection is pure noise. Switching axes past a tilt threshold is the
     * usual fix, but it makes the reading jump at the switch.
     *
     * <p>So we add the horizontal projections of two device axes: -Z (back) and
     * +Y (top edge). Held upright, -Z carries the whole vector and +Y projects
     * to nothing; laid flat the roles swap; in between both project onto the
     * same azimuth. Each vector is weighted by its own horizontal length simply
     * by not normalising it first, so the sum is continuous across the whole
     * tilt range with no threshold anywhere. The two axes are orthogonal, so
     * they can never both be vertical and the sum can never vanish.
     *
     * @return NaN if the matrix is degenerate.
     */
    static float backAzimuth(float[] r) {
        float e = -r[2] + r[1];   // (device -Z) + (device +Y), east components
        float n = -r[5] + r[4];   // ditto, north components
        if (Math.hypot(e, n) < 1e-4) return Float.NaN;
        return norm360((float) Math.toDegrees(Math.atan2(e, n)));
    }

    /** How far the back of the phone is tilted off horizontal; +90 is straight up. */
    static float backTilt(float[] r) {
        float up = -r[8];
        return (float) Math.toDegrees(Math.asin(Math.max(-1f, Math.min(1f, up))));
    }
}
