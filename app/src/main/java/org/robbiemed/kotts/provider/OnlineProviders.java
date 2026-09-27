package org.robbiemed.kotts.provider;

import android.content.Context;
import android.util.Base64;

import org.json.JSONObject;
import org.robbiemed.kotts.Prefs;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

/** Online TTS APIs. Each needs the user's own API key; nothing is sent anywhere unless one is chosen. */
public final class OnlineProviders {
    private OnlineProviders() { }

    /** Presets for the OpenAI-compatible provider: {id, label, baseUrl, model, voice}. */
    public static final String[][] OA_PRESETS = {
            {"openai", "OpenAI", "https://api.openai.com/v1", "gpt-4o-mini-tts", "alloy"},
            {"ppq", "PPQ.AI (ElevenLabs)", "https://api.ppq.ai/v1", "eleven_multilingual_v2", ""},
            {"custom", "Self-hosted / other", "http://192.168.1.10:8880/v1", "tts-1", ""},
    };

    abstract static class Base implements Provider {
        @Override public boolean supports(String lang) { return true; }
        @Override public List<String> voices(String lang) { return Collections.singletonList("default"); }
        @Override public boolean needsNetwork() { return true; }
        @Override public void release() { }

        static HttpURLConnection post(String url, JSONObject body, String... headers) throws IOException {
            HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
            h.setConnectTimeout(10000);
            h.setReadTimeout(60000);
            h.setDoOutput(true);
            h.setRequestMethod("POST");
            h.setRequestProperty("Content-Type", "application/json");
            for (int k = 0; k + 1 < headers.length; k += 2) h.setRequestProperty(headers[k], headers[k + 1]);
            try (OutputStream o = h.getOutputStream()) { o.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
            int code = h.getResponseCode();
            if (code / 100 != 2) {
                String err = Audio.readAll(h.getErrorStream());
                h.disconnect();
                throw new IOException("HTTP " + code + ": " + err);
            }
            return h;
        }
    }

    /** Anything that speaks POST {base}/audio/speech: OpenAI, PPQ.AI, Kokoro-FastAPI, openedai-speech… */
    public static final class OpenAi extends Base {
        @Override public String id() { return "openai"; }

        @Override public boolean ready(Context c) {
            return !Prefs.str(c, Prefs.OA_BASE, "").isEmpty()
                    && (!Prefs.str(c, Prefs.OA_KEY, "").isEmpty() || "custom".equals(Prefs.str(c, Prefs.OA_PRESET, "")));
        }

        @Override
        public void synth(Context c, String text, String lang, String voice, float rate, Sink sink) throws Exception {
            String preset = Prefs.str(c, Prefs.OA_PRESET, "openai");
            JSONObject b = new JSONObject()
                    .put("model", Prefs.str(c, Prefs.OA_MODEL, "tts-1"))
                    .put("input", text)
                    .put("response_format", "wav");
            String v = Prefs.str(c, Prefs.OA_VOICE, "");
            if (!v.isEmpty()) b.put("voice", v);
            if (Math.abs(rate - 1f) > 0.01f) b.put("speed", rate);
            if (!"openai".equals(preset)) b.put("language", lang); // OpenAI rejects unknown fields
            String key = Prefs.str(c, Prefs.OA_KEY, "");
            String base = Prefs.str(c, Prefs.OA_BASE, "").replaceAll("/+$", "");
            HttpURLConnection h = key.isEmpty() ? post(base + "/audio/speech", b)
                    : post(base + "/audio/speech", b, "Authorization", "Bearer " + key);
            try (InputStream in = h.getInputStream()) {
                Audio.any(in, c.getCacheDir(), sink);
            } finally {
                h.disconnect();
            }
        }
    }

    /** Google Cloud Text-to-Speech (API key). Korean Neural2/WaveNet/Chirp voices are very good. */
    public static final class Google extends Base {
        @Override public String id() { return "google"; }

        @Override public boolean ready(Context c) { return !Prefs.str(c, Prefs.G_KEY, "").isEmpty(); }

        @Override
        public void synth(Context c, String text, String lang, String voice, float rate, Sink sink) throws Exception {
            String code = lang.equals("ko") ? "ko-KR" : lang.equals("en") ? "en-US" : lang.equals("ja") ? "ja-JP" : lang;
            JSONObject vo = new JSONObject().put("languageCode", code);
            String name = Prefs.str(c, Prefs.G_VOICE, "ko-KR-Neural2-A");
            if (name.startsWith(code)) vo.put("name", name);
            JSONObject b = new JSONObject()
                    .put("input", new JSONObject().put("text", text))
                    .put("voice", vo)
                    .put("audioConfig", new JSONObject().put("audioEncoding", "LINEAR16").put("speakingRate", rate));
            HttpURLConnection h = post("https://texttospeech.googleapis.com/v1/text:synthesize?key="
                    + URLEncoder.encode(Prefs.str(c, Prefs.G_KEY, ""), "UTF-8"), b);
            String json;
            try (InputStream in = h.getInputStream()) {
                json = new String(readBytes(in), StandardCharsets.UTF_8);
            } finally {
                h.disconnect();
            }
            byte[] wav = Base64.decode(new JSONObject(json).getString("audioContent"), Base64.DEFAULT);
            Audio.any(new ByteArrayInputStream(wav), c.getCacheDir(), sink);
        }
    }

    /** ElevenLabs direct (API key + voice id). */
    public static final class ElevenLabs extends Base {
        @Override public String id() { return "elevenlabs"; }

        @Override public boolean ready(Context c) { return !Prefs.str(c, Prefs.EL_KEY, "").isEmpty(); }

        @Override
        public void synth(Context c, String text, String lang, String voice, float rate, Sink sink) throws Exception {
            String model = Prefs.str(c, Prefs.EL_MODEL, "eleven_multilingual_v2");
            JSONObject b = new JSONObject().put("text", text).put("model_id", model);
            if (model.contains("v2_5") || model.contains("v3")) b.put("language_code", lang);
            String vid = Prefs.str(c, Prefs.EL_VOICE, "JBFqnCBsd6RMkjVDRZzb");
            HttpURLConnection h = post("https://api.elevenlabs.io/v1/text-to-speech/" + URLEncoder.encode(vid, "UTF-8")
                    + "?output_format=pcm_24000", b, "xi-api-key", Prefs.str(c, Prefs.EL_KEY, ""));
            try (InputStream in = h.getInputStream()) {
                Audio.pcm(in, 24000, sink);
            } finally {
                h.disconnect();
            }
        }
    }

    static byte[] readBytes(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
        byte[] b = new byte[1 << 16];
        int n;
        while ((n = in.read(b)) > 0) o.write(b, 0, n);
        return o.toByteArray();
    }
}
