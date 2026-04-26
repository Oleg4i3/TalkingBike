package com.velo.speedometer;

import android.content.Intent;
import android.Manifest;
import android.content.pm.PackageManager;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.CheckBox;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.slider.Slider;

public class SettingsActivity extends AppCompatActivity {

    // ── Sliders ───────────────────────────────────────────────────────────────
    private Slider slThreshold, slDebounce, slMaxInterval;
    private Slider slAvgPeriod, slAvgInterval;
    private Slider slAutoPauseSec, slScreenDebounce;
    private Slider slGain, slAlpha;

    // ── Value labels ──────────────────────────────────────────────────────────
    private TextView tvThreshold, tvDebounce, tvMaxInterval;
    private TextView tvAvgPeriod, tvAvgInterval;
    private TextView tvAutoPauseSec, tvScreenDebounce;
    private TextView tvGain, tvAlpha;

    // ── Checkboxes ────────────────────────────────────────────────────────────
    private CheckBox cbSpeed, cbAvg, cbDistance;
    private CheckBox cbAutoPause, cbWalkingSpeed;
    private CheckBox cbScreenAnnounce, cbEnhancedAudio;
    private CheckBox cbCadence, cbExcludePauses;
    private CheckBox cbCadenceGyro, cbCadenceAcf;
    // Heart Rate
    private CheckBox   cbAnnounceHr;
    private com.google.android.material.slider.Slider slHrInterval;
    private android.widget.Button btnSelectHrDevice;
    private String     pendingHrAddress = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(R.string.title_settings);
        }
        bindViews();
        loadValues();
        hookLiveLabels();
        hookInfoButtons();
        ((MaterialButton) findViewById(R.id.btnSave)).setOnClickListener(v -> save());
    }

    @Override public boolean onSupportNavigateUp() { finish(); return true; }

    private void bindViews() {
        slThreshold    = findViewById(R.id.sliderThreshold);
        slDebounce     = findViewById(R.id.sliderDebounce);
        slMaxInterval  = findViewById(R.id.sliderMaxInterval);
        slAvgPeriod    = findViewById(R.id.sliderAvgPeriod);
        slAvgInterval  = findViewById(R.id.sliderAvgInterval);
        slAutoPauseSec = findViewById(R.id.sliderAutoPauseSec);
        slScreenDebounce = findViewById(R.id.sliderScreenDebounce);
        slGain         = findViewById(R.id.sliderGain);
        slAlpha        = findViewById(R.id.sliderAlpha);

        tvThreshold    = findViewById(R.id.tvThresholdVal);
        tvDebounce     = findViewById(R.id.tvDebounceVal);
        tvMaxInterval  = findViewById(R.id.tvMaxIntervalVal);
        tvAvgPeriod    = findViewById(R.id.tvAvgPeriodVal);
        tvAvgInterval  = findViewById(R.id.tvAvgIntervalVal);
        tvAutoPauseSec = findViewById(R.id.tvAutoPauseSecVal);
        tvScreenDebounce = findViewById(R.id.tvScreenDebounceVal);
        tvGain         = findViewById(R.id.tvGainVal);
        tvAlpha        = findViewById(R.id.tvAlphaVal);

        cbSpeed         = findViewById(R.id.cbSpeed);
        cbAvg           = findViewById(R.id.cbAvg);
        cbDistance      = findViewById(R.id.cbDistance);
        cbAutoPause     = findViewById(R.id.cbAutoPause);
        cbWalkingSpeed  = findViewById(R.id.cbWalkingSpeed);
        cbScreenAnnounce= findViewById(R.id.cbScreenAnnounce);
        cbEnhancedAudio = findViewById(R.id.cbEnhancedAudio);
        cbCadence        = findViewById(R.id.cbCadence);
        cbAnnounceHr     = findViewById(R.id.cbAnnounceHr);
        slHrInterval     = findViewById(R.id.slHrInterval);
        btnSelectHrDevice = findViewById(R.id.btnSelectHrDevice);
        cbCadenceGyro = findViewById(R.id.cbCadenceGyro);
        cbCadenceAcf  = findViewById(R.id.cbCadenceAcf);
        cbExcludePauses = findViewById(R.id.cbExcludePauses);
    }

    private void loadValues() {
        SharedPreferences p = prefs();
        slThreshold   .setValue(p.getFloat("speed_threshold", 5f));
        slDebounce    .setValue(p.getInt("speed_debounce", 10));
        slMaxInterval .setValue(p.getInt("max_announce_interval", 60));
        slAvgPeriod   .setValue(p.getInt("avg_period", 10));
        slAvgInterval .setValue(p.getInt("avg_interval", 2));
        slAutoPauseSec.setValue(p.getInt("auto_pause_sec", 20));
        slScreenDebounce.setValue(p.getInt("screen_debounce", 15));
        slGain        .setValue(p.getFloat("gain_db", 12f));
        slAlpha       .setValue(p.getFloat("ema_alpha", 0.3f));

        cbSpeed         .setChecked(p.getBoolean("announce_speed",    true));
        cbAvg           .setChecked(p.getBoolean("announce_avg",      true));
        cbDistance      .setChecked(p.getBoolean("announce_distance", true));
        cbAutoPause     .setChecked(p.getBoolean("auto_pause",        false));
        cbWalkingSpeed  .setChecked(p.getBoolean("walking_speed",     false));
        cbScreenAnnounce.setChecked(p.getBoolean("screen_announce",   true));
        cbEnhancedAudio .setChecked(p.getBoolean("enhanced_audio",    true));
        cbCadence    .setChecked(p.getBoolean("announce_cadence", false));
        if (cbAnnounceHr  != null) cbAnnounceHr .setChecked(p.getBoolean("announce_hr", false));
        if (slHrInterval  != null) slHrInterval .setValue(p.getInt("hr_interval_min", 5));
        if (btnSelectHrDevice != null) {
            String addr = p.getString("hr_device_address", null);
            btnSelectHrDevice.setText(addr != null ? "HR: " + addr : "Select HR device");
            btnSelectHrDevice.setOnClickListener(v -> showHrDeviceScanner());
        }
        if (cbCadenceGyro != null) cbCadenceGyro.setChecked(!"accel".equals(p.getString("cadence_sensor","gyro")));
        if (cbCadenceAcf  != null) cbCadenceAcf .setChecked(!"spectral".equals(p.getString("cadence_method","acf")));
        cbExcludePauses .setChecked(p.getBoolean("exclude_pauses_from_avg", false));

        updateAllLabels();
    }

    private void hookLiveLabels() {
        slThreshold   .addOnChangeListener((s,v,f) -> tvThreshold   .setText(fmtKmh(v)));
        slDebounce    .addOnChangeListener((s,v,f) -> tvDebounce    .setText(fmtSec((int)v)));
        slMaxInterval .addOnChangeListener((s,v,f) -> tvMaxInterval .setText(fmtSec((int)v)));
        slAvgPeriod   .addOnChangeListener((s,v,f) -> tvAvgPeriod   .setText(fmtPeriod((int)v)));
        slAvgInterval .addOnChangeListener((s,v,f) -> tvAvgInterval .setText(fmtMin((int)v)));
        slAutoPauseSec.addOnChangeListener((s,v,f) -> tvAutoPauseSec.setText(fmtSec((int)v)));
        slScreenDebounce.addOnChangeListener((s,v,f)->tvScreenDebounce.setText(fmtSec((int)v)));
        slGain        .addOnChangeListener((s,v,f) -> tvGain        .setText(fmtDb(v)));
        slAlpha       .addOnChangeListener((s,v,f) -> tvAlpha       .setText(String.format("%.2f",v)));
    }

    private void updateAllLabels() {
        tvThreshold   .setText(fmtKmh(slThreshold   .getValue()));
        tvDebounce    .setText(fmtSec((int) slDebounce   .getValue()));
        tvMaxInterval .setText(fmtSec((int) slMaxInterval.getValue()));
        tvAvgPeriod   .setText(fmtPeriod((int) slAvgPeriod.getValue()));
        tvAvgInterval .setText(fmtMin((int) slAvgInterval.getValue()));
        tvAutoPauseSec.setText(fmtSec((int) slAutoPauseSec.getValue()));
        tvScreenDebounce.setText(fmtSec((int) slScreenDebounce.getValue()));
        tvGain        .setText(fmtDb(slGain.getValue()));
        tvAlpha       .setText(String.format("%.2f", slAlpha.getValue()));
    }

    private void save() {
        prefs().edit()
                .putFloat("speed_threshold",       slThreshold   .getValue())
                .putInt  ("speed_debounce",   (int) slDebounce   .getValue())
                .putInt  ("max_announce_interval",(int)slMaxInterval.getValue())
                .putInt  ("avg_period",       (int) slAvgPeriod  .getValue())
                .putInt  ("avg_interval",     (int) slAvgInterval.getValue())
                .putInt  ("auto_pause_sec",   (int) slAutoPauseSec.getValue())
                .putInt  ("screen_debounce",  (int) slScreenDebounce.getValue())
                .putFloat("gain_db",               slGain.getValue())
                .putFloat("ema_alpha",             slAlpha.getValue())
                .putBoolean("announce_speed",    cbSpeed        .isChecked())
                .putBoolean("announce_avg",      cbAvg          .isChecked())
                .putBoolean("announce_distance", cbDistance     .isChecked())
                .putBoolean("auto_pause",        cbAutoPause    .isChecked())
                .putBoolean("walking_speed",     cbWalkingSpeed .isChecked())
                .putBoolean("screen_announce",   cbScreenAnnounce.isChecked())
                .putBoolean("enhanced_audio",    cbEnhancedAudio.isChecked())
                .putBoolean("announce_cadence",       cbCadence      .isChecked())
                .putBoolean("announce_hr",  cbAnnounceHr != null && cbAnnounceHr.isChecked())
                .putInt("hr_interval_min",  slHrInterval  != null ? (int) slHrInterval.getValue() : 5)
                .putString("hr_device_address", pendingHrAddress != null ? pendingHrAddress
                        : prefs().getString("hr_device_address", null))
                .putString("cadence_sensor", (cbCadenceGyro != null && cbCadenceGyro.isChecked()) ? "gyro" : "accel")
                .putString("cadence_method", (cbCadenceAcf  != null && cbCadenceAcf .isChecked()) ? "acf"  : "spectral")
                .putBoolean("exclude_pauses_from_avg", cbExcludePauses.isChecked())
                .apply();
        // Применяем настройки немедленно, если сервис уже запущен
        startService(new Intent(this, SpeedometerService.class)
                .setAction(SpeedometerService.ACTION_RELOAD));
        finish();
    }

    // ── Formatters ────────────────────────────────────────────────────────────
    private String fmtKmh(float v)     { return String.format("%.0f km/h", v); }
    private String fmtDb(float v)      { return String.format("+%.0f dB", v); }
    private String fmtMin(int v)       { return v + " min"; }
    private String fmtPeriod(int v)    { return v == 0 ? "Whole ride" : v + " min"; }
    private String fmtSec(int v) {
        if (v >= 60 && v % 60 == 0) return (v / 60) + " min";
        if (v >= 60) return (v / 60) + "m " + (v % 60) + "s";
        return v + " s";
    }

    // ── Info dialogs ──────────────────────────────────────────────────────────
    private void hookInfoButtons() {
        info(R.id.btnInfoThreshold,   "Speed change threshold",
                "Announces current speed when it changes by at least this many km/h since the last announcement.\n\nExample: 5 km/h — accelerating from 30 to 35 triggers \"Speed 35\".");
        info(R.id.btnInfoDebounce,    "Min time between alerts",
                "Even if speed keeps changing, announcements won't fire more often than this. Prevents rapid-fire on bumpy terrain.");
        info(R.id.btnInfoMaxInterval, "Max silence interval",
                "If speed hasn't changed enough to trigger, the app will still speak after this time. Keeps you informed even at steady pace.");
        info(R.id.btnInfoAvgPeriod,   "Average calculation period",
                "Average speed is calculated from GPS samples over the last N minutes.\n\n\"Whole ride\" uses all data since START.");
        info(R.id.btnInfoAvgInterval, "Average announcement interval",
                "How often average speed (and distance if enabled) is spoken automatically.");
        info(R.id.btnInfoAutoPause,   "Auto-pause",
                "Automatically pauses tracking when speed stays below 3 km/h for longer than the set duration. Resumes automatically when speed exceeds 6 km/h.");
        info(R.id.btnInfoWalking,     "Walking speed",
                "When speed drops below 6 km/h, announces \"Walking speed\" instead of a number. Useful to distinguish cycling from walking at traffic lights.");
        info(R.id.btnInfoScreen,      "Announce on screen wake",
                "When the phone screen turns on while still locked (e.g. pocket button press), the app speaks current speed and stats.\n\nDebounce prevents repeated announcements if you press multiple times.");
        info(R.id.btnInfoGain,        "Audio boost (screen-off only)",
                "When the screen is off, speech is synthesized to a file, then the gain is boosted digitally and a compressor+limiter is applied before playback.\n\nThis makes voice much louder and clearer in wind noise, at the cost of audio quality.\n\nHas no effect when screen is on (normal TTS is used instead).");
        info(R.id.btnInfoAlpha,       "EMA smoothing factor α",
                "Controls GPS speed smoothing:\n\n• 0.1 — very smooth, slow to react\n• 0.3 — good balance for cycling\n• 0.8 — fast reaction, noisier\n\nFormula: speed = α × gps + (1−α) × previous");
        info(R.id.btnInfoCadence,     "Announce cadence",
                "Reads the phone accelerometer to estimate pedalling cadence (RPM) without any external sensor.\n\nKeep the phone in a trouser pocket or bag attached to your body (not to the frame).\n\nCadence is spoken right after speed: \"Speed 28. Cadence 85\".\n\nReads 0 RPM when coasting — nothing is announced then.");
        info(R.id.btnInfoExcludePauses, "Exclude pauses from average",
                "When enabled, average speed is calculated only from time spent actively riding — manual pauses and auto-pauses are excluded from both the rolling window and the whole-ride average.\n\nUseful for comparing effort across rides with different pause patterns.");
    }

    private void info(int id, String title, String msg) {
        findViewById(id).setOnClickListener(v ->
                new AlertDialog.Builder(this)
                        .setTitle(title).setMessage(msg)
                        .setPositiveButton("Got it", null).show());
    }

    private void showHrDeviceScanner() {
        if (android.os.Build.VERSION.SDK_INT >= 31 &&
                checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)
                        != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT}, 42);
            return;
        }
        android.app.AlertDialog.Builder dlg = new android.app.AlertDialog.Builder(this);
        dlg.setTitle("Scanning for HR monitors…");
        android.widget.ListView lv = new android.widget.ListView(this);
        android.widget.ArrayAdapter<String> adapter = new android.widget.ArrayAdapter<>(this,
                android.R.layout.simple_list_item_1);
        lv.setAdapter(adapter);
        android.app.AlertDialog dialog = dlg.setView(lv)
                .setNegativeButton("Cancel", (d, w) -> d.dismiss()).show();

        // Temp monitor just for scanning
        HeartRateMonitor scanner = new HeartRateMonitor(this, null);
        java.util.Map<String,String> foundMap = new java.util.LinkedHashMap<>();
        scanner.startScan(new HeartRateMonitor.ScanListener() {
            @Override public void onDeviceFound(String name, String address, int rssi) {
                if (!foundMap.containsKey(address)) {
                    foundMap.put(address, name);
                    adapter.add(name + "  (" + address + ")  " + rssi + "dBm");
                    adapter.notifyDataSetChanged();
                }
            }
            @Override public void onScanFinished() {
                if (dialog.isShowing()) {
                    try { dialog.setTitle("Scan finished — " + foundMap.size() + " found"); }
                    catch (Exception ignored) {}
                }
            }
        });
        lv.setOnItemClickListener((parent, view, pos, id) -> {
            String item = adapter.getItem(pos);
            // extract address between ( )
            int a = item.indexOf('('), b = item.indexOf(')');
            if (a >= 0 && b > a) {
                pendingHrAddress = item.substring(a+1, b).trim();
                btnSelectHrDevice.setText("HR: " + pendingHrAddress);
            }
            scanner.stopScan();
            dialog.dismiss();
        });
        dialog.setOnDismissListener(d2 -> scanner.stopScan());
    }

    private SharedPreferences prefs() {
        return getSharedPreferences("settings", MODE_PRIVATE);
    }
}
