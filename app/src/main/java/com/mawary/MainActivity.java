package com.mawary;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.Toast;

import java.util.List;

/**
 * Wires the three inputs — compass, position, places — into the one view.
 *
 * <p>Deliberately thin: no fragments, no view models, no support libraries. The
 * activity owns the sensor and location subscriptions and releases them in
 * onPause, so the app costs nothing while it is in the background.
 */
public final class MainActivity extends Activity
        implements LocationListener, PlaceRepository.Listener, WorldView.Listener {

    private static final int REQ_LOCATION = 1;
    // Position feeds distances that are drawn to the nearest metre and searches
    // that only rerun after a quarter of the range. Asking the radio for a fix
    // twice a second bought nothing and cost battery.
    private static final long LOC_MIN_MS = 4000L;
    private static final float LOC_MIN_M = 12f;

    private static final String PREFS = "mawary";
    private static final String KEY_API = "places_api_key";

    private WorldView view;
    private Heading heading;
    private PlaceRepository places;
    private LocationManager locations;

    private boolean tracking;
    /**
     * Asking again on every resume turns a single "deny" into a dialog that
     * reappears every time the app comes back, which looks exactly like the app
     * having seized up. Ask once per launch and then leave the user alone.
     */
    private boolean permissionAsked;
    /**
     * The last fix we were handed. Kept here so the tap handler never has to
     * call into the location service, which is a binder round trip and has no
     * business on the main thread in the middle of a gesture.
     */
    private Location lastFix;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        view = new WorldView(this);
        view.setListener(this);
        setContentView(view);

        // After setContentView: the insets controller hangs off the decor view,
        // which the window does not create until there is content to decorate.
        goFullscreen();

        heading = new Heading(this, (azimuth, tilt, accuracy) -> view.setHeading(azimuth, tilt, accuracy));
        if (!heading.isAvailable()) {
            view.setStatus(getString(R.string.no_compass));
        }

        places = new PlaceRepository(this, apiKey(), this);
        locations = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
    }

    private String apiKey() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String stored = prefs.getString(KEY_API, "");
        return stored.isEmpty() ? BuildConfig.PLACES_API_KEY : stored;
    }

    @Override
    protected void onResume() {
        super.onResume();
        heading.start();
        if (hasLocationPermission()) {
            view.setPermissionNeeded(false);
            startTracking();
        } else {
            view.setPermissionNeeded(true);
            if (!permissionAsked) {
                permissionAsked = true;
                requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                        REQ_LOCATION);
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        heading.stop();
        stopTracking();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        places.shutdown();
    }

    // ---------------------------------------------------------- permission

    /**
     * Only ACCESS_FINE_LOCATION is declared, but the grant dialog still offers
     * "approximate", and picking it grants ACCESS_COARSE_LOCATION even though
     * the manifest never asked for it (verified on Android 16). A coarse fix
     * puts the marks in roughly the right place, so take it rather than sulk.
     */
    private boolean hasLocationPermission() {
        return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != REQ_LOCATION) return;
        if (hasLocationPermission()) {
            view.setPermissionNeeded(false);
            startTracking();
        } else {
            view.setPermissionNeeded(true);
            view.setStatus(getString(R.string.location_denied));
        }
    }

    // ------------------------------------------------------------ location

    private void startTracking() {
        if (tracking || locations == null) return;
        try {
            // Seed the view with whatever fix is already lying around, so the
            // plan is populated before the first GPS update arrives.
            Location best = null;
            for (String provider : locations.getProviders(true)) {
                Location l = locations.getLastKnownLocation(provider);
                if (l == null) continue;
                if (best == null || l.getTime() > best.getTime()) best = l;
            }
            if (best != null) onLocationChanged(best);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    && locations.getAllProviders().contains(LocationManager.FUSED_PROVIDER)) {
                locations.requestLocationUpdates(
                        LocationManager.FUSED_PROVIDER, LOC_MIN_MS, LOC_MIN_M, this);
            } else {
                if (locations.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    locations.requestLocationUpdates(
                            LocationManager.GPS_PROVIDER, LOC_MIN_MS, LOC_MIN_M, this);
                }
                if (locations.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                    locations.requestLocationUpdates(
                            LocationManager.NETWORK_PROVIDER, LOC_MIN_MS, LOC_MIN_M, this);
                }
            }
            tracking = true;
        } catch (SecurityException e) {
            view.setStatus(getString(R.string.location_denied));
        }
    }

    private void stopTracking() {
        if (!tracking || locations == null) return;
        try {
            locations.removeUpdates(this);
        } catch (SecurityException ignored) {
            // Permission revoked while we were running; nothing left to remove.
        }
        tracking = false;
    }

    @Override
    public void onLocationChanged(Location location) {
        lastFix = location;
        view.setOrigin(location.getLatitude(), location.getLongitude(),
                location.hasAccuracy() ? location.getAccuracy() : 0f);
        places.requestAround(location.getLatitude(), location.getLongitude(), view.getRangeM());
    }

    // LocationListener keeps these abstract on API levels below 30.
    @Override
    public void onProviderEnabled(String provider) {}

    @Override
    public void onProviderDisabled(String provider) {}

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {}

    // -------------------------------------------------------------- places

    @Override
    public void onPlaces(List<Poi> found, String source) {
        view.setPlaces(found, source);
        if (found.isEmpty()) view.setStatus(getString(R.string.none_in_range));
        else view.setStatus("");
    }

    @Override
    public void onStatus(String message) {
        view.setStatus(message);
    }

    // ----------------------------------------------------------- view taps

    @Override
    public void onRangeChanged(int radiusM) {
        places.invalidate();
        if (lastFix != null) {
            places.requestAround(lastFix.getLatitude(), lastFix.getLongitude(), radiusM);
        }
    }

    @Override
    public void onConfigureRequested() {
        final SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        input.setSingleLine(true);
        input.setHint("AIza...");
        input.setText(prefs.getString(KEY_API, ""));
        input.setTextColor(Color.WHITE);

        new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog)
                .setTitle(R.string.api_key_title)
                .setMessage(R.string.api_key_message)
                .setView(input)
                .setPositiveButton(android.R.string.ok, (d, which) -> {
                    String key = input.getText().toString().trim();
                    prefs.edit().putString(KEY_API, key).apply();
                    places.setApiKey(key.isEmpty() ? BuildConfig.PLACES_API_KEY : key);
                    if (lastFix != null) {
                        places.requestAround(lastFix.getLatitude(), lastFix.getLongitude(),
                                view.getRangeM());
                    }
                    Toast.makeText(this,
                            key.isEmpty() ? R.string.using_osm : R.string.using_google,
                            Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    // ------------------------------------------------------------ chrome

    private void goFullscreen() {
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.systemBars());
                c.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
    }
}
