package org.robbiemed.kotts;

import android.content.Context;
import android.content.SharedPreferences;

/** All settings live in one SharedPreferences file. */
public final class Prefs {
    private Prefs() { }

    public static final String PROVIDER = "provider";           // "supertonic" | "openai" | "google" | "elevenlabs"
    public static final String ST_VOICE = "st_voice";           // F1..F5, M1..M5
    public static final String ST_STEPS = "st_steps";           // flow-matching steps: 4 | 6 | 8
    public static final String ST_VARIANT = "st_variant";       // installed model: "compact" | "full"
    public static final String OA_PRESET = "oa_preset";
    public static final String OA_BASE = "oa_base";
    public static final String OA_KEY = "oa_key";
    public static final String OA_MODEL = "oa_model";
    public static final String OA_VOICE = "oa_voice";
    public static final String G_KEY = "g_key";
    public static final String G_VOICE = "g_voice";
    public static final String EL_KEY = "el_key";
    public static final String EL_VOICE = "el_voice";
    public static final String EL_MODEL = "el_model";

    public static SharedPreferences get(Context c) {
        return c.getApplicationContext().getSharedPreferences("kotts", Context.MODE_PRIVATE);
    }

    public static String str(Context c, String k, String def) {
        String v = get(c).getString(k, def);
        return v == null || v.isEmpty() ? def : v;
    }

    public static int num(Context c, String k, int def) { return get(c).getInt(k, def); }
}
