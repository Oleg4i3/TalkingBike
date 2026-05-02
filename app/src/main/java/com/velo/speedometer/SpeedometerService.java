package com.velo.speedometer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.hardware.camera2.CameraManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.AudioManager;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import android.app.KeyguardManager;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.media.session.MediaButtonReceiver;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import androidx.media.VolumeProviderCompat;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class SpeedometerService extends Service {

    private static final String TAG = "SpeakingBike";
    private static final String CHANNEL_ID = "sb_channel";
    private static final int    NOTIF_ID   = 1;

    public static final String ACTION_START    = "sb.START";
    public static final String ACTION_STOP     = "sb.STOP";
    public static final String ACTION_PAUSE    = "sb.PAUSE";
    public static final String ACTION_ANNOUNCE = "sb.ANNOUNCE";
    public static final String ACTION_RELOAD   = "sb.RELOAD";
    public static final String ACTION_RIDE_STOPPED     = "sb.RIDE_STOPPED";
    public static final String ACTION_METRO_TOGGLE    = "sb.METRO_TOGGLE";
    public static final String ACTION_EXIT            = "sb.EXIT";

    /** File name for persistent ride log (in getFilesDir()). */
    public static final String RIDE_LOG_FILENAME = "ride_log.csv";

    private final IBinder binder = new LocalBinder();
    public class LocalBinder extends Binder {
        public SpeedometerService getService() { return SpeedometerService.this; }
    }

    public enum TrackState { STOPPED, RUNNING, PAUSED }
    private TrackState state = TrackState.STOPPED;
    private boolean ttsReady = false;

    private LocationManager locationManager;
    private CameraManager   cameraManager;
    private CameraManager.TorchCallback torchCallback;
    private long lastTorchMs = 0;
    private static final long TORCH_DEBOUNCE_MS = 2000;

    private BroadcastReceiver screenOnReceiver;
    private long lastScreenAnnounceMs = 0;

    private TextToSpeech  tts;
    private AudioEnhancer audioEnhancer;

    // "uk" | "ru" | "en"
    private String lang = "en";

    private SpeedCalculator calculator;
    private Handler         handler;
    private Runnable        avgRunnable;
    private long            lastSpeedAnnounceMs = 0;
    private long            lastAnyAnnounceMs   = 0;

    private long slowStartMs = -1;
    private boolean autoPaused = false;

    // Speed history for graph: [elapsed_sec, speedKmh]
    private final java.util.List<float[]> speedHistory = new java.util.ArrayList<>();
    private long rideStartMs  = -1;   // ms when current ride started
    private long totalPauseMs = 0;    // accumulated pause duration this ride
    private long pauseStartMs = -1;   // ms when current pause started

    private SpeedListener listener;

    public interface SpeedListener {
        void onSpeedUpdate(float speedKmh, float avgKmh, float distanceKm);
        void onStateChanged(TrackState state);
        default void onMetronomeChanged(boolean playing) {}
        /** Called once when ride stops. Shows final summary in UI. */
        default void onRideFinished(float avgKmh, float distKm, String timeStr) {}
        default void onHrChanged(int bpm, boolean connected) {}
        /** Called when HR sensor battery level is read after connection. */
        default void onHrBattery(int percent) {}
    }

    private float   speedThresholdKmh;
    private long    speedDebounceMs;
    private long    maxSilenceMs;
    private int     avgPeriodMin;
    private int     avgIntervalMin;
    private boolean doAnnounceSpeed;
    private boolean doAnnounceAvg;
    private boolean doAnnounceDistance;
    private boolean autoPauseEnabled;
    private int     autoPauseSec;
    private boolean walkingSpeedEnabled;
    private boolean screenAnnounceEnabled;
    private int     screenAnnounceDebounceSec;
    private boolean enhancedAudioEnabled;
    private float   gainDb;
    private boolean excludePausesFromAvg;
    private boolean doAnnounceCadence;
    private CadenceDetector.Result lastCadenceResult = CadenceDetector.Result.EMPTY;
    private CadenceDetector cadenceDetector;

    // ── Metronome ─────────────────────────────────────────────────────────────
    private MetronomeEngine    metronomeEngine;
    private volatile Thread    metronomeThread;
    private volatile boolean   metronomeRunning  = false;
    private volatile int       metronomeBpm      = 80;
    private int                metSoundType      = MetronomeEngine.SOUND_MARACAS;
    private boolean            metSoundStrong    = true;
    private boolean            metSoundWeak      = true;
    private boolean            metVibStrong      = false;
    private boolean            metVibWeak        = true;
    // Adaptive metronome volume
    private boolean            metVolumeAdaptive = false;  // master switch
    private boolean            metVolOnCadence   = true;   // boost on cadence match
    private boolean            metVolOnHr        = false;  // boost on HR in zone
    private int                metHrMin          = 120;    // HR zone min
    private int                metHrMax          = 160;    // HR zone max

    // FIX #7: 3-level adaptive volume
    // -18 dB  if cadence >= metronomeBpm  (on-target or above)
    // -12 dB  if cadence is within the tolerance band below target
    //   0 dB  if cadence is below (metronomeBpm * (1 - pct/100))
    private static final float VOL_FULL  = 1.0f;
    private static final float VOL_MID   = dBtoLinear(-12f);
    private static final float VOL_DIM   = dBtoLinear(-18f);

    private MediaSessionCompat mediaSession;

    // ── Heart Rate Monitor ────────────────────────────────────────────────────
    private HeartRateMonitor    heartRateMonitor;
    private volatile int        lastHrBpm         = 0;
    private volatile int        lastHrBattery     = -1;   // -1 = not read yet
    private boolean             doAnnounceHr      = false;
    private int                 hrIntervalSec     = 60;
    private Runnable            hrAnnounceRunnable;
    private Runnable            cadenceRunnable;
    private Runnable            rideTimeRunnable;
    private boolean             doAnnounceRideTime  = false;
    private boolean             rideTimeExcludePauses = false;
    private int                 rideTimeIntervalMin = 5;
    private boolean             resumedPending      = false;  // de-dup "resumed"
    // HR zone alert
    private boolean             hrAlertEnabled      = false;
    private int                 hrAlertMin          = 120;
    private int                 hrAlertMax          = 160;
    // Hysteresis: track how long HR has been continuously outside zone
    // and how long it has been back inside (to re-arm alert).
    private long                hrOutsideSinceMs    = -1;   // when breach started
    private long                hrInsideSinceMs     = -1;   // when returned to zone
    private boolean             hrAlertLowFired     = false; // one-shot low alert
    private boolean             hrAlertHighFired    = false; // one-shot high alert
    private static final long   HR_HYSTERESIS_MS    = 10_000L; // 10 seconds
    // Cadence volume threshold
    private int                 metCadenceMinPct    = 10;     // % tolerance below metronomeBpm
    private final List<float[]> hrHistory = new ArrayList<>();  // [elapsed_s, bpm]
    private PowerManager.WakeLock metWakeLock;

    @Override
    public void onCreate() {
        super.onCreate();
        handler    = new Handler(Looper.getMainLooper());
        calculator = new SpeedCalculator(0.3f);
        cadenceDetector = createCadenceDetector();
        heartRateMonitor = new HeartRateMonitor(this, new HeartRateMonitor.HrListener() {
            @Override public void onHeartRate(int bpm) {
                lastHrBpm = bpm;
                if (rideStartMs > 0) {
                    float elapsed = (System.currentTimeMillis() - rideStartMs) / 1000f;
                    synchronized (hrHistory) { hrHistory.add(new float[]{elapsed, bpm}); }
                }
                if (listener != null) listener.onHrChanged(bpm, true);
                // One-shot HR zone alert
                checkHrAlert(bpm);
            }
            @Override public void onConnectionState(boolean connected) {
                if (!connected) lastHrBpm = 0;
                if (listener != null) listener.onHrChanged(lastHrBpm, connected);
            }
            // FIX #6: battery level — announce via TTS and forward to UI
            @Override public void onBatteryLevel(int percent) {
                lastHrBattery = percent;
                if (listener != null) listener.onHrBattery(percent);
                // Announce battery level via TTS when sensor first connects
                speak(str("Heart rate sensor battery ", "Батарея датчика пульсу ", "Батарея датчика пульса ")
                        + percent + "%");
            }
        });
        // Auto-connect to saved device on service start
        String savedAddr = getSharedPreferences("settings", MODE_PRIVATE)
                .getString("hr_device_address", null);
        if (savedAddr != null) heartRateMonitor.connect(savedAddr);
        createNotificationChannel();
        initMetronome();
        initTts();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_STICKY;
        String action = intent.getAction();
        if (action == null) return START_STICKY;
        switch (action) {
            case ACTION_START:    startTracking();   break;
            case ACTION_STOP:     stopTracking();    break;
            case ACTION_PAUSE:    togglePause();     break;
            case ACTION_ANNOUNCE: announceNow(true); break;
            case ACTION_RELOAD:       reloadSettings();    break;
            case ACTION_METRO_TOGGLE: toggleMetronome();   break;
            case ACTION_EXIT:         exitApp();             break;
            case "sb.REINIT_TTS":    reinitTts();           break;
        }
        return START_STICKY;
    }

    @Override public IBinder onBind(Intent intent) { return binder; }

    public TrackState getState() { return state; }
    public void setSpeedListener(SpeedListener l) { listener = l; }
    public CadenceDetector   getCadenceDetector()  { return cadenceDetector; }
    public HeartRateMonitor  getHeartRateMonitor() { return heartRateMonitor; }
    public int               getLastHrBpm()        { return lastHrBpm; }
    /** Returns last read battery level of HR sensor, -1 if not yet available. */
    public int               getLastHrBattery()    { return lastHrBattery; }
    public List<float[]>     getHrHistory()        { return hrHistory; }
    /** Speed history since ride start — synchronize on the returned list to iterate. */
    public java.util.List<float[]> getSpeedHistory() { return speedHistory; }

    public void startTracking() {
        if (state != TrackState.STOPPED) return;
        loadSettings();
        calculator.reset();
        lastSpeedAnnounceMs = 0;
        lastAnyAnnounceMs   = 0;
        slowStartMs         = -1;
        autoPaused          = false;
        rideStartMs         = System.currentTimeMillis();
        totalPauseMs        = 0;
        pauseStartMs        = -1;
        synchronized (speedHistory) { speedHistory.clear(); }
        state = TrackState.RUNNING;
        notifyStateChanged();

        Notification n = buildNotification(0, 0, 0);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        } else {
            startForeground(NOTIF_ID, n);
        }

        requestLocationUpdates();
        // FIX #1+#2: pass rideStartMs so sensor and GPS timestamps share the same origin
        cadenceDetector.start(rideStartMs);
        synchronized (hrHistory) { hrHistory.clear(); }
        if (doAnnounceHr)       scheduleHrTimer();
        registerTorchListener();
        if (screenAnnounceEnabled) registerScreenReceiver();
        if (doAnnounceAvg)      scheduleAvgTimer();
        if (doAnnounceCadence)  scheduleCadenceTimer();
        if (doAnnounceRideTime) scheduleRideTimeTimer();
        resumedPending      = false;
        hrAlertLowFired     = false;
        hrAlertHighFired    = false;
        hrOutsideSinceMs    = -1;
        hrInsideSinceMs     = -1;
        speak(str("Let's go", "Поїхали", "Погнали"));
    }

    public void stopTracking() {
        if (state == TrackState.STOPPED) return;
        state = TrackState.STOPPED;
        notifyStateChanged();

        if (locationManager != null) locationManager.removeUpdates(locationListener);
        cadenceDetector.stop();
        if (hrAnnounceRunnable != null) handler.removeCallbacks(hrAnnounceRunnable);
        if (cadenceRunnable    != null) handler.removeCallbacks(cadenceRunnable);
        if (rideTimeRunnable   != null) handler.removeCallbacks(rideTimeRunnable);
        if (avgRunnable != null)     handler.removeCallbacks(avgRunnable);
        unregisterTorchListener();
        unregisterScreenReceiver();
        stopForeground(true);
        sendBroadcast(new Intent(ACTION_RIDE_STOPPED));

        float finalAvg  = calculator.getAverageSpeed(avgPeriodMin, excludePausesFromAvg);
        float finalDist = calculator.getTotalDistanceKm();
        long  activeMs  = rideStartMs > 0
                ? (System.currentTimeMillis() - rideStartMs - totalPauseMs)
                : 0L;
        String timeStr  = fmtDuration(Math.max(0, activeMs));

        // FIX #5: save ride to persistent log file
        saveRideLog(finalDist, activeMs, finalAvg);

        if (listener != null) {
            listener.onSpeedUpdate(0, finalAvg, finalDist);
            listener.onRideFinished(finalAvg, finalDist, timeStr);
        }
        speak(str("Ride stopped. Distance ", "Приїхали. Дистанція ", "Приехали. Дистанция ")
                + fmtDist(finalDist)
                + str(". Time ", ". Час ", ". Время ")
                + timeStr);
    }

    public void togglePause() {
        if (state == TrackState.RUNNING) {
            autoPaused = false;
            state = TrackState.PAUSED;
            pauseStartMs = System.currentTimeMillis();
            calculator.setAccumulating(false);
            cadenceDetector.setPaused(true);
            notifyStateChanged();
            if (avgRunnable != null) handler.removeCallbacks(avgRunnable);
            updateNotification(calculator.getSmoothedSpeed(),
                    calculator.getAverageSpeed(avgPeriodMin, excludePausesFromAvg),
                    calculator.getTotalDistanceKm());
            speak(str("Paused", "Паузу", "Пауза"));
        } else if (state == TrackState.PAUSED) {
            autoPaused = false;
            state = TrackState.RUNNING;
            if (pauseStartMs > 0) { totalPauseMs += System.currentTimeMillis() - pauseStartMs; pauseStartMs = -1; }
            calculator.setAccumulating(true);
            cadenceDetector.setPaused(false);
            slowStartMs = -1;
            notifyStateChanged();
            lastAnyAnnounceMs = System.currentTimeMillis();
            if (doAnnounceAvg)      scheduleAvgTimer();
            if (doAnnounceCadence)  scheduleCadenceTimer();
            if (doAnnounceRideTime) scheduleRideTimeTimer();
            updateNotification(calculator.getSmoothedSpeed(),
                    calculator.getAverageSpeed(avgPeriodMin, excludePausesFromAvg),
                    calculator.getTotalDistanceKm());
            if (!resumedPending) {
                resumedPending = true;
                speak(str("Resumed", "Знову їдемо", "Продолжаем"));
            }
        }
    }

    /**
     * FIX #4: Screen-on announce now always includes ride time when the ride is active.
     * Previously announceNow only announced speed/avg/distance.
     */
    public void announceNow(boolean includeCurrentSpeed) {
        if (!ttsReady) return;
        long now = System.currentTimeMillis();
        if (lastAnyAnnounceMs > 0 && (now - lastAnyAnnounceMs) < 2000L) return;

        float speed = calculator.getSmoothedSpeed();
        float avg   = calculator.getAverageSpeed(avgPeriodMin, excludePausesFromAvg);
        float dist  = calculator.getTotalDistanceKm();

        StringBuilder sb = new StringBuilder();
        if (includeCurrentSpeed && doAnnounceSpeed) {
            sb.append(fmtSpeedWithCadence(speed)).append(". ");
        }
        if (doAnnounceAvg)
            sb.append(str("Average ", "Середня ", "Средняя "))
              .append(Math.round(avg)).append(". ");
        if (doAnnounceDistance)
            sb.append(str("Distance ", "Дистанція ", "Дистанция "))
              .append(fmtDist(dist)).append(". ");

        // Ride time — always included in screen-on/torch announce when ride is active
        if (includeCurrentSpeed && state == TrackState.RUNNING && rideStartMs > 0) {
            long activeMs = System.currentTimeMillis() - rideStartMs
                    - (rideTimeExcludePauses ? totalPauseMs : 0L);
            sb.append(str("Time ", "Час ", "Время "))
              .append(fmtDuration(Math.max(0, activeMs))).append(". ");
        }

        if (sb.length() > 0) {
            speak(sb.toString().trim());
            lastAnyAnnounceMs = System.currentTimeMillis();
            lastSpeedAnnounceMs = lastAnyAnnounceMs;
        }
    }

    private void requestLocationUpdates() {
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        try {
            locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER, 1000L, 0f,
                    locationListener, Looper.getMainLooper());
        } catch (SecurityException e) {
            Log.e(TAG, "No location permission", e);
        }
    }

    private final LocationListener locationListener = new LocationListener() {
        @Override
        public void onLocationChanged(@NonNull Location location) {
            if (!location.hasSpeed()) return;

            long  now   = System.currentTimeMillis();
            float speed = calculator.update(location.getSpeed(), now);
            float avg   = calculator.getAverageSpeed(avgPeriodMin, excludePausesFromAvg);
            float dist  = calculator.getTotalDistanceKm();

            // Record speed history for graph
            if (rideStartMs > 0) {
                float elapsed = (now - rideStartMs) / 1000f;
                synchronized (speedHistory) { speedHistory.add(new float[]{elapsed, speed}); }
            }

            if (listener != null) listener.onSpeedUpdate(speed, avg, dist);

            if (autoPaused && state == TrackState.PAUSED && speed > 6f) {
                togglePause();
            }

            if (state != TrackState.RUNNING) return;
            resumedPending = false;

            if (autoPauseEnabled) {
                if (speed < 3f) {
                    if (slowStartMs < 0) slowStartMs = now;
                    else if ((now - slowStartMs) > (long) autoPauseSec * 1000L) {
                        slowStartMs = -1;
                        autoPause();
                        return;
                    }
                } else {
                    slowStartMs = -1;
                }
            }

            if (walkingSpeedEnabled && speed < 6f && speed > 0.5f) {
                if (doAnnounceSpeed &&
                        calculator.shouldAnnounceSpeed(speedThresholdKmh, speedDebounceMs)) {
                    speak(str("Walking speed", "Швидкість пішохода", "Скорость черепахи"));
                    lastAnyAnnounceMs   = now;
                    lastSpeedAnnounceMs = now;
                }
                updateNotification(speed, avg, dist);
                return;
            }

            if (doAnnounceSpeed) {
                boolean forceByMaxSilence = maxSilenceMs > 0
                        && lastAnyAnnounceMs > 0
                        && (now - lastAnyAnnounceMs) >= maxSilenceMs;

                if (forceByMaxSilence) {
                    speak(fmtSpeedWithCadence(speed));
                    lastAnyAnnounceMs   = now;
                    lastSpeedAnnounceMs = now;
                } else if (calculator.shouldAnnounceSpeed(speedThresholdKmh, speedDebounceMs)) {
                    speak(fmtSpeedWithCadence(speed));
                    lastAnyAnnounceMs   = now;
                    lastSpeedAnnounceMs = now;
                }
            }

            updateNotification(speed, avg, dist);
        }

        @Override public void onProviderDisabled(@NonNull String p) {
            speak(str("GPS disabled", "Треба увімкнути GPS", "GPS включи"));
        }
    };

    private void scheduleAvgTimer() {
        avgRunnable = () -> {
            if (state == TrackState.RUNNING) {
                float speed = calculator.getSmoothedSpeed();
                float avg   = calculator.getAverageSpeed(avgPeriodMin, excludePausesFromAvg);
                float dist  = calculator.getTotalDistanceKm();
                long  now   = System.currentTimeMillis();

                StringBuilder sb = new StringBuilder();
                if (doAnnounceSpeed && lastSpeedAnnounceMs > 0
                        && (now - lastSpeedAnnounceMs) > 30_000L) {
                    sb.append(fmtSpeedWithCadence(speed)).append(". ");
                }
                if (doAnnounceAvg)
                    sb.append(str("Average ", "Середня ", "Средняя "))
                      .append(Math.round(avg)).append(". ");
                if (doAnnounceDistance)
                    sb.append(str("Distance ", "Дистанція ", "Дистанция "))
                      .append(fmtDist(dist)).append(". ");

                if (sb.length() > 0) {
                    speak(sb.toString().trim());
                    lastAnyAnnounceMs   = now;
                    lastSpeedAnnounceMs = now;
                }
                scheduleAvgTimer();
            }
        };
        handler.postDelayed(avgRunnable, (long) avgIntervalMin * 60_000L);
    }

    private void registerTorchListener() {
        cameraManager = (CameraManager) getSystemService(CAMERA_SERVICE);
        torchCallback = new CameraManager.TorchCallback() {
            @Override
            public void onTorchModeChanged(@NonNull String cameraId, boolean enabled) {
                if (!enabled) return;
                if (state == TrackState.STOPPED) return;
                long now = System.currentTimeMillis();
                if (now - lastTorchMs < TORCH_DEBOUNCE_MS) return;
                lastTorchMs = now;
                if (state == TrackState.PAUSED) togglePause();
                else announceNow(true);
            }
        };
        handler.postDelayed(
            () -> cameraManager.registerTorchCallback(torchCallback, handler), 500);
    }

    private void unregisterTorchListener() {
        if (cameraManager != null && torchCallback != null) {
            cameraManager.unregisterTorchCallback(torchCallback);
            torchCallback = null;
        }
    }

    private void registerScreenReceiver() {
        screenOnReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                String action = intent.getAction();
                if (action == null) return;
                if (state != TrackState.RUNNING) return;

                long now = System.currentTimeMillis();
                long debounceMs = (long) screenAnnounceDebounceSec * 1000L;

                if (Intent.ACTION_SCREEN_ON.equals(action)) {
                    KeyguardManager km = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
                    if (km != null && !km.isKeyguardLocked()) return;
                    if (now - lastScreenAnnounceMs < debounceMs) return;
                    lastScreenAnnounceMs = now;
                    announceNow(true);
                } else if (Intent.ACTION_USER_PRESENT.equals(action)) {
                    if (now - lastScreenAnnounceMs < debounceMs) return;
                    lastScreenAnnounceMs = now;
                    announceNow(true);
                }
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_USER_PRESENT);
        registerReceiver(screenOnReceiver, filter);
    }

    private void unregisterScreenReceiver() {
        if (screenOnReceiver != null) {
            try { unregisterReceiver(screenOnReceiver); } catch (Exception ignored) {}
            screenOnReceiver = null;
        }
    }

    // ── Independent cadence announce timer ──────────────────────────────────────
    private void scheduleCadenceTimer() {
        if (cadenceRunnable != null) handler.removeCallbacks(cadenceRunnable);
        cadenceRunnable = new Runnable() {
            @Override public void run() {
                if (state == TrackState.RUNNING) {
                    CadenceDetector.Result r = lastCadenceResult;
                    if (r != null && r.rpm > 0) {
                        speak(str("Cadence ", "Каденс ", "Круть ")
                                + Math.round(r.rpm));
                    }
                    if (doAnnounceCadence)
                        handler.postDelayed(this, (long) avgIntervalMin * 60_000L);
                }
            }
        };
        handler.postDelayed(cadenceRunnable, (long) avgIntervalMin * 60_000L);
    }

    // ── Independent ride-time announce timer ─────────────────────────────────
    private void scheduleRideTimeTimer() {
        if (rideTimeRunnable != null) handler.removeCallbacks(rideTimeRunnable);
        rideTimeRunnable = new Runnable() {
            @Override public void run() {
                if (state == TrackState.RUNNING) {
                    long activeMs = rideStartMs > 0
                            ? (System.currentTimeMillis() - rideStartMs
                               - (rideTimeExcludePauses ? totalPauseMs : 0L))
                            : 0L;
                    speak(str("Time ", "Час ", "Время ")
                            + fmtDuration(Math.max(0, activeMs)));
                    if (doAnnounceRideTime)
                        handler.postDelayed(this, (long) rideTimeIntervalMin * 60_000L);
                }
            }
        };
        handler.postDelayed(rideTimeRunnable, (long) rideTimeIntervalMin * 60_000L);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Ride Log
    // Файл: Downloads/VeloSpeedometer/ride_log.csv  (виден в файловом менеджере)
    // API 29+: MediaStore (разрешения не нужны)
    // API 24-28: прямой путь (нужен WRITE_EXTERNAL_STORAGE, запрашивается в MainActivity)
    // Внутренний кэш в getFilesDir() — резервная копия истории.
    // ═══════════════════════════════════════════════════════════════════════════

    private static final String LOG_DIR  = "VeloSpeedometer";
    private static final String LOG_NAME = "ride_log.csv";

    /**
     * Добавляет запись о поездке во внутренний кэш и перезаписывает публичный файл.
     * Вызывается из stopTracking() сразу после остановки поездки.
     */
    private void saveRideLog(float distKm, long activeMs, float avgKmh) {
        if (distKm < 0.01f) return;

        // 1. Читаем существующие строки из внутреннего кэша
        File cache = new File(getFilesDir(), LOG_NAME);
        List<String> lines = new ArrayList<>();
        if (cache.exists()) {
            try (BufferedReader br = new BufferedReader(new FileReader(cache))) {
                String l; while ((l = br.readLine()) != null) lines.add(l);
            } catch (IOException ignored) {}
        }
        if (lines.isEmpty()) lines.add("date,dist_km,duration_sec,avg_kmh");

        // 2. Добавляем новую запись
        String date = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date());
        lines.add(String.format(Locale.US, "%s,%.3f,%d,%.2f",
                date, distKm, activeMs / 1000L, avgKmh));

        // 3. Сохраняем во внутренний кэш
        try (PrintWriter pw = new PrintWriter(new FileWriter(cache, false))) {
            for (String l : lines) pw.println(l);
        } catch (IOException e) { Log.e(TAG, "saveRideLog cache", e); }

        // 4. Перезаписываем публичный файл
        String csv = android.text.TextUtils.join("\n", lines) + "\n";
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            saveLogMediaStore(csv);
        } else {
            saveLogLegacy(csv);
        }
    }

    @androidx.annotation.RequiresApi(android.os.Build.VERSION_CODES.Q)
    private void saveLogMediaStore(String csv) {
        android.content.ContentResolver cr = getContentResolver();
        android.net.Uri col = android.provider.MediaStore.Downloads.getContentUri(
                android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY);
        // Удаляем старый файл
        String rel = android.os.Environment.DIRECTORY_DOWNLOADS
                + java.io.File.separator + LOG_DIR + java.io.File.separator;
        cr.delete(col,
                android.provider.MediaStore.MediaColumns.DISPLAY_NAME + "=? AND "
                + android.provider.MediaStore.MediaColumns.RELATIVE_PATH + "=?",
                new String[]{ LOG_NAME, rel });
        // Создаём новый
        android.content.ContentValues cv = new android.content.ContentValues();
        cv.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, LOG_NAME);
        cv.put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "text/csv");
        cv.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
                android.os.Environment.DIRECTORY_DOWNLOADS
                + java.io.File.separator + LOG_DIR);
        android.net.Uri uri = cr.insert(col, cv);
        if (uri == null) { Log.e(TAG, "saveLogMediaStore: null uri"); return; }
        try (java.io.OutputStream os = cr.openOutputStream(uri)) {
            if (os != null) os.write(csv.getBytes("UTF-8"));
        } catch (IOException e) { Log.e(TAG, "saveLogMediaStore write", e); }
    }

    @SuppressWarnings("deprecation")
    private void saveLogLegacy(String csv) {
        File dir = new File(android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS), LOG_DIR);
        if (!dir.exists()) dir.mkdirs();
        try (PrintWriter pw = new PrintWriter(new FileWriter(new File(dir, LOG_NAME), false))) {
            pw.print(csv);
        } catch (IOException e) { Log.e(TAG, "saveLogLegacy", e); }
    }

    /** Читает историю из внутреннего кэша. */
    public List<String[]> readRideLog() {
        List<String[]> rows = new ArrayList<>();
        File cache = new File(getFilesDir(), LOG_NAME);
        if (!cache.exists()) return rows;
        try (BufferedReader br = new BufferedReader(new FileReader(cache))) {
            String line; boolean first = true;
            while ((line = br.readLine()) != null) {
                if (first) { first = false; continue; }
                String[] parts = line.split(",", -1);
                if (parts.length >= 4) rows.add(parts);
            }
        } catch (IOException e) { Log.e(TAG, "readRideLog", e); }
        return rows;
    }

    /** Очищает внутренний кэш и публичный файл. */
    public void clearRideLog() {
        new File(getFilesDir(), LOG_NAME).delete();
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            android.content.ContentResolver cr = getContentResolver();
            android.net.Uri col = android.provider.MediaStore.Downloads.getContentUri(
                    android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY);
            String rel = android.os.Environment.DIRECTORY_DOWNLOADS
                    + java.io.File.separator + LOG_DIR + java.io.File.separator;
            cr.delete(col,
                    android.provider.MediaStore.MediaColumns.DISPLAY_NAME + "=? AND "
                    + android.provider.MediaStore.MediaColumns.RELATIVE_PATH + "=?",
                    new String[]{ LOG_NAME, rel });
        } else {
            new File(android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DOWNLOADS), LOG_DIR + "/" + LOG_NAME).delete();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Heart Rate
    // ═══════════════════════════════════════════════════════════════════════════

    private void checkHrAlert(int bpm) {
        if (!hrAlertEnabled || state != TrackState.RUNNING) return;
        boolean tooLow  = bpm < hrAlertMin;
        boolean tooHigh = bpm > hrAlertMax;
        boolean inZone  = !tooLow && !tooHigh;
        long now = System.currentTimeMillis();

        if (inZone) {
            hrOutsideSinceMs = -1;
            if (hrInsideSinceMs < 0) hrInsideSinceMs = now;
            if (now - hrInsideSinceMs >= HR_HYSTERESIS_MS) {
                hrAlertLowFired  = false;
                hrAlertHighFired = false;
            }
        } else {
            hrInsideSinceMs = -1;
            if (hrOutsideSinceMs < 0) hrOutsideSinceMs = now;
            if (now - hrOutsideSinceMs >= HR_HYSTERESIS_MS) {
                if (tooLow && !hrAlertLowFired) {
                    hrAlertLowFired = true;
                    speak(str("Heart rate too low. ", "Пульс нижче цільового значення. ", "Пульс ниже цели. ")
                            + bpm, true);
                } else if (tooHigh && !hrAlertHighFired) {
                    hrAlertHighFired = true;
                    speak(str("Heart rate too high. ", "Пульс занадто високий. ", "Пульс слишком высокий. ")
                            + bpm, true);
                }
            }
        }
    }

    private void scheduleHrTimer() {
        if (hrAnnounceRunnable != null) handler.removeCallbacks(hrAnnounceRunnable);
        hrAnnounceRunnable = new Runnable() {
            @Override public void run() {
                if (state == TrackState.RUNNING && lastHrBpm > 0) {
                    speak(str("Heart rate ", "Пульс ", "Пульс ") + lastHrBpm, true);
                }
                if (doAnnounceHr) handler.postDelayed(this, hrIntervalSec * 1000L);
            }
        };
        handler.postDelayed(hrAnnounceRunnable, hrIntervalSec * 1000L);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Metronome
    // ═══════════════════════════════════════════════════════════════════════════

    private CadenceDetector createCadenceDetector() {
        SharedPreferences p = getSharedPreferences("settings", MODE_PRIVATE);
        boolean useGyro = !"accel".equals(p.getString("cadence_sensor", "gyro"));
        boolean useAcf  = !"spectral".equals(p.getString("cadence_method", "acf"));
        return new CadenceDetector(this, result -> lastCadenceResult = result, useGyro, useAcf);
    }

    public void exitApp() {
        if (state != TrackState.STOPPED) stopTracking();
        stopMetronome();
        stopForeground(true);
        stopSelf();
        android.os.Process.killProcess(android.os.Process.myPid());
    }

    private void initMetronome() {
        metronomeEngine = new MetronomeEngine(this);
        metronomeEngine.init();
        loadMetronomePrefs();
        setupMediaSession();
    }

    private void loadMetronomePrefs() {
        SharedPreferences p = getSharedPreferences("settings", MODE_PRIVATE);
        metronomeBpm      = p.getInt("metro_bpm", 80);
        metVolumeAdaptive = p.getBoolean("metro_vol_adaptive", false);
        metVolOnCadence   = p.getBoolean("metro_vol_cadence",  true);
        metVolOnHr        = p.getBoolean("metro_vol_hr",       false);
        metHrMin          = p.getInt("metro_hr_min",           120);
        metHrMax          = p.getInt("metro_hr_max",           160);
        metCadenceMinPct  = p.getInt("metro_cad_min_pct",      10);
        metSoundType   = p.getInt("metro_sound_type", MetronomeEngine.SOUND_MARACAS);
        metSoundStrong = p.getBoolean("metro_sound_strong", true);
        metSoundWeak   = p.getBoolean("metro_sound_weak", true);
        metVibStrong   = p.getBoolean("metro_vib_strong", false);
        metVibWeak     = p.getBoolean("metro_vib_weak", true);
        applyMetronomeParams();
    }

    private void applyMetronomeParams() {
        if (metronomeEngine != null)
            metronomeEngine.setParams(metSoundType, metSoundStrong, metSoundWeak, metVibStrong, metVibWeak);
    }

    private void setupMediaSession() {
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        metWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VeloSpeedometer::Metro");

        mediaSession = new MediaSessionCompat(this, "VeloMetronome");

        VolumeProviderCompat vp = new VolumeProviderCompat(
                VolumeProviderCompat.VOLUME_CONTROL_RELATIVE, 100, 50) {
            private long lastToggleMs = 0;
            @Override public void onAdjustVolume(int direction) {
                if (direction > 0) {
                    long now = System.currentTimeMillis();
                    if (now - lastToggleMs > 500) {
                        lastToggleMs = now;
                        toggleMetronome();
                    }
                }
                if (direction < 0) {
                    announceNow(true);
                }
            }
        };

        mediaSession.setPlaybackToRemote(vp);
        PlaybackStateCompat state = new PlaybackStateCompat.Builder()
                .setActions(PlaybackStateCompat.ACTION_PLAY | PlaybackStateCompat.ACTION_STOP)
                .setState(PlaybackStateCompat.STATE_PLAYING, 0, 1.0f)
                .build();
        mediaSession.setPlaybackState(state);
        mediaSession.setActive(true);
    }

    public void toggleMetronome() {
        if (metronomeRunning) stopMetronome(); else startMetronome();
    }

    public void startMetronome() {
        if (metronomeRunning) return;
        metronomeRunning = true;
        if (!metWakeLock.isHeld()) metWakeLock.acquire(2 * 60 * 60 * 1000L);
        notifyMetronomeChanged();
        metronomeThread = new Thread(() -> {
            long nextTime = System.currentTimeMillis();
            while (metronomeRunning) {
                boolean bothEnabled = metSoundStrong && metSoundWeak;
                long fullInterval  = 60000L / Math.max(1, metronomeBpm);
                long halfInterval  = fullInterval / 2;

                // Strong beat
                updateMetronomeVolume();
                metronomeEngine.beat(true);

                if (bothEnabled) {
                    nextTime += halfInterval;
                    long s1 = nextTime - System.currentTimeMillis();
                    if (s1 > 0) try { Thread.sleep(s1); } catch (InterruptedException e) { return; }

                    updateMetronomeVolume();
                    metronomeEngine.beat(false);

                    nextTime += halfInterval;
                } else {
                    nextTime += fullInterval;
                }

                long sleep = nextTime - System.currentTimeMillis();
                if (sleep > 0) {
                    try { Thread.sleep(sleep); } catch (InterruptedException ignored) {}
                } else {
                    nextTime = System.currentTimeMillis();
                }
            }
        });
        metronomeThread.setDaemon(true);
        metronomeThread.start();
    }

    /**
     * FIX #7: 3-level adaptive metronome volume.
     *
     * rpm >= metronomeBpm                          → -18 dB (VOL_DIM):   on-target or above
     * rpm in (threshold, metronomeBpm)             → -12 dB (VOL_MID):   slightly below target
     * rpm < threshold = metronomeBpm*(1-pct/100)  →   0 dB (VOL_FULL):  significantly below
     * no cadence signal (rpm == 0)                 →   0 dB (VOL_FULL):  can't confirm, alert
     *
     * threshold = metronomeBpm × (1 − metCadenceMinPct / 100)
     */
    private void updateMetronomeVolume() {
        if (metronomeEngine == null) return;
        if (!metVolumeAdaptive) { metronomeEngine.setVolume(VOL_FULL); return; }

        float rpm       = lastCadenceResult != null ? lastCadenceResult.rpm : 0f;
        float threshold = metronomeBpm * (1f - metCadenceMinPct / 100f);

        final float vol;
        if (rpm <= 0f) {
            // No cadence signal — use full volume to alert the rider
            vol = VOL_FULL;
        } else if (rpm >= metronomeBpm) {
            // On target or above — dim to -18 dB (unobtrusive)
            vol = VOL_DIM;
        } else if (rpm >= threshold) {
            // Within tolerance band below target — medium -12 dB
            vol = VOL_MID;
        } else {
            // Below threshold — full volume alert
            vol = VOL_FULL;
        }
        metronomeEngine.setVolume(vol);
    }

    public void stopMetronome() {
        metronomeRunning = false;
        if (metronomeThread != null) { metronomeThread.interrupt(); metronomeThread = null; }
        if (metWakeLock != null && metWakeLock.isHeld()) metWakeLock.release();
        notifyMetronomeChanged();
    }

    public boolean isMetronomePlaying() { return metronomeRunning; }

    public int getMetronomeBpm() { return metronomeBpm; }

    public void setMetronomeBpm(int bpm) {
        metronomeBpm = Math.max(40, Math.min(180, bpm));
        getSharedPreferences("settings", MODE_PRIVATE).edit()
                .putInt("metro_bpm", metronomeBpm).apply();
    }

    public void setMetronomeParams(int soundType, boolean ss, boolean sw, boolean vs, boolean vw) {
        metSoundType = soundType; metSoundStrong = ss; metSoundWeak = sw;
        metVibStrong = vs; metVibWeak = vw;
        applyMetronomeParams();
        getSharedPreferences("settings", MODE_PRIVATE).edit()
                .putInt("metro_sound_type", soundType)
                .putBoolean("metro_sound_strong", ss)
                .putBoolean("metro_sound_weak", sw)
                .putBoolean("metro_vib_strong", vs)
                .putBoolean("metro_vib_weak", vw).apply();
    }

    public int  getMetSoundType()   { return metSoundType; }
    public boolean isMetVolumeAdaptive() { return metVolumeAdaptive; }
    public boolean isMetVolOnCadence()   { return metVolOnCadence; }
    public boolean isMetVolOnHr()        { return metVolOnHr; }
    public int     getMetCadenceMinPct() { return metCadenceMinPct; }
    public int     getMetHrMin()         { return metHrMin; }
    public int     getMetHrMax()         { return metHrMax; }

    public void setMetVolumeAdaptive(boolean on, int cadMinPct) {
        metVolumeAdaptive = on;
        metCadenceMinPct  = cadMinPct;
        getSharedPreferences("settings", MODE_PRIVATE).edit()
                .putBoolean("metro_vol_adaptive",  on)
                .putInt("metro_cad_min_pct",       cadMinPct)
                .apply();
        if (!on) metronomeEngine.setVolume(VOL_FULL);
    }

    public void setHrAlert(boolean enabled, int min, int max) {
        hrAlertEnabled = enabled;
        hrAlertMin     = min;
        hrAlertMax     = max;
        hrAlertLowFired  = false;
        hrAlertHighFired = false;
        hrOutsideSinceMs = -1;
        hrInsideSinceMs  = -1;
        getSharedPreferences("settings", MODE_PRIVATE).edit()
                .putBoolean("hr_alert_enabled", enabled)
                .putInt("hr_alert_min", min)
                .putInt("hr_alert_max", max)
                .apply();
    }

    public boolean isHrAlertEnabled() { return hrAlertEnabled; }
    public int     getHrAlertMin()    { return hrAlertMin; }
    public int     getHrAlertMax()    { return hrAlertMax; }
    public boolean isMetSoundStrong(){ return metSoundStrong; }
    public boolean isMetSoundWeak()  { return metSoundWeak; }
    public boolean isMetVibStrong()  { return metVibStrong; }
    public boolean isMetVibWeak()    { return metVibWeak; }

    private void notifyMetronomeChanged() {
        handler.post(() -> { if (listener != null) listener.onMetronomeChanged(metronomeRunning); });
    }

    private void initTts() {
        tts = new TextToSpeech(this, status -> {
            if (status != TextToSpeech.SUCCESS) return;

            SharedPreferences prefs = getSharedPreferences("settings", MODE_PRIVATE);
            String pref = prefs.getString("tts_lang", "auto");
            String resolved;
            if ("auto".equals(pref)) {
                resolved = Locale.getDefault().getLanguage();
            } else {
                resolved = pref;
            }

            Locale ttsLocale;
            if ("uk".equals(resolved)) {
                ttsLocale = new Locale("uk", "UA");
                lang = "uk";
            } else if ("ru".equals(resolved)) {
                ttsLocale = new Locale("ru", "RU");
                lang = "ru";
            } else {
                ttsLocale = Locale.ENGLISH;
                lang = "en";
            }

            int result = tts.setLanguage(ttsLocale);
            if (result == TextToSpeech.LANG_MISSING_DATA
                    || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.w(TAG, "TTS: " + ttsLocale + " not supported, falling back to EN");
                lang = "en";
                result = tts.setLanguage(Locale.ENGLISH);
            }

            ttsReady = (result != TextToSpeech.LANG_MISSING_DATA
                     && result != TextToSpeech.LANG_NOT_SUPPORTED);
            audioEnhancer = new AudioEnhancer(this, tts);
            audioEnhancer.setGainDb(gainDb);
        });
    }

    private void speak(String text) { speak(text, false); }

    private void speak(String text, boolean urgent) {
        if (!ttsReady || tts == null) return;
        if (!urgent && state == TrackState.PAUSED) return;

        lastTorchMs = System.currentTimeMillis();

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        boolean screenOff = pm != null && !pm.isInteractive();

        if (urgent) {
            if (audioEnhancer != null) audioEnhancer.cancel();
            if (enhancedAudioEnabled && screenOff && audioEnhancer != null) {
                audioEnhancer.speak(text, null);
            } else {
                Bundle p = new Bundle();
                p.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC);
                tts.speak(text, TextToSpeech.QUEUE_FLUSH, p, "sb_urgent");
            }
        } else {
            if (enhancedAudioEnabled && screenOff && audioEnhancer != null) {
                audioEnhancer.speak(text, null);
            } else {
                Bundle p = new Bundle();
                p.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC);
                tts.speak(text, TextToSpeech.QUEUE_ADD, p, "sb");
            }
        }
    }

    // ── Локализация ───────────────────────────────────────────────────────────

    private String str(String en, String uk, String ru) {
        switch (lang) {
            case "uk": return uk;
            case "ru": return ru;
            default:   return en;
        }
    }

    private String fmtSpeed(float kmh) {
        return str("Speed ", "Швидкість ", "Скорость ") + Math.round(kmh);
    }

    private String fmtSpeedWithCadence(float kmh) {
        String s = fmtSpeed(kmh);
        if (!doAnnounceCadence) return s;
        CadenceDetector.Result r = lastCadenceResult;
        if (r.stable && r.rpm > 0f) {
            s += ". " + str("Cadence ", "Каденс ", "Круть ") + Math.round(r.rpm);
        } else if (r.stableAvgRpm > 0f) {
            s += ". " + str("Cadence approx ", "Каденс приблизно ", "Круть около ")
                 + Math.round(r.stableAvgRpm);
        } else if (r.rpm > 0f) {
            s += ". " + str("Cadence unstable", "Каденс нестабільний", "Частота педалирования нестабильная");
        }
        if (doAnnounceHr && lastHrBpm > 0)
            s += ". " + str("Heart ", "Пульс ", "Пульс ") + lastHrBpm;
        return s;
    }

    private static float dBtoLinear(float dB) {
        return (float) Math.pow(10.0, dB / 20.0);
    }

    private String fmtDuration(long ms) {
        long total = ms / 1000;
        long h = total / 3600; total %= 3600;
        long m = total / 60;
        long s = total % 60;
        StringBuilder sb = new StringBuilder();
        if (h > 0) {
            sb.append(h);
            switch (lang) {
                case "uk": sb.append(h == 1 ? " година" : " годин"); break;
                case "ru": sb.append(h == 1 ? " час" : " часов"); break;
                default:   sb.append(h == 1 ? " hour" : " hours"); break;
            }
        }
        if (m > 0 || h > 0) {
            if (sb.length() > 0) sb.append(" ");
            sb.append(m);
            switch (lang) {
                case "uk": sb.append(m == 1 ? " хвилина" : " хвилин"); break;
                case "ru": sb.append(m == 1 ? " минута" : " минут"); break;
                default:   sb.append(m == 1 ? " minute" : " minutes"); break;
            }
        }
        if (h == 0 && m < 5 && s > 0) {
            if (sb.length() > 0) sb.append(" ");
            sb.append(s);
            switch (lang) {
                case "uk": sb.append(s == 1 ? " секунда" : " секунд"); break;
                case "ru": sb.append(s == 1 ? " секунда" : " секунд"); break;
                default:   sb.append(s == 1 ? " second" : " seconds"); break;
            }
        }
        if (sb.length() == 0) sb.append(str("0 minutes", "0 хвилин", "0 минут"));
        return sb.toString();
    }

    private String fmtDist(float km) {
        if (km < 1f) {
            int m = Math.round(km * 1000);
            switch (lang) {
                case "uk": return m + " метрів";
                case "ru": return m + " метров";
                default:   return String.format(Locale.US, "%.0f meters", km * 1000);
            }
        }
        String s;
        switch (lang) {
            case "uk":
                s = String.format(Locale.US, "%.1f кілометрів", km);
                return s.replace(".", ",");
            case "ru":
                s = String.format(Locale.US, "%.1f километров", km);
                return s.replace(".", ",");
            default:
                return String.format(Locale.US, "%.1f kilometers", km);
        }
    }

    // ── Notification ─────────────────────────────────────────────────────────

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "SpeakingBike", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            getSystemService(NotificationManager.class).createNotificationChannel(ch);
        }
    }

    private Notification buildNotification(float speed, float avg, float dist) {
        int f = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;

        PendingIntent openPi = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class)
                        .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), f);
        PendingIntent announcePi = PendingIntent.getService(this, 1,
                new Intent(this, SpeedometerService.class).setAction(ACTION_ANNOUNCE), f);
        PendingIntent pausePi = PendingIntent.getService(this, 3,
                new Intent(this, SpeedometerService.class).setAction(ACTION_PAUSE), f);
        PendingIntent stopPi = PendingIntent.getService(this, 2,
                new Intent(this, SpeedometerService.class).setAction(ACTION_STOP), f);

        String stateTag = state == TrackState.PAUSED ? "⏸  " : "";
        String content  = state == TrackState.STOPPED ? "Tap to open"
                : String.format(Locale.US, "%s%.0f km/h  avg %.0f  %.2f km",
                                stateTag, speed, avg, dist);

        String pauseLabel = state == TrackState.PAUSED ? "▶ Resume" : "⏸ Pause";

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("🚴 SpeakingBike")
                .setContentText(content)
                .setSmallIcon(android.R.drawable.ic_menu_directions)
                .setContentIntent(openPi)
                .addAction(android.R.drawable.ic_lock_silent_mode_off, "📢 Say",  announcePi)
                .addAction(android.R.drawable.ic_media_pause,          pauseLabel, pausePi)
                .addAction(android.R.drawable.ic_delete,               "■ Stop",  stopPi)
                .setOngoing(true)
                .setSilent(true)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .build();
    }

    private void updateNotification(float speed, float avg, float dist) {
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE))
                .notify(NOTIF_ID, buildNotification(speed, avg, dist));
    }

    private void notifyStateChanged() {
        if (listener != null) listener.onStateChanged(state);
    }

    private void autoPause() {
        if (state != TrackState.RUNNING) return;
        speak(str(
            "You've been stopped for a while. Auto-paused.",
            "Ви довго стоїте. Лог на паузі.",
            "Долго стоите. Авто-пауза."));
        autoPaused = true;
        state = TrackState.PAUSED;
        pauseStartMs = System.currentTimeMillis();
        calculator.setAccumulating(false);
        cadenceDetector.setPaused(true);
        notifyStateChanged();
        if (avgRunnable != null) handler.removeCallbacks(avgRunnable);
        updateNotification(calculator.getSmoothedSpeed(),
                calculator.getAverageSpeed(avgPeriodMin, excludePausesFromAvg),
                calculator.getTotalDistanceKm());
    }

    public void reinitTts() {
        ttsReady = false;
        if (tts != null) { tts.stop(); tts.shutdown(); tts = null; }
        if (audioEnhancer != null) { audioEnhancer.cancel(); audioEnhancer = null; }
        initTts();
    }

    public void reloadSettings() {
        boolean prevScreenAnnounce = screenAnnounceEnabled;
        boolean prevDoAnnounceAvg  = doAnnounceAvg;
        loadSettings();

        boolean wasRunning = (state == TrackState.RUNNING);
        cadenceDetector.stop();
        cadenceDetector = createCadenceDetector();
        // FIX #1+#2: always pass shared rideStartMs when restarting detector
        if (wasRunning) cadenceDetector.start(rideStartMs);
        // Reconnect HR if device changed
        String hrAddr = getSharedPreferences("settings", MODE_PRIVATE).getString("hr_device_address", null);
        heartRateMonitor.disconnect();
        lastHrBattery = -1;
        if (hrAddr != null) heartRateMonitor.connect(hrAddr);
        // Reschedule HR timer
        if (hrAnnounceRunnable != null) handler.removeCallbacks(hrAnnounceRunnable);
        if (doAnnounceHr && state == TrackState.RUNNING) scheduleHrTimer();
        if (cadenceRunnable  != null) handler.removeCallbacks(cadenceRunnable);
        if (doAnnounceCadence && state == TrackState.RUNNING) scheduleCadenceTimer();
        if (rideTimeRunnable != null) handler.removeCallbacks(rideTimeRunnable);
        if (doAnnounceRideTime && state == TrackState.RUNNING) scheduleRideTimeTimer();

        if (state == TrackState.STOPPED) return;

        if (prevDoAnnounceAvg || doAnnounceAvg) {
            if (avgRunnable != null) handler.removeCallbacks(avgRunnable);
            if (doAnnounceAvg && state == TrackState.RUNNING) scheduleAvgTimer();
        }
        if (prevScreenAnnounce != screenAnnounceEnabled) {
            unregisterScreenReceiver();
            if (screenAnnounceEnabled) registerScreenReceiver();
        }
    }

    private void loadSettings() {
        SharedPreferences p = getSharedPreferences("settings", MODE_PRIVATE);
        speedThresholdKmh        = p.getFloat("speed_threshold", 5f);
        speedDebounceMs          = p.getInt("speed_debounce", 10) * 1000L;
        maxSilenceMs             = p.getInt("max_announce_interval", 60) * 1000L;
        avgPeriodMin             = p.getInt("avg_period", 10);
        avgIntervalMin           = p.getInt("avg_interval", 2);
        doAnnounceSpeed          = p.getBoolean("announce_speed",    true);
        doAnnounceAvg            = p.getBoolean("announce_avg",      true);
        doAnnounceDistance       = p.getBoolean("announce_distance", true);
        autoPauseEnabled         = p.getBoolean("auto_pause",        false);
        autoPauseSec             = p.getInt("auto_pause_sec",        20);
        walkingSpeedEnabled      = p.getBoolean("walking_speed",     false);
        screenAnnounceEnabled    = p.getBoolean("screen_announce",   true);
        screenAnnounceDebounceSec= p.getInt("screen_debounce",       15);
        enhancedAudioEnabled     = p.getBoolean("enhanced_audio",    true);
        gainDb                   = p.getFloat("gain_db",             12f);
        calculator.setAlpha(p.getFloat("ema_alpha", 0.3f));
        doAnnounceCadence        = p.getBoolean("announce_cadence",       false);
        doAnnounceHr             = p.getBoolean("announce_hr",            false);
        hrIntervalSec            = p.getInt("hr_interval_sec",           63);
        excludePausesFromAvg     = p.getBoolean("exclude_pauses_from_avg", false);
        doAnnounceRideTime       = p.getBoolean("announce_ride_time",     false);
        rideTimeExcludePauses    = p.getBoolean("ride_time_excl_pauses",  true);
        rideTimeIntervalMin      = p.getInt("ride_time_interval_min",     5);
        hrAlertEnabled           = p.getBoolean("hr_alert_enabled",       false);
        hrAlertMin               = p.getInt("hr_alert_min",              120);
        hrAlertMax               = p.getInt("hr_alert_max",              160);
        if (audioEnhancer != null) audioEnhancer.setGainDb(gainDb);
    }

    @Override
    public void onDestroy() {
        stopMetronome();
        if (metronomeEngine != null) metronomeEngine.release();
        if (heartRateMonitor != null) heartRateMonitor.disconnect();
        if (mediaSession    != null) mediaSession.release();
        if (metWakeLock != null && metWakeLock.isHeld()) metWakeLock.release();
        super.onDestroy();
        if (state != TrackState.STOPPED) stopTracking();
        if (audioEnhancer != null) audioEnhancer.release();
        if (tts != null) { tts.stop(); tts.shutdown(); }
        handler.removeCallbacksAndMessages(null);
    }
}
