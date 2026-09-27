package org.robbiemed.kotts.engine;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

import java.io.File;
import java.io.IOException;
import java.nio.FloatBuffer;
import java.nio.LongBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.Normalizer;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

/**
 * Supertonic 3 inference (https://huggingface.co/Supertone/supertonic-3), ported from
 * Supertone's reference Java/Python code. Pipeline per chunk of text:
 * NFKD text → ids → duration predictor + text encoder → N flow-matching steps → vocoder.
 *
 * Plain Java + ONNX Runtime only, so the same class runs on Android and on a desktop JVM.
 * Not thread-safe: callers serialize synth() calls.
 */
public final class Supertonic implements AutoCloseable {
    public static final String[] LANGS = {"ko", "en", "ja", "ar", "bg", "cs", "da", "de", "el", "es", "et", "fi",
            "fr", "hi", "hr", "hu", "id", "it", "lt", "lv", "nl", "pl", "pt", "ro", "ru", "sk", "sl", "sv", "tr", "uk", "vi"};

    /** Model directory layout (as published on Hugging Face): onnx/*.onnx, onnx/tts.json, onnx/unicode_indexer.json, voice_styles/*.json */
    public static final String[] MODEL_FILES = {"duration_predictor.onnx", "text_encoder.onnx",
            "vector_estimator.onnx", "vocoder.onnx", "tts.json", "unicode_indexer.json"};

    public final int sampleRate;
    private final int chunkSize, latentDim;
    private final long[] indexer;
    private final OrtEnvironment env;
    private final OrtSession dp, enc, est, voc;
    private final Random rng = new Random();

    /**
     * Minimum seconds per Hangul syllable. Supertonic predicts one duration for the whole chunk;
     * when that guess is too short the model squeezes the text in and drops syllables. A floor
     * stops that without slowing down sentences it already times well.
     */
    public float minSecPerSyllable = 0f;

    public static final class Style implements AutoCloseable {
        final OnnxTensor ttl, dp;
        Style(OnnxTensor ttl, OnnxTensor dp) { this.ttl = ttl; this.dp = dp; }
        @Override public void close() { ttl.close(); dp.close(); }
    }

    /** Called with each finished piece of audio; return false to stop early. */
    public interface Sink { boolean audio(float[] pcm, int len); }

    public Supertonic(File modelDir, int threads) throws IOException, OrtException {
        File onnx = new File(modelDir, "onnx");
        Object cfg = MiniJson.parse(read(new File(onnx, "tts.json")));
        sampleRate = num(cfg, "ae", "sample_rate");
        int compress = num(cfg, "ttl", "chunk_compress_factor");
        chunkSize = num(cfg, "ae", "base_chunk_size") * compress;
        latentDim = num(cfg, "ttl", "latent_dim") * compress;

        float[] idx = MiniJson.floats(MiniJson.parse(read(new File(onnx, "unicode_indexer.json"))));
        indexer = new long[idx.length];
        for (int k = 0; k < idx.length; k++) indexer[k] = (long) idx[k];

        env = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions o = new OrtSession.SessionOptions();
        o.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
        if (threads > 0) o.setIntraOpNumThreads(threads);
        dp = env.createSession(new File(onnx, "duration_predictor.onnx").getPath(), o);
        enc = env.createSession(new File(onnx, "text_encoder.onnx").getPath(), o);
        est = env.createSession(new File(onnx, "vector_estimator.onnx").getPath(), o);
        voc = env.createSession(new File(onnx, "vocoder.onnx").getPath(), o);
    }

    public Style loadStyle(File json) throws IOException, OrtException {
        Object root = MiniJson.parse(read(json));
        return new Style(tensor(root, "style_ttl"), tensor(root, "style_dp"));
    }

    private OnnxTensor tensor(Object root, String key) throws OrtException {
        float[] data = MiniJson.floats(MiniJson.path(root, key, "data"));
        float[] d = MiniJson.floats(MiniJson.path(root, key, "dims"));
        long[] shape = new long[d.length];
        for (int k = 0; k < d.length; k++) shape[k] = (long) d[k];
        return OnnxTensor.createTensor(env, FloatBuffer.wrap(data), shape);
    }

    /**
     * Speaks one piece of text. Long text is split into sentence-sized chunks that are
     * synthesized and handed to the sink one at a time, so playback can start early.
     *
     * @param speed 1.0 = model default pace (Supertone uses 1.05 as its "normal")
     * @param steps flow-matching steps: 8 is Supertone's default, 4–5 is faster and still clear
     */
    public void synth(String text, String lang, Style style, float speed, int steps, Sink sink)
            throws OrtException {
        int maxLen = lang.equals("ko") || lang.equals("ja") ? 120 : 300;
        float[] gap = new float[(int) (0.25f * sampleRate)];
        boolean first = true;
        for (String chunk : TextChunker.chunk(text, maxLen)) {
            float[] wav = infer(chunk, lang, style, speed, steps);
            if (wav == null) continue;
            if (!first && !sink.audio(gap, gap.length)) return;
            if (!sink.audio(wav, wav.length)) return;
            first = false;
        }
    }

    private float[] infer(String text, String lang, Style style, float speed, int steps) throws OrtException {
        long[] ids = encode(preprocess(text, lang));
        if (ids.length == 0) return null;
        int n = ids.length;
        float[] ones = new float[n];
        java.util.Arrays.fill(ones, 1f);

        try (OnnxTensor idsT = OnnxTensor.createTensor(env, LongBuffer.wrap(ids), new long[]{1, n});
             OnnxTensor maskT = OnnxTensor.createTensor(env, FloatBuffer.wrap(ones), new long[]{1, 1, n})) {

            float duration;
            Map<String, OnnxTensor> in = new HashMap<>();
            in.put("text_ids", idsT); in.put("style_dp", style.dp); in.put("text_mask", maskT);
            try (OrtSession.Result r = dp.run(in)) {
                duration = ((OnnxTensor) r.get(0)).getFloatBuffer().get(0) / speed;
            }
            duration = Math.max(duration, hangulSyllables(text) * minSecPerSyllable / speed);

            int wavLen = (int) (duration * sampleRate);
            int latLen = Math.max(1, (wavLen + chunkSize - 1) / chunkSize);
            float[] xt = new float[latentDim * latLen];
            for (int k = 0; k < xt.length; k++) xt[k] = (float) rng.nextGaussian();
            float[] latMask = new float[latLen];
            java.util.Arrays.fill(latMask, 1f);

            in.clear();
            in.put("text_ids", idsT); in.put("style_ttl", style.ttl); in.put("text_mask", maskT);
            try (OrtSession.Result encR = enc.run(in);
                 OnnxTensor latMaskT = OnnxTensor.createTensor(env, FloatBuffer.wrap(latMask), new long[]{1, 1, latLen});
                 OnnxTensor totalT = OnnxTensor.createTensor(env, new float[]{steps})) {
                OnnxTensor emb = (OnnxTensor) encR.get(0);
                for (int step = 0; step < steps; step++) {
                    try (OnnxTensor xtT = OnnxTensor.createTensor(env, FloatBuffer.wrap(xt), new long[]{1, latentDim, latLen});
                         OnnxTensor curT = OnnxTensor.createTensor(env, new float[]{step})) {
                        in.clear();
                        in.put("noisy_latent", xtT); in.put("text_emb", emb); in.put("style_ttl", style.ttl);
                        in.put("latent_mask", latMaskT); in.put("text_mask", maskT);
                        in.put("current_step", curT); in.put("total_step", totalT);
                        try (OrtSession.Result r = est.run(in)) {
                            FloatBuffer fb = ((OnnxTensor) r.get(0)).getFloatBuffer();
                            fb.get(xt, 0, Math.min(xt.length, fb.remaining()));
                        }
                    }
                }
            }

            try (OnnxTensor latT = OnnxTensor.createTensor(env, FloatBuffer.wrap(xt), new long[]{1, latentDim, latLen});
                 OrtSession.Result r = voc.run(java.util.Collections.singletonMap("latent", latT))) {
                FloatBuffer fb = ((OnnxTensor) r.get(0)).getFloatBuffer();
                int len = Math.min(wavLen, fb.remaining());
                float[] wav = new float[len];
                fb.get(wav, 0, len);
                return wav;
            }
        }
    }

    static int hangulSyllables(String s) {
        int n = 0;
        for (int k = 0; k < s.length(); k++) { char c = s.charAt(k); if (c >= 0xAC00 && c <= 0xD7A3) n++; }
        return n;
    }

    private long[] encode(String text) {
        int[] cps = text.codePoints().toArray();
        long[] out = new long[cps.length];
        int n = 0;
        for (int cp : cps) {
            long id = cp < indexer.length ? indexer[cp] : -1;
            if (id >= 0) out[n++] = id; // silently drop characters the model has no symbol for
        }
        return java.util.Arrays.copyOf(out, n);
    }

    /** Mirrors Supertone's reference preprocessing (NFKD splits Hangul into jamo, which is what the model reads). */
    static String preprocess(String text, String lang) {
        text = Normalizer.normalize(text, Normalizer.Form.NFKD);
        StringBuilder b = new StringBuilder(text.length());
        text.codePoints().forEach(cp -> { if (!isEmoji(cp)) b.appendCodePoint(cp); });
        text = b.toString()
                .replace('–', '-').replace('‑', '-').replace('—', '-').replace('_', ' ')
                .replace('“', '"').replace('”', '"').replace('‘', '\'').replace('’', '\'')
                .replace('´', '\'').replace('`', '\'')
                .replace('[', ' ').replace(']', ' ').replace('|', ' ').replace('/', ' ').replace('#', ' ')
                .replace('→', ' ').replace('←', ' ')
                .replaceAll("[♥☆♡©\\\\]", "")
                .replace("@", " at ").replace("e.g.,", "for example, ").replace("i.e.,", "that is, ")
                .replaceAll(" ([,.!?;:'])", "$1");
        while (text.contains("\"\"")) text = text.replace("\"\"", "\"");
        while (text.contains("''")) text = text.replace("''", "'");
        text = text.replaceAll("\\s+", " ").trim();
        if (text.isEmpty()) return "";
        if (!text.matches("(?s).*[.!?;:,'\"\\u201C\\u201D\\u2018\\u2019)\\]}…。」』】〉》›»]$")) text += ".";
        return "<" + lang + ">" + text + "</" + lang + ">";
    }

    private static boolean isEmoji(int cp) {
        return (cp >= 0x1F300 && cp <= 0x1FAFF) || (cp >= 0x2600 && cp <= 0x27BF) || (cp >= 0x1F1E6 && cp <= 0x1F1FF);
    }

    private static int num(Object cfg, String... path) { return ((Number) MiniJson.path(cfg, path)).intValue(); }

    private static String read(File f) throws IOException {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    @Override
    public void close() {
        for (OrtSession s : new OrtSession[]{dp, enc, est, voc}) {
            try { s.close(); } catch (OrtException ignored) { }
        }
    }
}
