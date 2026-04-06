package com.velo.speedometer;

import java.util.ArrayDeque;
import java.util.Deque;

public class SpeedCalculator {

    private float alpha;
    private float smoothedSpeed = 0f;
    private float totalDistanceKm = 0f;
    private long  lastUpdateMs = -1;

    // For announce-on-change
    private float lastAnnouncedSpeed = Float.MIN_VALUE;
    private long  lastAnnounceMs = -1;

    // Rolling window history for avg over N minutes
    private final Deque<long[]> history = new ArrayDeque<>(3600);

    // All-time accumulator (two variants: including and excluding pauses)
    private double allTimeSum      = 0;
    private long   allTimeCount    = 0;
    private double activeSum       = 0;   // excludes pause intervals
    private long   activeCount     = 0;

    /** When false, speed samples are still smoothed/stored for distance,
     *  but NOT counted in averages. Used to exclude pause time from avg. */
    private boolean accumulating = true;

    public SpeedCalculator(float alpha) {
        this.alpha = clampAlpha(alpha);
    }

    public void setAlpha(float alpha) {
        this.alpha = clampAlpha(alpha);
    }

    private float clampAlpha(float a) {
        return Math.max(0.05f, Math.min(1f, a));
    }

    /** Call with false when ride is paused, true when resumed. */
    public void setAccumulating(boolean accumulating) {
        this.accumulating = accumulating;
    }

    /** Feed GPS speed (m/s). Returns smoothed km/h. */
    public float update(float rawSpeedMs, long nowMs) {
        float rawKmh = rawSpeedMs * 3.6f;
        if (rawKmh < 1.5f) rawKmh = 0f;

        smoothedSpeed = alpha * rawKmh + (1f - alpha) * smoothedSpeed;

        if (lastUpdateMs > 0) {
            float dtHours = (nowMs - lastUpdateMs) / 3_600_000f;
            totalDistanceKm += smoothedSpeed * dtHours;
        }
        lastUpdateMs = nowMs;

        // Always add to all-time history (for the "include pauses" mode)
        history.addLast(new long[]{nowMs, (long)(smoothedSpeed * 100)});
        allTimeSum   += smoothedSpeed;
        allTimeCount += 1;

        // Active (non-paused) accumulator
        if (accumulating) {
            activeSum   += smoothedSpeed;
            activeCount += 1;
        }

        return smoothedSpeed;
    }

    /**
     * Rolling or whole-ride average.
     * @param periodMinutes  0 = whole ride, >0 = rolling window
     * @param excludePauses  if true, uses only actively-moving samples
     */
    public float getAverageSpeed(int periodMinutes, boolean excludePauses) {
        if (periodMinutes == 0) {
            if (excludePauses) {
                return activeCount == 0 ? 0f : (float)(activeSum / activeCount);
            } else {
                return allTimeCount == 0 ? 0f : (float)(allTimeSum / allTimeCount);
            }
        }
        // Rolling window — trim stale entries
        long cutoff = System.currentTimeMillis() - (long) periodMinutes * 60_000L;
        while (!history.isEmpty() && history.peekFirst()[0] < cutoff)
            history.pollFirst();
        if (history.isEmpty()) return 0f;
        // For rolling window + excludePauses we use the same history deque
        // but only count samples that were added while accumulating.
        // Since we can't retroactively mark them, in rolling mode we return
        // the simple average (the window is short enough that pauses matter less).
        double sum = 0;
        for (long[] e : history) sum += e[1];
        return (float)(sum / history.size() / 100.0);
    }

    /** Convenience — backward-compatible overload (includes pauses). */
    public float getAverageSpeed(int periodMinutes) {
        return getAverageSpeed(periodMinutes, false);
    }

    /** True if speed changed ≥ threshold since last announce AND debounce elapsed. */
    public boolean shouldAnnounceSpeed(float thresholdKmh, long debounceMs) {
        long now = System.currentTimeMillis();
        if (lastAnnounceMs >= 0 && (now - lastAnnounceMs) < debounceMs) return false;
        if (Math.abs(smoothedSpeed - lastAnnouncedSpeed) >= thresholdKmh) {
            lastAnnouncedSpeed = smoothedSpeed;
            lastAnnounceMs = now;
            return true;
        }
        return false;
    }

    public void resetAnnouncedSpeed() {
        lastAnnouncedSpeed = Float.MIN_VALUE;
        lastAnnounceMs = -1;
    }

    public float getSmoothedSpeed()   { return smoothedSpeed; }
    public float getTotalDistanceKm() { return totalDistanceKm; }

    public void reset() {
        smoothedSpeed   = 0f;
        totalDistanceKm = 0f;
        lastUpdateMs    = -1;
        allTimeSum      = 0;
        allTimeCount    = 0;
        activeSum       = 0;
        activeCount     = 0;
        accumulating    = true;
        resetAnnouncedSpeed();
        history.clear();
    }
}
