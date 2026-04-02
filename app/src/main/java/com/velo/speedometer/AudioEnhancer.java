package com.velo.speedometer;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.audiofx.DynamicsProcessing;
import android.os.Build;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Loud speech synthesis for noisy environments.
 *
 * Pipeline:
 *   TTS.synthesizeToFile() → PCM gain + soft limiter → MediaPlayer
 *   └─ DynamicsProcessing (API 28+): PreEQ + MBC + Limiter attached to session
 *
 * Only active when screen is off (caller must check PowerManager.isInteractive()).
 */
public class AudioEnhancer {

    private static final String TAG = "AudioEnhancer";
    private static final String TTS_FILE = "sb_tts_raw.wav";
    private static final String OUT_FILE = "sb_tts_enhanced.wav";

    private final Context context;
    private final TextToSpeech tts;
    private float gainDb = 12f;   // default +12 dB

    private MediaPlayer       mediaPlayer;
    private DynamicsProcessing dynProc;

    public AudioEnhancer(Context context, TextToSpeech tts) {
        this.context = context;
        this.tts     = tts;
    }

    public void setGainDb(float db) {
        this.gainDb = Math.max(0f, Math.min(30f, db));
    }

    /** Speak text through the enhancement pipeline. */
    public void speak(String text, Runnable onDone) {
        File rawFile = new File(context.getCacheDir(), TTS_FILE);

        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String id) {}
            @Override public void onError(String id) { if (onDone != null) onDone.run(); }

            @Override
            public void onDone(String id) {
                try {
                    File enhanced = processWav(rawFile, gainDb);
                    playFile(enhanced, onDone);
                } catch (Exception e) {
                    Log.e(TAG, "Processing failed", e);
                    if (onDone != null) onDone.run();
                }
            }
        });

        android.os.Bundle params = new android.os.Bundle();
        params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "enhance");
        tts.synthesizeToFile(text, params, rawFile, "enhance");
    }

    // ── PCM processing ────────────────────────────────────────────────────────

    /**
     * Reads WAV, applies gain + IIR high-pass (removes wind rumble below ~250 Hz)
     * + tanh soft limiter. Returns enhanced WAV file.
     */
    private File processWav(File input, float gainDb) throws Exception {
        byte[] raw = readAllBytes(input);
        if (raw.length < 44) throw new Exception("WAV too small");

        // Copy header (44 bytes for standard PCM WAV)
        byte[] header = new byte[44];
        System.arraycopy(raw, 0, header, 0, 44);

        int dataLen = raw.length - 44;
        short[] pcm = new short[dataLen / 2];
        ByteBuffer.wrap(raw, 44, dataLen)
                  .order(ByteOrder.LITTLE_ENDIAN)
                  .asShortBuffer()
                  .get(pcm);

        // Sample rate from header bytes 24-27
        int sampleRate = ByteBuffer.wrap(header, 24, 4)
                                   .order(ByteOrder.LITTLE_ENDIAN).getInt();
        if (sampleRate <= 0) sampleRate = 22050;

        // ── 1. High-pass filter (~250 Hz) to cut wind/road rumble ──
        // First-order IIR: y[n] = α*(y[n-1] + x[n] - x[n-1])
        float rc    = 1f / (float)(2 * Math.PI * 250.0);
        float dt    = 1f / sampleRate;
        float alpha = rc / (rc + dt);

        float[] hp = new float[pcm.length];
        hp[0] = pcm[0];
        for (int i = 1; i < pcm.length; i++) {
            hp[i] = alpha * (hp[i - 1] + pcm[i] - pcm[i - 1]);
        }

        // ── 2. Gain + tanh soft limiter ──
        float linGain  = (float) Math.pow(10.0, gainDb / 20.0);
        float ceiling  = 32767f * 0.95f;   // -0.45 dBFS headroom

        for (int i = 0; i < pcm.length; i++) {
            float s = hp[i] * linGain;
            // tanh soft clip: maps all reals to (-1,1), naturally limiting
            s = (float)(Math.tanh(s / ceiling) * ceiling);
            pcm[i] = (short) Math.max(-32768, Math.min(32767, (int) s));
        }

        // ── 3. Write enhanced WAV ──
        byte[] outPcm = new byte[pcm.length * 2];
        ByteBuffer.wrap(outPcm)
                  .order(ByteOrder.LITTLE_ENDIAN)
                  .asShortBuffer()
                  .put(pcm);

        File outFile = new File(context.getCacheDir(), OUT_FILE);
        try (FileOutputStream fos = new FileOutputStream(outFile)) {
            fos.write(header);
            fos.write(outPcm);
        }
        return outFile;
    }

    // ── Playback ──────────────────────────────────────────────────────────────

    private void playFile(File file, Runnable onDone) {
        releasePlayer();
        try {
            mediaPlayer = new MediaPlayer();
            mediaPlayer.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build());
            mediaPlayer.setDataSource(file.getAbsolutePath());
            mediaPlayer.setVolume(1f, 1f);
            mediaPlayer.prepare();

            attachDynamicsProcessing(mediaPlayer.getAudioSessionId());

            mediaPlayer.setOnCompletionListener(mp -> {
                releasePlayer();
                if (onDone != null) onDone.run();
            });
            mediaPlayer.start();
        } catch (Exception e) {
            Log.e(TAG, "Playback failed", e);
            releasePlayer();
            if (onDone != null) onDone.run();
        }
    }

    /**
     * Attach DynamicsProcessing (API 28+):
     *   PreEQ  – high-shelf boost at 2 kHz (+6 dB) for speech consonants
     *   MBC    – 2-band compressor (ratio 6:1 above −18 dBFS)
     *   Limiter– −1 dBFS ceiling
     */
    private void attachDynamicsProcessing(int sessionId) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return;
        try {
            DynamicsProcessing.Config cfg = new DynamicsProcessing.Config.Builder(
                    DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                    1,      // mono channel
                    true, 2,  // PreEQ on, 2 bands
                    true, 2,  // MBC on,   2 bands
                    true, 2,  // PostEQ on, 2 bands
                    true      // Limiter on
            ).build();

            dynProc = new DynamicsProcessing(0, sessionId, cfg);

            // PreEQ band 0: allow lows (pass-through shelf ~150 Hz)
            dynProc.setPreEqBandAllChannelsTo(0,
                    new DynamicsProcessing.EqBand(true, 150f, 0f));
            // PreEQ band 1: boost highs 2 kHz + (speech consonants, sibilants)
            dynProc.setPreEqBandAllChannelsTo(1,
                    new DynamicsProcessing.EqBand(true, 2000f, 6f));

            // MBC band 0 (<1.5 kHz): compress aggressively
            DynamicsProcessing.MbcBand mbc0 = new DynamicsProcessing.MbcBand(
			true, 1500f, 50f, 200f, 6f, -18f, 3f, 0f, 0f, 0f, 0f);
            dynProc.setMbcBandAllChannelsTo(0, mbc0);

            // MBC band 1 (>1.5 kHz): lighter compression
           DynamicsProcessing.MbcBand mbc1 = new DynamicsProcessing.MbcBand(
			true, 20000f, 50f, 200f, 4f, -24f, 3f, 0f, 0f, 0f, 0f);
            dynProc.setMbcBandAllChannelsTo(1, mbc1);

            // Limiter: hard ceiling at −1 dBFS
           dynProc.setLimiterAllChannelsTo(new DynamicsProcessing.Limiter(
			true, true, 0, 5f, 50f, 10f, -1f, 0f));
            dynProc.setEnabled(true);
        } catch (Exception e) {
            Log.w(TAG, "DynamicsProcessing setup failed: " + e.getMessage());
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void releasePlayer() {
        if (dynProc != null)     { dynProc.release();      dynProc = null; }
        if (mediaPlayer != null) { mediaPlayer.release();  mediaPlayer = null; }
    }

    public void release() {
        releasePlayer();
    }

    private static byte[] readAllBytes(File f) throws Exception {
        try (FileInputStream fis = new FileInputStream(f)) {
            byte[] buf = new byte[(int) f.length()];
            int read = 0;
            while (read < buf.length) {
                int r = fis.read(buf, read, buf.length - read);
                if (r < 0) break;
                read += r;
            }
            return buf;
        }
    }
}
