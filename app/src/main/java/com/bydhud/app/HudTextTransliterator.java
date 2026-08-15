package com.bydhud.app;

import android.icu.text.Transliterator;

import java.util.Locale;

/** Applies the user-selected text projection to HUD output copies. */
final class HudTextTransliterator {
    static final int OFF = 0;
    static final int UKRAINIAN = 1;
    static final int UNIVERSAL = 2;

    private static final Object UNIVERSAL_LOCK = new Object();
    private static Transliterator universal;

    private HudTextTransliterator() {
    }

    /** Returns a transformed copy of one output string; unknown modes are fail-open. */
    static String transform(String text, int mode) {
        if (text == null || text.isEmpty() || mode == OFF) {
            return text == null ? "" : text;
        }
        if (mode == UKRAINIAN) {
            return ukrainian(text);
        }
        if (mode == UNIVERSAL) {
            return universal(text);
        }
        return text;
    }

    /** Transforms only direct-channel road/cue text, leaving raw frame data and alerts intact. */
    static DirectTbtFrame transformFrame(DirectTbtFrame frame, int mode) {
        if (frame == null || mode == OFF) {
            return frame;
        }
        return frame.withNavigationText(
                transform(frame.getRoadText(), mode),
                transform(frame.getCueText(), mode));
    }

    private static String ukrainian(String text) {
        StringBuilder out = new StringBuilder(text.length() + 8);
        boolean wordStart = true;
        for (int index = 0; index < text.length();) {
            char source = text.charAt(index);

            if (isApostrophe(source)) {
                if (hasUkrainianLetterBefore(text, index)
                        && hasUkrainianLetterAfter(text, index)) {
                    index++;
                    continue;
                }
                out.append(source);
                wordStart = true;
                index++;
                continue;
            }
            if (source == '\u044c' || source == '\u042c') {
                if (hasUkrainianLetterBefore(text, index)) {
                    index++;
                    continue;
                }
                out.append(source);
                wordStart = false;
                index++;
                continue;
            }

            char lower = Character.toLowerCase(source);
            if (lower == '\u0437' && index + 1 < text.length()
                    && Character.toLowerCase(text.charAt(index + 1)) == '\u0433') {
                char second = text.charAt(index + 1);
                out.append(source == '\u0417'
                        ? (second == '\u0413' ? "ZGH" : "Zgh")
                        : "zgh");
                index += 2;
                wordStart = false;
                continue;
            }

            String mapped = map(lower, wordStart);
            if (mapped == null) {
                out.append(source);
                wordStart = !Character.isLetter(source);
                index++;
                continue;
            }
            out.append(caseMapped(mapped, source, text, index));
            wordStart = false;
            index++;
        }
        return out.toString();
    }

    private static String universal(String text) {
        try {
            synchronized (UNIVERSAL_LOCK) {
                if (universal == null) {
                    universal = Transliterator.getInstance("Any-Latin; Latin-ASCII");
                }
                String transformed = universal.transliterate(text);
                return transformed == null || (!text.isEmpty() && transformed.isEmpty())
                        ? text : transformed;
            }
        } catch (RuntimeException e) {
            return text;
        }
    }

    private static String map(char lower, boolean wordStart) {
        switch (lower) {
            case '\u0430': return "a";
            case '\u0431': return "b";
            case '\u0432': return "v";
            case '\u0433': return "h";
            case '\u0491': return "g";
            case '\u0434': return "d";
            case '\u0435': return "e";
            case '\u0454': return wordStart ? "ye" : "ie";
            case '\u0436': return "zh";
            case '\u0437': return "z";
            case '\u0438': return "y";
            case '\u0456': return "i";
            case '\u0457': return wordStart ? "yi" : "i";
            case '\u0439': return wordStart ? "y" : "i";
            case '\u043a': return "k";
            case '\u043b': return "l";
            case '\u043c': return "m";
            case '\u043d': return "n";
            case '\u043e': return "o";
            case '\u043f': return "p";
            case '\u0440': return "r";
            case '\u0441': return "s";
            case '\u0442': return "t";
            case '\u0443': return "u";
            case '\u0444': return "f";
            case '\u0445': return "kh";
            case '\u0446': return "ts";
            case '\u0447': return "ch";
            case '\u0448': return "sh";
            case '\u0449': return "shch";
            case '\u044e': return wordStart ? "yu" : "iu";
            case '\u044f': return wordStart ? "ya" : "ia";
            default: return null;
        }
    }

    private static String caseMapped(String mapped, char source, String text, int index) {
        if (!Character.isUpperCase(source)) return mapped;
        boolean uppercaseWord = index > 0
                && isUkrainianLetter(text.charAt(index - 1))
                && Character.isUpperCase(text.charAt(index - 1));
        if (!uppercaseWord && index + 1 < text.length()) {
            uppercaseWord = isUkrainianLetter(text.charAt(index + 1))
                    && Character.isUpperCase(text.charAt(index + 1));
        }
        return uppercaseWord ? mapped.toUpperCase(Locale.ROOT)
                : Character.toUpperCase(mapped.charAt(0)) + mapped.substring(1);
    }

    private static boolean isApostrophe(char value) {
        return value == '\'' || value == '\u2019' || value == '\u02bc' || value == '\u0060';
    }

    private static boolean hasUkrainianLetterBefore(String text, int index) {
        return index > 0 && isUkrainianLetter(text.charAt(index - 1));
    }

    private static boolean hasUkrainianLetterAfter(String text, int index) {
        return index + 1 < text.length() && isUkrainianLetter(text.charAt(index + 1));
    }

    private static boolean isUkrainianLetter(char value) {
        char lower = Character.toLowerCase(value);
        return map(lower, false) != null || lower == '\u044c';
    }
}
