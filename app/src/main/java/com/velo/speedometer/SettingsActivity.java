package com.velo.speedometer;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

public class SettingsActivity extends AppCompatActivity {

    private EditText etThreshold, etDebounce, etAvgPeriod, etAvgInterval, etAlpha;
    private CheckBox cbSpeed, cbAvg, cbDistance;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(R.string.title_settings);
        }

        etThreshold  = findViewById(R.id.etThreshold);
        etDebounce   = findViewById(R.id.etDebounce);
        etAvgPeriod  = findViewById(R.id.etAvgPeriod);
        etAvgInterval = findViewById(R.id.etAvgInterval);
        etAlpha      = findViewById(R.id.etAlpha);
        cbSpeed      = findViewById(R.id.cbSpeed);
        cbAvg        = findViewById(R.id.cbAvg);
        cbDistance   = findViewById(R.id.cbDistance);

        loadValues();
        hookInfoButtons();

        findViewById(R.id.btnSave).setOnClickListener(v -> saveAndFinish());
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    // ── Load / Save ───────────────────────────────────────────────────────────
    private void loadValues() {
        SharedPreferences p = prefs();
        etThreshold.setText(String.valueOf(p.getFloat("speed_threshold", 5f)));
        etDebounce.setText(String.valueOf(p.getInt("speed_debounce", 10)));
        etAvgPeriod.setText(String.valueOf(p.getInt("avg_period", 10)));
        etAvgInterval.setText(String.valueOf(p.getInt("avg_interval", 2)));
        etAlpha.setText(String.valueOf(p.getFloat("ema_alpha", 0.3f)));
        cbSpeed.setChecked(p.getBoolean("announce_speed", true));
        cbAvg.setChecked(p.getBoolean("announce_avg", true));
        cbDistance.setChecked(p.getBoolean("announce_distance", true));
    }

    private void saveAndFinish() {
        try {
            float threshold = Float.parseFloat(etThreshold.getText().toString());
            int   debounce  = Integer.parseInt(etDebounce.getText().toString());
            int   avgPeriod = Integer.parseInt(etAvgPeriod.getText().toString());
            int   avgInterval = Integer.parseInt(etAvgInterval.getText().toString());
            float alpha     = Float.parseFloat(etAlpha.getText().toString());

            // Basic sanity checks
            if (threshold < 0.5f || threshold > 100f)
                throw new IllegalArgumentException("Threshold must be 0.5–100");
            if (debounce < 1 || debounce > 3600)
                throw new IllegalArgumentException("Debounce must be 1–3600 s");
            if (avgPeriod < 1 || avgPeriod > 120)
                throw new IllegalArgumentException("Avg period must be 1–120 min");
            if (avgInterval < 1 || avgInterval > 60)
                throw new IllegalArgumentException("Avg interval must be 1–60 min");
            if (alpha < 0.05f || alpha > 1f)
                throw new IllegalArgumentException("Alpha must be 0.05–1.0");

            prefs().edit()
                    .putFloat("speed_threshold", threshold)
                    .putInt("speed_debounce", debounce)
                    .putInt("avg_period", avgPeriod)
                    .putInt("avg_interval", avgInterval)
                    .putFloat("ema_alpha", alpha)
                    .putBoolean("announce_speed", cbSpeed.isChecked())
                    .putBoolean("announce_avg", cbAvg.isChecked())
                    .putBoolean("announce_distance", cbDistance.isChecked())
                    .apply();

            finish();
        } catch (NumberFormatException e) {
            Toast.makeText(this, "Please enter valid numbers", Toast.LENGTH_SHORT).show();
        } catch (IllegalArgumentException e) {
            Toast.makeText(this, e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    // ── Info buttons ──────────────────────────────────────────────────────────
    private void hookInfoButtons() {
        info(R.id.btnInfoThreshold,
                "Speed change threshold",
                "Announces current speed when it rises or drops by at least this many km/h "
                + "since the last announcement.\n\nExample: 5 km/h — if you accelerate from "
                + "30 to 35 you'll hear \"Speed 35\".\n\nRange: 0.5–100");

        info(R.id.btnInfoDebounce,
                "Minimum time between alerts",
                "Even if the speed keeps changing, the app won't announce more often than "
                + "this many seconds.\n\nPrevents rapid-fire announcements on bumpy terrain."
                + "\n\nRange: 1–3600 s");

        info(R.id.btnInfoAvgPeriod,
                "Average calculation period",
                "Average speed is calculated using all GPS samples from the last N minutes. "
                + "A longer window smooths out short stops; a shorter window reacts faster."
                + "\n\nRange: 1–120 min");

        info(R.id.btnInfoAvgInterval,
                "Average announcement interval",
                "How often (in minutes) the app automatically announces average speed "
                + "(and distance if enabled).\n\nIndependent of the speed-change threshold."
                + "\n\nRange: 1–60 min");

        info(R.id.btnInfoAlpha,
                "EMA smoothing factor (α)",
                "Controls the Exponential Moving Average filter applied to raw GPS speed.\n\n"
                + "• Lower (e.g. 0.1) → very smooth, slow to react\n"
                + "• Higher (e.g. 0.7) → fast to react, noisier\n"
                + "• Default 0.3 is a good balance for cycling\n\n"
                + "Formula: speed = α × gps + (1−α) × prev\n\n"
                + "Range: 0.05–1.0");
    }

    private void info(int btnId, String title, String msg) {
        findViewById(btnId).setOnClickListener(v ->
                new AlertDialog.Builder(this)
                        .setTitle(title)
                        .setMessage(msg)
                        .setPositiveButton("Got it", null)
                        .show());
    }

    private SharedPreferences prefs() {
        return getSharedPreferences("settings", MODE_PRIVATE);
    }
}
