package com.velo.speedometer;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.IBinder;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.util.Locale;

public class MainActivity extends AppCompatActivity
        implements SpeedometerService.SpeedListener {

    private static final int PERM_REQUEST = 100;

    private TextView tvSpeed;
    private TextView tvStats;
    private Button   btnStartStop;

    private SpeedometerService service;
    private boolean bound = false;

    // Volume key double-press detection
    // Vol DOWN double = announce now
    // Vol UP   double = start / stop
    private long lastVolDown = 0;
    private long lastVolUp   = 0;
    private static final long DOUBLE_PRESS_WINDOW_MS = 500;

    // ── Lifecycle ─────────────────────────────────────────────────────────────
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Keep screen on while app is visible
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        tvSpeed      = findViewById(R.id.tvSpeed);
        tvStats      = findViewById(R.id.tvStats);
        btnStartStop = findViewById(R.id.btnStartStop);

        btnStartStop.setOnClickListener(v -> toggleTracking());

        findViewById(R.id.btnSettings).setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));

        checkLocationPermission();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!bound && hasLocationPermission()) bindToService();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (bound) {
            service.setSpeedListener(null);
            unbindService(serviceConnection);
            bound = false;
        }
    }

    // ── Service connection ────────────────────────────────────────────────────
    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder b) {
            service = ((SpeedometerService.LocalBinder) b).getService();
            service.setSpeedListener(MainActivity.this);
            bound = true;
            refreshButton();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            bound = false;
        }
    };

    private void bindToService() {
        Intent intent = new Intent(this, SpeedometerService.class);
        startService(intent);           // keep alive after unbind
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
    }

    // ── SpeedListener callback (called from service on GPS update) ────────────
    @Override
    public void onSpeedUpdate(float speedKmh, float avgKmh, float distanceKm) {
        runOnUiThread(() -> {
            tvSpeed.setText(String.valueOf(Math.round(speedKmh)));
            tvStats.setText(String.format(Locale.US,
                    "Avg  %.0f km/h     %.2f km", avgKmh, distanceKm));
            refreshButton();
        });
    }

    // ── Controls ──────────────────────────────────────────────────────────────
    private void toggleTracking() {
        if (!bound) return;
        if (service.isTracking()) service.stopTracking();
        else service.startTracking();
        refreshButton();
    }

    private void refreshButton() {
        if (!bound) return;
        boolean running = service.isTracking();
        btnStartStop.setText(running ? R.string.btn_stop : R.string.btn_start);
        btnStartStop.setBackgroundTintList(ContextCompat.getColorStateList(this,
                running ? R.color.colorButtonStop : R.color.colorButtonStart));
    }

    // ── Volume key interception (works when Activity is in foreground) ─────────
    // For background control use the notification action buttons.
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (!bound) return super.dispatchKeyEvent(event);
        int code = event.getKeyCode();
        if (code != KeyEvent.KEYCODE_VOLUME_DOWN && code != KeyEvent.KEYCODE_VOLUME_UP)
            return super.dispatchKeyEvent(event);

        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            long t = event.getEventTime();
            if (code == KeyEvent.KEYCODE_VOLUME_DOWN) {
                if (t - lastVolDown < DOUBLE_PRESS_WINDOW_MS) service.announceNow(true);
                lastVolDown = t;
            } else {
                if (t - lastVolUp < DOUBLE_PRESS_WINDOW_MS) toggleTracking();
                lastVolUp = t;
            }
        }
        return true; // consume – prevents volume change
    }

    // ── Permissions ───────────────────────────────────────────────────────────
    private boolean hasLocationPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void checkLocationPermission() {
        if (hasLocationPermission()) {
            bindToService();
        } else {
            ActivityCompat.requestPermissions(this,
                    new String[]{ Manifest.permission.ACCESS_FINE_LOCATION }, PERM_REQUEST);
        }
    }

    @Override
    public void onRequestPermissionsResult(int code,
            @NonNull String[] perms, @NonNull int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code == PERM_REQUEST
                && results.length > 0
                && results[0] == PackageManager.PERMISSION_GRANTED) {
            bindToService();
        }
    }
}
