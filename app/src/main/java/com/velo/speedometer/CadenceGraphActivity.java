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
    private MaterialButton  btnSaveCsv;

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

    // ── CSV save launcher ─────────────────────────────────────────────────────
    private final ActivityResultLauncher<String> saveLauncher =
            registerForActivityResult(new ActivityResultContracts.CreateDocument("text/csv"),
                    this::onSaveUriPicked);

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
        btnSaveCsv = findViewById(R.id.btnSaveCsv);

        btnLive.setOnClickListener(v -> chart.scrollToEnd());
        btnSaveCsv.setOnClickListener(v -> launchSave());
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
        new AlertDialog.Builder(this)
                .setTitle(R.string.dialog_save_title)
                .setMessage(R.string.dialog_save_message)
                .setPositiveButton(R.string.dialog_save_yes, (d, w) -> launchSave())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void launchSave() {
        String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        saveLauncher.launch("cadence_" + ts + ".csv");
    }

    private void onSaveUriPicked(Uri uri) {
        if (uri == null) return;

        // Snapshot all streams on main thread, write on background
        final float[][] accel, cadence, speed, hr;
        if (detector != null) {
            List<float[]> s = detector.getRawSamples();
            List<float[]> c = detector.getCadenceHistory();
            synchronized (s) { accel   = s.toArray(new float[0][]); }
            synchronized (c) { cadence = c.toArray(new float[0][]); }
        } else { accel = new float[0][]; cadence = new float[0][]; }
        if (service != null) {
            List<float[]> sp = service.getSpeedHistory();
            List<float[]> h  = service.getHrHistory();
            synchronized (sp) { speed = sp.toArray(new float[0][]); }
            synchronized (h)  { hr    = h.toArray(new float[0][]); }
        } else { speed = new float[0][]; hr = new float[0][]; }

        int total = accel.length;
        new Thread(() -> {
            boolean ok = writeCsv(uri, accel, cadence, speed, hr);
            runOnUiThread(() -> {
                if (ok)
                    Toast.makeText(this,
                            getString(R.string.csv_saved_ok, total),
                            Toast.LENGTH_LONG).show();
                else
                    Toast.makeText(this, R.string.csv_saved_error,
                            Toast.LENGTH_LONG).show();
            });
        }).start();
    }

    /**
     * Writes a multi-stream CSV with columns:
     *   time_sec, gyro_mag (or accel_mag), cadence_rpm, cadence_stable,
     *   speed_kmh, hr_bpm
     *
     * Streams are merged on the time axis via a simple linear-time merge.
     * Each row gets the last known value for streams that don't have a
     * sample at exactly that time (forward-fill).
     */
    private boolean writeCsv(Uri uri,
                              float[][] accel, float[][] cadence,
                              float[][] speed, float[][] hr) {
        try (OutputStream os = getContentResolver().openOutputStream(uri);
             java.io.BufferedWriter bw = new java.io.BufferedWriter(
                     new java.io.OutputStreamWriter(os, java.nio.charset.StandardCharsets.UTF_8))) {

            bw.write("time_sec,sensor_mag,cadence_rpm,cadence_stable,speed_kmh,hr_bpm
");

            // Pointers into each stream
            int ia = 0, ic = 0, is2 = 0, ih = 0;
            // Last-known values (forward-fill)
            float mag = 0, rpm = 0, stable = 0, spd = 0, hrBpm = 0;

            // Total events = union of all timestamps; drive by accel (highest freq)
            // But also emit events from other streams even if no accel sample
            // Use a simple approach: iterate accel; at each accel point, advance
            // other streams up to that time.
            int total = accel.length;
            if (total == 0 && cadence.length == 0 && speed.length == 0 && hr.length == 0)
                return false;  // nothing to write

            // If no accel data, synthesize timestamps from other streams
            float[] times;
            if (total > 0) {
                times = new float[total];
                for (int i = 0; i < total; i++) times[i] = accel[i][0];
            } else {
                // collect all timestamps from other streams
                java.util.TreeSet<Float> ts = new java.util.TreeSet<>();
                for (float[] r : cadence) ts.add(r[0]);
                for (float[] r : speed)   ts.add(r[0]);
                for (float[] r : hr)      ts.add(r[0]);
                times = new float[ts.size()];
                int idx = 0; for (float t : ts) times[idx++] = t;
            }

            for (float t : times) {
                // advance accel
                if (ia < accel.length && accel[ia][0] <= t) {
                    mag = accel[ia][1]; ia++;
                }
                // advance cadence (multiple samples may share same second)
                while (ic < cadence.length && cadence[ic][0] <= t) {
                    rpm = cadence[ic][1]; stable = cadence[ic][2]; ic++;
                }
                // advance speed
                while (is2 < speed.length && speed[is2][0] <= t) {
                    spd = speed[is2][1]; is2++;
                }
                // advance hr
                while (ih < hr.length && hr[ih][0] <= t) {
                    hrBpm = hr[ih][1]; ih++;
                }
                bw.write(String.format(Locale.US,
                        "%.3f,%.4f,%.1f,%d,%.2f,%d
",
                        t, mag, rpm, (int)stable, spd, (int)hrBpm));
            }
            bw.flush();
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    // ── Formatters ────────────────────────────────────────────────────────────

    /** Converts sample count to approximate ride duration string. */
    private String formatDuration(int samples) {
        int totalSec = samples / 50; // ~50 Hz
        int m = totalSec / 60, s = totalSec % 60;
        return String.format(Locale.US, "%d:%02d", m, s);
    }
}
