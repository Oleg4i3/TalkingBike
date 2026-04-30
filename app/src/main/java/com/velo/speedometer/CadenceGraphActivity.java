package com.velo.speedometer;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class CadenceGraphActivity extends AppCompatActivity {

    private static final long REFRESH_MS = 100L; // 10 fps chart refresh

    // ── Views ─────────────────────────────────────────────────────────────────
    private AccelChartView chart;
    private TextView        tvRpm;
    private TextView        tvSamples;
    private MaterialButton  btnLive;
    private MaterialButton  btnSaveSensor;
    private MaterialButton  btnSaveRide;

    // ── Service binding ───────────────────────────────────────────────────────
    private SpeedometerService service;
    private boolean            bound = false;
    private CadenceDetector    detector;

    private final ServiceConnection conn = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName n, IBinder b) {
            service  = ((SpeedometerService.LocalBinder) b).getService();
            detector = service.getCadenceDetector();
            bound    = true;
            // Wire all datasets immediately — before first tick()
            wireChartData();
        }
        @Override public void onServiceDisconnected(ComponentName n) {
            bound    = false;
            detector = null;
        }
    };

    /** Connect chart to all live data sources. Safe to call repeatedly. */
    private void wireChartData() {
        if (detector != null) {
            chart.setData(detector.getRawSamples());
            chart.setCadenceData(detector.getCadenceHistory());
        }
        if (service != null) {
            chart.setSpeedData(service.getSpeedHistory());
            chart.setHrData(service.getHrHistory());
        }
    }

    // ── Refresh ───────────────────────────────────────────────────────────────
    private final Handler  handler = new Handler(Looper.getMainLooper());
    private final Runnable refreshRunnable = new Runnable() {
        @Override public void run() {
            refreshChart();
            handler.postDelayed(this, REFRESH_MS);
        }
    };

    // ── Ride-stopped broadcast ────────────────────────────────────────────────
    private final BroadcastReceiver stoppedReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context ctx, Intent intent) {
            if (SpeedometerService.ACTION_RIDE_STOPPED.equals(intent.getAction())) {
                offerSave();
            }
        }
    };

    // ── CSV save launchers ────────────────────────────────────────────────────
    private final ActivityResultLauncher<String> saveSensorLauncher =
            registerForActivityResult(new ActivityResultContracts.CreateDocument("text/csv"),
                    this::onSaveSensorUri);

    private final ActivityResultLauncher<String> saveRideLauncher =
            registerForActivityResult(new ActivityResultContracts.CreateDocument("text/csv"),
                    this::onSaveRideUri);

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_cadence_graph);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(R.string.title_cadence_graph);
        }

        chart      = findViewById(R.id.accelChart);
        tvRpm      = findViewById(R.id.tvGraphRpm);
        tvSamples  = findViewById(R.id.tvSampleCount);
        btnLive    = findViewById(R.id.btnScrollEnd);
        btnSaveSensor = findViewById(R.id.btnSaveSensor);
        btnSaveRide   = findViewById(R.id.btnSaveRide);

        btnLive.setOnClickListener(v -> chart.scrollToEnd());
        String ts2 = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        if (btnSaveSensor != null)
            btnSaveSensor.setOnClickListener(v -> saveSensorLauncher.launch("sensor_" + ts2 + ".csv"));
        if (btnSaveRide != null)
            btnSaveRide.setOnClickListener(v -> saveRideLauncher.launch("ride_" + ts2 + ".csv"));
    }

    @Override
    protected void onStart() {
        super.onStart();
        // Bind to service (it may already be running)
        Intent i = new Intent(this, SpeedometerService.class);
        startService(i);
        bindService(i, conn, Context.BIND_AUTO_CREATE);

        // Android 14+ requires explicit exported flag for non-system receivers
        IntentFilter f = new IntentFilter(SpeedometerService.ACTION_RIDE_STOPPED);
        ContextCompat.registerReceiver(this, stoppedReceiver, f,
                ContextCompat.RECEIVER_NOT_EXPORTED);

        // Start refresh loop
        handler.post(refreshRunnable);
    }

    @Override
    protected void onStop() {
        super.onStop();
        handler.removeCallbacks(refreshRunnable);
        try { unregisterReceiver(stoppedReceiver); } catch (Exception ignored) {}
        if (bound) {
            unbindService(conn);
            bound    = false;
            detector = null;
        }
    }

    @Override public boolean onSupportNavigateUp() { finish(); return true; }

    // ── Chart refresh ─────────────────────────────────────────────────────────

    private void refreshChart() {
        if (!bound || service == null) return;
        detector = service.getCadenceDetector();
        if (detector == null) {
            tvRpm.setText(getString(R.string.cadence_no_signal));
            tvSamples.setText("0:00  (0 samples)");
            return;
        }
        // Always ensure chart is wired (handles ride restart mid-session)
        wireChartData();

        // Update cadence label using the full Result
        CadenceDetector.Result r = detector.getLastResult();
        if (r == null || r == CadenceDetector.Result.EMPTY) {
            tvRpm.setText(getString(R.string.cadence_no_signal));
        } else if (r.stable && r.rpm > 0) {
            tvRpm.setText(Math.round(r.rpm) + " RPM");
        } else if (r.stableAvgRpm > 0) {
            tvRpm.setText("~" + Math.round(r.stableAvgRpm) + " RPM");
        } else if (r.rpm > 0) {
            tvRpm.setText("? RPM");
        } else {
            tvRpm.setText(getString(R.string.cadence_no_signal));
        }

        // Update sample count label
        int count;
        synchronized (detector.getRawSamples()) {
            count = detector.getRawSamples().size();
        }
        tvSamples.setText(formatDuration(count) + "  (" + count + " samples)");

        // Tick the chart (reads latest time, triggers invalidate)
        chart.tick();

        // "Live" button: highlight when NOT auto-scrolling
        btnLive.setAlpha(chart.isAutoScroll() ? 0.4f : 1f);
    }

    // ── Save ──────────────────────────────────────────────────────────────────

    private void offerSave() {
        // Both buttons are already wired in onCreate; this dialog is now unused.
        // Kept for compatibility — just launch sensor save as default.
        String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        saveSensorLauncher.launch("sensor_" + ts + ".csv");
    }


    // ── Sensor CSV: time + raw sensor magnitude ─────────────────────────────
    private void onSaveSensorUri(Uri uri) {
        if (uri == null) return;
        // detector may have been wired after ride started — get fresh reference
        CadenceDetector det = (service != null) ? service.getCadenceDetector() : detector;
        if (det == null) {
            Toast.makeText(this, getString(R.string.csv_saved_error), Toast.LENGTH_LONG).show();
            return;
        }
        List<float[]> src = det.getRawSamples();
        if (src == null || src.isEmpty()) {
            Toast.makeText(this, "No sensor data recorded yet", Toast.LENGTH_LONG).show();
            return;
        }
        final float[][] snap;
        synchronized (src) { snap = src.toArray(new float[0][]); }
        new Thread(() -> {
            boolean ok = writeSensorCsv(uri, snap);
            runOnUiThread(() -> Toast.makeText(this,
                    ok ? getString(R.string.csv_saved_ok, snap.length)
                       : getString(R.string.csv_saved_error),
                    Toast.LENGTH_LONG).show());
        }).start();
    }

    private boolean writeSensorCsv(Uri uri, float[][] data) {
        try (OutputStream os = getContentResolver().openOutputStream(uri);
             java.io.BufferedWriter bw = new java.io.BufferedWriter(
                     new java.io.OutputStreamWriter(os,
                             java.nio.charset.StandardCharsets.UTF_8))) {
            bw.write("time_sec,sensor_mag");
            bw.newLine();
            for (float[] row : data) {
                // Round to 1 decimal second, 4 decimal sensor
                bw.write(String.format(Locale.US, "%.1f,%.4f", row[0], row[1]));
                bw.newLine();
            }
            bw.flush();
            return true;
        } catch (IOException e) { return false; }
    }

    // ── Ride CSV: speed, cadence, HR (one row per second approx) ─────────────
    private void onSaveRideUri(Uri uri) {
        if (uri == null) return;

        // Always get the CURRENT detector from service — it may have been
        // recreated by reloadSettings(), making the local `detector` field stale.
        CadenceDetector freshDet = (service != null)
                ? service.getCadenceDetector() : detector;

        final float[][] cadence, speed, hr;
        if (freshDet != null) {
            List<float[]> c = freshDet.getCadenceHistory();
            if (c != null && !c.isEmpty()) {
                synchronized (c) { cadence = c.toArray(new float[0][]); }
            } else { cadence = new float[0][]; }
        } else { cadence = new float[0][]; }
        if (service != null) {
            List<float[]> sp = service.getSpeedHistory();
            List<float[]> h  = service.getHrHistory();
            synchronized (sp) { speed = sp.toArray(new float[0][]); }
            synchronized (h)  { hr    = h.toArray(new float[0][]); }
        } else { speed = new float[0][]; hr = new float[0][]; }

        new Thread(() -> {
            boolean ok = writeRideCsv(uri, cadence, speed, hr);
            runOnUiThread(() -> Toast.makeText(this,
                    ok ? "Ride data saved" : getString(R.string.csv_saved_error),
                    Toast.LENGTH_LONG).show());
        }).start();
    }

    private boolean writeRideCsv(Uri uri,
                                  float[][] cadence, float[][] speed, float[][] hr) {
        // Build union of timestamps from all three streams, sorted
        java.util.TreeSet<Float> tsSet = new java.util.TreeSet<>();
        for (float[] r : cadence) tsSet.add(r[0]);
        for (float[] r : speed)   tsSet.add(r[0]);
        for (float[] r : hr)      tsSet.add(r[0]);
        if (tsSet.isEmpty()) return false;

        try (OutputStream os = getContentResolver().openOutputStream(uri);
             java.io.BufferedWriter bw = new java.io.BufferedWriter(
                     new java.io.OutputStreamWriter(os,
                             java.nio.charset.StandardCharsets.UTF_8))) {
            bw.write("time_sec,speed_kmh,cadence_rpm,cadence_stable,hr_bpm");
            bw.newLine();

            int ic = 0, is2 = 0, ih = 0;
            float rpm = 0, stable = 0, spd = 0, hrBpm = 0;

            for (float t : tsSet) {
                while (ic  < cadence.length && cadence[ic][0]  <= t) { rpm = cadence[ic][1]; stable = cadence[ic][2]; ic++; }
                while (is2 < speed.length   && speed[is2][0]   <= t) { spd = speed[is2][1]; is2++; }
                while (ih  < hr.length      && hr[ih][0]        <= t) { hrBpm = hr[ih][1]; ih++; }
                bw.write(String.format(Locale.US, "%.1f,%.2f,%.1f,%d,%d",
                        t, spd, rpm, (int) stable, (int) hrBpm));
                bw.newLine();
            }
            bw.flush();
            return true;
        } catch (IOException e) { return false; }
    }


    // ── Formatters ────────────────────────────────────────────────────────────

    /** Converts sample count to approximate ride duration string. */
    private String formatDuration(int samples) {
        int totalSec = samples / 50; // ~50 Hz
        int m = totalSec / 60, s = totalSec % 60;
        return String.format(Locale.US, "%d:%02d", m, s);
    }
}
