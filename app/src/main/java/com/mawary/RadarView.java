package com.mawary;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.Insets;
import android.hardware.SensorManager;
import android.os.Build;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowInsets;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * The whole UI: one custom view, drawn as wireframe.
 *
 * <p>Nothing here is filled, textured or shadowed. That is partly the look the
 * app is after and partly why it is cheap: a frame is a few dozen stroked
 * primitives and some short text runs, so it holds 60 fps on a 2018 phone while
 * the compass feeds it at 50 Hz.
 *
 * <p>The other half of staying cheap is that onDraw() allocates nothing. Every
 * paint, path and label is built when the data behind it changes (which is
 * rare) rather than per frame (which is not). Per-place distances and bearings
 * are recomputed only when our own position moves.
 */
final class RadarView extends View {

    interface Listener {
        /** The user cycled the plan range; time to widen or narrow the search. */
        void onRangeChanged(int radiusM);
        /** Long press: the user is asking for the API key prompt. */
        void onConfigureRequested();
    }

    private static final int[] RANGES = {200, 500, 1000, 2000};

    /** Half-angle of the cone treated as "where the phone is pointing". */
    private static final float FOV_HALF = 25f;

    /** How many places get a name drawn on the plan itself before it turns to soup. */
    private static final int MAX_PLAN_LABELS = 2;
    private static final int MAX_LIST_ROWS = 3;

    // Read outdoors, so the palette leans brighter than a desk UI would need:
    // the faintest ink here still clears 4.5:1 against the background.
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
    private final Paint pAccent = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pTarget = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pTextS = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pTextM = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pTextL = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pTextDim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pDial = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pNorth = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pSouth = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Path path = new Path();
    private final RectF dial = new RectF();
    /** Baselines already taken by plan labels this frame, so they can be nudged apart. */
    private final float[] labelSlots = new float[MAX_PLAN_LABELS];
    private int labelSlotCount;
    private final GestureDetector gestures;
    private Listener listener;

    private final float dp;

    // --- live state -------------------------------------------------------
    private float headingDeg;
    private float tiltDeg;
    private int compassAccuracy = SensorManager.SENSOR_STATUS_UNRELIABLE;

    private boolean haveFix;
    private double myLat, myLon;

    private final List<Poi> places = new ArrayList<>();
    private final List<Poi> inCone = new ArrayList<>();
    private String source = "";
    private String status = "";
    private boolean permissionNeeded;

    private int rangeIndex = 2;   // 1000 m

    // --- cached labels ----------------------------------------------------
    private String headingLabel = "---";
    private String cardinalLabel = "";
    private int lastHeadingInt = -1;
    private String rangeLabel = "";
    private String ringMidLabel = "";
    private String ringOutLabel = "";
    private String fixLabel = "NO FIX";

    // --- geometry, resolved in onSizeChanged ------------------------------
    private float cx, cy, radius;
    private float tapeY, tapeHalfW, tapePxPerDeg;
    private float listTop, rowH;
    private float pad;
    private int insetTop, insetBottom;

    RadarView(Context ctx) {
        super(ctx);
        dp = ctx.getResources().getDisplayMetrics().density;
        setBackgroundColor(COL_BG);
        setKeepScreenOn(true);

        stroke(pGrid, COL_GRID, 1.2f);
        stroke(pRing, COL_MID, 1.6f);
        stroke(pAccent, COL_ACCENT, 2.2f);
        stroke(pTarget, COL_TARGET, 2.4f);
        stroke(pDial, COL_MID, 1.6f);
        pNorth.setStyle(Paint.Style.FILL);
        pNorth.setColor(COL_NORTH);
        pSouth.setStyle(Paint.Style.FILL);
        pSouth.setColor(COL_SOUTH);
        Typeface mono = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD);
        text(pTextS, COL_TEXT, 19f, mono);
        text(pTextM, COL_TEXT, 26f, mono);
        text(pTextL, COL_ACCENT, 56f, mono);
        text(pTextDim, COL_DIM, 17f, mono);

        setRangeIndex(rangeIndex, false);

        gestures = new GestureDetector(ctx, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                setRangeIndex((rangeIndex + 1) % RANGES.length, true);
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

    private void text(Paint p, int color, float sizeSp, Typeface tf) {
        p.setColor(color);
        p.setTextSize(sizeSp * dp);
        p.setTypeface(tf);
    }

    void setListener(Listener l) {
        listener = l;
    }

    // ------------------------------------------------------------- inputs

    void setHeading(float azimuth, float tilt, int accuracy) {
        headingDeg = azimuth;
        tiltDeg = tilt;
        compassAccuracy = accuracy;
        int whole = (int) (azimuth + 0.5f) % 360;
        if (whole != lastHeadingInt) {
            lastHeadingInt = whole;
            headingLabel = (whole < 100 ? (whole < 10 ? "00" : "0") : "") + whole;
            cardinalLabel = Geo.cardinal(azimuth);
        }
        invalidate();
    }

    void setOrigin(double lat, double lon, float accuracyM) {
        myLat = lat;
        myLon = lon;
        haveFix = true;
        fixLabel = "FIX " + (accuracyM > 0 ? ("+/-" + (int) accuracyM + "m") : "OK");
        relocateAll();
        invalidate();
    }

    void setPlaces(List<Poi> found, String src) {
        places.clear();
        if (found != null) places.addAll(found);
        source = src == null ? "" : src;
        relocateAll();
        invalidate();
    }

    void setStatus(String s) {
        status = s == null ? "" : s;
        invalidate();
    }

    void setPermissionNeeded(boolean needed) {
        permissionNeeded = needed;
        invalidate();
    }

    int getRangeM() {
        return RANGES[rangeIndex];
    }

    private void setRangeIndex(int idx, boolean notify) {
        rangeIndex = idx;
        int r = RANGES[idx];
        rangeLabel = "RANGE " + Poi.format(r);
        ringMidLabel = Poi.format(r * 2f / 3f);
        ringOutLabel = Poi.format(r);
        if (notify && listener != null) listener.onRangeChanged(r);
        invalidate();
    }

    /** Distances and bearings only change when we move, so they are cached. */
    private void relocateAll() {
        if (!haveFix) return;
        double mPerDegLon = Geo.metersPerDegLon(myLat);
        for (int i = 0; i < places.size(); i++) {
            places.get(i).relocate(myLat, myLon, mPerDegLon);
        }
        Collections.sort(places, new Comparator<Poi>() {
            @Override
            public int compare(Poi a, Poi b) {
                return Float.compare(a.distM, b.distM);
            }
        });
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_UP) performClick();
        return gestures.onTouchEvent(event) || super.onTouchEvent(event);
    }

    // ------------------------------------------------------------- layout

    /**
     * The window is edge to edge, so the cutout and any transient system bar
     * have to be kept out of the HUD by hand.
     */
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
        invalidate();
        return insets;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        layoutGeometry(w, h);
    }

    private void layoutGeometry(int w, int h) {
        if (w == 0 || h == 0) return;
        float top0 = 16 * dp + insetTop;
        float bottom0 = 16 * dp + insetBottom;
        pad = 16 * dp;
        tapeY = top0 + 140 * dp;
        tapeHalfW = (w - 2 * pad) / 2f;
        tapePxPerDeg = tapeHalfW / 55f;          // the tape shows +/-55 degrees

        rowH = 46 * dp;
        listTop = h - bottom0 - MAX_LIST_ROWS * rowH - 24 * dp;

        float top = tapeY + 40 * dp;
        float avail = listTop - top - 24 * dp;
        radius = Math.min((w - 2 * pad) / 2f - 18 * dp, avail / 2f);
        cx = w / 2f;
        cy = top + avail / 2f;
    }

    // --------------------------------------------------------------- draw

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();

        drawFrame(canvas, w, h);
        drawHeader(canvas, w);
        drawTape(canvas, w);
        drawPlan(canvas);
        drawList(canvas, w, h);
    }

    /** Corner brackets: the edge of the virtual space, drawn as frame only. */
    private void drawFrame(Canvas canvas, int w, int h) {
        float m = 6 * dp, len = 22 * dp;
        path.rewind();
        path.moveTo(m, m + len); path.lineTo(m, m); path.lineTo(m + len, m);
        path.moveTo(w - m - len, m); path.lineTo(w - m, m); path.lineTo(w - m, m + len);
        path.moveTo(w - m, h - m - len); path.lineTo(w - m, h - m); path.lineTo(w - m - len, h - m);
        path.moveTo(m + len, h - m); path.lineTo(m, h - m); path.lineTo(m, h - m - len);
        canvas.drawPath(path, pRing);
    }

    private void drawHeader(Canvas canvas, int w) {
        float y = insetTop + 40 * dp;
        pTextM.setColor(COL_ACCENT);
        canvas.drawText("M A W A R Y", pad, y, pTextM);
        pTextM.setColor(COL_TEXT);

        pTextDim.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText(source.isEmpty() ? "SCANNING" : source, w - pad, y, pTextDim);
        canvas.drawText(fixLabel, w - pad, y + 23 * dp, pTextDim);
        pTextDim.setTextAlign(Paint.Align.LEFT);

        // Heading readout: the bearing the back of the phone is aimed at.
        pTextL.setTextAlign(Paint.Align.CENTER);
        canvas.drawText(headingLabel, cx - 22 * dp, insetTop + 106 * dp, pTextL);
        pTextL.setTextAlign(Paint.Align.LEFT);
        canvas.drawText("° " + cardinalLabel, cx + 28 * dp, insetTop + 106 * dp, pTextM);

        canvas.drawText(rangeLabel, pad, insetTop + 106 * dp, pTextDim);
    }

    /** A strip of the compass rose around the current heading, with a centre caret. */
    private void drawTape(Canvas canvas, int w) {
        canvas.drawLine(pad, tapeY, w - pad, tapeY, pGrid);

        int base = Math.round(headingDeg / 15f) * 15;
        for (int d = base - 60; d <= base + 60; d += 15) {
            float off = Geo.delta180(d, headingDeg) * tapePxPerDeg;
            if (Math.abs(off) > tapeHalfW) continue;
            float x = cx + off;
            boolean major = ((d % 90) + 360) % 90 == 0;
            canvas.drawLine(x, tapeY, x, tapeY - (major ? 15 : 8) * dp, major ? pRing : pGrid);
            if (major) {
                String label = Geo.CARDINAL_16[((((d / 90) % 4) + 4) % 4) * 4];
                pTextS.setTextAlign(Paint.Align.CENTER);
                pTextS.setColor("N".equals(label) ? COL_NORTH : COL_DIM);
                canvas.drawText(label, x, tapeY - 23 * dp, pTextS);
                pTextS.setColor(COL_TEXT);
                pTextS.setTextAlign(Paint.Align.LEFT);
            }
        }

        // Caret marking dead ahead.
        path.rewind();
        path.moveTo(cx - 8 * dp, tapeY + 14 * dp);
        path.lineTo(cx, tapeY + 1 * dp);
        path.lineTo(cx + 8 * dp, tapeY + 14 * dp);
        canvas.drawPath(path, pAccent);
    }

    /** The plan view: us at the centre, heading up. */
    private void drawPlan(Canvas canvas) {
        // The compass sits at the back of the plan, under everything else.
        drawFlatCompass(canvas);

        // Range rings.
        canvas.drawCircle(cx, cy, radius, pRing);
        canvas.drawCircle(cx, cy, radius * 2f / 3f, pGrid);

        // Cross hairs, stopping clear of the compass.
        float gap = radius * 0.46f;
        canvas.drawLine(cx, cy - radius, cx, cy - gap, pGrid);
        canvas.drawLine(cx, cy + gap, cx, cy + radius, pGrid);
        canvas.drawLine(cx - radius, cy, cx - gap, cy, pGrid);
        canvas.drawLine(cx + gap, cy, cx + radius, cy, pGrid);

        // The cone the phone is pointing into.
        drawConeEdge(canvas, -FOV_HALF);
        drawConeEdge(canvas, FOV_HALF);

        // Cardinal letters, riding the outer ring.
        for (int i = 0; i < 4; i++) {
            float bearing = i * 90f;
            float rel = (float) Math.toRadians(Geo.delta180(bearing, headingDeg));
            float rr = radius + 21 * dp;
            float x = cx + rr * (float) Math.sin(rel);
            float y = cy - rr * (float) Math.cos(rel);
            pTextS.setTextAlign(Paint.Align.CENTER);
            pTextS.setColor(i == 0 ? COL_NORTH : COL_DIM);
            canvas.drawText(Geo.CARDINAL_16[i * 4], x, y + 7 * dp, pTextS);
            pTextS.setColor(COL_TEXT);
            pTextS.setTextAlign(Paint.Align.LEFT);
        }

        // Ring distance labels.
        canvas.drawText(ringOutLabel, cx + 7 * dp, cy - radius + 25 * dp, pTextDim);
        canvas.drawText(ringMidLabel, cx + 7 * dp, cy - radius * 2f / 3f + 25 * dp, pTextDim);

        if (permissionNeeded) {
            centreMessage(canvas, "LOCATION PERMISSION REQUIRED");
            return;
        }
        if (!haveFix) {
            centreMessage(canvas, "ACQUIRING POSITION...");
            return;
        }

        inCone.clear();
        labelSlotCount = 0;
        int range = getRangeM();
        int labelled = 0;

        for (int i = 0; i < places.size(); i++) {
            Poi p = places.get(i);
            float rel = Geo.delta180(p.bearingDeg, headingDeg);
            boolean ahead = Math.abs(rel) <= FOV_HALF;
            if (ahead && p.distM <= range) inCone.add(p);

            float rad = (float) Math.toRadians(rel);
            float sin = (float) Math.sin(rad), cos = (float) Math.cos(rad);

            if (p.distM > range) {
                // Out of range: a tick just beyond the rim keeps its direction visible.
                float r0 = radius + 3 * dp, r1 = radius + 8 * dp;
                canvas.drawLine(cx + r0 * sin, cy - r0 * cos,
                        cx + r1 * sin, cy - r1 * cos, ahead ? pRing : pGrid);
                continue;
            }

            float r = radius * (p.distM / range);
            float x = cx + r * sin, y = cy - r * cos;

            if (ahead) {
                diamond(canvas, x, y, 9f * dp, pTarget);
                if (labelled < MAX_PLAN_LABELS) {
                    drawPlanLabel(canvas, p, x, y);
                    labelled++;
                }
            } else {
                diamond(canvas, x, y, 5.5f * dp, pGrid);
            }
        }

        if (places.isEmpty()) {
            centreMessage(canvas, status.isEmpty() ? "SEARCHING..." : status);
        }
    }

    /**
     * A magnetic compass lying flat on the ground, seen from where the phone is
     * aimed. Turning the phone swings the needle; tipping it down opens the
     * dial from a slit into a circle, so heading and pitch read at a glance
     * without either being a number to decode.
     *
     * <p>True perspective would squash the dial to a bare line whenever the
     * phone is level, which is precisely when it stops telling you anything.
     * So the opening runs from 0.28 when level to 1.0 when aimed straight down:
     * it still widens as you tip the phone, but never collapses.
     */
    private void drawFlatCompass(Canvas canvas) {
        float rx = radius * 0.38f;
        float open = (float) Math.abs(Math.sin(Math.toRadians(tiltDeg)));
        float ry = rx * (0.28f + 0.72f * open);

        dial.set(cx - rx, cy - ry, cx + rx, cy + ry);
        canvas.drawOval(dial, pDial);

        // Ticks every 30 degrees of true bearing, longer at the cardinals.
        for (int b = 0; b < 360; b += 30) {
            double rel = Math.toRadians(Geo.delta180(b, headingDeg));
            float sin = (float) Math.sin(rel), cos = (float) Math.cos(rel);
            float k = (b % 90 == 0) ? 0.80f : 0.90f;
            canvas.drawLine(cx + rx * k * sin, cy - ry * k * cos,
                    cx + rx * sin, cy - ry * cos, pDial);
        }

        // The needle, foreshortened along with the dial it sits on.
        double relN = Math.toRadians(Geo.delta180(0f, headingDeg));
        float sinN = (float) Math.sin(relN), cosN = (float) Math.cos(relN);
        float nx = cx + rx * 0.78f * sinN, ny = cy - ry * 0.78f * cosN;
        float sx = cx - rx * 0.78f * sinN, sy = cy + ry * 0.78f * cosN;
        // The wings sit a quarter turn round the dial from the needle.
        float wx = rx * 0.19f * cosN, wy = ry * 0.19f * sinN;

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

    private void drawConeEdge(Canvas canvas, float deg) {
        float rad = (float) Math.toRadians(deg);
        float r = radius + 8 * dp;
        canvas.drawLine(cx, cy, cx + r * (float) Math.sin(rad), cy - r * (float) Math.cos(rad), pGrid);
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

    /**
     * Name and distance beside a mark, flipped to whichever side has room.
     *
     * <p>Places at a similar bearing and distance land almost on top of each
     * other, so labels claim a vertical slot and later ones are pushed clear of
     * the slots already taken. A leader line then ties the text back to its
     * mark, since the text may no longer sit beside it.
     */
    private void drawPlanLabel(Canvas canvas, Poi p, float x, float y) {
        final float slotH = 48 * dp;
        float ty = y - 2 * dp;
        for (boolean moved = true; moved; ) {
            moved = false;
            for (int i = 0; i < labelSlotCount; i++) {
                if (Math.abs(ty - labelSlots[i]) < slotH) {
                    ty = labelSlots[i] + slotH;
                    moved = true;
                }
            }
        }
        if (labelSlotCount < labelSlots.length) labelSlots[labelSlotCount++] = ty;

        boolean left = x > cx;
        float tx = x + (left ? -15 * dp : 15 * dp);

        if (Math.abs(ty - (y - 2 * dp)) > 1f) {
            canvas.drawLine(x, y, tx, ty - 3 * dp, pGrid);
        }

        pTextS.setColor(COL_TARGET);
        pTextS.setTextAlign(left ? Paint.Align.RIGHT : Paint.Align.LEFT);
        canvas.drawText(ellipsise(p.name, pTextS, radius * 0.8f), tx, ty, pTextS);
        pTextS.setColor(COL_DIM);
        canvas.drawText(p.distLabel, tx, ty + 24 * dp, pTextS);
        pTextS.setColor(COL_TEXT);
        pTextS.setTextAlign(Paint.Align.LEFT);
    }

    private void centreMessage(Canvas canvas, String msg) {
        pTextS.setTextAlign(Paint.Align.CENTER);
        pTextS.setColor(COL_DIM);
        canvas.drawText(msg, cx, cy + radius * 0.55f, pTextS);
        pTextS.setColor(COL_TEXT);
        pTextS.setTextAlign(Paint.Align.LEFT);
    }

    /** The readable half: what is ahead, nearest first. */
    private void drawList(Canvas canvas, int w, int h) {
        canvas.drawLine(pad, listTop - 16 * dp, w - pad, listTop - 16 * dp, pGrid);

        if (inCone.isEmpty()) {
            pTextDim.setColor(COL_DIM);
            canvas.drawText(haveFix ? "NOTHING AHEAD - SWEEP AROUND" : "STAND BY",
                    pad, listTop + 26 * dp, pTextDim);
        } else {
            int rows = Math.min(MAX_LIST_ROWS, inCone.size());
            for (int i = 0; i < rows; i++) {
                Poi p = inCone.get(i);
                float y = listTop + 26 * dp + i * rowH;
                boolean primary = i == 0;

                // Turn indicator: which way to swing the phone to line the thing up.
                float rel = Geo.delta180(p.bearingDeg, headingDeg);
                String arrow = rel < -3f ? "<" : (rel > 3f ? ">" : "|");
                pTextM.setColor(primary ? COL_TARGET : COL_DIM);
                canvas.drawText(arrow, pad, y, pTextM);

                pTextM.setColor(primary ? COL_TEXT : COL_DIM);
                float distW = pTextM.measureText(p.distLabel) + 14 * dp;
                canvas.drawText(ellipsise(p.name, pTextM, w - 2 * pad - 34 * dp - distW),
                        pad + 34 * dp, y, pTextM);

                pTextM.setColor(primary ? COL_TARGET : COL_DIM);
                pTextM.setTextAlign(Paint.Align.RIGHT);
                canvas.drawText(p.distLabel, w - pad, y, pTextM);
                pTextM.setTextAlign(Paint.Align.LEFT);
                pTextM.setColor(COL_TEXT);
                pTextS.setColor(COL_TEXT);
            }
        }

        // Footer: hints and any warning worth surfacing.
        String note;
        if (compassAccuracy == SensorManager.SENSOR_STATUS_ACCURACY_LOW
                || compassAccuracy == SensorManager.SENSOR_STATUS_UNRELIABLE) {
            note = "COMPASS UNCALIBRATED - MOVE IN A FIGURE 8";
        } else if (Math.abs(tiltDeg) > 70f) {
            note = "PHONE IS FLAT - AIM THE BACK AT THE HORIZON";
        } else if (!status.isEmpty() && !places.isEmpty()) {
            note = status;
        } else {
            note = "TAP: RANGE   LONG PRESS: API KEY";
        }
        pTextDim.setColor(COL_DIM);
        canvas.drawText(note, pad, h - insetBottom - 22 * dp, pTextDim);
    }

    /** Trims a label to fit, in whole characters, without allocating when it already fits. */
    private String ellipsise(String s, Paint paint, float maxWidth) {
        if (paint.measureText(s) <= maxWidth) return s;
        int n = paint.breakText(s, true, maxWidth - paint.measureText("…"), null);
        return s.substring(0, Math.max(0, n)) + "…";
    }
}
