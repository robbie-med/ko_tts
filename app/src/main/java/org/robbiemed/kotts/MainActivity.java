package org.robbiemed.kotts;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.TextView;

import org.robbiemed.kotts.provider.OnlineProviders;
import org.robbiemed.kotts.provider.Providers;

import java.util.Locale;

/** Settings: pick a voice source, download the on-device model, enter API keys, try it out. */
public class MainActivity extends Activity {
    private static final int[] STEPS = {4, 6, 8};
    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextToSpeech tts;
    private boolean ttsReady, verifying;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_main);

        findViewById(R.id.open_settings).setOnClickListener(v -> {
            try { startActivity(new Intent("com.android.settings.TTS_SETTINGS")); }
            catch (ActivityNotFoundException e) { startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS)); }
        });

        RadioGroup g = findViewById(R.id.provider);
        String p = Prefs.str(this, Prefs.PROVIDER, "supertonic");
        g.check(p.equals("openai") ? R.id.p_openai : p.equals("google") ? R.id.p_google
                : p.equals("elevenlabs") ? R.id.p_elevenlabs : R.id.p_supertonic);
        g.setOnCheckedChangeListener((grp, id) -> {
            String np = id == R.id.p_openai ? "openai" : id == R.id.p_google ? "google"
                    : id == R.id.p_elevenlabs ? "elevenlabs" : "supertonic";
            Prefs.get(this).edit().putString(Prefs.PROVIDER, np).apply();
            if (!np.equals("supertonic")) Providers.SUPERTONIC.release();
            refresh();
        });

        // On-device model
        findViewById(R.id.dl_compact).setOnClickListener(v -> { ModelManager.start(this, ModelManager.COMPACT); refresh(); });
        findViewById(R.id.dl_full).setOnClickListener(v -> { ModelManager.start(this, ModelManager.FULL); refresh(); });
        findViewById(R.id.dl_cancel).setOnClickListener(v -> {
            Providers.SUPERTONIC.release();
            if (ModelManager.progress(this) != null) ModelManager.cancel(this); else ModelManager.delete(this);
            refresh();
        });
        String[] voiceLabels = new String[ModelManager.VOICES.length];
        for (int k = 0; k < voiceLabels.length; k++) {
            String v = ModelManager.VOICES[k];
            voiceLabels[k] = (v.startsWith("F") ? "♀ " : "♂ ") + v;
        }
        spinner(R.id.st_voice, voiceLabels, indexOf(ModelManager.VOICES, Prefs.str(this, Prefs.ST_VOICE, "F1")),
                i -> Prefs.get(this).edit().putString(Prefs.ST_VOICE, ModelManager.VOICES[i]).apply());
        int steps = Prefs.num(this, Prefs.ST_STEPS, 6);
        spinner(R.id.st_steps, new String[]{getString(R.string.q_fast), getString(R.string.q_balanced), getString(R.string.q_best)},
                steps <= 4 ? 0 : steps >= 8 ? 2 : 1,
                i -> Prefs.get(this).edit().putInt(Prefs.ST_STEPS, STEPS[i]).apply());

        // OpenAI-compatible
        String[] presetLabels = new String[OnlineProviders.OA_PRESETS.length];
        String[] presetIds = new String[presetLabels.length];
        for (int k = 0; k < presetLabels.length; k++) {
            presetIds[k] = OnlineProviders.OA_PRESETS[k][0];
            presetLabels[k] = OnlineProviders.OA_PRESETS[k][1];
        }
        bind(R.id.oa_base, Prefs.OA_BASE);
        bind(R.id.oa_key, Prefs.OA_KEY);
        bind(R.id.oa_model, Prefs.OA_MODEL);
        bind(R.id.oa_voice, Prefs.OA_VOICE);
        String cur = Prefs.str(this, Prefs.OA_PRESET, "");
        spinner(R.id.oa_preset, presetLabels, Math.max(0, indexOf(presetIds, cur)), i -> {
            String[] pr = OnlineProviders.OA_PRESETS[i];
            if (pr[0].equals(Prefs.str(this, Prefs.OA_PRESET, ""))) return;
            Prefs.get(this).edit().putString(Prefs.OA_PRESET, pr[0]).apply();
            ((EditText) findViewById(R.id.oa_base)).setText(pr[2]);
            ((EditText) findViewById(R.id.oa_model)).setText(pr[3]);
            ((EditText) findViewById(R.id.oa_voice)).setText(pr[4]);
        });

        bind(R.id.g_key, Prefs.G_KEY);
        bind(R.id.g_voice, Prefs.G_VOICE);
        bind(R.id.el_key, Prefs.EL_KEY);
        bind(R.id.el_voice, Prefs.EL_VOICE);
        bind(R.id.el_model, Prefs.EL_MODEL);

        findViewById(R.id.speak).setOnClickListener(v -> speak());
        tts = new TextToSpeech(this, st -> { ttsReady = st == TextToSpeech.SUCCESS; refresh(); }, getPackageName());
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
        ui.post(poll);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(poll);
    }

    @Override
    protected void onDestroy() {
        if (tts != null) tts.shutdown();
        super.onDestroy();
    }

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (ModelManager.progress(MainActivity.this) != null) refreshModel();
            ui.postDelayed(this, 700);
        }
    };

    private void refresh() {
        String p = Prefs.str(this, Prefs.PROVIDER, "supertonic");
        show(R.id.box_supertonic, p.equals("supertonic"));
        show(R.id.box_openai, p.equals("openai"));
        show(R.id.box_google, p.equals("google"));
        show(R.id.box_elevenlabs, p.equals("elevenlabs"));

        String def = null;
        try { if (tts != null && ttsReady) def = tts.getDefaultEngine(); } catch (Exception ignored) { }
        ((TextView) findViewById(R.id.engine_status)).setText(
                getPackageName().equals(def) ? R.string.is_default : R.string.not_default);
        refreshModel();
    }

    /** Model download/install state only; cheap enough to poll while downloading. */
    private void refreshModel() {
        TextView status = findViewById(R.id.model_status);
        ProgressBar bar = findViewById(R.id.model_progress);
        Button compact = findViewById(R.id.dl_compact), full = findViewById(R.id.dl_full), cancel = findViewById(R.id.dl_cancel);
        String installed = ModelManager.installed(this);
        ModelManager.Progress pr = ModelManager.progress(this);

        compact.setText(getString(installed == null ? R.string.dl_compact : R.string.dl_replace_compact, ModelManager.COMPACT_MB));
        full.setText(getString(installed == null ? R.string.dl_full : R.string.dl_replace_full, ModelManager.FULL_MB));
        show(compact, ModelManager.compactAvailable() && pr == null && !ModelManager.COMPACT.equals(installed));
        show(full, pr == null && !ModelManager.FULL.equals(installed));
        show(cancel, pr != null || installed != null);
        cancel.setText(pr != null ? R.string.dl_cancel : R.string.dl_delete);
        show(bar, pr != null && !pr.failed);

        if (pr != null && pr.running) {
            status.setText(getString(R.string.m_downloading, (int) (pr.done >> 20), (int) (pr.total >> 20)));
            bar.setIndeterminate(pr.total <= 0);
            if (pr.total > 0) bar.setProgress((int) (1000 * pr.done / pr.total));
        } else if (pr != null && pr.failed) {
            status.setText(R.string.m_failed);
            show(compact, ModelManager.compactAvailable());
            show(full, true);
        } else if (pr != null) {
            status.setText(R.string.m_verifying);
            bar.setIndeterminate(true);
            if (!verifying) {
                verifying = true;
                new Thread(() -> { ModelManager.finish(getApplicationContext()); ui.post(() -> { verifying = false; refresh(); }); }).start();
            }
        } else if (installed != null) {
            status.setText(ModelManager.COMPACT.equals(installed) ? R.string.m_ready_compact : R.string.m_ready_full);
        } else {
            String bad = Prefs.str(this, "dl_error", "");
            status.setText(bad.isEmpty() ? getString(R.string.m_none) : getString(R.string.m_bad, bad));
        }
    }

    private void speak() {
        TextView st = findViewById(R.id.try_status);
        if (!Providers.active(this).ready(this) || tts == null || !ttsReady) { st.setText(R.string.try_not_ready); return; }
        st.setText("");
        String text = ((EditText) findViewById(R.id.try_text)).getText().toString();
        boolean hangul = text.codePoints().anyMatch(cp -> cp >= 0xAC00 && cp <= 0xD7A3);
        tts.setLanguage(hangul ? Locale.KOREAN : Locale.getDefault());
        int r = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "try");
        if (r != TextToSpeech.SUCCESS) st.setText(getString(R.string.try_error, r));
    }

    // ---- small view helpers ----

    private interface OnPick { void pick(int i); }

    private void spinner(int id, String[] labels, int sel, OnPick cb) {
        Spinner s = findViewById(id);
        ArrayAdapter<String> a = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, labels);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        s.setAdapter(a);
        s.setSelection(Math.max(0, sel), false);
        s.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long l) { cb.pick(pos); }
            @Override public void onNothingSelected(AdapterView<?> p) { }
        });
    }

    private void bind(int id, String key) {
        EditText e = findViewById(id);
        e.setText(Prefs.get(this).getString(key, ""));
        e.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                Prefs.get(MainActivity.this).edit().putString(key, s.toString().trim()).apply();
            }
        });
    }

    private void show(int id, boolean on) { show(findViewById(id), on); }

    private static void show(View v, boolean on) { v.setVisibility(on ? View.VISIBLE : View.GONE); }

    private static int indexOf(String[] a, String v) {
        for (int k = 0; k < a.length; k++) if (a[k].equals(v)) return k;
        return -1;
    }
}
