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

    /** Smoothing factor per sample; ~120 ms time constant at the GAME rate. */
    private static final float ALPHA = 0.15f;

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
        rotationVector = sm == null ? null : sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR);
        accelerometer = sm == null ? null : sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        magnetometer = sm == null ? null : sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
    }

    boolean isAvailable() {
        return rotationVector != null || (accelerometer != null && magnetometer != null);
    }

    void start() {
        if (sm == null) return;
        if (rotationVector != null) {
            sm.registerListener(this, rotationVector, SensorManager.SENSOR_DELAY_GAME);
        } else if (accelerometer != null && magnetometer != null) {
            sm.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME);
            sm.registerListener(this, magnetometer, SensorManager.SENSOR_DELAY_UI);
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
        if (!primed) {
            smoothE = e;
            smoothN = n;
            primed = true;
        } else {
            smoothE += ALPHA * (e - smoothE);
            smoothN += ALPHA * (n - smoothN);
        }

        tiltDeg = Geo.backTilt(rotation);
        listener.onHeading(
                Geo.norm360((float) Math.toDegrees(Math.atan2(smoothE, smoothN))),
                tiltDeg, accuracy);
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int acc) {
        if (sensor.getType() == Sensor.TYPE_ROTATION_VECTOR
                || sensor.getType() == Sensor.TYPE_MAGNETIC_FIELD) {
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
