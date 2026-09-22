package com.mawary;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
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

    static final String PREFS = "mawary";
    static final String KEY_API = "places_api_key";
    static final String KEY_LABEL_TRANSPARENCY = "label_transparency";
    static final String KEY_TILT_RANGE = "tilt_range";
    static final String KEY_HIDE_PIN_DISTANCE = "hide_pin_distance";

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
    /** Where the places on screen came from, for the settings screen to report. */
    private String lastSource = "";
    /** The key the repository is using, so a resume can tell whether it changed. */
    private String appliedKey;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        view = new WorldView(this);
        view.setListener(this);
        view.setLabelTransparency(labelTransparency());
        view.setTiltRange(tiltRange());
        view.setHidePinDistance(hidePinDistance());
        setContentView(view);

        // After setContentView: the insets controller hangs off the decor view,
        // which the window does not create until there is content to decorate.
        goFullscreen();

        heading = new Heading(this, (azimuth, tilt, accuracy) -> view.setHeading(azimuth, tilt, accuracy));
        if (!heading.isAvailable()) {
            view.setStatus(getString(R.string.no_compass));
        }

        places = new PlaceRepository(this, apiKey(), this);
        appliedKey = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_API, "");
        locations = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
    }

    private String apiKey() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String stored = prefs.getString(KEY_API, "");
        return stored.isEmpty() ? BuildConfig.PLACES_API_KEY : stored;
    }

    private int labelTransparency() {
        return getSharedPreferences(PREFS, MODE_PRIVATE)
                .getInt(KEY_LABEL_TRANSPARENCY, WorldView.DEFAULT_LABEL_TRANSPARENCY);
    }

    private boolean tiltRange() {
        return getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(KEY_TILT_RANGE, true);
    }

    private boolean hidePinDistance() {
        return getSharedPreferences(PREFS, MODE_PRIVATE)
                .getBoolean(KEY_HIDE_PIN_DISTANCE, false);
    }

    @Override
    protected void onResume() {
        super.onResume();
        heading.start();
        applySettings();
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
        view.setOrigin(location.getLatitude(), location.getLongitude());
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
        view.setPlaces(found);
        lastSource = source == null ? "" : source;
        if (found.isEmpty()) view.setStatus(getString(R.string.none_in_range));
        else view.setStatus("");
    }

    @Override
    public void onStatus(String message) {
        view.setStatus(message);
    }

    @Override
    public void onBusy(boolean busy) {
        view.setBusy(busy);
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
    public void onSearchTapped() {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setSingleLine(true);
        // A faint "検索ワード" rather than a sample word: a sample sitting in the
        // box reads as something already entered.
        input.setHint(R.string.search_hint);
        input.setHintTextColor(0x66FFFFFF);
        input.setText(places.getQuery());
        input.setTextColor(Color.WHITE);

        AlertDialog dialog = new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog)
                .setTitle(R.string.search_title)
                .setMessage(R.string.search_message)
                .setView(input)
                .setPositiveButton(android.R.string.ok,
                        (d, which) -> applyQuery(input.getText().toString()))
                .setNeutralButton(R.string.search_clear, (d, which) -> applyQuery(""))
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        openForTyping(dialog, input);
    }

    /**
     * Opens a prompt ready to be typed into: focused, keyboard already up, and
     * whatever was in the box selected.
     *
     * <p>Selecting the text is not enough on its own. An unfocused field draws
     * no selection, so there is nothing to see, and with no keyboard the only
     * way to get one is to tap the field — which puts a caret where the finger
     * landed and throws the selection away. Raising the keyboard for them is
     * what makes the selection both visible and worth having.
     */
    private void openForTyping(AlertDialog dialog, EditText input) {
        if (dialog.getWindow() != null) {
            dialog.getWindow().setSoftInputMode(
                    WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        }
        dialog.show();
        input.requestFocus();
        input.selectAll();
        // Some keyboards ignore the window flag, so ask once the view is laid out.
        input.post(() -> {
            InputMethodManager imm = getSystemService(InputMethodManager.class);
            if (imm != null) imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
            input.selectAll();
        });
    }

    private void applyQuery(String raw) {
        String q = raw == null ? "" : raw.trim();
        places.setQuery(q);
        view.setQuery(q);
        view.setPlaces(null);
        if (lastFix != null) {
            places.requestAround(lastFix.getLatitude(), lastFix.getLongitude(), view.getRangeM());
        }
        Toast.makeText(this,
                q.isEmpty() ? getString(R.string.search_cleared)
                        : getString(R.string.searching_for, q),
                Toast.LENGTH_SHORT).show();
    }

    /**
     * A place was tapped, in the field or in the list: hand it to Google Maps.
     *
     * <p>What goes over is the <i>name, searched around</i> the coordinates we
     * hold, not a pin dropped at them. The two maps do not put a thing in the
     * same spot — OpenStreetMap's からまつ公園 and Google's are tens of metres
     * apart — so a pin at our coordinates lands beside Google's own record of
     * the place and opens nothing but a bare marker: no hours, no rating, no
     * phone. Measured on the device: the same place with {@code q=lat,lon(name)}
     * gave a dropped pin with "基本情報 / 距離を測定", while {@code q=name} gave
     * the clinic's own page with its rating and a call button.
     *
     * <p>The coordinates are not wasted, they become the centre of the search:
     * "セイコーマート" sent with a point in 南郷 lists the 南郷 branches, and the
     * same word sent with a point by 札幌駅 lists すすきの and 南8条. So a name
     * shared by several branches still resolves to the nearby ones, which is
     * what the pin was guarding against.
     *
     * <p>Google Maps is asked for by name, since that is what was asked for,
     * and any other map app takes it if Maps is not installed. Both are tried
     * rather than resolved first: resolving would need the package declared
     * visible, and a throw is cheap and cannot go stale.
     */
    @Override
    public void onPlaceTapped(Poi place) {
        String at = place.lat + "," + place.lon;
        // Nameless is only possible in theory — every source drops a place
        // without a name — but with nothing to search for, the point itself is
        // the best that can be said.
        String q = place.name == null || place.name.isEmpty() ? at : place.name;
        Intent i = new Intent(Intent.ACTION_VIEW,
                Uri.parse("geo:" + at + "?q=" + Uri.encode(q)));
        i.setPackage("com.google.android.apps.maps");
        try {
            startActivity(i);
            return;
        } catch (ActivityNotFoundException noMaps) {
            i.setPackage(null);
        }
        try {
            startActivity(i);
        } catch (ActivityNotFoundException noMapAtAll) {
            Toast.makeText(this, R.string.no_map_app, Toast.LENGTH_SHORT).show();
        }
    }

    /** The gear: a full screen of settings, told what the main screen knows. */
    @Override
    public void onSettingsTapped() {
        Intent i = new Intent(this, SettingsActivity.class);
        i.putExtra(SettingsActivity.EXTRA_SOURCE, lastSource);
        i.putExtra(SettingsActivity.EXTRA_ACCURACY,
                lastFix == null ? -1f : lastFix.hasAccuracy() ? lastFix.getAccuracy() : 0f);
        startActivity(i);
    }

    /**
     * Picks up whatever the settings screen changed. Called on every resume,
     * which is cheap, and means there is no result to hand back and forth.
     */
    private void applySettings() {
        view.setLabelTransparency(labelTransparency());
        view.setTiltRange(tiltRange());
        view.setHidePinDistance(hidePinDistance());
        String stored = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_API, "");
        if (stored.equals(appliedKey)) return;
        appliedKey = stored;
        places.setApiKey(stored.isEmpty() ? BuildConfig.PLACES_API_KEY : stored);
        if (lastFix != null) {
            places.requestAround(lastFix.getLatitude(), lastFix.getLongitude(), view.getRangeM());
        }
        Toast.makeText(this, stored.isEmpty() ? R.string.using_osm : R.string.using_google,
                Toast.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------ chrome

    private void goFullscreen() {
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        // Edge to edge, but the navigation bar stays: hiding it took away the
        // back and home buttons, and the strip it occupies was never where the
        // useful part of the screen was. Only the status bar is reclaimed.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.statusBars());
                c.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_FULLSCREEN);
        }
    }
}
