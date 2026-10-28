package dev.moon.hotwire.registration;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.jspecify.annotations.Nullable;

public final class ModNames {
    private static final int WORD_LENGTH = 5;

    private final Map<String, Integer> places = new HashMap<>();

    private final Map<String, List<Word>> byFirstWord = new HashMap<>();
    private final List<String> mods;

    private record Word(String bounded, int place) {
    }

    public ModNames(List<String> mods) {
        this.mods = List.copyOf(mods);
        for (int place = 0; place < this.mods.size(); place++) {
            String mod = this.mods.get(place);
            this.places.putIfAbsent(mod, place);
            String id = toWords(mod);
            if (id.length() >= WORD_LENGTH) {
                int end = id.indexOf('_');
                String first = end < 0 ? id : id.substring(0, end);
                this.byFirstWord.computeIfAbsent(first, any -> new ArrayList<>()).add(new Word("_" + id + "_", place));
            }
        }
    }

    public @Nullable String find(String key) {
        int best = this.findPlaces(key).min().orElse(Integer.MAX_VALUE);
        return best == Integer.MAX_VALUE ? null : this.mods.get(best);
    }

    public Set<String> findAll(String key) {
        return this.findPlaces(key).mapToObj(this.mods::get).collect(Collectors.toSet());
    }

    private IntStream findPlaces(String key) {
        IntStream.Builder found = IntStream.builder();
        Integer whole = this.places.get(key);
        if (whole != null) {
            found.add(whole);
        }
        for (int dot = key.indexOf('.'); dot >= 0; dot = key.indexOf('.', dot + 1)) {
            Integer named = this.places.get(key.substring(0, dot));
            if (named != null) {
                found.add(named);
            }
        }
        if (!this.byFirstWord.isEmpty()) {
            String words = "_" + toWords(key) + "_";
            int start = 1;
            while (start < words.length()) {
                int end = words.indexOf('_', start);
                for (Word word : this.byFirstWord.getOrDefault(words.substring(start, end), List.of())) {
                    if (words.contains(word.bounded())) {
                        found.add(word.place());
                    }
                }
                start = end + 1;
            }
        }
        return found.build();
    }

    static String toWords(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        StringBuilder out = new StringBuilder(lower.length());
        boolean gap = false;
        for (int at = 0; at < lower.length(); at++) {
            char one = lower.charAt(at);
            if ((one >= 'a' && one <= 'z') || (one >= '0' && one <= '9')) {
                out.append(one);
                gap = false;
            } else if (!gap) {
                out.append('_');
                gap = true;
            }
        }
        return out.toString();
    }
}
