package org.robbiemed.kotts.provider;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;

import org.robbiemed.kotts.ModelManager;
import org.robbiemed.kotts.Prefs;
import org.robbiemed.kotts.engine.Supertonic;

import java.io.File;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** On-device Supertonic 3. The model loads on first use and stays loaded until release(). */
public final class SupertonicProvider implements Provider {
    private Supertonic tts;
    private final Map<String, Supertonic.Style> styles = new HashMap<>();

    @Override public String id() { return "supertonic"; }

    @Override public boolean ready(Context c) { return ModelManager.installed(c) != null; }

    @Override public boolean supports(String lang) { return Arrays.asList(Supertonic.LANGS).contains(lang); }

    @Override public List<String> voices(String lang) { return Arrays.asList(ModelManager.VOICES); }

    @Override public boolean needsNetwork() { return false; }

    @Override
    public synchronized void synth(Context c, String text, String lang, String voice, float rate, Sink sink) throws Exception {
        long t0 = SystemClock.elapsedRealtime();
        if (tts == null) {
            int cores = Runtime.getRuntime().availableProcessors();
            int threads = Prefs.num(c, "st_threads", 0);
            tts = new Supertonic(ModelManager.dir(c), threads > 0 ? threads : Math.max(1, Math.min(4, cores - 1)));
            Log.i("KoTTS", "model loaded in " + (SystemClock.elapsedRealtime() - t0) + " ms");
        }
        if (voice == null || !Arrays.asList(ModelManager.VOICES).contains(voice))
            voice = Prefs.str(c, Prefs.ST_VOICE, "F1");
        Supertonic.Style st = styles.get(voice);
        if (st == null) {
            st = tts.loadStyle(new File(ModelManager.dir(c), "voice_styles/" + voice + ".json"));
            styles.put(voice, st);
        }
        if (!sink.start(tts.sampleRate)) return;
        int steps = Prefs.num(c, Prefs.ST_STEPS, 6);
        final byte[][] buf = {new byte[0]};
        final long t1 = SystemClock.elapsedRealtime();
        final long[] first = {0}, samples = {0}, blocked = {0};
        tts.synth(text, lang, st, 1.05f * rate, steps, (pcm, len) -> {
            if (buf[0].length < len * 2) buf[0] = new byte[len * 2];
            byte[] b = buf[0];
            for (int i = 0; i < len; i++) {
                int v = Math.round(Math.max(-1f, Math.min(1f, pcm[i])) * 32767f);
                b[2 * i] = (byte) v;
                b[2 * i + 1] = (byte) (v >> 8);
            }
            if (first[0] == 0) first[0] = SystemClock.elapsedRealtime() - t1;
            samples[0] += len;
            long tb = SystemClock.elapsedRealtime();
            boolean more = sink.pcm16(b, 0, len * 2); // blocks while Android's playback queue is full
            blocked[0] += SystemClock.elapsedRealtime() - tb;
            return more;
        });
        long ms = SystemClock.elapsedRealtime() - t1 - blocked[0];
        Log.i("KoTTS", String.format(java.util.Locale.ROOT, "%d chars, steps=%d: first audio %d ms, total %d ms for %.2f s audio (RTF %.2f)",
                text.length(), steps, first[0], ms, samples[0] / (float) tts.sampleRate, ms / 1000f / Math.max(0.01f, samples[0] / (float) tts.sampleRate)));
    }

    @Override
    public synchronized void release() {
        for (Supertonic.Style s : styles.values()) s.close();
        styles.clear();
        if (tts != null) { tts.close(); tts = null; }
    }
}
