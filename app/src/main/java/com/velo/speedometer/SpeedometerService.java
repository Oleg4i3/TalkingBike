package com.velo.speedometer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
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
import android.speech.tts.TextToSpeech;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;

import java.util.Locale;

public class SpeedometerService extends Service {

    private static final String TAG = "VeloService";
    private static final String CHANNEL_ID = "velo_channel";
    private static final int NOTIF_ID = 1;

    public static final String ACTION_START    = "velo.START";
    public static final String ACTION_STOP     = "velo.STOP";
    public static final String ACTION_ANNOUNCE = "velo.ANNOUNCE";

    // ── Binder ───────────────────────────────────────────────────────────────
    private final IBinder binder = new LocalBinder();

    public class LocalBinder extends Binder {
        public SpeedometerService getService() { return SpeedometerService.this; }
    }

    // ── State ─────────────────────────────────────────────────────────────────
    private boolean tracking = false;
    private boolean ttsReady = false;

    private LocationManager locationManager;
    private CameraManager   cameraManager;
    private CameraManager.TorchCallback torchCallback;

    private TextToSpeech   tts;
    private SpeedCalculator calculator;
    private Handler         handler;
    private Runnable        avgRunnable;

    private SpeedListener listener;

    // ── Settings ──────────────────────────────────────────────────────────────
    private float   speedThresholdKmh;
    private long    speedDebounceMs;
    private int     avgPeriodMin;
    private int     avgIntervalMin;
    private boolean doAnnounceSpeed;
    private boolean doAnnounceAvg;
    private boolean doAnnounceDistance;

    // ── Callback for MainActivity ─────────────────────────────────────────────
    public interface SpeedListener {
        void onSpeedUpdate(float speedKmh, float avgKmh, float distanceKm);
    }

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
            case ACTION_START:    startTracking();      break;
            case ACTION_STOP:     stopTracking();       break;
            case ACTION_ANNOUNCE: announceNow(true);    break;
        }
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) { return binder; }

    // ── Public API ────────────────────────────────────────────────────────────
    public boolean isTracking() { return tracking; }

    public void setSpeedListener(SpeedListener l) { listener = l; }

    public void startTracking() {
        if (tracking) return;
        loadSettings();
        calculator.reset();
        tracking = true;

        Notification n = buildNotification(0, 0, 0);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
        } else {
            startForeground(NOTIF_ID, n);
        }

        requestLocationUpdates();
        registerTorchListener();
        if (doAnnounceAvg) scheduleAvgAnnouncement();
        speak("Ride started");
    }

    public void stopTracking() {
        if (!tracking) return;
        tracking = false;
        if (locationManager != null) locationManager.removeUpdates(locationListener);
        if (avgRunnable != null) handler.removeCallbacks(avgRunnable);
        unregisterTorchListener();
        stopForeground(true);
        if (listener != null) listener.onSpeedUpdate(0, 0, calculator.getTotalDistanceKm());
        speak("Ride stopped. Distance " + formatDistance(calculator.getTotalDistanceKm()));
    }

    public void announceNow(boolean includeCurrentSpeed) {
        if (!ttsReady) return;
        float speed = calculator.getSmoothedSpeed();
        float avg   = calculator.getAverageSpeed(avgPeriodMin);
        float dist  = calculator.getTotalDistanceKm();

        StringBuilder sb = new StringBuilder();
        if (includeCurrentSpeed && doAnnounceSpeed)
            sb.append("Speed ").append(Math.round(speed)).append(". ");
        if (doAnnounceAvg)
            sb.append("Average ").append(Math.round(avg)).append(". ");
        if (doAnnounceDistance)
            sb.append("Distance ").append(formatDistance(dist)).append(". ");
        if (sb.length() > 0) speak(sb.toString().trim());
    }

    // ── Torch listener ────────────────────────────────────────────────────────
   private long lastTorchActionMs = 0;
private static final long TORCH_DEBOUNCE_MS = 1000;

private void registerTorchListener() {
    cameraManager = (CameraManager) getSystemService(CAMERA_SERVICE);
    torchCallback = new CameraManager.TorchCallback() {
        @Override
        public void onTorchModeChanged(@NonNull String cameraId, boolean enabled) {
            if (!enabled || !tracking) return;
            long now = System.currentTimeMillis();
            if (now - lastTorchActionMs < TORCH_DEBOUNCE_MS) return;
            lastTorchActionMs = now;
            announceNow(true);
            try {
                cameraManager.setTorchMode(cameraId, false);
            } catch (CameraAccessException e) {
                Log.w(TAG, "Could not extinguish torch: " + e.getMessage());
            }
        }
    };
    // Задержка чтобы не поймать initial state при регистрации
    handler.postDelayed(
        () -> cameraManager.registerTorchCallback(torchCallback, handler),
        500
    );
}
    private void unregisterTorchListener() {
        if (cameraManager != null && torchCallback != null) {
            cameraManager.unregisterTorchCallback(torchCallback);
            torchCallback = null;
        }
    }

    // ── Location ──────────────────────────────────────────────────────────────
    private void requestLocationUpdates() {
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        try {
            locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    1000L, 0f,
                    locationListener,
                    Looper.getMainLooper());
        } catch (SecurityException e) {
            Log.e(TAG, "Location permission missing", e);
        }
    }

    private final LocationListener locationListener = new LocationListener() {
        @Override
        public void onLocationChanged(@NonNull Location location) {
            if (!tracking || !location.hasSpeed()) return;

            long  now   = System.currentTimeMillis();
            float speed = calculator.update(location.getSpeed(), now);
            float avg   = calculator.getAverageSpeed(avgPeriodMin);
            float dist  = calculator.getTotalDistanceKm();

            if (listener != null) listener.onSpeedUpdate(speed, avg, dist);

            if (doAnnounceSpeed
                    && calculator.shouldAnnounceSpeed(speedThresholdKmh, speedDebounceMs)) {
                speak("Speed " + Math.round(speed));
            }

            updateNotification(speed, avg, dist);
        }

        @Override
        public void onProviderDisabled(@NonNull String provider) {
            speak("GPS disabled");
        }
    };

    // ── Average announcement timer ────────────────────────────────────────────
    private void scheduleAvgAnnouncement() {
        avgRunnable = () -> {
            if (tracking) {
                announceNow(false);
                scheduleAvgAnnouncement();
            }
        };
        handler.postDelayed(avgRunnable, (long) avgIntervalMin * 60_000L);
    }

    // ── TTS ───────────────────────────────────────────────────────────────────
    private void initTts() {
        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                int res = tts.setLanguage(Locale.ENGLISH);
                ttsReady = (res != TextToSpeech.LANG_MISSING_DATA
                         && res != TextToSpeech.LANG_NOT_SUPPORTED);
            }
        });
    }

    private void speak(String text) {
        if (!ttsReady || tts == null) return;
        Bundle params = new Bundle();
        params.putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_ALARM);
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, "velo");
    }

    // ── Notification ──────────────────────────────────────────────────────────
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "VeloSpeedometer", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("Cycling speed tracking");
            ch.setShowBadge(false);
            getSystemService(NotificationManager.class).createNotificationChannel(ch);
        }
    }

    private Notification buildNotification(float speed, float avg, float dist) {
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;

        PendingIntent openPi = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class)
                        .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), flags);

        PendingIntent announcePi = PendingIntent.getService(this, 1,
                new Intent(this, SpeedometerService.class).setAction(ACTION_ANNOUNCE), flags);

        PendingIntent stopPi = PendingIntent.getService(this, 2,
                new Intent(this, SpeedometerService.class).setAction(ACTION_STOP), flags);

        String content = tracking
                ? String.format(Locale.US, "%.0f km/h  |  avg %.0f  |  %.2f km",
                                speed, avg, dist)
                : "Tap to open";

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("🚴 SpeakingBike")
                .setContentText(content)
                .setSmallIcon(android.R.drawable.ic_menu_directions)
                .setContentIntent(openPi)
                .addAction(android.R.drawable.ic_lock_silent_mode_off, "📢 Announce", announcePi)
                .addAction(android.R.drawable.ic_media_pause, "■ Stop", stopPi)
                .setOngoing(true)
                .setSilent(true)
                .build();
    }

    private void updateNotification(float speed, float avg, float dist) {
        NotificationManager nm =
                (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.notify(NOTIF_ID, buildNotification(speed, avg, dist));
    }

    // ── Settings ──────────────────────────────────────────────────────────────
    private void loadSettings() {
        SharedPreferences p = getSharedPreferences("settings", MODE_PRIVATE);
        speedThresholdKmh  = p.getFloat("speed_threshold", 5f);
        speedDebounceMs    = p.getInt("speed_debounce", 10) * 1000L;
        avgPeriodMin       = p.getInt("avg_period", 10);
        avgIntervalMin     = p.getInt("avg_interval", 2);
        doAnnounceSpeed    = p.getBoolean("announce_speed", true);
        doAnnounceAvg      = p.getBoolean("announce_avg", true);
        doAnnounceDistance = p.getBoolean("announce_distance", true);
        calculator.setAlpha(p.getFloat("ema_alpha", 0.3f));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────
    private String formatDistance(float km) {
        if (km < 1f)
            return String.format(Locale.US, "%.0f meters", km * 1000);
        return String.format(Locale.US, "%.1f kilometers", km);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (tracking) stopTracking();
        if (tts != null) { tts.stop(); tts.shutdown(); }
        handler.removeCallbacksAndMessages(null);
    }
}
