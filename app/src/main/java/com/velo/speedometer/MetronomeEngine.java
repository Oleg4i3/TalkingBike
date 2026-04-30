package com.velo.speedometer;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Vibrator;
import android.os.VibrationEffect;

/**
 * PCM-based metronome audio engine.
 *
 * Generates AudioTrack buffers for 3 sound types × 2 (strong/weak beat).
 * A looping silent track keeps USAGE_MEDIA audio focus active even when the
 * metronome is paused — required so MediaSession VolumeProvider continues to
 * intercept hardware volume keys with the screen off.
 *
 * call init() once, beat() per tick, release() on destroy.
 */
public class MetronomeEngine {

    public static final int SOUND_MARACAS = 0;
    public static final int SOUND_CLICK   = 1;
    public static final int SOUND_BEEP    = 2;

    private static final int SAMPLE_RATE = 44100;
    private static final double BEAT_DURATION = 0.20; // seconds per beat buffer

    // [soundType][0=strong, 1=weak]
    private AudioTrack[][] tracks;
    private AudioTrack silentTrack;

    private final Vibrator vibrator;

    // Playback params (volatile — set from UI thread, read from beat thread)
    private volatile float   volume      = 1.0f;  // 0.0–1.0 linear
    private volatile int     soundType   = SOUND_MARACAS;
    private volatile boolean soundStrong = true;
    private volatile boolean soundWeak   = true;
    private volatile boolean vibStrong   = false;
    private volatile boolean vibWeak     = true;

    public MetronomeEngine(Context ctx) {
        vibrator = (Vibrator) ctx.getSystemService(Context.VIBRATOR_SERVICE);
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    public void init() {
        tracks = new AudioTrack[3][2];
        for (int type = 0; type < 3; type++) {
            tracks[type][0] = buildTrack(type, true);
            tracks[type][1] = buildTrack(type, false);
        }
        silentTrack = buildSilentTrack();
        silentTrack.play(); // looping silence keeps media audio focus
    }

    public void release() {
        if (silentTrack != null) { silentTrack.stop(); silentTrack.release(); silentTrack = null; }
        if (tracks == null) return;
        for (AudioTrack[] pair : tracks)
            for (AudioTrack t : pair) { if (t != null) { t.stop(); t.release(); } }
        tracks = null;
    }

    // ── Called from beat loop thread ──────────────────────────────────────────

    /**
     * Play one beat. isStrong=true for downbeat, false for upbeat.
     * Thread-safe: only AudioTrack methods are called, all volatile reads are safe.
     */
    public void beat(boolean isStrong) {
        boolean doSound = isStrong ? soundStrong : soundWeak;
        boolean doVib   = isStrong ? vibStrong   : vibWeak;

        if (doSound && tracks != null) {
            AudioTrack t = tracks[soundType][isStrong ? 0 : 1];
            t.stop();
            t.reloadStaticData();
            t.setVolume(volume);
            t.play();
        }

        if (doVib && vibrator != null) {
            long ms = isStrong ? 150L : 40L;
            try {
                vibrator.vibrate(VibrationEffect.createOneShot(
                        ms, VibrationEffect.DEFAULT_AMPLITUDE));
            } catch (Exception ignored) {}
        }
    }

    // ── Settings (can be changed mid-ride) ────────────────────────────────────

    /** volume: 0.0 = silent, 1.0 = full. Applied per-beat. */
    public void setVolume(float v) { this.volume = Math.max(0f, Math.min(1f, v)); }
    public float getVolume()       { return volume; }

    public void setParams(int soundType, boolean soundStrong, boolean soundWeak,
                          boolean vibStrong, boolean vibWeak) {
        this.soundType   = soundType;
        this.soundStrong = soundStrong;
        this.soundWeak   = soundWeak;
        this.vibStrong   = vibStrong;
        this.vibWeak     = vibWeak;
    }

    // ── PCM synthesis ─────────────────────────────────────────────────────────

    private AudioTrack buildTrack(int type, boolean strong) {
        int n = (int)(BEAT_DURATION * SAMPLE_RATE);
        float[] buf = new float[n];

        switch (type) {
            case SOUND_MARACAS: fillMaracas(buf, strong); break;
            case SOUND_CLICK:   fillClick(buf, strong);   break;
            case SOUND_BEEP:    fillBeep(buf, strong);    break;
        }
        return makeStaticTrack(buf);
    }

    /** * Maracas: высокочастотный шум с перкуссионной огибающей и эффектом "дробинок". 
     */
    private static void fillMaracas(float[] buf, boolean strong) {
        int n = buf.length;
        float prevNoise = 0f;
        float prevHp = 0f;
        
        // Настройки для сильной и слабой доли
        float alpha = strong ? 0.65f : 0.55f; // Коэффициент High-Pass фильтра
        double peakTime = strong ? 0.015 : 0.010; // Время максимальной громкости (атака)
        float vol = strong ? 1.0f : 0.6f;

        for (int i = 0; i < n; i++) {
            double t = (double) i / SAMPLE_RATE;
            
            // 1. Генерация сырого шума
            float noise = (float)(Math.random() * 2.0 - 1.0);
            
            // 2. High-Pass фильтр (срезаем низы)
            float hpNoise = alpha * (prevHp + noise - prevNoise);
            prevNoise = noise;
            prevHp = hpNoise;
            
            // 3. Перкуссионная огибающая (форма Gamma распределения)
            double envelope = (t / peakTime) * Math.exp(1.0 - t / peakTime);
            if (envelope < 0.001) envelope = 0.0;
            
            // 4. Легкая амплитудная модуляция (~80 Гц) для имитации дребезга
            float rattle = 0.85f + 0.55f * (float)Math.sin(2.0 * Math.PI * 40.0 * t);
            
            buf[i] = hpNoise * (float)envelope * rattle * vol;
        }
    }

    /** Click: damped sine + noise burst. Strong = 3 kHz, Weak = 1.2 kHz. */
    private static void fillClick(float[] buf, boolean strong) {
        double freq = strong ? 3000.0 : 1200.0;
        float  vol  = strong ? 1.0f   : 0.6f;
        for (int i = 0; i < buf.length; i++) {
            double t        = (double) i / SAMPLE_RATE;
            float  envelope = (float) Math.exp(-t * 150.0);
            if (envelope < 0.001f) continue;
            float  sine  = (float) Math.sin(2.0 * Math.PI * freq * t);
            float  noise = (float)(Math.random() * 2 - 1);
            buf[i] = (sine * 0.7f + noise * 0.3f) * envelope * vol;
        }
    }

    /** * Деревянная Кукушка: синусоида с нечетными гармониками, глиссандо и мягкой атакой. 
     */
    /** * Механическая Кукушка: мягкий звук деревянной трубы из старых настенных часов. 
     */
    private static void fillBeep(float[] buf, boolean strong) {
        // Базовые частоты ниже: 650 Hz (Ку) и 520 Hz (ку) — мажорная терция
        double baseFreq = strong ? 650.0 : 520.0;
        float vol = strong ? 0.9f : 0.6f;
        
        double phase = 0.0;
        
        for (int i = 0; i < buf.length; i++) {
            double t = (double) i / SAMPLE_RATE;
            
            // Огибающая: мягкий старт мехов (~20мс) и долгое, спокойное затухание
            double attack = 1.0 - Math.exp(-t * 120.0); 
            double decay = Math.exp(-t * 12.0);         
            double envelope = attack * decay;
            
            if (envelope < 0.001) continue;
            
            // Микро-спад частоты в начале (всего 2% вместо 25%), как при падении давления в мехе
            double currentFreq = baseFreq * (1.0 + 0.02 * Math.exp(-t * 50.0));
            phase += 2.0 * Math.PI * currentFreq / SAMPLE_RATE;
            
            // Тело звука: 
            // 1. Основа
            // 2. 2-я гармоника (октава) для деревянной "теплоты"
            // 3. 3-я гармоника для легкой "пустотности" трубы
            float fundamental = (float) Math.sin(phase);
            float secondHarm = (float) Math.sin(2.0 * phase) * 0.15f;
            float thirdHarm = (float) Math.sin(3.0 * phase) * 0.05f;
            
            // Chiff: мягкий шум воздуха в самом начале (дыхание меха)
            float airChiff = (float)(Math.random() * 2.0 - 1.0) * (float)Math.exp(-t * 400.0) * 0.08f;
            
            // Микшируем с запасом от клиппинга
            float sample = (fundamental + secondHarm + thirdHarm + airChiff) * 0.8f;
            
            buf[i] = sample * (float)envelope * vol;
        }
    }
  /** * old variant  
  private static void fillBeep(float[] buf, boolean strong) {
        double baseFreq = strong ? 800.0 : 640.0;
        float vol = strong ? 0.8f : 0.5f;
        
        double phase = 0.0;
        
        for (int i = 0; i < buf.length; i++) {
            double t = (double) i / SAMPLE_RATE;
            
            // Огибающая: плавная атака + экспоненциальный спад
            double attack = 1.0 - Math.exp(-t * 250.0);
            double decay = Math.exp(-t * 20.0);
            double envelope = attack * decay;
            
            if (envelope < 0.001) continue;
            
            // Pitch Glide: стартуем на 25% выше и падаем до базовой частоты
            double currentFreq = baseFreq * (1.0 + 0.25 * Math.exp(-t * 80.0));
            phase += 2.0 * Math.PI * currentFreq / SAMPLE_RATE;
            
            // Формирование тембра: основной тон + нечетные гармоники (деревянная трубка)
            float fundamental = (float) Math.sin(phase);
            float thirdHarm = (float) Math.sin(3.0 * phase) * 0.3f;
            float fifthHarm = (float) Math.sin(5.0 * phase) * 0.1f;
            
            // "Chiff" - короткий шумовой всплеск на атаке (дыхание/механика)
            float airChiff = (float)(Math.random() * 2.0 - 1.0) * (float)Math.exp(-t * 800.0) * 0.2f;
            
            // Микшируем и чуть приглушаем общий уровень, чтобы не было клиппинга
            float sample = (fundamental + thirdHarm + fifthHarm + airChiff) * 0.75f;
            
            buf[i] = sample * (float)envelope * vol;
        }
    }
    */

    /** 1-second silent loop — keeps USAGE_MEDIA focus alive for MediaSession. */
    private static AudioTrack buildSilentTrack() {
        float[] silence = new float[SAMPLE_RATE];
        AudioTrack t = makeStaticTrack(silence);
        t.setLoopPoints(0, silence.length, -1);
        return t;
    }

    private static AudioTrack makeStaticTrack(float[] buf) {
        AudioTrack t = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build())
                .setBufferSizeInBytes(buf.length * 4)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build();
        t.write(buf, 0, buf.length, AudioTrack.WRITE_BLOCKING);
        return t;
    }
}
