package org.robbiemed.kotts.provider;

import android.content.Context;

import java.util.List;

/** A source of speech: an on-device model or an online API. */
public interface Provider {
    /** Receives audio. Return false from either method to stop synthesis early. */
    interface Sink {
        boolean start(int sampleRate);
        boolean pcm16(byte[] buf, int off, int len);
    }

    String id();

    /** Model installed / API configured. */
    boolean ready(Context c);

    /** ISO 639-1 codes this provider can speak. */
    boolean supports(String lang);

    /** Voice ids offered for a language, first one is the default. */
    List<String> voices(String lang);

    boolean needsNetwork();

    /** Blocking. Text has already been normalized for its language. */
    void synth(Context c, String text, String lang, String voice, float rate, Sink sink) throws Exception;

    /** Drop any loaded model to free memory. */
    void release();
}
