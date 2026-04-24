package com.velo.speedometer;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Estimates bicycle cadence (RPM) from the phone GYROSCOPE using
 * AUTOCORRELATION (ACF).
 *
 * ── Why gyroscope ────────────────────────────────────────────────────────────
 * Road impacts (roots, gravel, bumps) create large LINEAR acceleration but
 * negligible ANGULAR velocity — the phone is pushed, not rotated.
 * Pedalling rotates the thigh and therefore the trouser pocket, producing a
 * clean periodic angular-velocity signal. In rough terrain this separation is
 * decisive: the gyroscope simply does not "see" most bump energy.
 *
 * Placement: phone in trouser pocket (thigh level). A frame/stem bag gives
 * a much weaker rotation signal and is not recommended.
 *
 * ── Why autocorrelation instead of FFT power spectrum ────────────────────────
 *
 *   R[lag] = Σ signal[n] · signal[n+lag]  /  Σ signal[n]²
 *
 * R[lag] measures directly how periodic the signal is at period lag.
 *
 *   • A bump contributes energy only at lag = 0 (single event, not repeating).
 *     → R[cadence_lag] ≈ 0  (bump does NOT fake a cadence).
 *
 *   • Pedalling at period T gives R[T] ≈ 0.3–0.8 (sustained, strong).
 *
 *   • The fundamental period ALWAYS produces the strongest ACF peak — not
 *     a harmonic. This eliminates the "double cadence" false-positive that
 *     plagued the FFT approach on rough terrain.
 *
 * ── DSP pipeline ─────────────────────────────────────────────────────────────
 *   1. Per-axis IIR low-pass (fc ≈ 6 Hz) on gyroscope axes.
 *   2. Magnitude = sqrt(lpfX²+lpfY²+lpfZ²) — all three axes contribute.
 *   3. DC removal + Hann window on the buffered magnitude signal.
 *   4. Direct normalized circular ACF for lags covering RPM_MIN–RPM_MAX
 *      and twice that range (for the second-period check).
 *      ~63 K multiply-adds per second — negligible on any modern Android.
 *   5. Peak lag via parabolic sub-sample interpolation → RPM.
 *   6. Three independent stability criteria.
 *
 * ── Stability criteria (all three must pass for stable=true) ─────────────────
 *   1. ACF peak ≥ ACF_STABLE — strong sustained periodicity.
 *   2. Second-period check: ACF at 2×peak_lag ≥ ACF_PERIOD2_MIN — the signal
 *      repeats for at least two full cycles; a single road-texture event that
 *      accidentally matches the cadence lag will not pass this.
 *   3. Temporal stability: std-dev of the last TEMPORAL_N estimates ≤ threshold.
 *
 * ── Tuning ───────────────────────────────────────────────────────────────────
 * All thresholds are named constants below. If detection is too sparse on
 * rough terrain, lower ACF_STABLE toward 0.25. If false positives appear
 * while coasting, raise it toward 0.45.
 */
public class CadenceDetector implements SensorEventListener {

    // ── Result ────────────────────────────────────────────────────────────────

    public static class Result {
        /** Detected cadence in RPM. 0 if nothing detected. */
        public final float   rpm;
        /** Confidence in [0, 1]. */
        public final float   confidence;
        /** True when all three stability criteria pass. */
        public final boolean stable;
        /**
         * Rolling average of STABLE estimates over STABLE_AVG_SEC seconds.
         * 0 when not enough stable data yet.
         */
        public final float   stableAvgRpm;

        Result(float rpm, float confidence, boolean stable, float stableAvgRpm) {
            this.rpm          = rpm;
            this.confidence   = confidence;
            this.stable       = stable;
            this.stableAvgRpm = stableAvgRpm;
        }

        public static final Result EMPTY = new Result(0, 0, false, 0);
    }

    // ── Listener ──────────────────────────────────────────────────────────────

    public interface Listener {
        /** Called on the main thread after each ACF window. */
        void onCadence(Result result);
    }

    // ── Tuning constants ──────────────────────────────────────────────────────

    private static final int   SAMPLE_RATE  = 50;   // Hz  (SENSOR_DELAY_GAME ≈ 50 Hz)
    private static final int   BUFFER_SIZE  = 512;  // ~10.24 s — power of 2
    private static final int   STEP_SIZE    = 50;   // recompute every ~1 s
    private static final float RPM_MIN      = 48f;
    private static final float RPM_MAX      = 108f;

    /**
     * Normalized ACF thresholds (signal range is approximately [-1, 1]).
     * A pure sinusoid at its fundamental period gives R = 1.0.
     * Real noisy cycling in a trouser pocket:
     *   smooth tarmac  → R ≈ 0.35–0.70
     *   forest / roots → R ≈ 0.15–0.40
     *
     * ACF_STABLE: lower = more detections but noisier; raise if coasting
     *   triggers false readings.
     * ACF_PERIOD2_MIN: 2nd-period presence; keep low — it is always weaker
     *   than the fundamental due to Hann-window roll-off at longer lags.
     */
    private static final float ACF_DETECT      = 0.15f;  // hard floor — below: no detection
    private static final float ACF_STABLE      = 0.35f;  // criterion 1 threshold
    private static final float ACF_PERIOD2_MIN = 0.08f;  // criterion 2 threshold

    private static final float TEMPORAL_MAX_STD = 4f;    // RPM std-dev ceiling (criterion 3)
    private static final int   TEMPORAL_N       = 8;     // recent estimates for temporal check
    private static final int   STABLE_AVG_SEC   = 30;    // rolling window for stableAvgRpm

    /**
     * IIR LPF coefficient: α = exp(−2π · 6 / 50) ≈ 0.470.
     * Passes cadence (0.8–1.8 Hz) and its 3rd harmonic (≤ 5.4 Hz).
     * Attenuates residual mechanical resonance above ~8 Hz.
     * Gyroscope needs this less than accelerometer, but it doesn't hurt.
     */
    private static final float LPF_COEFF = 0.470f;

    private static final int MAX_RAW = 360_000;  // ~2 h at 50 Hz

    // ── State ─────────────────────────────────────────────────────────────────

    private final SensorManager sensorManager;
    private final Listener      listener;
    private final Handler       mainHandler = new Handler(Looper.getMainLooper());

    // Magnitude circular buffer (LPF-filtered)
    private final float[] circBuf = new float[BUFFER_SIZE];
    private int head      = 0;
    private int filled    = 0;
    private int stepCount = 0;

    // Per-axis IIR filter state
    private float lpfX = 0f, lpfY = 0f, lpfZ = 0f;

    // Temporal stability
    private final Deque<Float> recentRpm = new ArrayDeque<>(TEMPORAL_N + 1);

    // Rolling average of stable readings only
    private final Deque<long[]> stableHistory = new ArrayDeque<>();  // [ms, rpm×100]

    // Graph data
    private final List<float[]> rawSamples     = new ArrayList<>();  // [elapsed_s, filtMag]
    private final List<float[]> cadenceHistory = new ArrayList<>();  // [elapsed_s, rpm, stable]
    private long rideStartMs = -1;

    private Result lastResult = Result.EMPTY;

    // ── Public API ────────────────────────────────────────────────────────────

    public CadenceDetector(Context ctx, Listener listener) {
        this.sensorManager = (SensorManager) ctx.getSystemService(Context.SENSOR_SERVICE);
        this.listener = listener;
    }

    public void start() {
        Sensor gyro = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        if (gyro != null)
            sensorManager.registerListener(this, gyro, SensorManager.SENSOR_DELAY_GAME);

        head = 0; filled = 0; stepCount = 0;
        lpfX = 0f; lpfY = 0f; lpfZ = 0f;
        rideStartMs = System.currentTimeMillis();
        recentRpm.clear();
        synchronized (stableHistory)  { stableHistory.clear(); }
        synchronized (rawSamples)     { rawSamples.clear(); }
        synchronized (cadenceHistory) { cadenceHistory.clear(); }
        lastResult = Result.EMPTY;
    }

    public void stop() {
        sensorManager.unregisterListener(this);
        lastResult = Result.EMPTY;
    }

    public Result getLastResult() { return lastResult; }

    /** Synchronize on the returned list before iterating. */
    public List<float[]> getRawSamples()     { return rawSamples; }
    public List<float[]> getCadenceHistory() { return cadenceHistory; }

    // ── SensorEventListener ───────────────────────────────────────────────────

    @Override
    public void onSensorChanged(SensorEvent event) {
        // Gyroscope values: angular velocity in rad/s
        float gx = event.values[0], gy = event.values[1], gz = event.values[2];

        // Per-axis IIR low-pass (fc ≈ 6 Hz)
        lpfX = LPF_COEFF * lpfX + (1f - LPF_COEFF) * gx;
        lpfY = LPF_COEFF * lpfY + (1f - LPF_COEFF) * gy;
        lpfZ = LPF_COEFF * lpfZ + (1f - LPF_COEFF) * gz;

        // Rotation magnitude — uses all three axes
        float mag = (float) Math.sqrt(lpfX*lpfX + lpfY*lpfY + lpfZ*lpfZ);

        circBuf[head] = mag;
        head = (head + 1) % BUFFER_SIZE;
        if (filled < BUFFER_SIZE) filled++;

        if (++stepCount >= STEP_SIZE && filled == BUFFER_SIZE) {
            stepCount = 0;
            processBuffer();
        }

        // Store for graph
        if (rideStartMs >= 0) {
            float elapsed = (System.currentTimeMillis() - rideStartMs) / 1000f;
            synchronized (rawSamples) {
                if (rawSamples.size() < MAX_RAW)
                    rawSamples.add(new float[]{elapsed, mag});
            }
        }
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    // ── DSP core — normalized circular ACF ───────────────────────────────────

    private void processBuffer() {

        // Unroll circular buffer: oldest sample first
        float[] signal = new float[BUFFER_SIZE];
        for (int i = 0; i < BUFFER_SIZE; i++)
            signal[i] = circBuf[(head + i) % BUFFER_SIZE];

        // DC removal — also removes gyroscope static bias / drift
        float mean = 0f;
        for (float v : signal) mean += v;
        mean /= BUFFER_SIZE;
        for (int i = 0; i < BUFFER_SIZE; i++) signal[i] -= mean;

        // Hann window — tapers edges to zero, reduces circular-ACF boundary artefacts
        for (int i = 0; i < BUFFER_SIZE; i++)
            signal[i] *= 0.5f * (1f - (float) Math.cos(2.0 * Math.PI * i / (BUFFER_SIZE - 1)));

        // Signal energy = R[0] un-normalized
        float energy = 0f;
        for (float v : signal) energy += v * v;
        if (energy < 1e-9f) { publish(0f, 0f, false); return; }

        // Lag range from RPM limits:
        //   lag = 60 / rpm * SAMPLE_RATE
        //   lagMin = 28  →  108 RPM
        //   lagMax = 62  →   48 RPM
        //   lagCheck = 124 = 2 × lagMax  (second-period of 48 RPM, maximum lag needed)
        final int lagMin   = (int) Math.ceil (60f / RPM_MAX * SAMPLE_RATE); // 28
        final int lagMax   = (int) Math.floor(60f / RPM_MIN * SAMPLE_RATE); // 62
        final int lagCheck = lagMax * 2;                                     // 124

        // ── Normalized circular ACF ───────────────────────────────────────────
        //
        //   R[lag] = Σ_{n=0}^{N-1} signal[n] · signal[(n+lag) mod N]  /  energy
        //
        // Direct computation: 124 lags × 512 samples = ~63 K multiplications
        // at 1 Hz → trivially fast on any Android device.
        //
        // Circular vs linear ACF: the Hann window zeros both ends of the signal,
        // so the circular wrap-around contributes ≈ 0. The result is equivalent
        // to the (more expensive) linear ACF for our purposes.
        //
        float[] acf = new float[lagCheck + 1];
        for (int lag = 1; lag <= lagCheck; lag++) {
            float sum = 0f;
            for (int n = 0; n < BUFFER_SIZE; n++)
                sum += signal[n] * signal[(n + lag) % BUFFER_SIZE];
            acf[lag] = sum / energy;
        }

        // Find peak in the cadence lag range [lagMin, lagMax]
        int   peakLag = lagMin;
        float peakAcf = -Float.MAX_VALUE;
        for (int lag = lagMin; lag <= lagMax; lag++) {
            if (acf[lag] > peakAcf) { peakAcf = acf[lag]; peakLag = lag; }
        }

        // ── Absolute detection floor ──────────────────────────────────────────
        if (peakAcf < ACF_DETECT) { publish(0f, 0f, false); return; }

        // Parabolic sub-sample interpolation for fractional lag accuracy
        float refinedLag = peakLag;
        if (peakLag > lagMin && peakLag < lagMax) {
            float y0 = acf[peakLag - 1], y1 = acf[peakLag], y2 = acf[peakLag + 1];
            float denom = 2f * (y0 - 2f*y1 + y2);
            if (Math.abs(denom) > 1e-9f)
                refinedLag = peakLag - (y2 - y0) / denom;
        }

        float rpm = 60f * SAMPLE_RATE / refinedLag;
        rpm = Math.max(RPM_MIN, Math.min(RPM_MAX, rpm));

        // ── CRITERION 1: ACF peak strength ────────────────────────────────────
        // Measures how strongly periodic the signal is at the detected cadence.
        boolean acfPass = peakAcf >= ACF_STABLE;

        // ── CRITERION 2: second-period presence ───────────────────────────────
        // A truly periodic signal at lag L must also correlate at lag 2L.
        // A road-texture bump that accidentally lands at lag L will NOT produce
        // elevated ACF at 2L — that event is not repeating at the same interval.
        // Note: ACF[2L] is always weaker than ACF[L] due to Hann-window roll-off
        // at longer lags, hence ACF_PERIOD2_MIN is set much lower than ACF_STABLE.
        boolean period2Pass = false;
        int lag2 = Math.round(refinedLag * 2f);
        if (lag2 <= lagCheck) {
            period2Pass = acf[lag2] >= ACF_PERIOD2_MIN;
        }

        // ── CRITERION 3: temporal stability ──────────────────────────────────
        recentRpm.addLast(rpm);
        if (recentRpm.size() > TEMPORAL_N) recentRpm.pollFirst();
        boolean temporalPass = false;
        if (recentRpm.size() >= TEMPORAL_N) {
            float sumR = 0f;
            for (float r : recentRpm) sumR += r;
            float meanR = sumR / recentRpm.size();
            float varR  = 0f;
            for (float r : recentRpm) varR += (r - meanR) * (r - meanR);
            temporalPass = (float) Math.sqrt(varR / recentRpm.size()) <= TEMPORAL_MAX_STD;
        }

        // Confidence: geometric mean of three [0,1] sub-scores
        float acfScore      = Math.min(1f, (peakAcf - ACF_DETECT) / (ACF_STABLE - ACF_DETECT));
        float period2Score  = period2Pass ? 1.0f : 0.3f;
        float temporalScore = recentRpm.size() < TEMPORAL_N ? 0.5f
                            : (temporalPass ? 1.0f : 0.2f);
        float confidence    = (float) Math.pow(acfScore * period2Score * temporalScore, 1.0 / 3.0);

        publish(rpm, confidence, acfPass && period2Pass && temporalPass);
    }

    // ── Publish result + rolling stable average ───────────────────────────────

    private void publish(float rpm, float confidence, boolean stable) {
        long now      = System.currentTimeMillis();
        long cutoffMs = now - (long) STABLE_AVG_SEC * 1000L;

        if (stable && rpm > 0)
            synchronized (stableHistory) {
                stableHistory.addLast(new long[]{now, (long)(rpm * 100)});
            }

        float stableAvg = 0f;
        synchronized (stableHistory) {
            while (!stableHistory.isEmpty() && stableHistory.peekFirst()[0] < cutoffMs)
                stableHistory.pollFirst();
            if (!stableHistory.isEmpty()) {
                double sum = 0;
                for (long[] e : stableHistory) sum += e[1];
                stableAvg = (float)(sum / stableHistory.size() / 100.0);
            }
        }

        lastResult = new Result(rpm, confidence, stable, stableAvg);

        if (rideStartMs >= 0 && rpm > 0) {
            float elapsed = (now - rideStartMs) / 1000f;
            synchronized (cadenceHistory) {
                cadenceHistory.add(new float[]{elapsed, rpm, stable ? 1f : 0f});
            }
        }

        if (listener != null) {
            final Result r = lastResult;
            mainHandler.post(() -> listener.onCadence(r));
        }
    }
}
