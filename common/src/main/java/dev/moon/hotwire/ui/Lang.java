package dev.moon.hotwire.ui;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.IllegalFormatException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

public final class Lang {
    private static final String ENGLISH_FILE = "assets/hotwire/lang/en_us.json";
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

    @SuppressWarnings("AnnotateFormatMethod")
    public static String fill(String format, @Nullable Object... values) {
        if (values.length == 0) {
            return format;
        }
        try {
            return String.format(Locale.ROOT, format, values);
        } catch (IllegalFormatException e) {
            return format;
        }
    }

}
