package dev.syed.tvpilot.tv;

import android.content.Context;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.Choreographer;
import android.view.View;

/**
 * The voice bar. A frosted glass circle with the white "cookie" mic fades and floats up at the bottom centre, holds
 * for a beat, then slowly stretches sideways into the pill, the cookie gliding to its left end as the words fade in.
 * Leaving, it folds back into the circle, which sinks away. While listening the cookie breathes with your voice, turns slowly and sends out soft
 * rings; while thinking it smooths into a circle with a light orbiting it; then your words appear. Everything is drawn
 * here (no layouts), driven by springs, so it stays smooth on slow TV chips.
 */
final class MicBar extends View implements Choreographer.FrameCallback {
    static final int HIDDEN = 0, LISTENING = 1, THINKING = 2, RESULT = 3, ERROR = 4;

    interface Gone { void onGone(); }

    private int state = HIDDEN;
    private String title = "", text = "", hint = "";
    private final Spring appear = new Spring(95f, 0.82f, 0f);    // the circle floating in
    private final Spring expand = new Spring(70f, 0.86f, 0f);    // 0 = circle, 1 = full pill
    private float circleAge;                                      // s since the circle arrived
    private boolean holdCircle;                                   // pre-circle: stay round until the real bar takes over
    /** Set when a pre-circle is on screen: the next bar starts already round and in place (no second entrance). */
    static volatile long circleHandoffUntil;
    private final Spring pop = new Spring(260f, 0.52f, 0f);      // the cookie's bouncy entrance
    private final Spring morph = new Spring(120f, 0.9f, 1f);     // 1 = scalloped cookie, 0 = circle
    private final Spring level = new Spring(320f, 0.8f, 0f);     // your voice
    private final Spring textIn = new Spring(140f, 0.95f, 0f);
    private float levelTarget, spin, orbit, time;
    private final float[] rings = new float[4];                  // ages of the rings (s), < 0 = unused
    private float nextRing;
    private long lastFrame;
    private boolean running, speaking;
    private Gone gone;

    private final Paint panel = new Paint(Paint.ANTI_ALIAS_FLAG), rim = new Paint(Paint.ANTI_ALIAS_FLAG),
            shadow = new Paint(Paint.ANTI_ALIAS_FLAG), cookie = new Paint(Paint.ANTI_ALIAS_FLAG),
            glow = new Paint(Paint.ANTI_ALIAS_FLAG), glyph = new Paint(Paint.ANTI_ALIAS_FLAG),
            glyphStroke = new Paint(Paint.ANTI_ALIAS_FLAG), ring = new Paint(Paint.ANTI_ALIAS_FLAG),
            arc = new Paint(Paint.ANTI_ALIAS_FLAG), sheen = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint titlePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG), textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG),
            hintPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF r = new RectF(), tmp = new RectF();
    private StaticLayout textLayout;
    private int layoutWidth = -1;
    private String layoutText;

    MicBar(Context c) {
        super(c);
        setLayerType(LAYER_TYPE_HARDWARE, null);
        for (int i = 0; i < rings.length; i++) rings[i] = -1;
        rim.setStyle(Paint.Style.STROKE);
        ring.setStyle(Paint.Style.STROKE);
        arc.setStyle(Paint.Style.STROKE);
        arc.setStrokeCap(Paint.Cap.ROUND);
        glyphStroke.setStyle(Paint.Style.STROKE);
        glyphStroke.setStrokeCap(Paint.Cap.ROUND);
        glyph.setColor(0xFF141416);
        glyphStroke.setColor(0xFF141416);
        Typeface bold = Typeface.create("sans-serif-medium", Typeface.NORMAL);
        titlePaint.setTypeface(bold);
        titlePaint.setColor(Color.WHITE);
        textPaint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        textPaint.setColor(0xD9FFFFFF);
        hintPaint.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        hintPaint.setColor(0x73FFFFFF);
    }

    void setOnGone(Gone g) { gone = g; }

    int state() { return state; }

    boolean showing() { return state != HIDDEN; }

    /** Shows (or moves to) a state. */
    void show(int newState, String title, String text, String hint) {
        boolean wasHidden = state == HIDDEN;
        state = newState;
        this.title = title == null ? "" : title;
        boolean textChanged = !TextUtils.equals(this.text, text == null ? "" : text);
        this.text = text == null ? "" : text;
        this.hint = hint == null ? "" : hint;
        holdCircle = false;
        speaking = false;
        if (wasHidden && seamless) {   // moved to another window mid-task: carry on as it was, no grow-in
            seamless = false;
            appear.snap(1); pop.snap(1); expand.snap(1); circleAge = 1; textIn.snap(1);
            wasHidden = false;
        }
        if (wasHidden) {
            boolean handoff = System.currentTimeMillis() < circleHandoffUntil;
            circleHandoffUntil = 0;
            appear.snap(handoff ? 1 : 0);
            expand.snap(0);
            circleAge = handoff ? 0.2f : 0;
            pop.snap(handoff ? 1 : 0);
            morph.snap(1);
            level.snap(0);
            textIn.snap(0);
            for (int i = 0; i < rings.length; i++) rings[i] = -1;
            nextRing = 0;
        }
        appear.target = 1;
        pop.target = 1;
        morph.target = newState == LISTENING ? 1 : newState == THINKING ? 0 : 0.55f;
        if (newState != LISTENING) levelTarget = 0;
        if (textChanged) {
            textIn.snap(0);
        }
        textIn.target = 1;
        start();
        invalidate();
    }

    /** Just the circle, floating in over whatever is on screen (covers Google's popup while it's being closed). */
    void showCircle() {
        show(LISTENING, "", "", "");
        holdCircle = true;
    }

    /** TV Pilot is talking: the cookie scallops again and pulses with the voice. */
    void setSpeaking(boolean s) {
        speaking = s;
        morph.target = s ? 1 : state == LISTENING ? 1 : state == THINKING ? 0 : 0.55f;
        if (!s) levelTarget = 0;
        start();
    }

    void setLevel(float l) {
        levelTarget = Math.max(0, Math.min(1, l));
    }

    void setLiveText(String t) {
        text = t == null ? "" : t;
        invalidate();
    }

    /** Slides away; onGone runs once it's off screen. */
    static boolean seamless;

    void hide() {
        if (state == HIDDEN) return;
        state = HIDDEN;
        expand.target = 0;   // fold into the circle first; doFrame lets it sink once it's round
        if (expand.value < 0.25f) { appear.target = 0; pop.target = 0; }
        start();
    }

    private void start() {
        if (running) return;
        running = true;
        lastFrame = 0;
        Choreographer.getInstance().postFrameCallback(this);
    }

    @Override protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        running = false;
        Choreographer.getInstance().removeFrameCallback(this);
    }

    @Override public void doFrame(long now) {
        if (!running) return;
        float dt = lastFrame == 0 ? 1 / 60f : Math.min(0.05f, (now - lastFrame) / 1e9f);
        lastFrame = now;
        time += dt;
        appear.step(dt);
        if (state != HIDDEN) {
            if (appear.value > 0.6f) circleAge += dt;
            // Hold round for a beat, then stretch.
            if (!holdCircle && circleAge > 0.22f) expand.target = 1;
        } else if (expand.value < 0.25f) {
            appear.target = 0;
            pop.target = 0;
        }
        expand.step(dt);
        pop.step(dt);
        morph.step(dt);
        level.target = levelTarget;
        level.step(dt);
        textIn.step(dt);
        float speed = state == THINKING ? 1.6f : 0.17f + level.value * 0.5f;
        spin += dt * speed * 360f / 6f;
        orbit += dt * 360f * (state == THINKING ? 1.1f : 0f);
        // Rings: one every ~0.7 s while listening, sooner and brighter when you're loud.
        if (state == LISTENING || (speaking && state == RESULT)) {
            nextRing -= dt * (0.6f + level.value * 1.8f);
            if (nextRing <= 0) {
                for (int i = 0; i < rings.length; i++) if (rings[i] < 0) { rings[i] = 0; break; }
                nextRing = 0.7f;
            }
        }
        for (int i = 0; i < rings.length; i++) if (rings[i] >= 0) { rings[i] += dt; if (rings[i] > 1.4f) rings[i] = -1; }
        invalidate();
        if (state == HIDDEN && appear.target == 0 && appear.value < 0.004f && appear.velocity <= 0.01f) {
            running = false;
            appear.snap(0);
            if (gone != null) gone.onGone();
            return;
        }
        Choreographer.getInstance().postFrameCallback(this);
    }

    @Override protected void onDraw(Canvas c) {
        float a = appear.value;
        if (a <= 0.002f && state == HIDDEN) return;
        int w = getWidth(), h = getHeight();
        float k = w / 1920f;   // designed on a 1920-wide screen; the view is the bottom band of it
        float fullW = Math.min(w * 0.64f, 1180 * k), ph = 156 * k, margin = 58 * k;
        float e = Math.max(0, expand.value);
        float pw = ph + (fullW - ph) * Math.min(1.02f, e);
        float rise = (1 - a) * 70 * k;
        float left = (w - pw) / 2f, top = h - margin - ph + rise;
        float alpha = Math.max(0, Math.min(1, a * 1.4f));
        r.set(left, top, left + pw, top + ph);
        float rad = ph / 2f;
        // The circle grows from 55% as it floats in.
        float sc = 0.55f + 0.45f * Math.min(1.05f, Math.max(0, a));
        c.save();
        c.scale(sc, sc, r.centerX(), r.centerY());
        // Words only once the pill is mostly open.
        float words = Math.max(0, Math.min(1, (e - 0.55f) / 0.4f));

        // Shadow and glass
        shadow.setColor(Color.argb(Math.round(150 * alpha), 0, 0, 0));
        shadow.setMaskFilter(new BlurMaskFilter(34 * k, BlurMaskFilter.Blur.NORMAL));
        tmp.set(r);
        tmp.offset(0, 14 * k);
        c.drawRoundRect(tmp, rad, rad, shadow);
        panel.setShader(new LinearGradient(0, r.top, 0, r.bottom,
                Color.argb(Math.round(242 * alpha), 44, 44, 48), Color.argb(Math.round(242 * alpha), 22, 22, 25), Shader.TileMode.CLAMP));
        c.drawRoundRect(r, rad, rad, panel);
        // frosted sheen across the top half
        sheen.setShader(new LinearGradient(0, r.top, 0, r.centerY(),
                Color.argb(Math.round(30 * alpha), 255, 255, 255), Color.argb(0, 255, 255, 255), Shader.TileMode.CLAMP));
        c.drawRoundRect(r, rad, rad, sheen);
        rim.setStrokeWidth(1.6f * k);
        rim.setShader(new LinearGradient(0, r.top, 0, r.bottom,
                Color.argb(Math.round(80 * alpha), 255, 255, 255), Color.argb(Math.round(18 * alpha), 255, 255, 255), Shader.TileMode.CLAMP));
        c.drawRoundRect(r, rad, rad, rim);

        // The cookie mic
        float cx = r.left + rad, cy = r.centerY();
        float p = Math.max(0, pop.value);
        float lv = level.value;
        float base = 50 * k * (0.55f + 0.45f * p) * (1 + 0.10f * lv);
        if (state == THINKING) base *= 1 + 0.03f * (float) Math.sin(time * 5);
        // rings
        for (float age : rings) {
            if (age < 0) continue;
            float t = age / 1.4f;
            float rr = base * (1.05f + 0.85f * t);
            ring.setStrokeWidth((3.2f - 2.2f * t) * k);
            ring.setColor(Color.argb(Math.round(alpha * (1 - t) * (70 + 110 * lv)), 255, 255, 255));
            c.drawCircle(cx, cy, rr, ring);
        }
        // glow
        glow.setShader(new RadialGradient(cx, cy, base * 2.1f,
                Color.argb(Math.round(alpha * (40 + 70 * lv)), 255, 255, 255), Color.argb(0, 255, 255, 255), Shader.TileMode.CLAMP));
        c.drawCircle(cx, cy, base * 2.1f, glow);
        // scalloped body
        float amp = 0.075f * Math.max(0, morph.value) + 0.03f * lv * morph.value;
        path.reset();
        int steps = 180;
        double rot = Math.toRadians(spin);
        for (int i = 0; i <= steps; i++) {
            double t = i * 2 * Math.PI / steps;
            double rr = base * (1 + amp * Math.cos(9 * (t + rot)));
            float x = cx + (float) (rr * Math.cos(t)), y = cy + (float) (rr * Math.sin(t));
            if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
        }
        path.close();
        cookie.setShader(new RadialGradient(cx - base * 0.35f, cy - base * 0.45f, base * 1.6f,
                Color.argb(Math.round(255 * alpha), 255, 255, 255), Color.argb(Math.round(255 * alpha), 222, 222, 228), Shader.TileMode.CLAMP));
        c.drawPath(path, cookie);
        // thinking: a light orbiting the cookie
        if (state == THINKING || morph.value < 0.4f) {
            float ta = Math.max(0, 1 - morph.value / 0.4f) * alpha;
            tmp.set(cx - base * 1.28f, cy - base * 1.28f, cx + base * 1.28f, cy + base * 1.28f);
            arc.setStrokeWidth(4 * k);
            arc.setShader(null);
            arc.setColor(Color.argb(Math.round(230 * ta), 255, 255, 255));
            c.drawArc(tmp, orbit, 70, false, arc);
            arc.setColor(Color.argb(Math.round(70 * ta), 255, 255, 255));
            c.drawArc(tmp, orbit + 180, 40, false, arc);
        }
        drawMic(c, cx, cy, base * 0.95f, alpha);

        // Text, clipped to the glass while it stretches
        c.clipRect(r.left + rad, r.top, r.right - rad * 0.4f, r.bottom);
        alpha *= words;
        if (alpha <= 0.003f) { c.restore(); return; }
        float tx = cx + base * 1.0f + 48 * k * 0.75f + 24 * k, right = r.right - 44 * k;
        float hintW = 0;
        if (!hint.isEmpty()) {
            hintPaint.setTextSize(22 * k);
            hintPaint.setAlpha(Math.round(115 * alpha));
            hintW = hintPaint.measureText(hint);
            c.drawText(hint, right - hintW, r.centerY() + 8 * k, hintPaint);
            hintW += 28 * k;
        }
        float avail = Math.max(50 * k, right - hintW - tx);
        titlePaint.setTextSize(36 * k);
        titlePaint.setAlpha(Math.round(255 * alpha));
        String t = TextUtils.ellipsize(title, titlePaint, avail, TextUtils.TruncateAt.END).toString();
        boolean hasText = !text.isEmpty();
        float titleY = hasText ? r.top + 62 * k : r.centerY() + 13 * k;
        c.drawText(t, tx, titleY, titlePaint);
        if (hasText) {
            textPaint.setTextSize(27 * k);
            int lw = Math.round(avail);
            if (textLayout == null || lw != layoutWidth || !text.equals(layoutText)) {
                textLayout = StaticLayout.Builder.obtain(text, 0, text.length(), textPaint, lw)
                        .setMaxLines(2).setEllipsize(TextUtils.TruncateAt.END)
                        .setAlignment(Layout.Alignment.ALIGN_NORMAL).setLineSpacing(0, 1.0f).build();
                layoutWidth = lw;
                layoutText = text;
            }
            float ti = Math.max(0, Math.min(1, textIn.value));
            textPaint.setAlpha(Math.round(217 * alpha * ti));
            c.save();
            c.translate(tx, r.top + 80 * k + (1 - ti) * 10 * k);
            textLayout.draw(c);
            c.restore();
        }
        c.restore();
    }

    /** The microphone glyph: capsule, cradle, stem and base, in near-black on the white cookie. */
    private void drawMic(Canvas c, float cx, float cy, float s, float alpha) {
        int al = Math.round(255 * alpha);
        glyph.setAlpha(al);
        glyphStroke.setAlpha(al);
        float w = s * 0.34f, h = s * 0.62f;
        tmp.set(cx - w / 2, cy - h * 0.66f, cx + w / 2, cy + h * 0.18f);
        c.drawRoundRect(tmp, w / 2, w / 2, glyph);
        glyphStroke.setStrokeWidth(s * 0.085f);
        tmp.set(cx - s * 0.33f, cy - s * 0.42f, cx + s * 0.33f, cy + s * 0.26f);
        c.drawArc(tmp, 15, 150, false, glyphStroke);
        c.drawLine(cx, cy + s * 0.27f, cx, cy + s * 0.44f, glyphStroke);
        c.drawLine(cx - s * 0.17f, cy + s * 0.46f, cx + s * 0.17f, cy + s * 0.46f, glyphStroke);
    }
}
