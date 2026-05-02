package com.velo.speedometer;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;

import java.util.List;
import java.util.Locale;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.CheckBox;
import android.widget.RadioGroup;
import android.widget.RadioButton;

public class MainActivity extends AppCompatActivity
        implements SpeedometerService.SpeedListener {

    private static final int PERM       = 100;
    private static final int PERM_NOTIF = 101;

    private TextView       tvSpeed, tvStats, tvPauseLabel, tvCadence;
    private MaterialButton btnStart, btnPause, btnStop;
    private LinearLayout   llRunning;

    private SpeedometerService service;
    private boolean bound = false;

    private long lastVolDown = 0, lastVolUp = 0;

    // Metronome UI widgets
    private TextView    tvMetBpm;
    private SeekBar     sbMetBpm;
    private CheckBox    cbMetSoundStrong, cbMetSoundWeak, cbMetVibStrong, cbMetVibWeak;
    private RadioGroup  rgMetSound;
    private com.google.android.material.button.MaterialButton btnMetronome;
    private com.google.android.material.button.MaterialButton btnExit;
    private CheckBox    cbMetVolumeAdaptive;
    private TextView    tvHr;
    private TextView    tvUnit;
    private boolean     metronomeUiReady = false;
    private static final long DBL = 500;

    // FIX #6: battery level display
    private int lastHrBattery = -1;

    private final android.os.Handler cadenceHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable cadenceRunnable = new Runnable() {
        @Override public void run() {
            updateCadenceDisplay();
            cadenceHandler.postDelayed(this, 500);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        tvSpeed      = findViewById(R.id.tvSpeed);
        tvStats      = findViewById(R.id.tvStats);
        tvPauseLabel = findViewById(R.id.tvPauseLabel);
        tvCadence    = findViewById(R.id.tvCadence);
        btnStart     = findViewById(R.id.btnStart);
        btnPause     = findViewById(R.id.btnPause);
        btnStop      = findViewById(R.id.btnStop);
        llRunning    = findViewById(R.id.llRunningButtons);

        // Metronome widgets
        tvMetBpm         = findViewById(R.id.tvMetBpm);
        sbMetBpm         = findViewById(R.id.sbMetBpm);
        cbMetSoundStrong = findViewById(R.id.cbMetSoundStrong);
        cbMetSoundWeak   = findViewById(R.id.cbMetSoundWeak);
        cbMetVibStrong   = findViewById(R.id.cbMetVibStrong);
        cbMetVibWeak     = findViewById(R.id.cbMetVibWeak);
        rgMetSound       = findViewById(R.id.rgMetSound);
        btnMetronome     = findViewById(R.id.btnMetronome);
        btnExit          = findViewById(R.id.btnExit);
        if (btnExit != null) btnExit.setOnClickListener(v -> {
            if (bound) { service.exitApp(); }
            else { finishAndRemoveTask(); }
        });
        tvHr             = findViewById(R.id.tvHr);
        tvUnit           = findViewById(R.id.tvUnit);

        sbMetBpm.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean user) {
                int bpm = p + 40;
                tvMetBpm.setText(bpm + " BPM");
                if (bound && user) service.setMetronomeBpm(bpm);
            }
            @Override public void onStartTrackingTouch(SeekBar sb) {}
            @Override public void onStopTrackingTouch(SeekBar sb) {}
        });

        rgMetSound.setOnCheckedChangeListener((g, id) -> pushMetParams());
        cbMetSoundStrong.setOnCheckedChangeListener((b, c) -> pushMetParams());
        cbMetSoundWeak  .setOnCheckedChangeListener((b, c) -> pushMetParams());
        cbMetVibStrong  .setOnCheckedChangeListener((b, c) -> pushMetParams());
        cbMetVibWeak    .setOnCheckedChangeListener((b, c) -> pushMetParams());

        btnMetronome.setOnClickListener(v -> { if (bound) service.toggleMetronome(); });

        // Adaptive volume widgets
        cbMetVolumeAdaptive = findViewById(R.id.cbMetVolumeAdaptive);
        if (cbMetVolumeAdaptive != null)
            cbMetVolumeAdaptive.setOnClickListener(v -> pushMetVolParams());

        metronomeUiReady = true;

        btnStart.setOnClickListener(v -> { if (bound) service.startTracking(); });
        btnPause.setOnClickListener(v -> { if (bound) service.togglePause(); });
        btnStop .setOnClickListener(v -> { if (bound) service.stopTracking(); });

        findViewById(R.id.btnSettings).setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));
        findViewById(R.id.btnCadenceGraph).setOnClickListener(v ->
                startActivity(new Intent(this, CadenceGraphActivity.class)));

        // FIX #5: ride log viewer button
        View btnLog = findViewById(R.id.btnRideLog);
        if (btnLog != null) btnLog.setOnClickListener(v -> showRideLog());

        checkPermission();
    }

    @Override protected void onResume() {
        super.onResume();
        if (!bound && hasPermission()) bindService();
        cadenceHandler.post(cadenceRunnable);
    }

    @Override protected void onPause() {
        super.onPause();
        cadenceHandler.removeCallbacks(cadenceRunnable);
        if (bound) {
            service.setSpeedListener(null);
            unbindService(conn);
            bound = false;
        }
    }

    private final ServiceConnection conn = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName n, IBinder b) {
            service = ((SpeedometerService.LocalBinder) b).getService();
            service.setSpeedListener(MainActivity.this);
            bound = true;
            refreshUI(service.getState());
            syncMetronomeUi();
            // Restore HR visibility after bind
            lastHrBattery = service.getLastHrBattery();
            onHrChanged(service.getLastHrBpm(), service.getLastHrBpm() > 0);
        }
        @Override public void onServiceDisconnected(ComponentName n) { bound = false; }
    };

    private void bindService() {
        Intent i = new Intent(this, SpeedometerService.class);
        startService(i);
        bindService(i, conn, Context.BIND_AUTO_CREATE);
    }

    @Override
    public void onSpeedUpdate(float speedKmh, float avgKmh, float distKm) {
        runOnUiThread(() -> {
            if (tvUnit != null) tvUnit.setText("km/h");
            tvSpeed.setText(String.valueOf(Math.round(speedKmh)));
            tvStats.setText(String.format(Locale.US,
                    "Avg  %.0f km/h     %.2f km", avgKmh, distKm));
        });
    }

    private void pushMetVolParams() {
        if (!bound || !metronomeUiReady) return;
        boolean on  = cbMetVolumeAdaptive != null && cbMetVolumeAdaptive.isChecked();
        int pct = getSharedPreferences("settings", MODE_PRIVATE)
                .getInt("metro_cad_min_pct", 80);
        service.setMetVolumeAdaptive(on, pct);
    }

    private void pushMetParams() {
        if (!bound || !metronomeUiReady) return;
        int soundType = 0;
        int cid = rgMetSound.getCheckedRadioButtonId();
        if (cid == R.id.rbMetClick) soundType = 1;
        else if (cid == R.id.rbMetBeep) soundType = 2;
        service.setMetronomeParams(soundType,
                cbMetSoundStrong.isChecked(), cbMetSoundWeak.isChecked(),
                cbMetVibStrong.isChecked(),   cbMetVibWeak.isChecked());
    }

    private void syncMetronomeUi() {
        if (!bound || !metronomeUiReady) return;
        int bpm = service.getMetronomeBpm();
        sbMetBpm.setProgress(bpm - 40);
        tvMetBpm.setText(bpm + " BPM");
        int st = service.getMetSoundType();
        if (st == 1) rgMetSound.check(R.id.rbMetClick);
        else if (st == 2) rgMetSound.check(R.id.rbMetBeep);
        else rgMetSound.check(R.id.rbMetMaracas);
        cbMetSoundStrong.setChecked(service.isMetSoundStrong());
        cbMetSoundWeak  .setChecked(service.isMetSoundWeak());
        cbMetVibStrong  .setChecked(service.isMetVibStrong());
        cbMetVibWeak    .setChecked(service.isMetVibWeak());
        refreshMetronomeButton(service.isMetronomePlaying());
        if (cbMetVolumeAdaptive != null) cbMetVolumeAdaptive.setChecked(service.isMetVolumeAdaptive());
    }

    public void onMetMinus(android.view.View v) {
        if (!bound) return;
        int bpm = Math.max(40, service.getMetronomeBpm() - 1);
        service.setMetronomeBpm(bpm);
        sbMetBpm.setProgress(bpm - 40);
        tvMetBpm.setText(bpm + " BPM");
    }

    public void onMetPlus(android.view.View v) {
        if (!bound) return;
        int bpm = Math.min(180, service.getMetronomeBpm() + 1);
        service.setMetronomeBpm(bpm);
        sbMetBpm.setProgress(bpm - 40);
        tvMetBpm.setText(bpm + " BPM");
    }

    private void refreshMetronomeButton(boolean playing) {
        if (btnMetronome == null) return;
        if (playing) {
            btnMetronome.setText("⏹  METRONOME OFF");
            btnMetronome.setBackgroundTintList(
                    androidx.core.content.ContextCompat.getColorStateList(this, R.color.colorButtonStop));
        } else {
            btnMetronome.setText("▶  METRONOME ON");
            btnMetronome.setBackgroundTintList(
                    androidx.core.content.ContextCompat.getColorStateList(this, R.color.colorButtonStart));
        }
    }

    /**
     * FIX #6: shows HR bpm AND battery level when available.
     * Format: "♥ 72  🔋43%"  or just "♥ 72" if battery not read yet.
     */
    @Override
    public void onHrChanged(int bpm, boolean connected) {
        runOnUiThread(() -> {
            if (tvHr == null) return;
            boolean showHr = bound && service != null
                    && getSharedPreferences("settings", MODE_PRIVATE)
                       .getBoolean("announce_hr", false);
            tvHr.setVisibility(showHr ? View.VISIBLE : View.GONE);
            if (!connected || bpm <= 0) {
                tvHr.setText("♥ --");
            } else {
                String batStr = (lastHrBattery >= 0) ? "  🔋" + lastHrBattery + "%" : "";
                tvHr.setText("♥ " + bpm + batStr);
            }
        });
    }

    /** FIX #6: called when battery level arrives from HeartRateMonitor */
    @Override
    public void onHrBattery(int percent) {
        lastHrBattery = percent;
        // Refresh HR display to include new battery info
        if (bound && service != null) {
            onHrChanged(service.getLastHrBpm(), service.getLastHrBpm() > 0);
        }
    }

    @Override
    public void onMetronomeChanged(boolean playing) {
        runOnUiThread(() -> refreshMetronomeButton(playing));
    }

    @Override
    public void onRideFinished(float avgKmh, float distKm, String timeStr) {
        runOnUiThread(() ->
            tvStats.setText(String.format(Locale.US,
                "Avg  %.0f km/h     %.2f km     %s", avgKmh, distKm, timeStr)));
    }

    private void updateCadenceDisplay() {
        if (!bound || tvCadence == null) return;
        boolean showCad = getSharedPreferences("settings", MODE_PRIVATE)
                .getBoolean("announce_cadence", false);
        tvCadence.setVisibility(showCad ? View.VISIBLE : View.GONE);
        if (!showCad) return;
        CadenceDetector.Result r = service.getCadenceDetector().getLastResult();
        if (r == null || r.rpm == 0) {
            tvCadence.setText("~ rpm");
        } else {
            tvCadence.setText(String.format(Locale.US, "%.0f rpm", r.rpm));
        }
    }

    @Override
    public void onStateChanged(SpeedometerService.TrackState state) {
        runOnUiThread(() -> refreshUI(state));
    }

    private void refreshUI(SpeedometerService.TrackState state) {
        switch (state) {
            case STOPPED:
                btnStart.setVisibility(View.VISIBLE);
                llRunning.setVisibility(View.GONE);
                tvPauseLabel.setVisibility(View.GONE);
                tvSpeed.setText("\uD83D\uDEB4");
                if (tvUnit != null)
                    tvUnit.setText("\uD83C\uDF4C  \uD83D\uDEB0  \uD83D\uDCA7");
                break;
            case RUNNING:
                btnStart.setVisibility(View.GONE);
                llRunning.setVisibility(View.VISIBLE);
                tvPauseLabel.setVisibility(View.GONE);
                if (tvUnit != null) tvUnit.setText("km/h");
                btnPause.setText(R.string.btn_pause);
                btnPause.setBackgroundTintList(ContextCompat.getColorStateList(
                        this, R.color.colorButtonPause));
                break;
            case PAUSED:
                btnStart.setVisibility(View.GONE);
                llRunning.setVisibility(View.VISIBLE);
                tvPauseLabel.setVisibility(View.VISIBLE);
                btnPause.setText(R.string.btn_resume);
                btnPause.setBackgroundTintList(ContextCompat.getColorStateList(
                        this, R.color.colorButtonStart));
                break;
        }
    }

    // ── FIX #5: Ride Log viewer ───────────────────────────────────────────────

    private void showRideLog() {
        if (!bound || service == null) return;
        List<String[]> rows = service.readRideLog();

        if (rows.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle("Ride Log")
                    .setMessage("No rides recorded yet.")
                    .setPositiveButton("OK", null)
                    .show();
            return;
        }

        // Compute summary
        double totalDistKm  = 0;
        double totalTimeSec = 0;
        double totalAvgKmh  = 0;
        int    count        = rows.size();
        for (String[] r : rows) {
            try { totalDistKm  += Double.parseDouble(r[1]); } catch (Exception ignored) {}
            try { totalTimeSec += Double.parseDouble(r[2]); } catch (Exception ignored) {}
            try { totalAvgKmh  += Double.parseDouble(r[3]); } catch (Exception ignored) {}
        }
        double avgDistKm   = totalDistKm  / count;
        double avgTimeSec  = totalTimeSec / count;
        double avgSpeedKmh = totalAvgKmh  / count;

        // Build recent rides text (last 20)
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.US,
                "═══ SUMMARY (%d rides) ═══\n" +
                "Total distance:  %.1f km\n" +
                "Total time:      %s\n" +
                "Avg dist/ride:   %.1f km\n" +
                "Avg time/ride:   %s\n" +
                "Avg speed:       %.1f km/h\n\n" +
                "═══ RECENT RIDES ═══\n",
                count,
                totalDistKm,
                fmtSecDuration((long) totalTimeSec),
                avgDistKm,
                fmtSecDuration((long) avgTimeSec),
                avgSpeedKmh));

        int start = Math.max(0, rows.size() - 20);
        for (int i = rows.size() - 1; i >= start; i--) {
            String[] r = rows.get(i);
            try {
                double dist = Double.parseDouble(r[1]);
                long   sec  = (long) Double.parseDouble(r[2]);
                double spd  = Double.parseDouble(r[3]);
                sb.append(String.format(Locale.US,
                        "%s\n  %.2f km  %s  %.1f km/h\n",
                        r[0], dist, fmtSecDuration(sec), spd));
            } catch (Exception e) {
                sb.append(String.join(",", r)).append("\n");
            }
        }

        new AlertDialog.Builder(this)
                .setTitle("Ride Log")
                .setMessage(sb.toString())
                .setPositiveButton("Close", null)
                .setNeutralButton("Clear log", (d, w) -> confirmClearLog())
                .show();
    }

    private void confirmClearLog() {
        new AlertDialog.Builder(this)
                .setTitle("Clear ride log?")
                .setMessage("This will delete all recorded rides. This cannot be undone.")
                .setPositiveButton("Clear", (d, w) -> {
                    if (bound && service != null) service.clearRideLog();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** Format total seconds to h:mm or mm:ss for display (not TTS). */
    private static String fmtSecDuration(long totalSec) {
        long h = totalSec / 3600;
        long m = (totalSec % 3600) / 60;
        long s = totalSec % 60;
        if (h > 0) return String.format(Locale.US, "%dh %02dm", h, m);
        return String.format(Locale.US, "%dm %02ds", m, s);
    }

    // ── Volume key handling ───────────────────────────────────────────────────

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (!bound) return super.dispatchKeyEvent(e);
        int code = e.getKeyCode();
        if (code != KeyEvent.KEYCODE_VOLUME_DOWN && code != KeyEvent.KEYCODE_VOLUME_UP)
            return super.dispatchKeyEvent(e);
        if (e.getAction() == KeyEvent.ACTION_DOWN) {
            long t = e.getEventTime();
            if (code == KeyEvent.KEYCODE_VOLUME_DOWN) {
                if (t - lastVolDown < DBL) service.announceNow(true);
                lastVolDown = t;
            } else { // VOLUME_UP
                if (t - lastVolUp < DBL) {
                    service.togglePause();
                } else {
                    service.toggleMetronome();
                }
                lastVolUp = t;
            }
        }
        return true;
    }

    // ── Permissions ───────────────────────────────────────────────────────────

    private boolean hasPermission() {
        return ContextCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private void checkPermission() {
        if (hasPermission()) {
            bindService();
            requestNotifPermission();
            requestStoragePermission();
        } else {
            ActivityCompat.requestPermissions(this,
                    new String[]{ Manifest.permission.ACCESS_FINE_LOCATION }, PERM);
        }
    }

    /** WRITE_EXTERNAL_STORAGE нужен только на API 24-28 для лога в Downloads. */
    private void requestStoragePermission() {
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
                ContextCompat.checkSelfPermission(this,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{ Manifest.permission.WRITE_EXTERNAL_STORAGE }, 102);
        }
    }

    private void requestNotifPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this,
                    Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{ Manifest.permission.POST_NOTIFICATIONS },
                        PERM_NOTIF);
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int code,
            @NonNull String[] p, @NonNull int[] r) {
        super.onRequestPermissionsResult(code, p, r);
        if (code == PERM && r.length > 0
                && r[0] == PackageManager.PERMISSION_GRANTED) {
            bindService();
            requestNotifPermission();
            requestStoragePermission();
        }
        // 102 = WRITE_EXTERNAL_STORAGE — сервис использует разрешение сам
    }
}
