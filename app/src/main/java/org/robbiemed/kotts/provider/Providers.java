package org.robbiemed.kotts.provider;

import android.content.Context;

import org.robbiemed.kotts.Prefs;

/** Registry of providers; the user picks one active provider in settings. */
public final class Providers {
    private Providers() { }

    public static final SupertonicProvider SUPERTONIC = new SupertonicProvider();
    public static final Provider[] ALL = {SUPERTONIC, new OnlineProviders.OpenAi(),
            new OnlineProviders.Google(), new OnlineProviders.ElevenLabs()};

    public static Provider active(Context c) {
        String id = Prefs.str(c, Prefs.PROVIDER, SUPERTONIC.id());
        for (Provider p : ALL) if (p.id().equals(id)) return p;
        return SUPERTONIC;
    }
}
