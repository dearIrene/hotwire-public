package dev.moon.hotwire.update;

import dev.moon.hotwire.modrinth.ModrinthClient;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

public final class HotwireChanges {
    private static final int MOST_VERSIONS = 8;

    private HotwireChanges() {
    }

    public static String join(List<ModrinthClient.Version> versions, Predicate<String> newer) {
        List<String> numbers = new ArrayList<>();
        List<String> texts = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ModrinthClient.Version version : versions) {
            String number = stripBuild(version.number());
            if (numbers.size() < MOST_VERSIONS && newer.test(version.number()) && seen.add(number)) {
                String text = withoutMark(version.changelog());
                if (!text.isBlank()) {
                    numbers.add(number);
                    texts.add(text);
                }
            }
        }
        if (texts.size() == 1) {
            return texts.getFirst();
        }
        StringBuilder all = new StringBuilder();
        for (int at = 0; at < texts.size(); at++) {
            all.append("## ").append(numbers.get(at)).append("\n\n").append(texts.get(at)).append("\n\n");
        }
        return all.toString().strip();
    }

    private static String withoutMark(@Nullable String changelog) {
        return changelog == null ? "" : changelog.lines().filter(line -> !UrgentUpdate.isMark(line))
                .collect(Collectors.joining("\n")).strip();
    }

    private static String stripBuild(String number) {
        int build = number.indexOf('+');
        return build > 0 ? number.substring(0, build) : number;
    }
}
