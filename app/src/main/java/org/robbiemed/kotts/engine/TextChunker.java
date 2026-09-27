package org.robbiemed.kotts.engine;

import java.util.ArrayList;
import java.util.List;

/** Splits text into sentence-sized chunks no longer than maxLen, preferring sentence, then comma, then word breaks. */
public final class TextChunker {
    private TextChunker() { }

    /**
     * The first sentence always goes alone so audio starts quickly; later sentences are
     * merged up to maxLen while the first one is already playing.
     */
    public static List<String> chunk(String text, int maxLen) {
        List<String> out = new ArrayList<>();
        for (String para : text.trim().split("\\n\\s*\\n")) {
            para = para.replaceAll("\\s+", " ").trim();
            if (para.isEmpty()) continue;
            // Sentence ends: . ! ? … and their CJK forms, followed by space or end.
            StringBuilder cur = new StringBuilder();
            for (String sent : para.split("(?<=[.!?…。！？])\\s+")) {
                if (sent.length() > maxLen) {
                    flush(cur, out);
                    for (String part : splitLong(sent, maxLen)) out.add(part);
                    continue;
                }
                if (cur.length() > 0 && (out.isEmpty() || cur.length() + 1 + sent.length() > maxLen)) flush(cur, out);
                if (cur.length() > 0) cur.append(' ');
                cur.append(sent);
            }
            flush(cur, out);
        }
        return out;
    }

    private static List<String> splitLong(String s, int maxLen) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String part : s.split("(?<=[,、，;])\\s*")) {
            if (part.length() > maxLen) {
                flush(cur, out);
                for (String w : part.split(" ")) {
                    if (cur.length() > 0 && cur.length() + 1 + w.length() > maxLen) flush(cur, out);
                    if (cur.length() > 0) cur.append(' ');
                    cur.append(w.length() > maxLen ? w.substring(0, maxLen) : w);
                }
                continue;
            }
            if (cur.length() > 0 && cur.length() + 1 + part.length() > maxLen) flush(cur, out);
            if (cur.length() > 0) cur.append(' ');
            cur.append(part);
        }
        flush(cur, out);
        return out;
    }

    private static void flush(StringBuilder cur, List<String> out) {
        String s = cur.toString().trim();
        if (!s.isEmpty()) out.add(s);
        cur.setLength(0);
    }
}
