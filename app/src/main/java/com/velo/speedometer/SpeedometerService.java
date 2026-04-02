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
import android.hardware.camera2.CameraAccessException;
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

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;

import java.util.Locale;

public class SpeedometerService extends Service {

    private static final String TAG = "SpeakingBike";
    private static final String CHANNEL_ID = "sb_channel";
    private static final int    NOTIF_ID   = 1;

    public static final String ACTION_START    = "sb.START";
    public static final String ACTION_STOP     = "sb.STOP";
    public static final String ACTION_PAUSE    = "sb.PAUSE";
    public static final String ACTION_ANNOUNCE = "sb.ANNOUNCE";

    // ── Binder ────────────────────────────────────────────────────────────────
    private final IBinder binder = new LocalBinder();
    public class LocalBinder extends Binder {
        public SpeedometerService getService() { return SpeedometerService.this; }
    }

    // ── State ─────────────────────────────────────────────────────────────────
    public enum TrackState { STOPPED, RUNNING, PAUSED }
    private TrackState state = TrackState.STOPPED;
    private boolean ttsReady = false;

    // ── Hardware helpers ──────────────────────────────────────────────────────
    private LocationManager locationManager;
    private CameraManager   cameraManager;
    private CameraManager.TorchCallback torchCallback;
    private long lastTorchMs = 0;
    private static final long TORCH_DEBOUNCE_MS = 2000;

    // ── Screen-on receiver ────────────────────────────────────────────────────
    private BroadcastReceiver screenOnReceiver;
    private long lastScreenAnnounceMs = 0;

    // ── Audio ─────────────────────────────────────────────────────────────────
    private TextToSpeech  tts;
    private AudioEnhancer audioEnhancer;

    // ── Timers ────────────────────────────────────────────────────────────────
    private SpeedCalculator calculator;
    private Handler         handler;
    private Runnable        avgRunnable;
    private long            lastSpeedAnnounceMs = 0;  // tracks last SPEED announce
    private long            lastAnyAnnounceMs   = 0;  // tracks any announce (for max-silence)

    // ── Auto-pause state ──────────────────────────────────────────────────────
    private long slowStartMs = -1;   // when we first noticed speed < autoPauseSpeedKmh

    // ── Callbacks ─────────────────────────────────────────────────────────────
    private SpeedListener listener;

    public interface SpeedListener {
        void onSpeedUpdate(float speedKmh, float avgKmh, float distanceKm);
        void onStateChanged(TrackState state);
    }

    // ── Settings (loaded on start) ────────────────────────────────────────────
    private float   speedThresholdKmh;
    private long    speedDebounceMs;
    private long    maxSilenceMs;
    private int     avgPeriodMin;          // 0 = whole ride
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

    // ─────────────────────────────────────────────────────────────────────────
    @Override
    public void onCreate() {
        super.onCreate();
        handler    = new Handler(Looper.getMainLooper());
        calculator = new SpeedCalculator(0.3f);
        createNotificationChannel();
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
        }
        return START_STICKY;
    }

    @Override public IBinder onBind(Intent intent) { return binder; }

    // ── Public API ────────────────────────────────────────────────────────────
    public TrackState getState() { return state; }
    public void setSpeedListener(SpeedListener l) { listener = l; }

    public void startTracking() {
        if (state != TrackState.STOPPED) return;
        loadSettings();
        calculator.reset();
        lastSpeedAnnounceMs = 0;
        lastAnyAnnounceMs   = 0;
        slowStartMs         = -1;
        state = TrackState.RUNNING;
        notifyStateChanged();

        Notification n = buildNotification(0, 0, 0);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        } else {
            startForeground(NOTIF_ID, n);
        }

        requestLocationUpdates();
        registerTorchListener();
        if (screenAnnounceEnabled) registerScreenReceiver();
        if (doAnnounceAvg) scheduleAvgTimer();
        speak("Ride started");
    }

    public void stopTracking() {
        if (state == TrackState.STOPPED) return;
        state = TrackState.STOPPED;
        notifyStateChanged();

        if (locationManager != null) locationManager.removeUpdates(locationListener);
        if (avgRunnable != null)     handler.removeCallbacks(avgRunnable);
        unregisterTorchListener();
        unregisterScreenReceiver();
        stopForeground(true);

        if (listener != null)
            listener.onSpeedUpdate(0, 0, calculator.getTotalDistanceKm());
        speak("Ride stopped. Distance " + fmtDist(calculator.getTotalDistanceKm()));
    }

    public void togglePause() {
        if (state == TrackState.RUNNING) {
            state = TrackState.PAUSED;
            notifyStateChanged();
            if (avgRunnable != null) handler.removeCallbacks(avgRunnable);
            updateNotification(calculator.getSmoothedSpeed(),
                    calculator.getAverageSpeed(avgPeriodMin),
                    calculator.getTotalDistanceKm());
            speak("Paused");
        } else if (state == TrackState.PAUSED) {
            state = TrackState.RUNNING;
            slowStartMs = -1;
            notifyStateChanged();
            lastAnyAnnounceMs = System.currentTimeMillis();
            calculator.resetAnnouncedSpeed();
            if (doAnnounceAvg) scheduleAvgTimer();
            updateNotification(calculator.getSmoothedSpeed(),
                    calculator.getAverageSpeed(avgPeriodMin),
                    calculator.getTotalDistanceKm());
            speak("Resumed");
        }
    }

    public void announceNow(boolean includeCurrentSpeed) {
        if (!ttsReady) return;
        float speed = calculator.getSmoothedSpeed();
        float avg   = calculator.getAverageSpeed(avgPeriodMin);
        float dist  = calculator.getTotalDistanceKm();

        StringBuilder sb = new StringBuilder();
        if (includeCurrentSpeed && doAnnounceSpeed) {
            sb.append(fmtSpeed(speed)).append(". ");
        }
        if (doAnnounceAvg)
            sb.append("Average ").append(Math.round(avg)).append(". ");
        if (doAnnounceDistance)
            sb.append("Distance ").append(fmtDist(dist)).append(". ");

        if (sb.length() > 0) {
            speak(sb.toString().trim());
            lastAnyAnnounceMs = System.currentTimeMillis();
            // reset speed-change timer so the next period starts fresh
            lastSpeedAnnounceMs = lastAnyAnnounceMs;
            calculator.resetAnnouncedSpeed();
        }
    }

    // ── Location ──────────────────────────────────────────────────────────────
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
            float avg   = calculator.getAverageSpeed(avgPeriodMin);
            float dist  = calculator.getTotalDistanceKm();

            // Always update UI even when paused (GPS stays alive to keep lock)
            if (listener != null) listener.onSpeedUpdate(speed, avg, dist);

            if (state != TrackState.RUNNING) return;

            // ── Auto-pause logic ──────────────────────────────────────────────
            if (autoPauseEnabled) {
                if (speed < 3f) {
                    if (slowStartMs < 0) slowStartMs = now;
                    else if ((now - slowStartMs) > (long) autoPauseSec * 1000L) {
                        slowStartMs = -1;
                        togglePause();
                        return;
                    }
                } else {
                    slowStartMs = -1;
                }
            }
            // Auto-resume
            if (state == TrackState.PAUSED && speed > 6f) {
                togglePause();
            }

            // ── Walking speed ─────────────────────────────────────────────────
            if (walkingSpeedEnabled && speed < 6f && speed > 0.5f) {
                if (doAnnounceSpeed &&
                        calculator.shouldAnnounceSpeed(speedThresholdKmh, speedDebounceMs)) {
                    speak("Walking speed");
                    lastAnyAnnounceMs   = now;
                    lastSpeedAnnounceMs = now;
                }
                updateNotification(speed, avg, dist);
                return;
            }

            // ── Speed-change announce ─────────────────────────────────────────
            if (doAnnounceSpeed) {
                boolean forceByMaxSilence = maxSilenceMs > 0
                        && lastAnyAnnounceMs > 0
                        && (now - lastAnyAnnounceMs) >= maxSilenceMs;

                if (forceByMaxSilence) {
                    speak(fmtSpeed(speed));
                    lastAnyAnnounceMs   = now;
                    lastSpeedAnnounceMs = now;
                    calculator.resetAnnouncedSpeed();
                } else if (calculator.shouldAnnounceSpeed(speedThresholdKmh, speedDebounceMs)) {
                    speak(fmtSpeed(speed));
                    lastAnyAnnounceMs   = now;
                    lastSpeedAnnounceMs = now;
                }
            }

            updateNotification(speed, avg, dist);
        }

        @Override public void onProviderDisabled(@NonNull String p) { speak("GPS disabled"); }
    };

    // ── Avg timer ─────────────────────────────────────────────────────────────
    private void scheduleAvgTimer() {
        avgRunnable = () -> {
            if (state == TrackState.RUNNING) {
                // Announce avg+distance; also current speed IF not recently announced
                float speed = calculator.getSmoothedSpeed();
                float avg   = calculator.getAverageSpeed(avgPeriodMin);
                float dist  = calculator.getTotalDistanceKm();
                long  now   = System.currentTimeMillis();

                StringBuilder sb = new StringBuilder();
                // Include current speed in timer announce if not recently said
                if (doAnnounceSpeed && lastSpeedAnnounceMs > 0
                        && (now - lastSpeedAnnounceMs) > 30_000L) {
                    sb.append(fmtSpeed(speed)).append(". ");
                }
                if (doAnnounceAvg)
                    sb.append("Average ").append(Math.round(avg)).append(". ");
                if (doAnnounceDistance)
                    sb.append("Distance ").append(fmtDist(dist)).append(". ");

                if (sb.length() > 0) {
                    speak(sb.toString().trim());
                    lastAnyAnnounceMs   = now;
                    lastSpeedAnnounceMs = now;
                    calculator.resetAnnouncedSpeed();
                }
                scheduleAvgTimer();
            }
        };
        handler.postDelayed(avgRunnable, (long) avgIntervalMin * 60_000L);
    }

    // ── Torch listener ────────────────────────────────────────────────────────
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

    // ── Screen-on receiver ────────────────────────────────────────────────────
    private void registerScreenReceiver() {
        screenOnReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                if (!Intent.ACTION_SCREEN_ON.equals(intent.getAction())) return;
                if (state != TrackState.RUNNING) return;

                // Strategy A: only if phone is still locked
                KeyguardManager km =
                        (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
                if (km != null && !km.isKeyguardLocked()) return;

                // Strategy B: debounce
                long now = System.currentTimeMillis();
                long debounceMs = (long) screenAnnounceDebounceSec * 1000L;
                if (now - lastScreenAnnounceMs < debounceMs) return;
                lastScreenAnnounceMs = now;

                announceNow(true);
            }
        };
        IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_ON);
        registerReceiver(screenOnReceiver, filter);
    }

    private void unregisterScreenReceiver() {
        if (screenOnReceiver != null) {
            try { unregisterReceiver(screenOnReceiver); } catch (Exception ignored) {}
            screenOnReceiver = null;
        }
    }

    // ── TTS / AudioEnhancer ───────────────────────────────────────────────────
    private void initTts() {
        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                int r = tts.setLanguage(java.util.Locale.ENGLISH);
                ttsReady = (r != TextToSpeech.LANG_MISSING_DATA
                         && r != TextToSpeech.LANG_NOT_SUPPORTED);
                audioEnhancer = new AudioEnhancer(this, tts);
                audioEnhancer.setGainDb(gainDb);
            }
        });
    }

    private void speak(String text) {
        if (!ttsReady || tts == null) return;
        // Silence during pause
        if (state == TrackState.PAUSED) return;

        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        boolean screenOff = pm != null && !pm.isInteractive();

        if (enhancedAudioEnabled && screenOff && audioEnhancer != null) {
            audioEnhancer.speak(text, null);
        } else {
            Bundle params = new Bundle();
            params.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_ALARM);
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, "sb");
        }
    }

    // ── Notification ──────────────────────────────────────────────────────────
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
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)  // show on lock screen
                .build();
    }

    private void updateNotification(float speed, float avg, float dist) {
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE))
                .notify(NOTIF_ID, buildNotification(speed, avg, dist));
    }

    private void notifyStateChanged() {
        if (listener != null) listener.onStateChanged(state);
    }

    // ── Settings ──────────────────────────────────────────────────────────────
    private void loadSettings() {
        SharedPreferences p = getSharedPreferences("settings", MODE_PRIVATE);
        speedThresholdKmh        = p.getFloat("speed_threshold", 5f);
        speedDebounceMs          = p.getInt("speed_debounce", 10) * 1000L;
        maxSilenceMs             = p.getInt("max_announce_interval", 60) * 1000L;
        avgPeriodMin             = p.getInt("avg_period", 10);   // 0 = whole ride
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
        if (audioEnhancer != null) audioEnhancer.setGainDb(gainDb);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────
    private String fmtSpeed(float kmh) {
        return "Speed " + Math.round(kmh);
    }

    private String fmtDist(float km) {
        if (km < 1f) return String.format(Locale.US, "%.0f meters", km * 1000);
        return String.format(Locale.US, "%.1f kilometers", km);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (state != TrackState.STOPPED) stopTracking();
        if (audioEnhancer != null) audioEnhancer.release();
        if (tts != null) { tts.stop(); tts.shutdown(); }
        handler.removeCallbacksAndMessages(null);
    }
}
