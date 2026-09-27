package org.robbiemed.kotts.engine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Just enough JSON to read model configs and voice styles, with no dependency on org.json,
 * so the engine code also runs on a desktop JVM for testing.
 * Objects become Map, arrays become List, numbers become Double.
 */
public final class MiniJson {
    private final String s;
    private int i;

    private MiniJson(String s) { this.s = s; }

    public static Object parse(String text) {
        MiniJson p = new MiniJson(text);
        p.ws();
        Object v = p.value();
        p.ws();
        if (p.i != p.s.length()) throw p.err("trailing data");
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Object path(Object root, String... keys) {
        Object o = root;
        for (String k : keys) {
            if (!(o instanceof Map)) return null;
            o = ((Map<String, Object>) o).get(k);
        }
        return o;
    }

    /** Flattens a (nested) numeric array into a float[] in row-major order. */
    public static float[] floats(Object arr) {
        List<Float> out = new ArrayList<>();
        flatten(arr, out);
        float[] f = new float[out.size()];
        for (int k = 0; k < f.length; k++) f[k] = out.get(k);
        return f;
    }

    private static void flatten(Object o, List<Float> out) {
        if (o instanceof List) for (Object x : (List<?>) o) flatten(x, out);
        else out.add(((Number) o).floatValue());
    }

    private RuntimeException err(String m) { return new IllegalArgumentException("JSON: " + m + " at " + i); }

    private void ws() { while (i < s.length() && s.charAt(i) <= ' ') i++; }

    private Object value() {
        char c = s.charAt(i);
        if (c == '{') return object();
        if (c == '[') return array();
        if (c == '"') return string();
        if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
        if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
        if (s.startsWith("null", i)) { i += 4; return null; }
        return number();
    }

    private Map<String, Object> object() {
        Map<String, Object> m = new LinkedHashMap<>();
        i++; ws();
        if (s.charAt(i) == '}') { i++; return m; }
        while (true) {
            ws(); String k = string(); ws();
            if (s.charAt(i++) != ':') throw err("expected :");
            ws(); m.put(k, value()); ws();
            char c = s.charAt(i++);
            if (c == '}') return m;
            if (c != ',') throw err("expected , or }");
        }
    }

    private List<Object> array() {
        List<Object> a = new ArrayList<>();
        i++; ws();
        if (s.charAt(i) == ']') { i++; return a; }
        while (true) {
            ws(); a.add(value()); ws();
            char c = s.charAt(i++);
            if (c == ']') return a;
            if (c != ',') throw err("expected , or ]");
        }
    }

    private String string() {
        if (s.charAt(i) != '"') throw err("expected string");
        StringBuilder b = new StringBuilder();
        i++;
        while (true) {
            char c = s.charAt(i++);
            if (c == '"') return b.toString();
            if (c != '\\') { b.append(c); continue; }
            char e = s.charAt(i++);
            switch (e) {
                case 'n': b.append('\n'); break;
                case 't': b.append('\t'); break;
                case 'r': b.append('\r'); break;
                case 'b': b.append('\b'); break;
                case 'f': b.append('\f'); break;
                case 'u': b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; break;
                default: b.append(e);
            }
        }
    }

    private Double number() {
        int st = i;
        while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
        if (st == i) throw err("unexpected character");
        return Double.parseDouble(s.substring(st, i));
    }
}
