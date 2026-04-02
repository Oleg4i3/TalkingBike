package com.velo.speedometer;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Manages speed data: EMA smoothing, rolling average, distance tracking,
 * and speed-change announce trigger.
 */
public class SpeedCalculator {

    private float alpha;
    private float smoothedSpeed = 0f;
    private float totalDistanceKm = 0f;
    private long lastUpdateMs = -1;

    // For announce-on-change logic
    private float lastAnnouncedSpeed = Float.MIN_VALUE;
    private long lastAnnounceMs = -1;

    // Rolling history: each entry is { timestampMs, speedKmh * 100 } (int-packed for memory)
    private final Deque<long[]> history = new ArrayDeque<>(600);

    public SpeedCalculator(float alpha) {
        this.alpha = Math.max(0.05f, Math.min(1f, alpha));
    }

    public void setAlpha(float alpha) {
        this.alpha = Math.max(0.05f, Math.min(1f, alpha));
    }

    /**
     * Feed a new GPS speed sample.
     *
     * @param rawSpeedMs GPS speed in m/s (from Location.getSpeed())
     * @param nowMs      current epoch millis
     * @return smoothed speed in km/h
     */
    public float update(float rawSpeedMs, long nowMs) {
        float rawKmh = rawSpeedMs * 3.6f;
        // Noise gate: GPS jitter below ~1.5 km/h → treat as stopped
        if (rawKmh < 1.5f) rawKmh = 0f;

        smoothedSpeed = alpha * rawKmh + (1f - alpha) * smoothedSpeed;

        // Accumulate distance (trapezoidal would be nicer, but EMA already smoothed)
        if (lastUpdateMs > 0) {
            float dtHours = (nowMs - lastUpdateMs) / 3_600_000f;
            totalDistanceKm += smoothedSpeed * dtHours;
        }
        lastUpdateMs = nowMs;

        // Store in rolling history
        history.addLast(new long[]{ nowMs, (long)(smoothedSpeed * 100) });

        return smoothedSpeed;
    }

    /**
     * Rolling average speed over the last {@code periodMinutes} minutes.
     * Prunes entries older than the window on each call.
     */
    public float getAverageSpeed(int periodMinutes) {
        long cutoff = System.currentTimeMillis() - (long) periodMinutes * 60_000L;
        while (!history.isEmpty() && history.peekFirst()[0] < cutoff) {
            history.pollFirst();
        }
        if (history.isEmpty()) return 0f;
        double sum = 0;
        for (long[] e : history) sum += e[1];
        return (float)(sum / history.size() / 100.0);
    }

    /**
     * Returns true if speed has changed by more than {@code thresholdKmh}
     * since the last announcement, and at least {@code debounceMs} have passed.
     */
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

    /** Force next announce-on-change check to fire regardless of threshold. */
    public void resetAnnouncedSpeed() {
        lastAnnouncedSpeed = Float.MIN_VALUE;
        lastAnnounceMs = -1;
    }

    public float getSmoothedSpeed() { return smoothedSpeed; }
    public float getTotalDistanceKm() { return totalDistanceKm; }

    public void reset() {
        smoothedSpeed = 0f;
        totalDistanceKm = 0f;
        lastUpdateMs = -1;
        lastAnnouncedSpeed = Float.MIN_VALUE;
        lastAnnounceMs = -1;
        history.clear();
    }
}
