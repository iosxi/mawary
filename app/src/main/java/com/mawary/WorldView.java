package com.mawary;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Insets;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.hardware.SensorManager;
import android.os.Build;
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
        /** The magnifier: the user wants to say what they are looking for. */
        void onSearchTapped();
        /** The gear: the user wants the settings screen. */
        void onSettingsTapped();
    }

    /**
     * Ranges reach far enough to be useful with a search word: mountains are
     * not 2 km away. Past WIDE_RADIUS the unfiltered sweep asks only for
     * landmarks, so the far end stays answerable.
     */
    private static final int[] RANGES = {100, 200, 500, 1000, 2000, 5000, 10000};

    /** Half-width of the field of view. The screen spans exactly this each way. */
    private static final float SPAN = 90f;

    /** Fractions of the range that get a labelled ground band. */
    private static final float[] BANDS = {0.03f, 0.1f, 0.25f, 0.5f, 1f};
    /** How hard the ground curves away at the edges of the view. */
    private static final float BOW = 0.25f;

    /**
     * Every stake in view gets a name, up to this many; the nearest win. A
     * search can bring back 180 places, and past a few dozen names the field
     * is solid text anyway, so this also bounds what a frame has to place.
     */
    private static final int MAX_FIELD_LABELS = 30;

    /**
     * The eight spots a label can take around its stake, in order of
     * preference: the direction it sits in, and which point of the label the
     * leader line meets (0 = left / top edge, 0.5 = middle, 1 = right / bottom).
     * Above comes first because above is further away, and it is the ground
     * nearer than the stake that the label would otherwise hide.
     */
    private static final float D = 0.7071f;
    private static final float[] SPOT_DX = {0, D, -D, 1, -1, D, -D, 0};
    private static final float[] SPOT_DY = {-1, -D, -D, 0, 0, D, D, 1};
    private static final float[] SPOT_AX = {0.5f, 0, 1, 0, 1, 0, 1, 0.5f};
    private static final float[] SPOT_AY = {1, 1, 1, 0.5f, 0.5f, 0, 0, 0};
    /**
     * How far out along each direction a label may sit, in dp. Finely stepped
     * at the near end, where the shortest leader that clears a neighbour is
     * usually found.
     */
    private static final float[] LEADS = {8, 20, 34, 52, 76, 108, 150};
    private static final int SPOTS = SPOT_DX.length * LEADS.length;
    /**
     * What a pixel of leader costs, against a pixel of overlap. Taking a
     * leader a label-height further out has to save more than a label-height
     * square of overlap: short leaders win unless the overlap is real.
     */
    private static final float LEAD_WEIGHT = 2.5f;

    /** Label backgrounds are the night sky; how much of what is behind shows is a setting. */
    private static final int COL_LABEL_BG = 0x0005080A;
    /** A button's face, and its face while a finger is on it. */
    private static final int COL_BUTTON = 0xFF0E1B1F;
    private static final int COL_BUTTON_DOWN = 0xFF1F4148;
    static final int DEFAULT_LABEL_TRANSPARENCY = 66;
    /** Deliberately not a whole number: a half row showing is what says "scroll me". */
    private static final float LIST_ROWS = 3.5f;

    /** Below this the reading has not visibly changed, so there is nothing to redraw. */
    private static final float HEADING_EPS = 0.4f;
    private static final float TILT_EPS = 0.8f;

    /**
     * The tilt-driven range. Held upright (the back facing the horizon) is the
     * far end, laid flat is the near end, and the steps are spread evenly in
     * between. Each end gets a few degrees to itself, since nobody holds a
     * phone at exactly 0° or 90°.
     */
    private static final float TILT_FAR_DEG = 10f;
    private static final float TILT_NEAR_DEG = 80f;
    /**
     * How far past the halfway mark between two steps the tilt has to go
     * before the range moves, in steps. Without it a hand resting on a
     * boundary flickers between the two.
     */
    private static final float TILT_HYSTERESIS = 0.2f;
    /**
     * The search waits for the tilt to settle this long. Tipping the phone
     * from flat to upright crosses every step on the way, and each one would
     * otherwise send a search.
     */
    private static final long TILT_SETTLE_MS = 700L;
    private static final int COL_SLIDER_OFF = 0xFF6F7F80;

    /**
     * The horizon glow: a soft grey band that shows while the phone is held
     * upright, so upright can be found without reading the slider. Full
     * strength within TILT_FAR_DEG, where the tilt range is already at its far
     * end, and gone by HORIZON_FADE_DEG.
     */
    private static final float HORIZON_FADE_DEG = 25f;
    private static final int COL_HORIZON = 0x8C8A9A9C;
    private static final float HORIZON_HALF_DP = 16f;

    /** The list shrinks a name to fit rather than cutting it, down to this floor. */
    private static final float LIST_MAX_SP = 18f;
    private static final float LIST_MIN_SP = 12f;

    static final int COL_BG = 0xFF05080A;
    static final int COL_GRID = 0xFF27525C;
    /** Label outlines: a step up from the grid, so a bubble stands off the ground lines. */
    static final int COL_BUBBLE = 0xFF3F818A;
    static final int COL_MID = 0xFF49A0A6;
    private static final int COL_ACCENT = 0xFF5CF0D8;
    static final int COL_TARGET = 0xFFFFC46B;
    static final int COL_TEXT = 0xFFE2FAF6;
    static final int COL_DIM = 0xFFA6CFD0;
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
    private final Paint pLabelBg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pBubble = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pButton = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pNotice = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pSpinner = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pSpinTrack = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Path path = new Path();
    /** A label's tail, before it is merged into the bubble. */
    private final Path tail = new Path();
    /**
     * Labels placed this frame, nearest first: left, top, right, bottom, then
     * where the leader meets it. Filled before anything is drawn, so every
     * label can be kept off the ones already placed.
     */
    private final float[] labelBox = new float[MAX_FIELD_LABELS * 4];
    private final float[] labelEnd = new float[MAX_FIELD_LABELS * 2];
    private final Poi[] labelPoi = new Poi[MAX_FIELD_LABELS];
    private int labelCount;
    private final float[] labelW = new float[MAX_FIELD_LABELS];
    private final int[] labelSpot = new int[MAX_FIELD_LABELS];
    /** What each spot of each label costs against the fixed scenery, this frame. */
    private final float[] spotCost = new float[MAX_FIELD_LABELS * SPOTS];
    /** The box spotBox() last worked out, and where its leader meets it. */
    private float cL, cT, cR, cB, cEx, cEy;
    /** Font metrics for the two lines of a label, read once. */
    private float nameAsc, nameDesc, distAsc, distDesc;
    private final RectF oval = new RectF();
    private final Paint pHorizon = new Paint();
    private Listener listener;

    /** Set while a finger is on the range slider, so taps elsewhere stay unclaimed. */
    private boolean sliding;
    /** Set while a finger is dragging the list. */
    private boolean scrolling;
    /** Which button a finger went down on: BTN_NONE, BTN_SEARCH or BTN_SETTINGS. */
    private int pressed = BTN_NONE;
    private static final int BTN_NONE = 0, BTN_SEARCH = 1, BTN_SETTINGS = 2;
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
    private String status = "";
    /** A search is out. The spinner turns for exactly as long as this is set. */
    private boolean busy;
    private boolean permissionNeeded;

    private int rangeIndex = 3;   // 1000 m
    /** The step the last search was sent for, so a return to it sends none. */
    private int searchedIndex = rangeIndex;
    /** The tilt sets the range, and the slider only shows it. */
    private boolean tiltRange;
    private final Runnable commitRange = this::commitRange;
    private String query = "";

    // --- wording, read once: onDraw() must not touch resources ------------
    private final String sNeedPermission, sAcquiring, sSearching;
    private final String sNothingAhead, sStandBy, sCalibrate;
    private final String rangeFmt;

    // --- cached labels ----------------------------------------------------
    private String headingLabel = "---";
    private int lastHeadingInt = -1;
    private String rangeLabel = "";
    private String rangeShort = "";
    private final String[] bandLabels = new String[BANDS.length];

    // --- geometry, resolved in layoutGeometry -----------------------------
    private float cx, pad;
    private float statusY;
    private float fieldTop, fieldBottom, horizonBase;
    private float halfSpanPx;
    private float compassRx, compassThick, compassCy;
    private float listTop, rowH;
    /** The range slider: the track's centre line, its ends, and where a finger counts. */
    private float sliderX, sliderTop, sliderBottom;
    private final RectF sliderHit = new RectF();
    /** Everything the slider draws, readout included, for labels to keep off. */
    private final RectF sliderArea = new RectF();
    /** The two buttons, top right: search, then settings to its right. */
    private final RectF searchBtn = new RectF(), settingsBtn = new RectF();
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
        nameAsc = -pName.ascent();
        nameDesc = pName.descent();
        distAsc = -pSmall.ascent();
        distDesc = pSmall.descent();

        pLabelBg.setStyle(Paint.Style.FILL);
        pLabelBg.setColor(COL_LABEL_BG);
        setLabelTransparency(DEFAULT_LABEL_TRANSPARENCY);
        stroke(pBubble, COL_BUBBLE, 1.4f);
        // Round, so the tail's sharp point does not grow a miter spike past the stake.
        pBubble.setStrokeJoin(Paint.Join.ROUND);
        pButton.setStyle(Paint.Style.FILL);
        pNotice.setStyle(Paint.Style.FILL);
        pNotice.setColor(0xE605080A);
        stroke(pSpinner, COL_TARGET, 4f);
        pSpinner.setStrokeCap(Paint.Cap.ROUND);
        stroke(pSpinTrack, COL_GRID, 4f);

        sNeedPermission = ctx.getString(R.string.need_permission);
        sAcquiring = ctx.getString(R.string.acquiring);
        sSearching = ctx.getString(R.string.searching);
        sNothingAhead = ctx.getString(R.string.nothing_ahead);
        sStandBy = ctx.getString(R.string.stand_by);
        sCalibrate = ctx.getString(R.string.calibrate);
        rangeFmt = ctx.getString(R.string.range_fmt);

        setRangeIndex(rangeIndex, false);
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
        if (tiltRange) followTilt();
        int whole = (int) (azimuth + 0.5f) % 360;
        if (whole != lastHeadingInt) {
            lastHeadingInt = whole;
            headingLabel = (whole < 100 ? (whole < 10 ? "00" : "0") : "") + whole
                    + "° " + Geo.cardinal(azimuth);
        }
        postInvalidateOnAnimation();
    }

    void setOrigin(double lat, double lon) {
        myLat = lat;
        myLon = lon;
        haveFix = true;
        relocateAll();
        postInvalidateOnAnimation();
    }

    void setPlaces(List<Poi> found) {
        places.clear();
        if (found != null) places.addAll(found);
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

    /** How see-through the label backgrounds are, in percent: 0 solid, 100 not there. */
    void setLabelTransparency(int percent) {
        int pct = Math.max(0, Math.min(100, percent));
        pLabelBg.setAlpha(Math.round(255 * (100 - pct) / 100f));
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

    void setBusy(boolean b) {
        if (b == busy) return;
        busy = b;
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

    /** Whether the tilt sets the range. While it does, the slider cannot be dragged. */
    void setTiltRange(boolean on) {
        if (on == tiltRange) return;
        tiltRange = on;
        if (on) {
            sliding = false;
            if (!Float.isNaN(headingDeg)) followTilt();
        }
        postInvalidateOnAnimation();
    }

    /**
     * Moves the range to the step the tilt asks for. Upright is 0° here and
     * flat is ±90°: which way up the screen faces makes no difference.
     */
    private void followTilt() {
        float lean = Math.abs(tiltDeg);
        float t = (TILT_NEAR_DEG - lean) / (TILT_NEAR_DEG - TILT_FAR_DEG);
        if (t < 0f) t = 0f;
        if (t > 1f) t = 1f;
        float pos = t * (RANGES.length - 1);
        if (Math.abs(pos - rangeIndex) < 0.5f + TILT_HYSTERESIS) return;
        setRangeIndex(Math.round(pos), false);
        removeCallbacks(commitRange);
        postDelayed(commitRange, TILT_SETTLE_MS);
    }

    /** Sends the search for the step now showing, unless it was the last one sent. */
    private void commitRange() {
        if (rangeIndex == searchedIndex) return;
        searchedIndex = rangeIndex;
        if (listener != null) listener.onRangeChanged(getRangeM());
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(commitRange);
        super.onDetachedFromWindow();
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
        // Drawn translated to wherever the horizon is, so the shader is built once.
        float hh = HORIZON_HALF_DP * dp;
        pHorizon.setShader(new LinearGradient(0, -hh, 0, hh,
                new int[]{COL_HORIZON & 0x00FFFFFF, COL_HORIZON, COL_HORIZON & 0x00FFFFFF},
                null, Shader.TileMode.CLAMP));

        prepareLabels();
    }

    /** Trims names to the widths they will be drawn at, once, not once a frame. */
    private void prepareLabels() {
        if (getWidth() == 0) return;
        // Narrower than it once was: names now sit beside their stakes, and a
        // label half the field wide leaves nowhere for the next one to go.
        float fieldW = halfSpanPx * 0.8f;
        float listW = getWidth() - 2 * pad - 30 * dp - pDist.measureText("8888m") - 12 * dp;
        for (int i = 0; i < places.size(); i++) {
            Poi p = places.get(i);
            p.fieldLabel = ellipsise(p.name, pName, fieldW);
            p.nameW = pName.measureText(p.fieldLabel);
            p.distW = pSmall.measureText(p.distLabel);

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
     * The buttons, the range slider and the list each own
     * their patch; a tap anywhere else is deliberately unclaimed.
     */
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        // The buttons are asked first; the slider answers only inside its own
        // patch, which is kept generous for a thumb.
        if (action == MotionEvent.ACTION_DOWN) {
            int hit = buttonAt(event.getX(), event.getY());
            if (hit != BTN_NONE) {
                pressed = hit;
                postInvalidateOnAnimation();
                return true;
            }
        }
        if (action == MotionEvent.ACTION_DOWN && !tiltRange
                && sliderHit.contains(event.getX(), event.getY())) {
            sliding = true;
            getParent().requestDisallowInterceptTouchEvent(true);
            setRangeIndex(indexAt(event.getY()), false);
            return true;
        }
        if (pressed != BTN_NONE) {
            if (action == MotionEvent.ACTION_UP) {
                int was = pressed;
                pressed = BTN_NONE;
                postInvalidateOnAnimation();
                performClick();
                // Only if the finger lifts where it went down: sliding off a
                // button is how you change your mind about pressing it.
                if (buttonAt(event.getX(), event.getY()) == was && listener != null) {
                    if (was == BTN_SEARCH) listener.onSearchTapped();
                    else listener.onSettingsTapped();
                }
            } else if (action == MotionEvent.ACTION_CANCEL) {
                pressed = BTN_NONE;
                postInvalidateOnAnimation();
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
                commitRange();
                performClick();
            }
            return true;
        }
        return super.onTouchEvent(event);
    }

    /** Which button is under a finger. The gap between them belongs to neither. */
    private int buttonAt(float x, float y) {
        if (searchBtn.contains(x, y)) return BTN_SEARCH;
        if (settingsBtn.contains(x, y)) return BTN_SETTINGS;
        return BTN_NONE;
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
        // The slider no longer runs the height of the screen, so the field
        // takes the whole width, centred: the half-circle has to stay
        // symmetric about dead ahead.
        cx = w / 2f;
        halfSpanPx = cx - pad;
        // In from the edge a little, where a right thumb rests.
        sliderX = w - pad - 22 * dp;

        // Buttons at the size a thumb can hit without looking: 48dp square,
        // 8dp apart, off the edge by the same margin as everything else.
        float btn = 48 * dp, gap = 8 * dp, btnTop = insetTop + 8 * dp;
        settingsBtn.set(w - pad - btn, btnTop, w - pad, btnTop + btn);
        searchBtn.set(settingsBtn.left - gap - btn, btnTop, settingsBtn.left - gap, btnTop + btn);
        // The heading and the range read on the buttons' centre line.
        statusY = btnTop + btn / 2f + 6 * dp;

        rowH = 30 * dp;
        listViewH = LIST_ROWS * rowH;
        listTop = h - insetBottom - 8 * dp - listViewH;

        // Small. The compass is where you are, not what you came to look at;
        // every dp it gives back is a dp of the thing being searched for.
        compassRx = Math.min(w * 0.115f, 50 * dp);
        compassThick = 11 * dp;
        // You stand at the centre of the compass, so that is where the ground
        // runs out. Seat it high enough that a fully open dial plus its rim
        // still clears the list below.
        compassCy = listTop - 16 * dp - compassRx - compassThick;

        fieldTop = btnTop + btn + 10 * dp;
        fieldBottom = compassCy;
        horizonBase = fieldTop + (fieldBottom - fieldTop) * 0.19f;

        // Low and short: down beside the compass, where the ground is nearest
        // and least is ever drawn, ending just above the list.
        sliderBottom = listTop - 44 * dp;
        sliderTop = Math.max(fieldTop + 40 * dp, sliderBottom - 190 * dp);
        sliderHit.set(sliderX - 34 * dp, sliderTop - 22 * dp,
                w - pad * 0.5f, sliderBottom + 16 * dp);

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
        drawHorizon(canvas, w, horizonY);
        drawGround(canvas, horizonY);
        drawPlaces(canvas, horizonY);
        drawCompass(canvas);
        drawList(canvas, w, h);
        drawSlider(canvas);
        if (busy) {
            drawSpinner(canvas, horizonY);
            // The one thing that redraws on a timer, and only while it turns.
            postInvalidateOnAnimation();
        }
        drawNotice(canvas);
    }

    /** The grey glow along the horizon, strongest with the phone upright. */
    private void drawHorizon(Canvas canvas, int w, float horizonY) {
        float lean = Math.abs(tiltDeg);
        if (lean >= HORIZON_FADE_DEG) return;
        float a = lean <= TILT_FAR_DEG ? 1f
                : (HORIZON_FADE_DEG - lean) / (HORIZON_FADE_DEG - TILT_FAR_DEG);
        pHorizon.setAlpha(Math.round(255 * a));
        float hh = HORIZON_HALF_DP * dp;
        canvas.save();
        canvas.translate(0, horizonY);
        canvas.drawRect(0, -hh, w, hh, pHorizon);
        canvas.restore();
    }

    /**
     * Searching: an arc chasing round a faint ring, in the middle of the field,
     * on a card of its own so it reads over whatever labels are already up,
     * with what the search is doing written under it. The arc swings between
     * short and long as it turns, so it looks alive even when the frame rate
     * drops and the turn itself stutters.
     */
    private void drawSpinner(Canvas canvas, float horizonY) {
        String words = status.isEmpty() ? sSearching : status;
        float r = 18 * dp;
        float textW = pSmall.measureText(words);
        float halfW = Math.max(r + 24 * dp, textW / 2f + 20 * dp);
        float midY = (horizonY + fieldBottom) / 2f;
        float top = midY - 46 * dp, bottom = midY + 46 * dp;
        oval.set(cx - halfW, top, cx + halfW, bottom);
        canvas.drawRoundRect(oval, 14 * dp, 14 * dp, pNotice);
        canvas.drawRoundRect(oval, 14 * dp, 14 * dp, pBubble);

        float sy = top + 16 * dp + r;
        long t = android.os.SystemClock.uptimeMillis();
        float spin = (t % 900L) * 360f / 900f;
        float sweep = 150f + 110f * (float) Math.sin(t % 1800L * (2 * Math.PI / 1800.0));
        canvas.drawCircle(cx, sy, r, pSpinTrack);
        oval.set(cx - r, sy - r, cx + r, sy + r);
        canvas.drawArc(oval, spin, sweep, false, pSpinner);

        pSmall.setTextAlign(Paint.Align.CENTER);
        pSmall.setColor(COL_TEXT);
        canvas.drawText(words, cx, bottom - 14 * dp, pSmall);
        pSmall.setColor(COL_DIM);
        pSmall.setTextAlign(Paint.Align.LEFT);
    }

    /**
     * Something worth saying — the compass wants calibrating, a search went
     * wrong — as a pill at the top of the field, drawn over everything. Only
     * while there is something to say: there is no footer holding a line open.
     * With nothing found the same words are already in the middle of the
     * field, so they are not said twice.
     */
    private void drawNotice(Canvas canvas) {
        String note;
        if (compassAccuracy == SensorManager.SENSOR_STATUS_ACCURACY_LOW
                || compassAccuracy == SensorManager.SENSOR_STATUS_UNRELIABLE) {
            note = sCalibrate;
        } else if (!status.isEmpty() && !places.isEmpty() && !busy) {
            // While searching, the spinner card already carries the status.
            note = status;
        } else {
            return;
        }
        pTiny.setColor(COL_TEXT);
        pTiny.setTextAlign(Paint.Align.CENTER);
        float half = pTiny.measureText(note) / 2f + 12 * dp;
        float top = fieldTop + 2 * dp, bottom = top + 28 * dp;
        oval.set(cx - half, top, cx + half, bottom);
        canvas.drawRoundRect(oval, 14 * dp, 14 * dp, pNotice);
        canvas.drawRoundRect(oval, 14 * dp, 14 * dp, pBubble);
        canvas.drawText(note, cx, top + 19 * dp, pTiny);
        pTiny.setTextAlign(Paint.Align.LEFT);
        pTiny.setColor(COL_DIM);
    }

    /**
     * The range control, drawn as a scroll bar so it reads as something to
     * drag: a rounded track with a dot at each step, and a thumb that sits on
     * the chosen one, gripped across the middle. Far is at the top, to match
     * the view, and the two ends are labelled so which way is which is never
     * a guess. The chosen range is written beside the thumb on a card of its
     * own, since the field shows through everywhere else.
     */
    private void drawSlider(Canvas canvas) {
        float half = 6 * dp;
        oval.set(sliderX - half, sliderTop - half * 2, sliderX + half, sliderBottom + half * 2);
        canvas.drawRoundRect(oval, half, half, pNotice);
        canvas.drawRoundRect(oval, half, half, pBubble);
        pButton.setColor(COL_MID);
        for (int i = 0; i < RANGES.length; i++) {
            canvas.drawCircle(sliderX, sliderY(i), 1.8f * dp, pButton);
        }

        float y = sliderY(rangeIndex);
        float tw = 11 * dp, th = 18 * dp;
        oval.set(sliderX - tw, y - th, sliderX + tw, y + th);
        // Grey while the tilt drives it: it still says where the range is, but
        // no longer looks like something to take hold of.
        pButton.setColor(tiltRange ? COL_SLIDER_OFF : sliding ? COL_TEXT : COL_TARGET);
        canvas.drawRoundRect(oval, tw, tw, pButton);
        pButton.setColor(COL_BUTTON);
        for (int g = -1; g <= 1; g++) {
            canvas.drawRect(sliderX - 5 * dp, y + g * 4.5f * dp - 0.8f * dp,
                    sliderX + 5 * dp, y + g * 4.5f * dp + 0.8f * dp, pButton);
        }

        // Which end is which.
        pTiny.setTextAlign(Paint.Align.CENTER);
        pTiny.setColor(COL_DIM);
        canvas.drawText(Poi.format(RANGES[RANGES.length - 1]), sliderX, sliderTop - 20 * dp, pTiny);
        canvas.drawText(Poi.format(RANGES[0]), sliderX, sliderBottom + 26 * dp, pTiny);
        pTiny.setTextAlign(Paint.Align.LEFT);

        // The chosen range, beside the thumb.
        float textW = pSmall.measureText(rangeShort);
        float right = sliderX - tw - 8 * dp;
        oval.set(right - textW - 16 * dp, y - 14 * dp, right, y + 14 * dp);
        canvas.drawRoundRect(oval, 14 * dp, 14 * dp, pNotice);
        canvas.drawRoundRect(oval, 14 * dp, 14 * dp, pBubble);
        pSmall.setTextAlign(Paint.Align.RIGHT);
        pSmall.setColor(tiltRange ? COL_TEXT : COL_TARGET);
        canvas.drawText(rangeShort, right - 8 * dp, y + 6 * dp, pSmall);
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

        // The search button is lit while it is holding a word.
        Paint search = query.isEmpty() ? pRing : pTarget;
        drawButton(canvas, searchBtn, pressed == BTN_SEARCH, search);
        float r = 8 * dp, mx = searchBtn.centerX(), my = searchBtn.centerY();
        canvas.drawCircle(mx - 2 * dp, my - 2 * dp, r * 0.8f, search);
        canvas.drawLine(mx + 2.6f * dp, my + 2.6f * dp, mx + 8 * dp, my + 8 * dp, search);

        drawButton(canvas, settingsBtn, pressed == BTN_SETTINGS, pRing);
        drawGear(canvas, settingsBtn.centerX(), settingsBtn.centerY());
    }

    /** A button's face: a rounded square, lit up while a finger is on it. */
    private void drawButton(Canvas canvas, RectF r, boolean down, Paint edge) {
        pButton.setColor(down ? COL_BUTTON_DOWN : COL_BUTTON);
        canvas.drawRoundRect(r, 12 * dp, 12 * dp, pButton);
        canvas.drawRoundRect(r, 12 * dp, 12 * dp, edge == pTarget ? pTarget : pBubble);
    }

    /** A gear: a ring with eight teeth and a hole, the sign for settings everywhere. */
    private void drawGear(Canvas canvas, float x, float y) {
        float outer = 9.5f * dp, inner = 6.5f * dp;
        for (int i = 0; i < 8; i++) {
            double a = Math.PI / 4 * i;
            float c = (float) Math.cos(a), sn = (float) Math.sin(a);
            canvas.drawLine(x + c * inner, y + sn * inner, x + c * outer, y + sn * outer, pRing);
        }
        canvas.drawCircle(x, y, inner, pRing);
        canvas.drawCircle(x, y, 2.6f * dp, pRing);
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

        // The aim line down the middle.
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
            // While a search is out the spinner says so, words and all.
            if (!busy) centreMessage(canvas, status.isEmpty() ? sSearching : status, horizonY);
            return;
        }

        int range = getRangeM();
        final float stem = 16 * dp;
        for (int i = 0; i < places.size(); i++) {
            Poi p = places.get(i);
            if (p.distM > range) continue;
            float rel = Geo.delta180(p.bearingDeg, headingDeg);
            if (Math.abs(rel) > SPAN) continue;          // behind us: not our business

            // Anything on screen is lit: being in view is being found.
            ahead.add(p);

            float u = (float) Math.sqrt(p.distM / range);
            float x = screenX(rel), y = groundY(u, rel, horizonY);
            p.sx = x;
            p.sy = y;

            // A stake in the ground: the foot is where the thing is.
            canvas.drawLine(x, y, x, y - stem, pTarget);
            diamond(canvas, x, y - stem - 5 * dp, 7f * dp, pTarget);
        }

        layoutLabels(stem);
        // Draws furthest first, so where it cannot be helped the nearest end up on top.
        for (int i = labelCount - 1; i >= 0; i--) {
            drawLabel(canvas, i, stem);
        }
    }

    /**
     * Places every label at once, keeping leaders short above all.
     *
     * <p>A long leader is worse than an overlap: a label sitting beside one
     * stake while its line runs off to another reads as belonging to the wrong
     * one. So a leader's length is the main cost, and a leader that crosses
     * another leader, or runs through another label, is charged on top.
     *
     * <p>This is point-feature label placement, the problem Christensen, Marks
     * and Shieber solved by simulated annealing. Annealing is random, and a
     * frame that lays out a little differently each time makes labels shimmer,
     * so this takes the deterministic cousin: a greedy start, nearest first so
     * the nearest get first pick, then a few passes that move each label to its
     * best spot given where all the others now sit. Every pass can only lower
     * the total, and three settle it in practice.
     *
     * <p>What a spot costs against the fixed scenery — stakes, compass, range
     * readout, its own leader — does not depend on the other labels, so it is
     * worked out once per frame and only the label-against-label part is redone.
     */
    private void layoutLabels(float stem) {
        int n = Math.min(ahead.size(), MAX_FIELD_LABELS);
        labelCount = n;
        final float padX = 6 * dp, padY = 3 * dp;
        float h = padY + nameAsc + nameDesc + distAsc + distDesc + padY;
        // The slider, its end labels and the readout at whatever step it is
        // on, widened to where the readout could be.
        sliderArea.set(sliderX - 11 * dp - 8 * dp - pSmall.measureText("10.0km") - 16 * dp,
                sliderTop - 34 * dp, sliderX + 12 * dp, sliderBottom + 30 * dp);

        for (int i = 0; i < n; i++) {
            Poi p = ahead.get(i);
            labelPoi[i] = p;
            float w = Math.max(p.nameW, p.distW) + 2 * padX;
            labelW[i] = w;
            float px = p.sx, py = p.sy - stem - 5 * dp;
            for (int s = 0; s < SPOTS; s++) {
                spotBox(px, py, w, h, s);
                float cost = 0f;
                for (int k = 0; k < ahead.size(); k++) {
                    Poi q = ahead.get(k);
                    cost += overlap(cL, cT, cR, cB,
                            q.sx - 8 * dp, q.sy - stem - 13 * dp, q.sx + 8 * dp, q.sy);
                }
                cost += overlap(cL, cT, cR, cB, cx - compassRx, compassCy - compassRx,
                        cx + compassRx, compassCy + compassRx + compassThick);
                // The slider is the one control out in the field, so a label
                // all but never sits on it.
                cost += 30f * overlap(cL, cT, cR, cB, sliderArea.left, sliderArea.top,
                        sliderArea.right, sliderArea.bottom);
                // Measured after the label is pulled onto the screen, so a
                // spot off the edge is charged for the leader it really gets.
                cost += (float) Math.hypot(cEx - px, cEy - py) * h * LEAD_WEIGHT;
                cost += (s % SPOT_DX.length) * 4 * dp * dp;
                if (s != p.labelSpot) cost += w * h * 0.1f;
                spotCost[i * SPOTS + s] = cost;
            }
        }

        for (int pass = 0; pass < 3; pass++) {
            for (int i = 0; i < n; i++) {
                // The first pass sees only the labels already placed; later
                // passes see all of them where they now are.
                int others = pass == 0 ? i : n;
                Poi p = labelPoi[i];
                float w = labelW[i];
                float px = p.sx, py = p.sy - stem - 5 * dp;
                int best = 0;
                float bestCost = Float.MAX_VALUE;
                for (int s = 0; s < SPOTS; s++) {
                    float cost = spotCost[i * SPOTS + s];
                    if (cost >= bestCost) continue;
                    spotBox(px, py, w, h, s);
                    for (int k = 0; k < others && cost < bestCost; k++) {
                        if (k == i) continue;
                        float kl = labelBox[k * 4], kt = labelBox[k * 4 + 1];
                        float kr = labelBox[k * 4 + 2], kb = labelBox[k * 4 + 3];
                        Poi q = labelPoi[k];
                        float qx = q.sx, qy = q.sy - stem - 5 * dp;
                        float kex = labelEnd[k * 2], key = labelEnd[k * 2 + 1];
                        cost += 2f * overlap(cL, cT, cR, cB, kl, kt, kr, kb);
                        if (segmentsCross(px, py, cEx, cEy, qx, qy, kex, key)) {
                            cost += 2f * h * h;
                        }
                        if (segmentHitsBox(px, py, cEx, cEy, kl, kt, kr, kb)) cost += h * h;
                        if (segmentHitsBox(qx, qy, kex, key, cL, cT, cR, cB)) cost += h * h;
                    }
                    if (cost < bestCost) {
                        bestCost = cost;
                        best = s;
                    }
                }
                spotBox(px, py, w, h, best);
                labelSpot[i] = best;
                labelBox[i * 4] = cL;
                labelBox[i * 4 + 1] = cT;
                labelBox[i * 4 + 2] = cR;
                labelBox[i * 4 + 3] = cB;
                labelEnd[i * 2] = cEx;
                labelEnd[i * 2 + 1] = cEy;
            }
        }
        for (int i = 0; i < n; i++) labelPoi[i].labelSpot = labelSpot[i];
    }

    /**
     * The box a label would take at a spot, and where its leader would meet
     * it, into cL..cEy. A box that would leave the field is pulled back in and
     * the leader follows it.
     */
    private void spotBox(float px, float py, float w, float h, int s) {
        int dir = s % SPOT_DX.length;
        float l = LEADS[s / SPOT_DX.length] * dp;
        float left = px + SPOT_DX[dir] * l - SPOT_AX[dir] * w;
        float top = py + SPOT_DY[dir] * l - SPOT_AY[dir] * h;
        float minX = pad * 0.5f, maxX = cx + halfSpanPx + 8 * dp;
        float minY = fieldTop, maxY = listTop - 12 * dp;
        if (left + w > maxX) left = maxX - w;
        if (left < minX) left = minX;
        if (top + h > maxY) top = maxY - h;
        if (top < minY) top = minY;
        cL = left;
        cT = top;
        cR = left + w;
        cB = top + h;
        cEx = left + SPOT_AX[dir] * w;
        cEy = top + SPOT_AY[dir] * h;
    }

    /** Whether two segments properly cross; touching at an end does not count. */
    private static boolean segmentsCross(float ax, float ay, float bx, float by,
                                         float ex, float ey, float fx, float fy) {
        float d1 = cross(ex, ey, fx, fy, ax, ay), d2 = cross(ex, ey, fx, fy, bx, by);
        float d3 = cross(ax, ay, bx, by, ex, ey), d4 = cross(ax, ay, bx, by, fx, fy);
        return d1 * d2 < 0 && d3 * d4 < 0;
    }

    private static float cross(float ox, float oy, float ax, float ay, float bx, float by) {
        return (ax - ox) * (by - oy) - (ay - oy) * (bx - ox);
    }

    /** Whether a segment passes through the inside of a box (Liang–Barsky). */
    private static boolean segmentHitsBox(float x0, float y0, float x1, float y1,
                                          float l, float t, float r, float b) {
        float dx = x1 - x0, dy = y1 - y0;
        float t0 = 0f, t1 = 1f;
        float pp, qq;
        for (int e = 0; e < 4; e++) {
            if (e == 0) { pp = -dx; qq = x0 - l; }
            else if (e == 1) { pp = dx; qq = r - x0; }
            else if (e == 2) { pp = -dy; qq = y0 - t; }
            else { pp = dy; qq = b - y0; }
            if (pp == 0f) {
                if (qq <= 0f) return false;
            } else {
                float u = qq / pp;
                if (pp < 0f) {
                    if (u > t1) return false;
                    if (u > t0) t0 = u;
                } else {
                    if (u < t0) return false;
                    if (u < t1) t1 = u;
                }
            }
        }
        return t1 - t0 > 1e-3f;
    }

    /** Area two rectangles share. */
    private static float overlap(float l1, float t1, float r1, float b1,
                                 float l2, float t2, float r2, float b2) {
        float w = Math.min(r1, r2) - Math.max(l1, l2);
        float h = Math.min(b1, b2) - Math.max(t1, t2);
        return w > 0 && h > 0 ? w * h : 0f;
    }

    /**
     * One label as placed: a speech bubble whose tail points at the middle of
     * the stake's head, name over distance on a see-through ground.
     */
    private void drawLabel(Canvas canvas, int n, float stem) {
        Poi p = labelPoi[n];
        float left = labelBox[n * 4], top = labelBox[n * 4 + 1];
        float right = labelBox[n * 4 + 2], bottom = labelBox[n * 4 + 3];
        float mid = (left + right) / 2f;

        // A speech bubble: box and tail are one outline, so there is no
        // telling a line apart from the label it belongs to. The tail is kept
        // a sliver — as wide at the root as it needs to read as a tail, and
        // no wider — and runs to a point at the middle of the stake's head.
        path.rewind();
        path.addRoundRect(left, top, right, bottom, 4 * dp, 4 * dp, Path.Direction.CW);
        float tipX = p.sx, tipY = p.sy - stem - 5 * dp;
        float ex = labelEnd[n * 2], ey = labelEnd[n * 2 + 1];
        if (Math.hypot(tipX - ex, tipY - ey) > 2 * dp) {
            // The root sits a little inside the box, so the two shapes merge
            // into one outline instead of meeting at a seam.
            float mx = mid - ex, my = (top + bottom) / 2f - ey;
            float ml = (float) Math.hypot(mx, my);
            float inset = Math.min(8 * dp, ml);
            float bx = ml > 0 ? ex + mx / ml * inset : ex;
            float by = ml > 0 ? ey + my / ml * inset : ey;
            float dx = tipX - bx, dy = tipY - by;
            float dl = (float) Math.hypot(dx, dy);
            float half = 3 * dp;
            float nx = -dy / dl * half, ny = dx / dl * half;
            tail.rewind();
            tail.moveTo(bx + nx, by + ny);
            tail.lineTo(tipX, tipY);
            tail.lineTo(bx - nx, by - ny);
            tail.close();
            path.op(tail, Path.Op.UNION);
        }
        canvas.drawPath(path, pLabelBg);
        canvas.drawPath(path, pBubble);

        float nameBase = top + 3 * dp + nameAsc;
        pName.setTextAlign(Paint.Align.CENTER);
        canvas.drawText(p.fieldLabel, mid, nameBase, pName);
        pName.setTextAlign(Paint.Align.LEFT);

        pSmall.setTextAlign(Paint.Align.CENTER);
        pSmall.setColor(COL_DIM);
        canvas.drawText(p.distLabel, mid, nameBase + nameDesc + distAsc, pSmall);
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
                String arrow = rel < -3f ? "<" : (rel > 3f ? ">" : "|");

                // Everything listed is in view, so everything is lit.
                pDist.setColor(COL_TARGET);
                canvas.drawText(arrow, pad, y, pDist);

                pList.setTextSize(p.listSize);
                pList.setColor(COL_TEXT);
                canvas.drawText(p.listLabel, pad + 24 * dp, y, pList);

                pDist.setColor(COL_TARGET);
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
    }

    /** Trims a label to fit, in whole characters. Called when data changes, not per frame. */
    private String ellipsise(String s, Paint paint, float maxWidth) {
        if (maxWidth <= 0 || paint.measureText(s) <= maxWidth) return s;
        int n = paint.breakText(s, true, maxWidth - paint.measureText("…"), null);
        return s.substring(0, Math.max(0, n)) + "…";
    }
}
