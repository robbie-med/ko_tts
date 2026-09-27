package org.robbiemed.kotts.engine;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Spells out digits, units and symbols as spoken Korean before synthesis, because the
 * acoustic model only reliably reads Hangul. Handles Sino vs native numbers (세 시 but 삼십 분),
 * phone numbers digit by digit (공일공), decimals (삼십팔 점 이), times, dates, money and units.
 */
public final class KoreanNormalizer {
    private KoreanNormalizer() { }

    private static final String[] DIGIT = {"영", "일", "이", "삼", "사", "오", "육", "칠", "팔", "구"};
    private static final String[] PHONE_DIGIT = {"공", "일", "이", "삼", "사", "오", "육", "칠", "팔", "구"};
    private static final String[] NATIVE_ONES = {"", "한", "두", "세", "네", "다섯", "여섯", "일곱", "여덟", "아홉"};
    private static final String[] NATIVE_TENS = {"", "열", "스물", "서른", "마흔", "쉰", "예순", "일흔", "여든", "아흔"};

    /** Counters that take native Korean numbers (한 개, 두 명, 세 시). Longest first so 시간 wins over 시. */
    private static final String NATIVE_COUNTERS = "번째|시간|군데|가지|켤레|송이|그루|마리|살|시|개(?!월)|명|잔|권|병|장|대|벌|채|척|달|곳|사람|판|통|알|줄";

    private static final Pattern PHONE = Pattern.compile("(?<![\\d-])(0\\d{1,2})[- .](\\d{3,4})[- .](\\d{4})(?![\\d-])");
    private static final Pattern TIME = Pattern.compile("(?<![\\d:])(\\d{1,2}):(\\d{2})(?![\\d:])");
    private static final Pattern MONEY_PREFIX = Pattern.compile("([₩$€¥£])\\s?(\\d[\\d,]*(?:\\.\\d+)?)");
    private static final Pattern MONTH = Pattern.compile("(?<![\\d,.])(\\d{1,2})\\s?월");
    private static final Pattern NATIVE = Pattern.compile("(?<![\\d,.])(\\d{1,2})\\s?(" + NATIVE_COUNTERS + ")");
    private static final Pattern UNIT = Pattern.compile("(\\d[\\d,]*(?:\\.\\d+)?)\\s?(°C|℃|°|%|kg|mg|g|km|cm|mm|m|ml|mL|L|l|kcal)(?![A-Za-z])");
    private static final Pattern NUMBER = Pattern.compile("\\d[\\d,]*(?:\\.\\d+)?");

    public static String normalize(String s) {
        s = replace(PHONE, s, m -> digits(m.group(1)) + ", " + digits(m.group(2)) + ", " + digits(m.group(3)));
        s = replace(TIME, s, m -> {
            int h = Integer.parseInt(m.group(1)), min = Integer.parseInt(m.group(2));
            String r = (h >= 1 && h <= 12 ? nativeNum(h) : sino(h)) + " 시";
            if (min == 30) return r + " 반";
            return min == 0 ? r : r + " " + sino(min) + " 분";
        });
        s = replace(MONEY_PREFIX, s, m -> {
            String unit = m.group(1).equals("₩") ? "원" : m.group(1).equals("$") ? "달러" : m.group(1).equals("€") ? "유로"
                    : m.group(1).equals("¥") ? "엔" : "파운드";
            return number(m.group(2)) + " " + unit;
        });
        s = replace(MONTH, s, m -> {
            int n = Integer.parseInt(m.group(1));
            return (n == 6 ? "유" : n == 10 ? "시" : sino(n)) + "월";
        });
        s = replace(NATIVE, s, m -> {
            int n = Integer.parseInt(m.group(1));
            if (n == 0) return "영 " + m.group(2);
            if (m.group(2).equals("번째") && n == 1) return "첫 번째";
            if (m.group(2).equals("시") && n > 12) return sino(n) + " 시";
            return nativeNum(n) + " " + m.group(2);
        });
        s = replace(UNIT, s, m -> number(m.group(1)) + " " + unit(m.group(2)));
        s = replace(NUMBER, s, m -> number(m.group()));
        return s;
    }

    private interface Fn { String apply(Matcher m); }

    private static String replace(Pattern p, String s, Fn fn) {
        Matcher m = p.matcher(s);
        StringBuffer b = new StringBuffer();
        while (m.find()) m.appendReplacement(b, Matcher.quoteReplacement(fn.apply(m)));
        m.appendTail(b);
        return b.toString();
    }

    private static String unit(String u) {
        switch (u) {
            case "°C": case "℃": case "°": return "도";
            case "%": return "퍼센트";
            case "kg": return "킬로그램";
            case "mg": return "밀리그램";
            case "g": return "그램";
            case "km": return "킬로미터";
            case "cm": return "센티미터";
            case "mm": return "밀리미터";
            case "m": return "미터";
            case "ml": case "mL": return "밀리리터";
            case "kcal": return "킬로칼로리";
            default: return "리터";
        }
    }

    /** "1,500" → 천오백, "38.2" → 삼십팔 점 이 */
    static String number(String raw) {
        String t = raw.replace(",", "");
        int dot = t.indexOf('.');
        String whole = dot < 0 ? t : t.substring(0, dot);
        if (whole.length() > 16) return digits(t.replace(".", " 점 "));
        String r = whole.length() > 1 && whole.startsWith("0") ? digits(whole) : sino(Long.parseLong(whole.isEmpty() ? "0" : whole));
        if (dot >= 0 && dot < t.length() - 1) r += " 점 " + digits(t.substring(dot + 1));
        return r;
    }

    private static String digits(String d) {
        StringBuilder b = new StringBuilder();
        for (char c : d.toCharArray()) b.append(Character.isDigit(c) ? PHONE_DIGIT[c - '0'] : String.valueOf(c));
        return b.toString();
    }

    /** Sino-Korean reading, 만-grouped: 12345 → 만 이천삼백사십오 (spaced by 만/억/조 groups). */
    static String sino(long n) {
        if (n == 0) return "영";
        String[] big = {"", "만", "억", "조", "경"};
        StringBuilder out = new StringBuilder();
        int g = 0;
        while (n > 0) {
            int part = (int) (n % 10000);
            if (part > 0) {
                String p = four(part);
                if (g == 1 && part == 1) p = ""; // 만, not 일만
                out.insert(0, p + big[g] + (out.length() > 0 ? " " : ""));
            }
            n /= 10000;
            g++;
        }
        return out.toString().trim();
    }

    private static String four(int n) {
        String[] pos = {"천", "백", "십", ""};
        int[] div = {1000, 100, 10, 1};
        StringBuilder b = new StringBuilder();
        for (int k = 0; k < 4; k++) {
            int d = n / div[k] % 10;
            if (d == 0) continue;
            if (!(d == 1 && k < 3)) b.append(DIGIT[d]);
            b.append(pos[k]);
        }
        return b.toString();
    }

    /** Native Korean for 1–99 in counter form (한, 두, 스무); falls back to Sino above 99. */
    static String nativeNum(int n) {
        if (n < 1 || n > 99) return sino(n);
        if (n == 20) return "스무";
        return NATIVE_TENS[n / 10] + NATIVE_ONES[n % 10];
    }
}
