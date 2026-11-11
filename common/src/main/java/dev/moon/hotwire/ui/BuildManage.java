package dev.moon.hotwire.ui;

import dev.moon.hotwire.dev.DevBuildInfo;
import dev.moon.hotwire.dev.DevClassChange;
import dev.moon.hotwire.dev.DevModView;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

final class BuildManage {
    static final long BIG_FLOOR = 2L * 1024 * 1024;
    static final long MODERATE_FLOOR = 512L * 1024;

    enum Weight { NORMAL, MODERATE, BIG }

    private static final long DAY_MILLIS = 24L * 60 * 60 * 1000;

    private BuildManage() {
    }

    static Weight findWeight(long size, Collection<Long> sizes) {
        List<Long> sorted = sizes.stream().sorted().toList();
        long middle = sorted.isEmpty() ? 0 : sorted.get(sorted.size() / 2);
        if (size >= Math.max(4 * middle, BIG_FLOOR)) {
            return Weight.BIG;
        }
        return size >= Math.max(2 * middle, MODERATE_FLOOR) ? Weight.MODERATE : Weight.NORMAL;
    }

    static Weight findAge(long at, long now) {
        long age = now - at;
        if (age < DAY_MILLIS) {
            return Weight.NORMAL;
        }
        return age < 7 * DAY_MILLIS ? Weight.MODERATE : Weight.BIG;
    }

    static String describeDate(long at, ZoneId zone, Locale locale, boolean seconds) {
        return DateTimeFormatter.ofPattern(seconds ? "MMM d HH:mm:ss" : "MMM d HH:mm", locale)
                .format(Instant.ofEpochMilli(at).atZone(zone));
    }

    static String describeShortDate(long at, long now, ZoneId zone, Locale locale) {
        ZonedDateTime when = Instant.ofEpochMilli(at).atZone(zone);
        boolean today = when.toLocalDate().equals(Instant.ofEpochMilli(now).atZone(zone).toLocalDate());
        return DateTimeFormatter.ofPattern(today ? "HH:mm" : "MMM d", locale).format(when);
    }

    static int findColor(Weight weight) {
        return switch (weight) {
            case BIG -> Palette.BAD;
            case MODERATE -> Palette.WARN;
            case NORMAL -> Palette.GOOD;
        };
    }

    static boolean canClear(DevModView mod, int build) {
        return build != mod.runningBuild() && build != mod.codeBuild() && build != 0
                && mod.savedBuilds().contains(build);
    }

    static List<Integer> pickAllButNewest(DevModView mod, int left) {
        List<Integer> clearable = new ArrayList<>(mod.savedBuilds().stream().filter(build -> canClear(mod, build))
                .sorted((a, b) -> Integer.compare(b, a)).toList());
        return clearable.size() <= left ? List.of() : List.copyOf(clearable.subList(left, clearable.size()));
    }

    static List<Integer> pickOlderThan(DevModView mod, long millis, long now) {
        return mod.savedBuilds().stream().filter(build -> canClear(mod, build)).filter(build -> {
            DevBuildInfo info = mod.infos().get(build);
            return info != null && info.at() < now - millis;
        }).toList();
    }

    static long sumSizes(DevModView mod, Collection<Integer> builds) {
        long sum = 0;
        for (int build : builds) {
            sum += mod.sizes().getOrDefault(build, 0L);
        }
        return sum;
    }

    static int[] countLines(DevClassChange change) {
        int[] counted = new int[2];
        for (List<String> diff : change.diffs().values()) {
            for (String line : diff) {
                if (line.startsWith("+")) {
                    counted[0]++;
                } else if (line.startsWith("-")) {
                    counted[1]++;
                }
            }
        }
        return counted;
    }
}
