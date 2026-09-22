package com.mawary;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Insets;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.hardware.SensorManager;
import android.os.Build;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The whole UI: a first-person view of the 180 degrees the phone is aimed at.
 *
 * <p>You are not looking down at a map. You are standing in the middle of it,
 * facing whichever way the back of the phone faces, and the screen is your
 * field of view: <b>left to right is bearing</b>, the full half-circle ahead
 * spread across the width, and <b>up the screen is further away</b>, running
 * from your own feet at the bottom to the range limit at the horizon. Whatever
 * is behind you is behind you, and is not drawn.
 *
 * <p>Your own position is the compass at the bottom: a thick disc lying on the
 * ground at your feet, which turns as you turn and opens from a slit into a
 * circle as you tip the phone down.
 *
 * <p>Two things keep this cheap. onDraw() allocates nothing — every paint,
 * path and label is built when the value behind it changes, not per frame. And
 * the view only redraws when something has actually moved: the compass reports
 * at 54 Hz whether or not the phone is moving, so redrawing on every reading
 * meant burning the CPU and GPU around the clock while standing still.
 */
final class WorldView extends View {

    interface Listener {
        /** The user cycled the range; time to widen or narrow the search. */
        void onRangeChanged(int radiusM);
        /** Long press: the user is asking for the API key prompt. */
        void onConfigureRequested();
        /** The magnifier: the user wants to say what they are looking for. */
        void onSearchTapped();
    }

    /**
     * Ranges reach far enough to be useful with a search word: mountains are
     * not 2 km away. Past WIDE_RADIUS the unfiltered sweep asks only for
     * landmarks, so the far end stays answerable.
     */
    private static final int[] RANGES = {100, 200, 500, 1000, 2000, 5000, 10000};

    /** Half-width of the field of view. The screen spans exactly this each way. */
    private static final float SPAN = 90f;
    /** Half-angle of the band counted as "dead ahead". */
    private static final float AIM = 25f;

    /** Fractions of the range that get a labelled ground band. */
    private static final float[] BANDS = {0.03f, 0.1f, 0.25f, 0.5f, 1f};
    /** How hard the ground curves away at the edges of the view. */
    private static final float BOW = 0.25f;

    private static final int MAX_FIELD_LABELS = 3;
    /** Deliberately not a whole number: a half row showing is what says "scroll me". */
    private static final float LIST_ROWS = 3.5f;

    /** Below this the reading has not visibly changed, so there is nothing to redraw. */
    private static final float HEADING_EPS = 0.4f;
    private static final float TILT_EPS = 0.8f;

    /** The list shrinks a name to fit rather than cutting it, down to this floor. */
    private static final float LIST_MAX_SP = 18f;
    private static final float LIST_MIN_SP = 12f;

    private static final int COL_BG = 0xFF05080A;
    private static final int COL_GRID = 0xFF27525C;
    private static final int COL_MID = 0xFF49A0A6;
    private static final int COL_ACCENT = 0xFF5CF0D8;
    private static final int COL_TARGET = 0xFFFFC46B;
    private static final int COL_TEXT = 0xFFE2FAF6;
    private static final int COL_DIM = 0xFFA6CFD0;
    /** North is red here for the same reason it is red on a real compass. */
    private static final int COL_NORTH = 0xFFFF5A5A;
    private static final int COL_SOUTH = 0xFF93ADAD;

    private final Paint pGrid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pRing = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pTarget = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pDial = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pNorth = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pSouth = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pBody = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pName = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pList = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pDist = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pSmall = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pTiny = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Path path = new Path();
    /** Label baselines already taken this frame, so names cannot pile up. */
    private final float[] labelSlots = new float[MAX_FIELD_LABELS];
    private int labelSlotCount;
    private final RectF oval = new RectF();
    private final GestureDetector gestures;
    private Listener listener;

    /** Set while a finger is on the range slider, so taps elsewhere stay unclaimed. */
    private boolean sliding;
    private int slideStartIndex;
    /** Set while a finger is dragging the list, or is down on the magnifier. */
    private boolean scrolling, tapIcon;
    private float lastTouchY;

    private final float dp;

    // --- live state -------------------------------------------------------
    private float headingDeg = Float.NaN;
    private float tiltDeg;
    private int compassAccuracy = SensorManager.SENSOR_STATUS_UNRELIABLE;

    private boolean haveFix;
    private double myLat, myLon;

    private final List<Poi> places = new ArrayList<>();
    /** Everything in the half-circle ahead, nearest first: what the list shows. */
    private final List<Poi> ahead = new ArrayList<>();
    /** How far the list is scrolled, and what it would take to scroll it all. */
    private float listScroll, listContentH, listViewH;
    private String source = "";
    private String status = "";
    private boolean permissionNeeded;

    private int rangeIndex = 3;   // 1000 m
    private String query = "";

    // --- wording, read once: onDraw() must not touch resources ------------
    private final String sScanning, sNeedPermission, sAcquiring, sSearching;
    private final String sNothingAhead, sStandBy, sCalibrate, sHint, sFixOk;
    private final String rangeFmt, fixFmt;
    private final String sSourceGoogle, sSourceOsm, sGoogleShort, sOsmShort;

    // --- cached labels ----------------------------------------------------
    private String headingLabel = "---";
    private int lastHeadingInt = -1;
    private String rangeLabel = "";
    private String rangeShort = "";
    private final String[] bandLabels = new String[BANDS.length];
    private String fixLabel;
    /** Source and fix accuracy, joined once: the footer has no room to spare. */
    private String footerRight = "";

    // --- geometry, resolved in layoutGeometry -----------------------------
    private float cx, pad;
    private float statusY;
    private float fieldTop, fieldBottom, horizonBase;
    private float halfSpanPx;
    private float compassRx, compassThick, compassCy;
    private float listTop, rowH, hintY;
    private float sliderX, sliderTop, sliderBottom, sliderHit;
    private float iconCx, iconCy, iconR;
    private int insetTop, insetBottom;

    WorldView(Context ctx) {
        super(ctx);
        dp = ctx.getResources().getDisplayMetrics().density;
        setBackgroundColor(COL_BG);
        setKeepScreenOn(true);

        stroke(pGrid, COL_GRID, 1.2f);
        stroke(pRing, COL_MID, 1.6f);
        stroke(pTarget, COL_TARGET, 2.4f);
        stroke(pDial, COL_MID, 1.8f);
        pNorth.setStyle(Paint.Style.FILL);
        pNorth.setColor(COL_NORTH);
        pSouth.setStyle(Paint.Style.FILL);
        pSouth.setColor(COL_SOUTH);
        pBody.setStyle(Paint.Style.FILL);
        pBody.setColor(COL_BG);

        Typeface mono = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD);
        text(pList, COL_TEXT, LIST_MAX_SP, mono);
        text(pDist, COL_TEXT, 17f, mono);
        text(pName, COL_TARGET, 20f, mono);
        text(pSmall, COL_DIM, 16f, mono);
        text(pTiny, COL_DIM, 13f, mono);

        sScanning = ctx.getString(R.string.source_scanning);
        sNeedPermission = ctx.getString(R.string.need_permission);
        sAcquiring = ctx.getString(R.string.acquiring);
        sSearching = ctx.getString(R.string.searching);
        sNothingAhead = ctx.getString(R.string.nothing_ahead);
        sStandBy = ctx.getString(R.string.stand_by);
        sCalibrate = ctx.getString(R.string.calibrate);
        sHint = ctx.getString(R.string.hint);
        sFixOk = ctx.getString(R.string.fix_ok);
        rangeFmt = ctx.getString(R.string.range_fmt);
        fixFmt = ctx.getString(R.string.fix_fmt);
        fixLabel = ctx.getString(R.string.fix_none);
        sSourceGoogle = ctx.getString(R.string.source_google);
        sSourceOsm = ctx.getString(R.string.source_osm);
        sGoogleShort = ctx.getString(R.string.source_google_short);
        sOsmShort = ctx.getString(R.string.source_osm_short);
        footerRight = sScanning + "  " + fixLabel;

        setRangeIndex(rangeIndex, false);

        gestures = new GestureDetector(ctx, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public void onLongPress(MotionEvent e) {
                if (listener != null) listener.onConfigureRequested();
            }
        });
    }

    private void stroke(Paint p, int color, float widthDp) {
        p.setStyle(Paint.Style.STROKE);
        p.setColor(color);
        p.setStrokeWidth(widthDp * dp);
        p.setStrokeCap(Paint.Cap.SQUARE);
    }

    private void text(Paint p, int color, float sizeDp, Typeface tf) {
        p.setColor(color);
        p.setTextSize(sizeDp * dp);
        p.setTypeface(tf);
    }

    void setListener(Listener l) {
        listener = l;
    }

    // ------------------------------------------------------------- inputs

    /**
     * A new compass reading. Readings arrive whether or not the phone has
     * moved, so anything too small to see is dropped here rather than turned
     * into a frame: standing still should cost nothing.
     */
    void setHeading(float azimuth, float tilt, int accuracy) {
        if (!Float.isNaN(headingDeg)
                && Math.abs(Geo.delta180(azimuth, headingDeg)) < HEADING_EPS
                && Math.abs(tilt - tiltDeg) < TILT_EPS
                && accuracy == compassAccuracy) {
            return;
        }
        headingDeg = azimuth;
        tiltDeg = tilt;
        compassAccuracy = accuracy;
        int whole = (int) (azimuth + 0.5f) % 360;
        if (whole != lastHeadingInt) {
            lastHeadingInt = whole;
            headingLabel = (whole < 100 ? (whole < 10 ? "00" : "0") : "") + whole
                    + "° " + Geo.cardinal(azimuth);
        }
        postInvalidateOnAnimation();
    }

    void setOrigin(double lat, double lon, float accuracyM) {
        myLat = lat;
        myLon = lon;
        haveFix = true;
        fixLabel = accuracyM > 0
                ? String.format(java.util.Locale.US, fixFmt, (int) accuracyM)
                : sFixOk;
        footerRight = (source.isEmpty() ? sScanning : shortSource(source)) + "  " + fixLabel;
        relocateAll();
        postInvalidateOnAnimation();
    }

    void setPlaces(List<Poi> found, String src) {
        places.clear();
        if (found != null) places.addAll(found);
        source = src == null ? "" : src;
        footerRight = (source.isEmpty() ? sScanning : shortSource(source)) + "  " + fixLabel;
        listScroll = 0f;
        relocateAll();
        postInvalidateOnAnimation();
    }

    /** The word being searched for, shown where the range used to sit. */
    void setQuery(String q) {
        String next = q == null ? "" : q.trim();
        if (next.equals(query)) return;
        query = next;
        listScroll = 0f;
        postInvalidateOnAnimation();
    }

    String getQuery() {
        return query;
    }

    void setStatus(String s) {
        String next = s == null ? "" : s;
        if (next.equals(status)) return;
        status = next;
        postInvalidateOnAnimation();
    }

    void setPermissionNeeded(boolean needed) {
        if (needed == permissionNeeded) return;
        permissionNeeded = needed;
        postInvalidateOnAnimation();
    }

    int getRangeM() {
        return RANGES[rangeIndex];
    }

    private void setRangeIndex(int idx, boolean notify) {
        rangeIndex = idx;
        int r = RANGES[idx];
        rangeShort = Poi.format(r);
        rangeLabel = String.format(java.util.Locale.US, rangeFmt, rangeShort);
        for (int i = 0; i < BANDS.length; i++) {
            bandLabels[i] = Poi.format(r * BANDS[i]);
        }
        if (notify && listener != null) listener.onRangeChanged(r);
        postInvalidateOnAnimation();
    }

    /** Distances and bearings only change when we move, so they are cached. */
    private void relocateAll() {
        if (!haveFix) return;
        double mPerDegLon = Geo.metersPerDegLon(myLat);
        for (int i = 0; i < places.size(); i++) {
            places.get(i).relocate(myLat, myLon, mPerDegLon);
        }
        Collections.sort(places, (a, b) -> Float.compare(a.distM, b.distM));
        prepareLabels();
    }

    /** Trims names to the widths they will be drawn at, once, not once a frame. */
    private void prepareLabels() {
        if (getWidth() == 0) return;
        float fieldW = halfSpanPx * 1.15f;
        float listW = getWidth() - 2 * pad - 30 * dp - pDist.measureText("8888m") - 12 * dp;
        for (int i = 0; i < places.size(); i++) {
            Poi p = places.get(i);
            p.fieldLabel = ellipsise(p.name, pName, fieldW);

            // Shop names carry the branch on the end, so cutting the tail off
            // loses the part that tells two of them apart. Shrink the row until
            // the whole name fits, and only cut once there is nowhere left to go.
            float size = LIST_MAX_SP * dp;
            pList.setTextSize(size);
            while (size > LIST_MIN_SP * dp && pList.measureText(p.name) > listW) {
                size -= dp;
                pList.setTextSize(size);
            }
            p.listSize = size;
            p.listLabel = ellipsise(p.name, pList, listW);
        }
        pList.setTextSize(LIST_MAX_SP * dp);
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    /**
     * The slider owns the strip down the right-hand edge; everything else is
     * left to the gesture detector, which at the moment only watches for the
     * long press. A plain tap is deliberately unclaimed.
     */
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        // The magnifier sits above the slider and inside its column, so it has
        // to be asked first, and the slider only answers for its own height.
        if (action == MotionEvent.ACTION_DOWN && inSearchIcon(event.getX(), event.getY())) {
            tapIcon = true;
            return true;
        }
        if (action == MotionEvent.ACTION_DOWN && event.getX() >= sliderHit
                && event.getY() >= sliderTop - 24 * dp
                && event.getY() <= sliderBottom + 24 * dp) {
            sliding = true;
            slideStartIndex = rangeIndex;
            getParent().requestDisallowInterceptTouchEvent(true);
            setRangeIndex(indexAt(event.getY()), false);
            return true;
        }
        if (tapIcon) {
            if (action == MotionEvent.ACTION_UP) {
                tapIcon = false;
                performClick();
                if (inSearchIcon(event.getX(), event.getY()) && listener != null) {
                    listener.onSearchTapped();
                }
            } else if (action == MotionEvent.ACTION_CANCEL) {
                tapIcon = false;
            }
            return true;
        }
        if (action == MotionEvent.ACTION_DOWN && event.getY() >= listTop
                && event.getY() <= listTop + listViewH && listContentH > listViewH) {
            scrolling = true;
            lastTouchY = event.getY();
            getParent().requestDisallowInterceptTouchEvent(true);
            return true;
        }
        if (scrolling) {
            if (action == MotionEvent.ACTION_MOVE) {
                float y = event.getY();
                listScroll = clampScroll(listScroll - (y - lastTouchY));
                lastTouchY = y;
                postInvalidateOnAnimation();
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                scrolling = false;
                performClick();
            }
            return true;
        }
        if (sliding) {
            if (action == MotionEvent.ACTION_MOVE) {
                setRangeIndex(indexAt(event.getY()), false);
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                sliding = false;
                // Only once the finger lifts, so dragging across the detents
                // does not fire a search at every step.
                if (rangeIndex != slideStartIndex && listener != null) {
                    listener.onRangeChanged(getRangeM());
                }
                performClick();
            }
            return true;
        }
        return gestures.onTouchEvent(event) || super.onTouchEvent(event);
    }

    private boolean inSearchIcon(float x, float y) {
        return Math.abs(x - iconCx) <= iconR * 2.4f && Math.abs(y - iconCy) <= iconR * 2.4f;
    }

    private float clampScroll(float v) {
        float max = Math.max(0f, listContentH - listViewH);
        if (v < 0f) return 0f;
        return v > max ? max : v;
    }

    /** Which detent a finger at this height is asking for. Far is up, as on screen. */
    private int indexAt(float y) {
        float t = (sliderBottom - y) / (sliderBottom - sliderTop);
        if (t < 0f) t = 0f;
        if (t > 1f) t = 1f;
        return Math.round(t * (RANGES.length - 1));
    }

    private float sliderY(int index) {
        return sliderBottom - (sliderBottom - sliderTop) * index / (RANGES.length - 1);
    }

    // ------------------------------------------------------------- layout

    @Override
    public WindowInsets onApplyWindowInsets(WindowInsets insets) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Insets bars = insets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            insetTop = bars.top;
            insetBottom = bars.bottom;
        } else {
            insetTop = insets.getSystemWindowInsetTop();
            insetBottom = insets.getSystemWindowInsetBottom();
        }
        layoutGeometry(getWidth(), getHeight());
        postInvalidateOnAnimation();
        return insets;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        layoutGeometry(w, h);
    }

    private void layoutGeometry(int w, int h) {
        if (w == 0 || h == 0) return;
        pad = 16 * dp;
        // The slider takes a strip off the right, so the field is centred in
        // what is left: the half-circle has to stay symmetric about dead ahead.
        float sliderStrip = 30 * dp;
        cx = (w - sliderStrip) / 2f;
        halfSpanPx = cx - pad;
        sliderX = w - pad - 7 * dp;
        sliderHit = w - sliderStrip - 26 * dp;

        statusY = insetTop + 26 * dp;

        rowH = 30 * dp;
        listViewH = LIST_ROWS * rowH;
        hintY = h - insetBottom - 12 * dp;
        listTop = hintY - 28 * dp - listViewH;

        // Small. The compass is where you are, not what you came to look at;
        // every dp it gives back is a dp of the thing being searched for.
        compassRx = Math.min(w * 0.115f, 50 * dp);
        compassThick = 11 * dp;
        // You stand at the centre of the compass, so that is where the ground
        // runs out. Seat it high enough that a fully open dial plus its rim
        // still clears the list below.
        compassCy = listTop - 16 * dp - compassRx - compassThick;

        fieldTop = insetTop + 34 * dp;
        fieldBottom = compassCy;
        horizonBase = fieldTop + (fieldBottom - fieldTop) * 0.19f;

        iconR = 9 * dp;
        iconCx = w - pad - iconR - 3 * dp;
        iconCy = insetTop + 20 * dp;

        sliderTop = fieldTop + 26 * dp;
        sliderBottom = fieldBottom - 10 * dp;

        prepareLabels();
    }

    // --------------------------------------------------------------- draw

    /** Screen x for a bearing relative to where we are aimed. */
    private float screenX(float relDeg) {
        return cx + halfSpanPx * (relDeg / SPAN);
    }

    /**
     * Screen y for a point on the ground, given how far away it is as a
     * fraction of the range and which way it lies. The ground bows away at the
     * edges, the way a wide field of view makes it.
     */
    private float groundY(float u, float relDeg, float horizonY) {
        float base = fieldBottom - u * (fieldBottom - horizonY);
        float t = relDeg / SPAN;
        // Under a real lens the drop below the horizon goes as 1/distance, so a
        // ring of fixed radius sags at the edges by a share of how far below the
        // horizon it already is. The horizon itself is infinitely far and so
        // stays dead straight; the ground at your feet curves hardest.
        return base + (base - horizonY) * BOW * t * t;
    }

    /** Where the horizon sits: tipping the phone down lifts it, as it would. */
    private float horizonY() {
        float shift = tiltDeg * 0.9f * dp;
        float max = 46 * dp;
        if (shift > max) shift = max;
        if (shift < -max) shift = -max;
        return horizonBase + shift;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        float horizonY = horizonY();

        drawStatus(canvas, w);
        drawGround(canvas, horizonY);
        drawPlaces(canvas, horizonY);
        drawCompass(canvas);
        drawList(canvas, w, h);
        drawSlider(canvas);
        drawCorners(canvas, w, h);
    }

    /** Corner brackets: the edge of the virtual space, drawn as frame only. */
    private void drawCorners(Canvas canvas, int w, int h) {
        float m = 6 * dp, len = 20 * dp;
        path.rewind();
        path.moveTo(m, m + len); path.lineTo(m, m); path.lineTo(m + len, m);
        path.moveTo(w - m - len, m); path.lineTo(w - m, m); path.lineTo(w - m, m + len);
        path.moveTo(w - m, h - m - len); path.lineTo(w - m, h - m); path.lineTo(w - m - len, h - m);
        path.moveTo(m + len, h - m); path.lineTo(m, h - m); path.lineTo(m, h - m - len);
        canvas.drawPath(path, pRing);
    }

    /** The range control: a detent per step, far at the top to match the view. */
    private void drawSlider(Canvas canvas) {
        canvas.drawLine(sliderX, sliderTop, sliderX, sliderBottom, pGrid);
        for (int i = 0; i < RANGES.length; i++) {
            float y = sliderY(i);
            boolean on = i == rangeIndex;
            canvas.drawLine(sliderX - (on ? 11 : 7) * dp, y,
                    sliderX + (on ? 11 : 7) * dp, y, on ? pTarget : pRing);
        }

        float y = sliderY(rangeIndex);
        path.rewind();
        path.moveTo(sliderX - 9 * dp, y - 10 * dp);
        path.lineTo(sliderX + 9 * dp, y - 10 * dp);
        path.lineTo(sliderX + 9 * dp, y + 10 * dp);
        path.lineTo(sliderX - 9 * dp, y + 10 * dp);
        path.close();
        canvas.drawPath(path, pTarget);

        pSmall.setTextAlign(Paint.Align.RIGHT);
        pSmall.setColor(COL_TARGET);
        canvas.drawText(rangeShort, sliderX - 15 * dp, y + 6 * dp, pSmall);
        pSmall.setColor(COL_DIM);
        pSmall.setTextAlign(Paint.Align.LEFT);
    }

    private void drawStatus(Canvas canvas, int w) {
        canvas.drawText(headingLabel, pad, statusY, pSmall);

        // What you asked for takes the middle when there is one; otherwise the
        // range does, which is what you are implicitly asking for.
        pSmall.setTextAlign(Paint.Align.CENTER);
        pSmall.setColor(query.isEmpty() ? COL_DIM : COL_TARGET);
        canvas.drawText(query.isEmpty() ? rangeLabel : query, cx - 6 * dp, statusY, pSmall);
        pSmall.setColor(COL_DIM);
        pSmall.setTextAlign(Paint.Align.LEFT);

        drawSearchIcon(canvas);
    }

    /** A magnifier, lit when it is holding a word. */
    private void drawSearchIcon(Canvas canvas) {
        Paint paint = query.isEmpty() ? pRing : pTarget;
        canvas.drawCircle(iconCx - iconR * 0.25f, iconCy - iconR * 0.25f, iconR * 0.8f, paint);
        float k = iconR * 0.62f;
        canvas.drawLine(iconCx + k * 0.45f, iconCy + k * 0.45f,
                iconCx + iconR * 1.15f, iconCy + iconR * 1.15f, paint);
    }

    /** The ground plane: the horizon, the bearing marks on it, the distance bands. */
    private void drawGround(Canvas canvas, float horizonY) {
        // Distance bands, nearest first, each bowing away at the edges.
        for (int i = 0; i < BANDS.length; i++) {
            float u = (float) Math.sqrt(BANDS[i]);
            boolean isHorizon = i == BANDS.length - 1;
            polyline(canvas, u, horizonY, isHorizon ? pRing : pGrid);
            canvas.drawText(bandLabels[i], pad + 2 * dp,
                    groundY(u, -SPAN * 0.88f, horizonY) + 17 * dp, pTiny);
        }

        // Bearing marks, standing on the horizon where they belong.
        int base = Math.round(headingDeg / 22.5f);
        for (int k = base - 4; k <= base + 4; k++) {
            float bearing = k * 22.5f;
            float rel = Geo.delta180(bearing, headingDeg);
            if (Math.abs(rel) > SPAN) continue;
            float x = screenX(rel);
            float y = groundY(1f, rel, horizonY);
            int idx = ((k % 16) + 16) % 16;
            boolean major = idx % 2 == 0;
            canvas.drawLine(x, y, x, y - (major ? 15 : 8) * dp, major ? pRing : pGrid);
            if (major) {
                pSmall.setTextAlign(Paint.Align.CENTER);
                pSmall.setColor(idx == 0 ? COL_NORTH : COL_DIM);
                canvas.drawText(Geo.CARDINAL_16[idx], x, y - 22 * dp, pSmall);
                pSmall.setColor(COL_DIM);
                pSmall.setTextAlign(Paint.Align.LEFT);
            }
        }

        // The band we count as dead ahead, and the aim line down the middle.
        aimEdge(canvas, -AIM, horizonY);
        aimEdge(canvas, AIM, horizonY);
        canvas.drawLine(cx, groundY(1f, 0f, horizonY), cx, fieldBottom, pGrid);
    }

    /** Draws one constant-distance band across the field. */
    private void polyline(Canvas canvas, float u, float horizonY, Paint paint) {
        path.rewind();
        for (int i = 0; i <= 12; i++) {
            float rel = -SPAN + (2 * SPAN) * i / 12f;
            float x = screenX(rel), y = groundY(u, rel, horizonY);
            if (i == 0) path.moveTo(x, y);
            else path.lineTo(x, y);
        }
        canvas.drawPath(path, paint);
    }

    /** One side of the aim band, following the ground from the horizon to our feet. */
    private void aimEdge(Canvas canvas, float rel, float horizonY) {
        float x = screenX(rel);
        canvas.drawLine(x, groundY(1f, rel, horizonY), x, groundY(0f, rel, horizonY), pGrid);
    }

    private void drawPlaces(Canvas canvas, float horizonY) {
        ahead.clear();
        if (permissionNeeded) {
            centreMessage(canvas, sNeedPermission, horizonY);
            return;
        }
        if (!haveFix) {
            centreMessage(canvas, sAcquiring, horizonY);
            return;
        }
        if (places.isEmpty()) {
            centreMessage(canvas, status.isEmpty() ? sSearching : status, horizonY);
            return;
        }

        int range = getRangeM();
        labelSlotCount = 0;
        for (int i = 0; i < places.size(); i++) {
            Poi p = places.get(i);
            if (p.distM > range) continue;
            float rel = Geo.delta180(p.bearingDeg, headingDeg);
            if (Math.abs(rel) > SPAN) continue;          // behind us: not our business

            boolean onAim = Math.abs(rel) <= AIM;
            // The list holds the whole half-circle, nearest first, so that
            // scrolling it is worth doing; the aimed ones are simply lit up.
            ahead.add(p);

            float u = (float) Math.sqrt(p.distM / range);
            float x = screenX(rel), y = groundY(u, rel, horizonY);

            // A stake in the ground: the foot is where the thing is.
            float stem = (onAim ? 16 : 9) * dp;
            Paint paint = onAim ? pTarget : pGrid;
            canvas.drawLine(x, y, x, y - stem, paint);
            diamond(canvas, x, y - stem - 5 * dp, (onAim ? 7f : 4.5f) * dp, paint);

        }

        // Names last, so a neighbouring stake cannot be drawn over them, and
        // only for what is dead ahead: the rest would be a thicket.
        int labels = 0;
        for (int i = 0; i < ahead.size() && labels < MAX_FIELD_LABELS; i++) {
            Poi p = ahead.get(i);
            float rel = Geo.delta180(p.bearingDeg, headingDeg);
            if (Math.abs(rel) > AIM) continue;
            float u = (float) Math.sqrt(p.distM / range);
            float x = screenX(rel), y = groundY(u, rel, horizonY);
            drawFieldLabel(canvas, p, x, y - 42 * dp, y - 25 * dp);
            labels++;
        }
    }

    /**
     * A name above its stake. Two places on much the same bearing land on top of
     * one another, so a name claims a line and later ones are lifted clear of
     * the lines already taken, with a leader back to the stake they belong to.
     */
    private void drawFieldLabel(Canvas canvas, Poi p, float x, float ty, float stakeTop) {
        final float slotH = 48 * dp;
        // Bounded on purpose: nothing inside onDraw() gets to loop on a
        // condition. One pass per taken slot is provably enough, and the cap
        // means a surprise cannot turn a frame into a spin on the UI thread.
        for (int guard = 0; guard <= labelSlots.length; guard++) {
            boolean moved = false;
            for (int i = 0; i < labelSlotCount; i++) {
                if (Math.abs(ty - labelSlots[i]) < slotH) {
                    ty = labelSlots[i] - slotH;
                    moved = true;
                }
            }
            if (!moved) break;
        }
        if (labelSlotCount < labelSlots.length) labelSlots[labelSlotCount++] = ty;

        // Name and distance travel together, so the pair never lands on a
        // neighbour's name.
        if (stakeTop - (ty + 20 * dp) > 8 * dp) {
            canvas.drawLine(x, stakeTop, x, ty + 24 * dp, pGrid);
        }
        pName.setTextAlign(Paint.Align.CENTER);
        canvas.drawText(p.fieldLabel, x, ty, pName);
        pName.setTextAlign(Paint.Align.LEFT);

        pSmall.setTextAlign(Paint.Align.CENTER);
        pSmall.setColor(COL_DIM);
        canvas.drawText(p.distLabel, x, ty + 19 * dp, pSmall);
        pSmall.setTextAlign(Paint.Align.LEFT);
    }

    private void diamond(Canvas canvas, float x, float y, float s, Paint paint) {
        path.rewind();
        path.moveTo(x, y - s);
        path.lineTo(x + s, y);
        path.lineTo(x, y + s);
        path.lineTo(x - s, y);
        path.close();
        canvas.drawPath(path, paint);
    }

    private void centreMessage(Canvas canvas, String msg, float horizonY) {
        pSmall.setTextAlign(Paint.Align.CENTER);
        canvas.drawText(msg, cx, (horizonY + fieldBottom) / 2f, pSmall);
        pSmall.setTextAlign(Paint.Align.LEFT);
    }

    /**
     * You: a compass lying on the ground at your feet, drawn as a solid disc
     * with a rim rather than a flat outline.
     *
     * <p>The two cues work against each other on purpose. Aimed at the horizon
     * you see the dial edge-on, so it is a slit and the rim is at its thickest;
     * tip the phone down and the dial opens towards a full circle while the rim
     * thins away to nothing. Between them the tilt is legible without a number,
     * and at any tilt the red needle says which way north is.
     *
     * <p>True perspective would close the dial to a bare line when level, which
     * is exactly when it stops saying anything, so the opening is held to a
     * floor.
     */
    private void drawCompass(Canvas canvas) {
        float cy = compassCy;
        float rx = compassRx;
        float open = (float) Math.abs(Math.sin(Math.toRadians(tiltDeg)));
        if (open < 0.22f) open = 0.22f;
        float ry = rx * open;
        float side = compassThick * (float) Math.sqrt(1 - open * open);

        // Solid, not see-through: it is an object sitting on the ground, and the
        // ground lines have no business showing through it.
        if (side > 0.5f) {
            oval.set(cx - rx, cy - ry + side, cx + rx, cy + ry + side);
            canvas.drawOval(oval, pBody);
            canvas.drawRect(cx - rx, cy, cx + rx, cy + side, pBody);
            canvas.drawArc(oval, 0f, 180f, false, pDial);
            canvas.drawLine(cx - rx, cy, cx - rx, cy + side, pDial);
            canvas.drawLine(cx + rx, cy, cx + rx, cy + side, pDial);
        }

        // The face.
        oval.set(cx - rx, cy - ry, cx + rx, cy + ry);
        canvas.drawOval(oval, pBody);
        canvas.drawOval(oval, pDial);

        for (int b = 0; b < 360; b += 30) {
            double rel = Math.toRadians(Geo.delta180(b, headingDeg));
            float sin = (float) Math.sin(rel), cos = (float) Math.cos(rel);
            float k = (b % 90 == 0) ? 0.78f : 0.89f;
            canvas.drawLine(cx + rx * k * sin, cy - ry * k * cos,
                    cx + rx * sin, cy - ry * cos, pDial);
        }

        // The needle, foreshortened along with the face it lies on.
        double relN = Math.toRadians(Geo.delta180(0f, headingDeg));
        float sinN = (float) Math.sin(relN), cosN = (float) Math.cos(relN);
        float nx = cx + rx * 0.76f * sinN, ny = cy - ry * 0.76f * cosN;
        float sx = cx - rx * 0.76f * sinN, sy = cy + ry * 0.76f * cosN;
        float wx = rx * 0.18f * cosN, wy = ry * 0.18f * sinN;

        path.rewind();
        path.moveTo(nx, ny);
        path.lineTo(cx + wx, cy + wy);
        path.lineTo(cx - wx, cy - wy);
        path.close();
        canvas.drawPath(path, pNorth);

        path.rewind();
        path.moveTo(sx, sy);
        path.lineTo(cx + wx, cy + wy);
        path.lineTo(cx - wx, cy - wy);
        path.close();
        canvas.drawPath(path, pSouth);

        canvas.drawCircle(cx, cy, 5 * dp, pDial);
    }

    /** What is dead ahead, nearest first, in the size you can read while walking. */
    /**
     * What is in the half-circle ahead, nearest first, scrollable.
     *
     * <p>The viewport is deliberately three and a half rows tall: a row cut in
     * half at the bottom edge is the plainest way to say there is more below.
     * Only the rows that fall inside it are drawn, so a long list costs the
     * same as a short one.
     */
    private void drawList(Canvas canvas, int w, int h) {
        float top = listTop, bottom = listTop + listViewH;
        canvas.drawLine(pad, top - 10 * dp, w - pad, top - 10 * dp, pGrid);

        listContentH = ahead.size() * rowH;
        listScroll = clampScroll(listScroll);

        if (ahead.isEmpty()) {
            pSmall.setColor(COL_DIM);
            canvas.drawText(haveFix ? sNothingAhead : sStandBy, pad, top + 20 * dp, pSmall);
        } else {
            canvas.save();
            canvas.clipRect(pad, top, w - pad, bottom);

            int first = (int) (listScroll / rowH);
            int last = Math.min(ahead.size(), first + (int) (LIST_ROWS + 2));
            for (int i = first; i < last; i++) {
                Poi p = ahead.get(i);
                float y = top + 20 * dp + i * rowH - listScroll;

                float rel = Geo.delta180(p.bearingDeg, headingDeg);
                boolean onAim = Math.abs(rel) <= AIM;
                String arrow = rel < -3f ? "<" : (rel > 3f ? ">" : "|");

                pDist.setColor(onAim ? COL_TARGET : COL_DIM);
                canvas.drawText(arrow, pad, y, pDist);

                pList.setTextSize(p.listSize);
                pList.setColor(onAim ? COL_TEXT : COL_DIM);
                canvas.drawText(p.listLabel, pad + 24 * dp, y, pList);

                pDist.setColor(onAim ? COL_TARGET : COL_DIM);
                pDist.setTextAlign(Paint.Align.RIGHT);
                canvas.drawText(p.distLabel, w - pad - 8 * dp, y, pDist);
                pDist.setTextAlign(Paint.Align.LEFT);
                pDist.setColor(COL_TEXT);
            }
            canvas.restore();

            // A bar showing how much of the list is on screen, drawn only when
            // some of it is not.
            if (listContentH > listViewH) {
                float trackX = w - pad;
                float frac = listViewH / listContentH;
                float thumb = Math.max(18 * dp, listViewH * frac);
                float travel = listViewH - thumb;
                float ty = top + travel * (listScroll / (listContentH - listViewH));
                canvas.drawLine(trackX, top, trackX, bottom, pGrid);
                pTarget.setStrokeWidth(3.5f * dp);
                canvas.drawLine(trackX, ty, trackX, ty + thumb, pTarget);
                pTarget.setStrokeWidth(2.4f * dp);
            }
        }

        String note;
        if (compassAccuracy == SensorManager.SENSOR_STATUS_ACCURACY_LOW
                || compassAccuracy == SensorManager.SENSOR_STATUS_UNRELIABLE) {
            note = sCalibrate;
        } else if (!status.isEmpty() && !places.isEmpty()) {
            note = status;
        } else {
            note = sHint;
        }
        pTiny.setColor(COL_DIM);
        canvas.drawText(note, pad, hintY, pTiny);
        pTiny.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText(footerRight, w - pad, hintY, pTiny);
        pTiny.setTextAlign(Paint.Align.LEFT);
    }

    /** The footer is narrow, so the source gets its short spelling there. */
    private String shortSource(String src) {
        if (src.equals(sSourceOsm)) return sOsmShort;
        if (src.equals(sSourceGoogle)) return sGoogleShort;
        return src;
    }

    /** Trims a label to fit, in whole characters. Called when data changes, not per frame. */
    private String ellipsise(String s, Paint paint, float maxWidth) {
        if (maxWidth <= 0 || paint.measureText(s) <= maxWidth) return s;
        int n = paint.breakText(s, true, maxWidth - paint.measureText("…"), null);
        return s.substring(0, Math.max(0, n)) + "…";
    }
}
