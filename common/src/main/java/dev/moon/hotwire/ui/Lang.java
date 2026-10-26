package dev.moon.hotwire.ui;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.IllegalFormatException;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiFunction;
import org.jspecify.annotations.Nullable;

public final class Lang {
    private static final String ENGLISH_FILE = "/assets/hotwire/lang/en_us.json";
    private static volatile BiFunction<String, @Nullable Object[], @Nullable String> translator = Lang::getEnglish;

    private Lang() {
    }

    public static void useTranslator(BiFunction<String, @Nullable Object[], @Nullable String> translate) {
        translator = translate;
    }

    public static String get(String key, @Nullable Object... values) {
        String said = translator.apply(key, values);
        return said == null ? getEnglish(key, values) : said;
    }

    public static String getEnglish(String key, @Nullable Object... values) {
        return fill(English.WORDS.getOrDefault(key, key), values);
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

    private static final class English {
        static final Map<String, String> WORDS = read();

        private static Map<String, String> read() {
            try (InputStream in = Lang.class.getResourceAsStream(ENGLISH_FILE)) {
                if (in == null) {
                    return Map.of();
                }
                Map<String, String> words = new Gson().fromJson(new InputStreamReader(in, StandardCharsets.UTF_8),
                        new TypeToken<Map<String, String>>() { }.getType());
                return words == null ? Map.of() : Map.copyOf(words);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }
}
