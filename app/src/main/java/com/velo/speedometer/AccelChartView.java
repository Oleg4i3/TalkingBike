package com.velo.speedometer;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.widget.OverScroller;

import java.util.List;
import java.util.Locale;

/**
 * Two-panel chart in one View:
 *   TOP 65%  — raw |accel| (m/s²) + cadence overlay (RPM)
 *   BOTTOM 35% — GPS speed (km/h)
 *
 * Both panels share the same X (time) axis, scroll and zoom together.
 *
 * Touch:
 *   Horizontal drag / fling  — scroll in time
 *   Pinch / spread           — zoom (1 s … 120 s)
 */
public class AccelChartView extends View {

    // ── Window ────────────────────────────────────────────────────────────────
    private static final float WIN_MIN_SEC = 1f;
    private static final float WIN_MAX_SEC = 120f;
    private static final float WIN_DEF_SEC = 10f;
    private static final float GRAVITY     = 9.81f;

    // Cadence Y range (right axis of top panel)
    private static final float RPM_LO = 40f;
    private static final float RPM_HI = 150f;

    // Panel split
    private static final float ACCEL_FRAC = 0.65f;  // top panel fraction of total height
    private static final float DIV_H_DP   = 1.5f;
    private static final float RIGHT_DP   = 38f;     // right margin for cadence axis labels

    // ── Data ──────────────────────────────────────────────────────────────────
    private List<float[]> accelData   = null; // [elapsed_sec, mag_ms2]
    private List<float[]> cadenceData = null; // [elapsed_sec, rpm, stable(1/0)]
    private List<float[]> speedData   = null; // [elapsed_sec, speedKmh]
    private List<float[]> hrData      = null; // [elapsed_sec, bpm]

    // HR Y range (right axis of bottom panel), fixed physiological range
    private static final float HR_LO = 40f;
    private static final float HR_HI = 200f;

    // ── Scroll / zoom state ───────────────────────────────────────────────────
    private float totalSec   = 0f;
    private float viewEndSec = WIN_DEF_SEC;
    private float windowSec  = WIN_DEF_SEC;
    private boolean autoScroll = true;

    // Accel Y range (lerp-smoothed)
    private float aLo = 7f, aHi = 13f;
    // Speed Y range (lerp-smoothed)
    private float sLo = 0f, sHi = 40f;

    // ── Paints ────────────────────────────────────────────────────────────────
    private final Paint pBg         = new Paint();
    private final Paint pDiv        = new Paint();
    private final Paint pGrid       = new Paint();
    private final Paint pGrav       = new Paint();
    private final Paint pAccel      = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pCadStable  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pCadUncert  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pSpeed      = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pSpeedFill  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pHr        = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pHrLabel   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pLabelL     = new Paint(Paint.ANTI_ALIAS_FLAG); // accel axis
    private final Paint pLabelR     = new Paint(Paint.ANTI_ALIAS_FLAG); // cadence axis
    private final Paint pLabelS     = new Paint(Paint.ANTI_ALIAS_FLAG); // speed axis
    private final Paint pLive       = new Paint(Paint.ANTI_ALIAS_FLAG);

    private final Path pathAccel     = new Path();
    private final Path pathCadStable = new Path();
    private final Path pathCadUncert = new Path();
    private final Path pathSpeed     = new Path();
    private final Path pathSpeedFill = new Path();
    private final Path pathHr        = new Path();

    // ── Gestures ──────────────────────────────────────────────────────────────
    private final GestureDetector      gesture;
    private final ScaleGestureDetector scale;
    private final OverScroller         scroller;

    private float rightPx;
    private float divPx;

    private final Runnable flingTick = new Runnable() {
        @Override public void run() {
            if (scroller.computeScrollOffset()) {
                viewEndSec = scroller.getCurrX() / 1000f;
                clamp();
                autoScroll = viewEndSec >= totalSec - 0.3f;
                invalidate();
                postOnAnimation(this);
            }
        }
    };

    // ── Constructor ───────────────────────────────────────────────────────────

    public AccelChartView(Context ctx) { this(ctx, null); }

    public AccelChartView(Context ctx, AttributeSet attrs) {
        super(ctx, attrs);
        rightPx = dp(ctx, RIGHT_DP);
        divPx   = dp(ctx, DIV_H_DP);

        pBg.setColor(0xFF111111); pBg.setStyle(Paint.Style.FILL);
        pDiv.setColor(0xFF333333); pDiv.setStyle(Paint.Style.FILL);
        pGrid.setColor(0xFF232323); pGrid.setStyle(Paint.Style.STROKE); pGrid.setStrokeWidth(1f);

        pGrav.setColor(0xFF2A4A2A); pGrav.setStyle(Paint.Style.STROKE); pGrav.setStrokeWidth(1.5f);
        pGrav.setPathEffect(new DashPathEffect(new float[]{8, 6}, 0));

        pAccel.setColor(0xFF00E5CC); pAccel.setStyle(Paint.Style.STROKE);
        pAccel.setStrokeWidth(1.8f); pAccel.setStrokeJoin(Paint.Join.ROUND);

        pCadStable.setColor(0xFFFFD600); pCadStable.setStyle(Paint.Style.STROKE);
        pCadStable.setStrokeWidth(3.5f); pCadStable.setStrokeJoin(Paint.Join.ROUND);
        pCadStable.setStrokeCap(Paint.Cap.ROUND);

        pCadUncert.setColor(0xFFFF8C00); pCadUncert.setStyle(Paint.Style.STROKE);
        pCadUncert.setStrokeWidth(3f);
        pCadUncert.setPathEffect(new DashPathEffect(new float[]{12, 8}, 0));
        pCadUncert.setStrokeCap(Paint.Cap.ROUND);

        pSpeed.setColor(0xFF4CAF50); pSpeed.setStyle(Paint.Style.STROKE);
        pSpeed.setStrokeWidth(2f); pSpeed.setStrokeJoin(Paint.Join.ROUND);

        pSpeedFill.setColor(0x224CAF50); pSpeedFill.setStyle(Paint.Style.FILL);

        float sp10 = sp(ctx, 10), sp12 = sp(ctx, 12);
        pLabelL.setColor(0xFF555555); pLabelL.setTextSize(sp10);
        pLabelR.setColor(0xFFAA9900); pLabelR.setTextSize(sp10);
        pLabelS.setColor(0xFF3A7A3A); pLabelS.setTextSize(sp10);
        pHr.setColor(0xFFFF3333); pHr.setStyle(Paint.Style.STROKE);
        pHr.setStrokeWidth(2.2f); pHr.setStrokeJoin(Paint.Join.ROUND);
        pHrLabel.setColor(0xFFCC2222); pHrLabel.setTextSize(sp10);
        pLive.setColor(0xFFFF6B35);   pLive.setTextSize(sp12); pLive.setFakeBoldText(true);

        scroller = new OverScroller(ctx);

        gesture = new GestureDetector(ctx, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(MotionEvent e) {
                scroller.abortAnimation(); removeCallbacks(flingTick); return true;
            }
            @Override public boolean onScroll(MotionEvent e1, MotionEvent e2, float dx, float dy) {
                if (scale.isInProgress()) return false;
                viewEndSec += dx * (windowSec / Math.max(getWidth() - rightPx, 1f));
                clamp(); autoScroll = viewEndSec >= totalSec - 0.3f; invalidate(); return true;
            }
            @Override public boolean onFling(MotionEvent e1, MotionEvent e2, float vx, float vy) {
                if (scale.isInProgress()) return false;
                float spp = windowSec / Math.max(getWidth() - rightPx, 1f);
                scroller.fling((int)(viewEndSec*1000), 0, (int)(-vx*spp*1000), 0,
                        (int)(windowSec*1000), (int)((totalSec+1f)*1000), 0, 0);
                postOnAnimation(flingTick); return true;
            }
        });

        scale = new ScaleGestureDetector(ctx,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            private float focusSec;
            @Override public boolean onScaleBegin(ScaleGestureDetector d) {
                scroller.abortAnimation(); removeCallbacks(flingTick);
                float chartW = Math.max(getWidth() - rightPx, 1f);
                focusSec = (viewEndSec - windowSec) + (d.getFocusX() / chartW) * windowSec;
                return true;
            }
            @Override public boolean onScale(ScaleGestureDetector d) {
                float newWin = Math.max(WIN_MIN_SEC, Math.min(WIN_MAX_SEC, windowSec / d.getScaleFactor()));
                float chartW = Math.max(getWidth() - rightPx, 1f);
                float fx = (focusSec - (viewEndSec - windowSec)) / windowSec;
                viewEndSec = focusSec + (1f - fx) * newWin;
                windowSec  = newWin;
                clamp(); autoScroll = viewEndSec >= totalSec - 0.3f; invalidate(); return true;
            }
        });
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public void setData(List<float[]> accel)           { accelData   = accel; }
    public void setCadenceData(List<float[]> cadence)  { cadenceData = cadence; }
    public void setSpeedData(List<float[]> speed)      { speedData   = speed; }
    public List<float[]> getDataSource()               { return accelData; }
    public void setHrData(List<float[]> hr)                { hrData = hr; }

    /** Call every ~100 ms to refresh. */
    public void tick() {
        if (accelData == null) return;
        float latest = 0f;
        synchronized (accelData) {
            if (!accelData.isEmpty()) latest = accelData.get(accelData.size()-1)[0];
        }
        // Also check HR and speed data for latest time
        if (hrData != null) {
            synchronized (hrData) {
                if (!hrData.isEmpty()) {
                    float ht = hrData.get(hrData.size()-1)[0];
                    if (ht > latest) latest = ht;
                }
            }
        }
        if (speedData != null) {
            synchronized (speedData) {
                if (!speedData.isEmpty()) {
                    float st = speedData.get(speedData.size()-1)[0];
                    if (st > latest) latest = st;
                }
            }
        }
        totalSec = latest;
        if (autoScroll) viewEndSec = totalSec;
        updateYRanges();
        invalidate();
    }

    public void scrollToEnd() {
        scroller.abortAnimation(); removeCallbacks(flingTick);
        autoScroll = true; viewEndSec = totalSec; invalidate();
    }

    public boolean isAutoScroll() { return autoScroll; }

    // ── Touch ─────────────────────────────────────────────────────────────────

    @Override public boolean onTouchEvent(MotionEvent e) {
        scale.onTouchEvent(e);
        gesture.onTouchEvent(e);
        return true;
    }

    // ── Drawing ───────────────────────────────────────────────────────────────

    @Override
    protected void onDraw(Canvas canvas) {
        final int   W      = getWidth();
        final int   H      = getHeight();
        final float chartW = W - rightPx;
        final int   accelH = (int)(H * ACCEL_FRAC);  // top panel height
        final int   speedH = H - accelH - (int)divPx; // bottom panel height
        final float startSec = viewEndSec - windowSec;

        canvas.drawRect(0, 0, W, H, pBg);

        // ── Shared vertical time grid ─────────────────────────────────────────
        float step = gridStep();
        float gs = (float) Math.ceil(startSec / step) * step;
        for (float t = gs; t <= viewEndSec; t += step) {
            float x = tX(t, startSec, chartW);
            canvas.drawLine(x, 0, x, H, pGrid);
            if (shouldLabel(t, step)) {
                int ti = (int) t;
                String lbl = String.format(Locale.US, "%d:%02d", ti/60, ti%60);
                canvas.drawText(lbl, x+3, H-4, pLabelL);
            }
        }

        // ══ TOP PANEL: accel + cadence ════════════════════════════════════════
        canvas.save();
        canvas.clipRect(0, 0, W, accelH);

        float aRange = Math.max(aHi - aLo, 0.1f);

        // Gravity reference
        float yGrav = accelY(GRAVITY, accelH, aRange);
        if (yGrav >= 0 && yGrav <= accelH)
            canvas.drawLine(0, yGrav, chartW, yGrav, pGrav);

        // Accel path
        pathAccel.reset();
        boolean first = true;
        int idx = bsearch(accelData, startSec - 0.1f);
        if (accelData != null) synchronized (accelData) {
            for (int i = idx; i < accelData.size(); i++) {
                float[] s = accelData.get(i);
                if (s[0] > viewEndSec + 0.1f) break;
                float x = tX(s[0], startSec, chartW);
                float y = accelY(s[1], accelH, aRange);
                if (first) { pathAccel.moveTo(x, y); first = false; }
                else          pathAccel.lineTo(x, y);
            }
        }
        canvas.drawPath(pathAccel, pAccel);

        // Cadence path — split into stable/uncertain segments
        pathCadStable.reset(); pathCadUncert.reset();
        boolean fs = true, fu = true;
        if (cadenceData != null) {
            int ci = bsearch(cadenceData, startSec - 2f);
            synchronized (cadenceData) {
                for (int i = ci; i < cadenceData.size(); i++) {
                    float[] c = cadenceData.get(i);
                    if (c[0] > viewEndSec + 2f) break;
                    float x = tX(c[0], startSec, chartW);
                    float y = cadenceY(c[1], accelH);
                    if (c[2] > 0.5f) {           // stable
                        if (fs) { pathCadStable.moveTo(x, y); fs = false; }
                        else      pathCadStable.lineTo(x, y);
                        fu = true;               // break uncertain path
                    } else {                     // uncertain
                        if (fu) { pathCadUncert.moveTo(x, y); fu = false; }
                        else      pathCadUncert.lineTo(x, y);
                        fs = true;               // break stable path
                    }
                }
            }
        }
        canvas.drawPath(pathCadStable, pCadStable);
        canvas.drawPath(pathCadUncert, pCadUncert);

        // Left axis: accel labels
        canvas.drawText(fmt1(aHi),           4, pLabelL.getTextSize()+2, pLabelL);
        canvas.drawText(fmt1((aLo+aHi)/2f),  4, accelH/2f,               pLabelL);
        canvas.drawText(fmt1(aLo),           4, accelH-4,                 pLabelL);

        // Right axis: cadence labels
        canvas.drawText(Math.round(RPM_HI)+"", W-rightPx+2, pLabelR.getTextSize()+2, pLabelR);
        canvas.drawText(Math.round((RPM_LO+RPM_HI)/2f)+"", W-rightPx+2, accelH/2f,   pLabelR);
        canvas.drawText(Math.round(RPM_LO)+"", W-rightPx+2, accelH-4,                pLabelR);

        // Thin right-axis separator
        canvas.drawLine(chartW, 0, chartW, accelH, pGrid);

        canvas.restore();

        // ── Divider bar ───────────────────────────────────────────────────────
        canvas.drawRect(0, accelH, W, accelH + divPx, pDiv);

        // ══ BOTTOM PANEL: speed ═══════════════════════════════════════════════
        canvas.save();
        canvas.translate(0, accelH + divPx);
        canvas.clipRect(0, 0, W, speedH);

        float sRange = Math.max(sHi - sLo, 1f);

        // Speed fill + line
        pathSpeed.reset(); pathSpeedFill.reset();
        boolean sf = true;
        if (speedData != null) {
            int si = bsearch(speedData, startSec - 1f);
            synchronized (speedData) {
                for (int i = si; i < speedData.size(); i++) {
                    float[] s = speedData.get(i);
                    if (s[0] > viewEndSec + 1f) break;
                    float x = tX(s[0], startSec, chartW);
                    float y = speedY(s[1], speedH, sRange);
                    if (sf) {
                        pathSpeed.moveTo(x, y);
                        pathSpeedFill.moveTo(x, speedH);
                        pathSpeedFill.lineTo(x, y);
                        sf = false;
                    } else {
                        pathSpeed.lineTo(x, y);
                        pathSpeedFill.lineTo(x, y);
                    }
                }
            }
            if (!sf) {
                // Close fill path to bottom
                pathSpeedFill.lineTo(tX(viewEndSec, startSec, chartW), speedH);
                pathSpeedFill.close();
            }
        }
        canvas.drawPath(pathSpeedFill, pSpeedFill);
        canvas.drawPath(pathSpeed, pSpeed);

        // HR line
        pathHr.reset();
        boolean fhr = true;
        if (hrData != null) {
            int hi2 = bsearch(hrData, startSec - 5f);
            synchronized (hrData) {
                for (int i = hi2; i < hrData.size(); i++) {
                    float[] h = hrData.get(i);
                    if (h[0] > viewEndSec + 5f) break;
                    float x  = tX(h[0], startSec, chartW);
                    float y  = speedH * (1f - (h[1] - HR_LO) / (HR_HI - HR_LO));
                    if (fhr) { pathHr.moveTo(x, y); fhr = false; }
                    else        pathHr.lineTo(x, y);
                }
            }
        }
        canvas.drawPath(pathHr, pHr);

        // Speed axis labels (left)
        canvas.drawText(fmt0(sHi)+" km/h", 4, pLabelS.getTextSize()+2, pLabelS);
        canvas.drawText(fmt0((sLo+sHi)/2f),  4, speedH/2f,               pLabelS);
        canvas.drawText(fmt0(sLo),           4, speedH-4,                 pLabelS);

        // HR axis labels (right)
        canvas.drawText(Math.round(HR_HI)+" bpm", W-rightPx+2, pHrLabel.getTextSize()+2, pHrLabel);
        canvas.drawText(Math.round((HR_LO+HR_HI)/2f)+"",       W-rightPx+2, speedH/2f,  pHrLabel);
        canvas.drawText(Math.round(HR_LO)+"",                  W-rightPx+2, speedH-4,   pHrLabel);

        canvas.drawLine(chartW, 0, chartW, speedH, pGrid);

        canvas.restore();

        // ── LIVE + zoom hint (on top of everything) ───────────────────────────
        float liveY = pLive.getTextSize() + 4;
        if (autoScroll) {
            String s = "● LIVE";
            canvas.drawText(s, chartW - pLive.measureText(s) - 4, liveY, pLive);
        }
        if (Math.abs(windowSec - WIN_DEF_SEC) > 0.5f) {
            String z = String.format(Locale.US, "%.0f s", windowSec);
            canvas.drawText(z, chartW/2 - pLabelL.measureText(z)/2, liveY, pLabelL);
        }
    }

    // ── Coordinate helpers ────────────────────────────────────────────────────

    private float tX(float t, float startSec, float chartW) {
        return (t - startSec) / windowSec * chartW;
    }

    private float accelY(float v, int panelH, float range) {
        return panelH * (1f - (v - aLo) / range);
    }

    private float cadenceY(float rpm, int panelH) {
        return panelH * (1f - (rpm - RPM_LO) / (RPM_HI - RPM_LO));
    }

    private float speedY(float kmh, int panelH, float range) {
        return panelH * (1f - (kmh - sLo) / range);
    }

    private void clamp() {
        float min = windowSec;
        float max = totalSec + 0.5f;
        if (viewEndSec < min) viewEndSec = min;
        if (viewEndSec > max) viewEndSec = max;
    }

    /** Binary search: first index where list[i][0] >= targetSec. */
    private int bsearch(List<float[]> list, float targetSec) {
        if (list == null) return 0;
        synchronized (list) {
            int lo = 0, hi = list.size()-1, res = list.size();
            while (lo <= hi) {
                int mid = (lo+hi)>>>1;
                if (list.get(mid)[0] < targetSec) lo = mid+1;
                else { res = mid; hi = mid-1; }
            }
            return res;
        }
    }

    private void updateYRanges() {
        float startSec = viewEndSec - windowSec;
        // Accel range
        if (accelData != null) {
            float lo = Float.MAX_VALUE, hi = -Float.MAX_VALUE;
            int idx = bsearch(accelData, startSec);
            synchronized (accelData) {
                for (int i = idx; i < accelData.size(); i++) {
                    float[] s = accelData.get(i); if (s[0] > viewEndSec) break;
                    if (s[1] < lo) lo = s[1]; if (s[1] > hi) hi = s[1];
                }
            }
            if (lo < Float.MAX_VALUE) {
                float pad = Math.max((hi-lo)*0.25f, 1f);
                aLo = lerp(aLo, lo-pad, 0.15f); aHi = lerp(aHi, hi+pad, 0.15f);
            }
        }
        // Speed range
        if (speedData != null) {
            float hi = 5f;
            int idx = bsearch(speedData, startSec);
            synchronized (speedData) {
                for (int i = idx; i < speedData.size(); i++) {
                    float[] s = speedData.get(i); if (s[0] > viewEndSec) break;
                    if (s[1] > hi) hi = s[1];
                }
            }
            sHi = lerp(sHi, hi * 1.2f, 0.1f);
        }
    }

    // ── Grid helpers ──────────────────────────────────────────────────────────

    /** Choose a grid step that keeps ~5–15 lines on screen. */
    private float gridStep() {
        float[] steps = {0.5f, 1f, 2f, 5f, 10f, 15f, 30f, 60f};
        for (float s : steps) if (windowSec / s <= 15) return s;
        return 60f;
    }

    private boolean shouldLabel(float t, float step) {
        if (step < 1f) return Math.abs(t - Math.round(t)) < 0.01f;
        return ((int)(t / step)) % 2 == 0;
    }

    // ── Formatters ────────────────────────────────────────────────────────────
    private static String fmt1(float v) { return String.format(Locale.US, "%.1f", v); }
    private static String fmt0(float v) { return String.format(Locale.US, "%.0f", v); }

    // ── Utils ─────────────────────────────────────────────────────────────────
    private static float lerp(float a, float b, float t) { return a + (b-a)*t; }
    private static float sp(Context c, float sp) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, c.getResources().getDisplayMetrics());
    }
    private static float dp(Context c, float dp) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, c.getResources().getDisplayMetrics());
    }
}
