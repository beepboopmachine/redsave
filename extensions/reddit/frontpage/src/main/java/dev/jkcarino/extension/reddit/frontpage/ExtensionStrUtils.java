package dev.jkcarino.extension.reddit.frontpage;

import java.util.Locale;

public final class ExtensionStrUtils {
    private ExtensionStrUtils() {}

    public static boolean isBlank(String s) {
        if (s == null) return true;
        int len = s.length();
        for (int i = 0; i < len; i++) {
            char c = s.charAt(i);
            if (!Character.isWhitespace(c) && c != '\u00A0' && c != '\u2007' && c != '\u202F') {
                return false;
            }
        }
        return true;
    }

    public static String trim(String s) {
        if (s == null) return "";
        int start = 0;
        int end = s.length();
        while (start < end) {
            char c = s.charAt(start);
            if (Character.isWhitespace(c) || c == '\u00A0' || c == '\u2007' || c == '\u202F') {
                start++;
            } else {
                break;
            }
        }
        while (end > start) {
            char c = s.charAt(end - 1);
            if (Character.isWhitespace(c) || c == '\u00A0' || c == '\u2007' || c == '\u202F') {
                end--;
            } else {
                break;
            }
        }
        return s.substring(start, end);
    }

    public static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }

    public static boolean contains(String s, String needle) {
        return s != null && needle != null && s.contains(needle);
    }

    public static boolean containsIgnoreCase(String s, String needle) {
        if (s == null || needle == null) return false;
        return s.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));
    }

    public static boolean endsWith(String s, String suffix) {
        return s != null && suffix != null && s.endsWith(suffix);
    }

    public static boolean startsWith(String s, String prefix) {
        return s != null && prefix != null && s.startsWith(prefix);
    }

    public static String replace(String s, String target, String replacement) {
        if (s == null) return "";
        if (target == null || replacement == null || target.isEmpty()) return s;
        return s.replace(target, replacement);
    }

    public static int lastIndexOf(String s, char ch) {
        return s == null ? -1 : s.lastIndexOf(ch);
    }
}
