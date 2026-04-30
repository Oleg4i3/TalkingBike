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
            
            // 4. Легкая амплитудная модуляция (~90 Гц) для имитации дребезга
            float rattle = 0.85f + 0.35f * (float)Math.sin(2.0 * Math.PI * 90.0 * t) * (float)Math.sin(2.0 * Math.PI * 77.0 * t);
            
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

   /**
     * Акустическая деревянная кукушка (имитация механизма старых ходиков).
     * Основана на физической модели закрытой трубы (stopped pipe) и меха.
    
    private static void fillBeep(float[] buf, boolean strong) {
        // Классический интервал кукушки — мажорная терция.
        // Верхняя нота (Ку) ~ 659 Гц, нижняя (ку) ~ 523 Гц.
        double baseFreq = strong ? 659.25 : 523.25;
        float vol = strong ? 0.9f : 0.7f;
        
        double phase = 0.0;
        
        for (int i = 0; i < buf.length; i++) {
            double t = (double) i / SAMPLE_RATE;
            
            // 1. Огибающая трубы (Trapezoid / ADSR), а не струны (Exponential)!
            // Воздух из мехов дует ровно, а потом мех закрывается.
            double attack = Math.min(1.0, t / 0.02); // 20мс на раздув (нарастание давления)
            
            // Мех сдувается примерно на 80мс и клапан полностью закрывается к 130мс
            double release = Math.max(0.0, 1.0 - Math.max(0.0, t - 0.08) / 0.05);
            
            // Перемножая их, получаем трапецию: / \
            double envelope = attack * release;
            
            if (envelope < 0.001) continue;
            
            // Микро-падение давления к концу звучания (частота съезжает вниз всего на 1-1.5% по мере сдутия меха)
            double currentFreq = baseFreq * (1.0 + 0.015 * release);
            phase += 2.0 * Math.PI * currentFreq / SAMPLE_RATE;
            
            // 2. Спектр закрытой деревянной трубы (Gedackt pipe).
            // Доминируют нечетные гармоники (3, 5), четные почти отсутствуют.
            float fundamental = (float) Math.sin(phase);
            float h2 = (float) Math.sin(2.0 * phase) * 0.02f; // Едва уловимая 2-я (для "деревянности")
            float h3 = (float) Math.sin(3.0 * phase) * 0.15f; // Сильная 3-я (характерная "пустота" закрытой трубы)
            float h5 = (float) Math.sin(5.0 * phase) * 0.05f; // Легкая 5-я
            
            // 3. "Chiff" - акустический шум при рассекании воздуха о кромку свистка в первые миллисекунды
            float chiff = (float)(Math.random() * 2.0 - 1.0) * (float)Math.exp(-t * 250.0) * 0.1f;
            
            // Миксуем все компоненты
            float sample = (fundamental + h2 + h3 + h5 + chiff) * 0.85f;
            
            buf[i] = sample * (float)envelope * vol;
        }
    }
*/

    /**
     * Акустическая деревянная кукушка.
     * С выраженным шумом мехов ("вздохом") в начале.
     */
    private static void fillBeep(float[] buf, boolean strong) {
        double baseFreq = strong ? 659.25 : 523.25;
        float vol = strong ? 0.9f : 0.7f;
        
        double phase = 0.0;
        float prevNoise = 0f; // Переменная для фильтра "воздуха"
        
        for (int i = 0; i < buf.length; i++) {
            double t = (double) i / SAMPLE_RATE;
            
            // 1. Огибающая трубы
            double attack = Math.min(1.0, t / 0.03); // 30мс на раздув
            double release = Math.max(0.0, 1.0 - Math.max(0.0, t - 0.08) / 0.05);
            double pipeEnvelope = attack * release;
            
            // Если и труба, и шум затухли — пропускаем вычисления
            if (pipeEnvelope < 0.001 && t > 0.15) continue; 
            
            // 2. Генерация тона (закрытая труба)
            double currentFreq = baseFreq * (1.0 + 0.015 * release);
            phase += 2.0 * Math.PI * currentFreq / SAMPLE_RATE;
            
            float fundamental = (float) Math.sin(phase);
            float h2 = (float) Math.sin(2.0 * phase) * 0.02f;
            float h3 = (float) Math.sin(3.0 * phase) * 0.18f;
            float h5 = (float) Math.sin(5.0 * phase) * 0.05f;
            
            // Смешиваем тон и применяем к нему огибающую трубы
            float toneSample = (fundamental + h2 + h3 + h5) * (float)pipeEnvelope;
            
            // 3. "Вздох кукушки" (Chiff / Air)
            float rawNoise = (float)(Math.random() * 2.0 - 1.0);
            // Простейший Low-Pass фильтр: срезает "песок", оставляет густой "пшшш"
            // Чем меньше коэффициент (0.1f), тем глуше звук воздуха
            prevNoise += 0.1f * (rawNoise - prevNoise); 
            
            // Независимая огибающая для шума: стартует сразу (1.0) и затухает за ~50-60 мс
            double noiseEnvelope = Math.exp(-t * 25.0); 
            
            // Формируем вздох (0.8f - это громкость "пшшш", можете увеличить, если нужно больше)
            float airSigh = prevNoise * (float)noiseEnvelope * 0.8f;
            
            // 4. Итоговый микс (Тон + Воздух)
            // Умножаем на 0.8f, чтобы избежать перегруза (клиппинга) при сложении
            buf[i] = (toneSample + airSigh) * 0.8f * vol;
        }
    }
    
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
