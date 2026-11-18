package dev.moon.hotwire.ui;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

public final class Lang {
    private static final String ENGLISH_FILE = "assets/hotwire/lang/en_us.json";
    private static final Pattern BLANK = Pattern.compile("%(?:([1-9][0-9]?)\\$)?([sd%])");
    private static volatile BiFunction<String, @Nullable Object[], @Nullable String> translator = Lang::getEnglish;

    private static volatile @Nullable Map<String, String> english;
    private static volatile Supplier<@Nullable InputStream> englishFrom = () -> null;

    private Lang() {
    }

    public static void useTranslator(BiFunction<String, @Nullable Object[], @Nullable String> translate) {
        translator = translate;
    }

    public static String get(String key, @Nullable Object... values) {
        String said = translator.apply(key, values);
        return said == null ? getEnglish(key, values) : said;
    }

    public static void useEnglishFrom(Supplier<@Nullable InputStream> from) {
        englishFrom = from;
        english = null;
    }

    public static String getEnglish(String key, @Nullable Object... values) {
        return fill(findEnglish().getOrDefault(key, key), values);
    }

    private static Map<String, String> findEnglish() {
        Map<String, String> words = english;
        if (words != null) {
            return words;
        }
        words = readEnglish();
        if (!words.isEmpty()) {
            english = words;
        }
        return words;
    }

    private static Map<String, String> readEnglish() {
        List<Supplier<@Nullable InputStream>> sources = List.of(
                () -> Lang.class.getResourceAsStream("/" + ENGLISH_FILE),
                () -> {
                    ClassLoader own = Lang.class.getClassLoader();
                    return own == null ? null : own.getResourceAsStream(ENGLISH_FILE);
                },
                () -> {
                    ClassLoader context = Thread.currentThread().getContextClassLoader();
                    return context == null ? null : context.getResourceAsStream(ENGLISH_FILE);
                },
                englishFrom);
        for (Supplier<@Nullable InputStream> source : sources) {
            try (InputStream in = source.get()) {
                if (in == null) {
                    continue;
                }
                Map<String, String> words = new Gson().fromJson(new InputStreamReader(in, StandardCharsets.UTF_8),
                        new TypeToken<Map<String, String>>() { }.getType());
                if (words != null && !words.isEmpty()) {
                    return Map.copyOf(words);
                }
            } catch (IOException | RuntimeException e) {
            }
        }
        return Map.of();
    }

    public static String fill(String format, @Nullable Object... values) {
        if (values.length == 0) {
            return format;
        }
        Matcher blank = BLANK.matcher(format);
        StringBuilder out = new StringBuilder(format.length() + 16);
        int sequence = 0;
        int last = 0;
        while (blank.find()) {
            String number = blank.group(1);
            String kind = blank.group(2);

            if (format.indexOf('%', last) < blank.start() || (number != null && kind.equals("%"))) {
                return format;
            }
            out.append(format, last, blank.start());
            last = blank.end();
            if (kind.equals("%")) {
                out.append('%');
                continue;
            }
            int index = number == null ? sequence++ : Integer.parseInt(number) - 1;
            if (index >= values.length || (kind.equals("d") && !isWhole(values[index]))) {
                return format;
            }
            out.append(values[index]);
        }
        if (format.indexOf('%', last) >= 0) {
            return format;
        }
        return out.append(format, last, format.length()).toString();
    }

    private static boolean isWhole(@Nullable Object value) {
        return value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte;
    }

}
