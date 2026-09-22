package com.mawary;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Insets;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

/**
 * The settings, as a screen of their own rather than a dialog you had to know
 * to long-press for. A handful of things to set, and underneath them what the
 * main screen knows about where its data came from: that used to sit in a
 * footer across the bottom of the main screen, which is space the field can use.
 *
 * <p>Changes are saved as they are made (the sliders and switches) or on the way out
 * (the key), and the main screen reads them back when it resumes, so there is
 * no result to pass back.
 */
public final class SettingsActivity extends Activity {

    static final String EXTRA_SOURCE = "source";
    /** Metres; 0 for a fix with no stated accuracy, negative for no fix at all. */
    static final String EXTRA_ACCURACY = "accuracy";

    /** One percent: the default is 66, which a coarser step could not land back on. */
    private static final int TRANSPARENCY_STEP = 1;

    private float dp;
    private SharedPreferences prefs;
    private EditText keyInput;
    /** The sample label, kept so the switches that change its shape can redraw it. */
    private LabelPreview preview;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        dp = getResources().getDisplayMetrics().density;
        prefs = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(WorldView.COL_BG);
        // Android 15 and later draw every app edge to edge, so keep clear of
        // the bars, and of the keyboard while the key is being typed.
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Insets bars = insets.getInsets(WindowInsets.Type.systemBars()
                        | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            } else {
                v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                        insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            }
            return insets;
        });

        root.addView(header());

        ScrollView scroll = new ScrollView(this);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(px(16), px(4), px(16), px(24));
        body.addView(sectionTitle(R.string.section_display));
        body.addView(transparencyCard());
        body.addView(gap(), new LinearLayout.LayoutParams(1, px(12)));
        // Below the transparency, because it changes the same sample label.
        body.addView(switchCard(R.string.pin_distance_title, R.string.pin_distance_message,
                MainActivity.KEY_HIDE_PIN_DISTANCE, false, on -> preview.setShowDistance(!on)));
        body.addView(sectionTitle(R.string.section_search));
        body.addView(switchCard(R.string.tilt_range_title, R.string.tilt_range_message,
                MainActivity.KEY_TILT_RANGE, true, null));
        body.addView(gap(), new LinearLayout.LayoutParams(1, px(12)));
        body.addView(apiKeyCard());
        scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        root.addView(infoBlock());
        setContentView(root);
    }

    @Override
    protected void onPause() {
        super.onPause();
        prefs.edit().putString(MainActivity.KEY_API, keyInput.getText().toString().trim()).apply();
    }

    // ------------------------------------------------------------ pieces

    /** A back button a thumb can hit, and the title. */
    private View header() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(px(8), px(8), px(16), px(8));

        TextView back = new TextView(this);
        back.setText("←");
        back.setTextColor(WorldView.COL_TEXT);
        back.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        back.setGravity(Gravity.CENTER);
        back.setBackground(buttonFace());
        back.setContentDescription(getString(R.string.back));
        back.setOnClickListener(v -> finish());
        row.addView(back, new LinearLayout.LayoutParams(px(48), px(48)));

        TextView title = new TextView(this);
        title.setText(R.string.settings_title);
        title.setTextColor(WorldView.COL_TEXT);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setPadding(px(16), 0, 0, 0);
        row.addView(title);
        return row;
    }

    private View sectionTitle(int text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(WorldView.COL_MID);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(px(4), px(20), 0, px(8));
        return t;
    }

    /** The transparency: a slider, and a sample label on a scrap of field to judge it by. */
    private View transparencyCard() {
        LinearLayout card = card();

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = itemTitle(R.string.transparency_title);
        top.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        final TextView value = itemTitle(0);
        value.setTextColor(WorldView.COL_TARGET);
        top.addView(value);
        card.addView(top);
        card.addView(itemNote(R.string.transparency_message));

        preview = new LabelPreview(this);
        final int start = prefs.getInt(MainActivity.KEY_LABEL_TRANSPARENCY,
                WorldView.DEFAULT_LABEL_TRANSPARENCY);
        value.setText(getString(R.string.percent_fmt, start));
        preview.setTransparency(start);
        preview.setShowDistance(!prefs.getBoolean(MainActivity.KEY_HIDE_PIN_DISTANCE, false));

        SeekBar bar = new SeekBar(this);
        bar.setMax(100 / TRANSPARENCY_STEP);
        bar.setProgress(start / TRANSPARENCY_STEP);
        bar.setProgressTintList(ColorStateList.valueOf(WorldView.COL_TARGET));
        bar.setThumbTintList(ColorStateList.valueOf(WorldView.COL_TARGET));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                int pct = progress * TRANSPARENCY_STEP;
                value.setText(getString(R.string.percent_fmt, pct));
                preview.setTransparency(pct);
                prefs.edit().putInt(MainActivity.KEY_LABEL_TRANSPARENCY, pct).apply();
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
            }
        });
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, px(48));
        card.addView(bar, barLp);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, px(96));
        lp.topMargin = px(4);
        card.addView(preview, lp);
        return card;
    }

    /**
     * A card that is one switch: title, switch, and what it does underneath.
     * Saved the moment it is flipped, and {@code onChange} is for anything on
     * this screen that has to follow it, like the sample label.
     */
    private View switchCard(int title, int message, String key, boolean byDefault,
                            java.util.function.Consumer<Boolean> onChange) {
        LinearLayout card = card();

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(itemTitle(title),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Switch sw = new Switch(this);
        sw.setChecked(prefs.getBoolean(key, byDefault));
        int[][] states = {{android.R.attr.state_checked}, {}};
        sw.setThumbTintList(new ColorStateList(states,
                new int[]{WorldView.COL_TARGET, WorldView.COL_DIM}));
        sw.setTrackTintList(new ColorStateList(states,
                new int[]{WorldView.COL_TARGET, WorldView.COL_GRID}));
        sw.setOnCheckedChangeListener((b, on) -> {
            prefs.edit().putBoolean(key, on).apply();
            if (onChange != null) onChange.accept(on);
        });
        sw.setContentDescription(getString(title));
        top.addView(sw);
        card.addView(top);
        card.addView(itemNote(message));
        // The whole card toggles, not just the small switch.
        card.setOnClickListener(v -> sw.toggle());
        return card;
    }

    private View gap() {
        return new View(this);
    }

    private View apiKeyCard() {
        LinearLayout card = card();
        card.addView(itemTitle(R.string.api_key_title));
        card.addView(itemNote(R.string.api_key_message));

        keyInput = new EditText(this);
        keyInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        keyInput.setSingleLine(true);
        keyInput.setHint("AIza...");
        keyInput.setText(prefs.getString(MainActivity.KEY_API, ""));
        keyInput.setTextColor(WorldView.COL_TEXT);
        keyInput.setHintTextColor(0xFF5E8A8E);
        keyInput.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        GradientDrawable field = new GradientDrawable();
        field.setColor(WorldView.COL_BG);
        field.setStroke(px(1), WorldView.COL_BUBBLE);
        field.setCornerRadius(px(8));
        keyInput.setBackground(field);
        keyInput.setPadding(px(12), 0, px(12), 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, px(48));
        lp.topMargin = px(12);
        card.addView(keyInput, lp);
        return card;
    }

    /**
     * What the main screen knows about its data, at the foot of the screen:
     * where the places came from, how good the fix is, and which build this is.
     */
    private View infoBlock() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(px(20), 0, px(20), px(16));
        View rule = new View(this);
        rule.setBackgroundColor(WorldView.COL_GRID);
        LinearLayout.LayoutParams ruleLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, px(1)));
        ruleLp.bottomMargin = px(12);
        box.addView(rule, ruleLp);

        String source = getIntent().getStringExtra(EXTRA_SOURCE);
        if (source == null || source.isEmpty()) source = getString(R.string.source_scanning);
        float acc = getIntent().getFloatExtra(EXTRA_ACCURACY, -1f);
        String fix = acc < 0 ? getString(R.string.fix_none)
                : acc == 0 ? getString(R.string.fix_ok)
                : getString(R.string.fix_fmt, Math.round(acc));

        box.addView(infoRow(R.string.info_source, source));
        box.addView(infoRow(R.string.info_fix, fix));
        box.addView(infoRow(R.string.info_version,
                BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")"));
        return box;
    }

    private View infoRow(int label, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, px(3), 0, px(3));
        TextView l = new TextView(this);
        l.setText(label);
        l.setTextColor(WorldView.COL_DIM);
        l.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        row.addView(l, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextColor(WorldView.COL_TEXT);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        row.addView(v);
        return row;
    }

    // ------------------------------------------------------------ styling

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(px(16), px(14), px(16), px(16));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF0B1518);
        bg.setStroke(px(1), WorldView.COL_GRID);
        bg.setCornerRadius(px(12));
        card.setBackground(bg);
        return card;
    }

    private GradientDrawable buttonFace() {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF0E1B1F);
        bg.setStroke(px(1), WorldView.COL_BUBBLE);
        bg.setCornerRadius(px(12));
        return bg;
    }

    private TextView itemTitle(int text) {
        TextView t = new TextView(this);
        if (text != 0) t.setText(text);
        t.setTextColor(WorldView.COL_TEXT);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private TextView itemNote(int text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(WorldView.COL_DIM);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        t.setLineSpacing(0f, 1.2f);
        t.setPadding(0, px(6), 0, 0);
        return t;
    }

    private int px(float dips) {
        return Math.round(dips * dp);
    }

    /**
     * A scrap of the main screen: a few ground lines, two stakes and a label in
     * its bubble over one of them, drawn the way the field draws it, so the
     * transparency can be judged against something showing through.
     */
    private static final class LabelPreview extends View {
        private final float dp;
        private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint stake = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint name = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint dist = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final Path tail = new Path();
        private final RectF box = new RectF();
        private final String sample, sampleDist;
        /** Follows the "no distance on the pin" switch, so the shape is the real one. */
        private boolean showDistance = true;

        LabelPreview(Context ctx) {
            super(ctx);
            dp = ctx.getResources().getDisplayMetrics().density;
            sample = ctx.getString(R.string.preview_name);
            sampleDist = ctx.getString(R.string.preview_dist);
            grid.setStyle(Paint.Style.STROKE);
            grid.setColor(WorldView.COL_GRID);
            grid.setStrokeWidth(1.2f * dp);
            stake.setStyle(Paint.Style.STROKE);
            stake.setColor(WorldView.COL_TARGET);
            stake.setStrokeWidth(2.4f * dp);
            bg.setStyle(Paint.Style.FILL);
            bg.setColor(WorldView.COL_BG);
            edge.setStyle(Paint.Style.STROKE);
            edge.setColor(WorldView.COL_BUBBLE);
            edge.setStrokeWidth(1.4f * dp);
            edge.setStrokeJoin(Paint.Join.ROUND);
            Typeface mono = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD);
            name.setColor(WorldView.COL_TARGET);
            name.setTextSize(20 * dp);
            name.setTypeface(mono);
            name.setTextAlign(Paint.Align.CENTER);
            dist.setColor(WorldView.COL_DIM);
            dist.setTextSize(16 * dp);
            dist.setTypeface(mono);
            dist.setTextAlign(Paint.Align.CENTER);
            setBackgroundColor(WorldView.COL_BG);
        }

        void setTransparency(int pct) {
            bg.setAlpha(Math.round(255 * (100 - pct) / 100f));
            invalidate();
        }

        void setShowDistance(boolean show) {
            if (show == showDistance) return;
            showDistance = show;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight();
            for (int i = 1; i <= 4; i++) {
                float y = h * i / 5f;
                c.drawLine(0, y, w, y - 6 * dp, grid);
            }
            c.drawLine(w * 0.5f, 0, w * 0.5f, h, grid);

            // Two stakes: one the label belongs to, one behind the label.
            float sx = w * 0.78f, sy = h - 10 * dp;
            drawStake(c, sx, sy);
            drawStake(c, w * 0.42f, h * 0.62f);

            float bw = name.measureText(sample) + 12 * dp;
            float bh = (showDistance ? 48 : 30) * dp;
            float left = w * 0.42f - bw / 2f, top = (h - bh) / 2f - 6 * dp;
            box.set(left, top, left + bw, top + bh);
            path.rewind();
            path.addRoundRect(box, 4 * dp, 4 * dp, Path.Direction.CW);
            float tipX = sx, tipY = sy - 21 * dp;
            float bx = box.right - 8 * dp, by = box.centerY();
            float dx = tipX - bx, dy = tipY - by, dl = (float) Math.hypot(dx, dy);
            float nx = -dy / dl * 3 * dp, ny = dx / dl * 3 * dp;
            tail.rewind();
            tail.moveTo(bx + nx, by + ny);
            tail.lineTo(tipX, tipY);
            tail.lineTo(bx - nx, by - ny);
            tail.close();
            path.op(tail, Path.Op.UNION);
            c.drawPath(path, bg);
            c.drawPath(path, edge);
            c.drawText(sample, box.centerX(), top + 22 * dp, name);
            if (showDistance) c.drawText(sampleDist, box.centerX(), top + 41 * dp, dist);
        }

        private void drawStake(Canvas c, float x, float y) {
            c.drawLine(x, y, x, y - 16 * dp, stake);
            float s = 7 * dp, cy = y - 21 * dp;
            path.rewind();
            path.moveTo(x, cy - s);
            path.lineTo(x + s, cy);
            path.lineTo(x, cy + s);
            path.lineTo(x - s, cy);
            path.close();
            c.drawPath(path, stake);
        }
    }
}
