package com.mawary;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;

/**
 * Where the back of the phone is aimed, as a true-north bearing.
 *
 * <p>Prefers TYPE_ROTATION_VECTOR and falls back to raw accelerometer plus
 * magnetometer on devices that lack it. The matrix-to-bearing step lives in
 * {@link Geo#backAzimuth}, which explains why it uses the axes it does.
 */
final class Heading implements SensorEventListener {

    interface Listener {
        void onHeading(float azimuthDeg, float tiltDeg, int accuracy);
    }

    /** Smoothing factor per sample; about a 150 ms time constant at our rate. */
    private static final float ALPHA = 0.22f;

    /**
     * 30 Hz. The GAME rate delivers 54 Hz on this hardware, which is more
     * readings than a compass drawn for a human to read can use, and every one
     * of them wakes the application processor.
     */
    private static final int PERIOD_US = 33_000;

    private final SensorManager sm;
    private final Sensor rotationVector;
    private final Sensor accelerometer;
    private final Sensor magnetometer;
    private final Listener listener;

    private final float[] rotation = new float[9];
    private final float[] gravity = new float[3];
    private final float[] geomagnetic = new float[3];
    private boolean haveGravity, haveGeomagnetic;

    /** Smoothed heading as a unit vector, to keep the 359 -> 0 wrap harmless. */
    private float smoothE, smoothN;
    private boolean primed;

    private int accuracy = SensorManager.SENSOR_STATUS_UNRELIABLE;
    private float tiltDeg;

    Heading(Context ctx, Listener listener) {
        this.listener = listener;
        sm = (SensorManager) ctx.getSystemService(Context.SENSOR_SERVICE);
        // The geomagnetic rotation vector is the same fused orientation without
        // the gyroscope, which is the part that costs battery. A compass being
        // read by a walking human does not need the gyro's short-term accuracy,
        // and this one is smoothed anyway; fall back only if it is missing.
        Sensor rv = null;
        if (sm != null) {
            rv = sm.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR);
            if (rv == null) rv = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        }
        rotationVector = rv;
        accelerometer = sm == null ? null : sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        magnetometer = sm == null ? null : sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
    }

    boolean isAvailable() {
        return rotationVector != null || (accelerometer != null && magnetometer != null);
    }

    void start() {
        if (sm == null) return;
        if (rotationVector != null) {
            sm.registerListener(this, rotationVector, PERIOD_US);
        } else if (accelerometer != null && magnetometer != null) {
            sm.registerListener(this, accelerometer, PERIOD_US);
            sm.registerListener(this, magnetometer, PERIOD_US);
        }
    }

    void stop() {
        if (sm != null) sm.unregisterListener(this);
        primed = false;
        haveGravity = haveGeomagnetic = false;
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        switch (event.sensor.getType()) {
            case Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR:
            case Sensor.TYPE_ROTATION_VECTOR:
                SensorManager.getRotationMatrixFromVector(rotation, event.values);
                emit();
                break;
            case Sensor.TYPE_ACCELEROMETER:
                System.arraycopy(event.values, 0, gravity, 0, 3);
                haveGravity = true;
                if (haveGeomagnetic
                        && SensorManager.getRotationMatrix(rotation, null, gravity, geomagnetic)) {
                    emit();
                }
                break;
            case Sensor.TYPE_MAGNETIC_FIELD:
                System.arraycopy(event.values, 0, geomagnetic, 0, 3);
                haveGeomagnetic = true;
                break;
            default:
                break;
        }
    }

    /**
     * Folds a fresh rotation matrix into the smoothed heading and publishes it.
     * The angle itself is computed by {@link Geo#backAzimuth}; smoothing runs on
     * the unit vector rather than the angle so the 359-to-0 wrap stays harmless.
     */
    private void emit() {
        float azimuth = Geo.backAzimuth(rotation);
        if (Float.isNaN(azimuth)) return;

        double rad = Math.toRadians(azimuth);
        float e = (float) Math.sin(rad), n = (float) Math.cos(rad);
        // The tilt gets the same smoothing: things on screen follow it, and
        // unsmoothed they shiver with the hand.
        float tilt = Geo.backTilt(rotation);
        if (!primed) {
            smoothE = e;
            smoothN = n;
            tiltDeg = tilt;
            primed = true;
        } else {
            smoothE += ALPHA * (e - smoothE);
            smoothN += ALPHA * (n - smoothN);
            tiltDeg += ALPHA * (tilt - tiltDeg);
        }

        listener.onHeading(
                Geo.norm360((float) Math.toDegrees(Math.atan2(smoothE, smoothN))),
                tiltDeg, accuracy);
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int acc) {
        int type = sensor.getType();
        if (type == Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR
                || type == Sensor.TYPE_ROTATION_VECTOR
                || type == Sensor.TYPE_MAGNETIC_FIELD) {
            accuracy = acc;
        }
    }

    int getAccuracy() {
        return accuracy;
    }

    float getTiltDeg() {
        return tiltDeg;
    }
}
