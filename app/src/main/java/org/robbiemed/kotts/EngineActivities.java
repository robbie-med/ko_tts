package org.robbiemed.kotts;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;

import org.robbiemed.kotts.engine.Supertonic;
import org.robbiemed.kotts.provider.Provider;
import org.robbiemed.kotts.provider.Providers;

import java.util.ArrayList;
import java.util.Locale;

/** Small headless activities the Android TTS framework calls into. */
public final class EngineActivities {
    private EngineActivities() { }

    /** CHECK_TTS_DATA: which languages are installed and usable right now. */
    public static class CheckVoiceData extends Activity {
        @Override
        protected void onCreate(Bundle b) {
            super.onCreate(b);
            Provider p = Providers.active(this);
            ArrayList<String> ok = new ArrayList<>(), missing = new ArrayList<>();
            String[] langs = p.id().equals("supertonic") ? Supertonic.LANGS : new String[]{"ko", "en", "ja"};
            for (String l : langs) {
                Locale loc = new Locale(l);
                String tag = loc.getISO3Language();
                String country = l.equals("ko") ? "KOR" : l.equals("en") ? "USA" : l.equals("ja") ? "JPN" : "";
                if (!country.isEmpty()) tag += "-" + country;
                (p.ready(this) ? ok : missing).add(tag);
            }
            Intent r = new Intent()
                    .putStringArrayListExtra(TextToSpeech.Engine.EXTRA_AVAILABLE_VOICES, ok)
                    .putStringArrayListExtra(TextToSpeech.Engine.EXTRA_UNAVAILABLE_VOICES, missing);
            setResult(ok.isEmpty() ? TextToSpeech.Engine.CHECK_VOICE_DATA_FAIL : TextToSpeech.Engine.CHECK_VOICE_DATA_PASS, r);
            finish();
        }
    }

    /** GET_SAMPLE_TEXT: what the "Listen to an example" button in Android settings says. */
    public static class SampleText extends Activity {
        @Override
        protected void onCreate(Bundle b) {
            super.onCreate(b);
            String lang = getIntent().getStringExtra("language");
            String l = KoTtsService.iso1(lang);
            String text = "ko".equals(l) ? "안녕하세요. 한국어 음성 합성 엔진입니다. 오늘 날씨가 참 좋네요."
                    : "ja".equals(l) ? "こんにちは。これは音声合成の例です。"
                    : "This is an example of speech synthesis.";
            setResult(TextToSpeech.LANG_AVAILABLE, new Intent().putExtra("sampleText", text));
            finish();
        }
    }
}
