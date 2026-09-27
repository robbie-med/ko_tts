package org.robbiemed.kotts;

import android.media.AudioFormat;
import android.speech.tts.SynthesisCallback;
import android.speech.tts.SynthesisRequest;
import android.speech.tts.TextToSpeech;
import android.speech.tts.TextToSpeechService;
import android.speech.tts.Voice;
import android.util.Log;

import org.robbiemed.kotts.engine.KoreanNormalizer;
import org.robbiemed.kotts.engine.Supertonic;
import org.robbiemed.kotts.provider.Provider;
import org.robbiemed.kotts.provider.Providers;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.Set;

/** The system TTS engine. Android calls this from any app once Ko TTS is chosen (or asked for by package). */
public class KoTtsService extends TextToSpeechService {
    private static final String TAG = "KoTTS";
    /** Languages offered by online providers in the voice list (they may speak more). */
    private static final String[] ONLINE_LANGS = {"ko", "en", "ja"};
    private static final Map<String, String> ISO3 = new HashMap<>();

    static {
        for (String l : Locale.getISOLanguages()) {
            try { ISO3.put(new Locale(l).getISO3Language(), l); } catch (MissingResourceException ignored) { }
        }
    }

    private volatile boolean stopped;
    private volatile String[] current = {"kor", "KOR", ""};

    static String iso1(String lang) {
        if (lang == null || lang.isEmpty()) return null;
        if (lang.length() == 2) return lang.toLowerCase(Locale.ROOT);
        return ISO3.get(lang.toLowerCase(Locale.ROOT));
    }

    private List<String> langs(Provider p) {
        List<String> out = new ArrayList<>();
        for (String l : p.id().equals("supertonic") ? Supertonic.LANGS : ONLINE_LANGS) if (p.supports(l)) out.add(l);
        return out;
    }

    @Override
    protected int onIsLanguageAvailable(String lang, String country, String variant) {
        String l = iso1(lang);
        Provider p = Providers.active(this);
        if (l == null || !p.supports(l)) return TextToSpeech.LANG_NOT_SUPPORTED;
        if (!p.ready(this)) return TextToSpeech.LANG_MISSING_DATA;
        return country == null || country.isEmpty() ? TextToSpeech.LANG_AVAILABLE : TextToSpeech.LANG_COUNTRY_AVAILABLE;
    }

    @Override
    protected String[] onGetLanguage() { return current; }

    @Override
    protected int onLoadLanguage(String lang, String country, String variant) {
        int r = onIsLanguageAvailable(lang, country, variant);
        if (r >= TextToSpeech.LANG_AVAILABLE) current = new String[]{lang, country == null ? "" : country, ""};
        return r;
    }

    @Override
    protected void onStop() { stopped = true; }

    @Override
    protected void onSynthesizeText(SynthesisRequest req, SynthesisCallback cb) {
        stopped = false;
        Provider p = Providers.active(this);
        String lang = iso1(req.getLanguage());
        if (lang == null) lang = "ko";
        if (!p.ready(this)) { cb.error(TextToSpeech.ERROR_NOT_INSTALLED_YET); return; }

        CharSequence cs = req.getCharSequenceText();
        String text = cs == null ? "" : cs.toString();
        if (lang.equals("ko")) text = KoreanNormalizer.normalize(text);
        if (text.trim().isEmpty()) {
            cb.start(22050, AudioFormat.ENCODING_PCM_16BIT, 1);
            cb.done();
            return;
        }
        float rate = Math.max(0.3f, Math.min(3f, req.getSpeechRate() / 100f));
        String vn = req.getVoiceName();
        String voice = vn != null && vn.contains("-") ? vn.substring(vn.lastIndexOf('-') + 1) : null;
        final int max = Math.max(1024, cb.getMaxBufferSize());
        try {
            p.synth(this, text, lang, voice, rate, new Provider.Sink() {
                @Override public boolean start(int sr) {
                    return !stopped && cb.start(sr, AudioFormat.ENCODING_PCM_16BIT, 1) == TextToSpeech.SUCCESS;
                }

                @Override public boolean pcm16(byte[] buf, int off, int len) {
                    for (int i = 0; i < len; i += max) {
                        if (stopped) return false;
                        if (cb.audioAvailable(buf, off + i, Math.min(max, len - i)) != TextToSpeech.SUCCESS) return false;
                    }
                    return !stopped;
                }
            });
            if (!stopped) cb.done();
        } catch (Throwable e) {
            Log.e(TAG, "synthesis failed", e);
            cb.error(p.needsNetwork() ? TextToSpeech.ERROR_NETWORK : TextToSpeech.ERROR_SYNTHESIS);
        }
    }

    @Override
    public List<Voice> onGetVoices() {
        Provider p = Providers.active(this);
        boolean ready = p.ready(this);
        // Never null: Voice is parceled to other apps, and a null feature set crashes them.
        Set<String> feats = ready ? new HashSet<>() : new HashSet<>(Collections.singletonList(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED));
        List<Voice> out = new ArrayList<>();
        for (String l : langs(p)) {
            Locale loc = new Locale(l);
            for (String v : p.voices(l))
                out.add(new Voice(l + "-" + p.id() + "-" + v, loc, Voice.QUALITY_HIGH,
                        p.needsNetwork() ? Voice.LATENCY_HIGH : Voice.LATENCY_NORMAL, p.needsNetwork(), feats));
        }
        return out;
    }

    @Override
    public int onIsValidVoiceName(String name) {
        for (Voice v : onGetVoices()) if (v.getName().equals(name)) return TextToSpeech.SUCCESS;
        return TextToSpeech.ERROR;
    }

    @Override
    public int onLoadVoice(String name) { return onIsValidVoiceName(name); }

    @Override
    public String onGetDefaultVoiceNameFor(String lang, String country, String variant) {
        String l = iso1(lang);
        Provider p = Providers.active(this);
        if (l == null || !p.supports(l)) return null;
        String v = p.id().equals("supertonic") ? Prefs.str(this, Prefs.ST_VOICE, "F1") : p.voices(l).get(0);
        return l + "-" + p.id() + "-" + v;
    }

    @Override
    public void onDestroy() {
        Providers.SUPERTONIC.release();
        super.onDestroy();
    }
}
