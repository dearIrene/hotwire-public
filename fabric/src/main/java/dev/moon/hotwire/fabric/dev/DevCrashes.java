package dev.moon.hotwire.fabric.dev;

import dev.moon.hotwire.dev.DevCrash;
import dev.moon.hotwire.dev.DevEvent;
import dev.moon.hotwire.ui.Lang;
import dev.moon.hotwire.ui.Notice;
import dev.moon.hotwire.ui.Notices;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class DevCrashes {
    private static final Logger LOGGER = LoggerFactory.getLogger(DevCrashes.class);

    private static final int MOST_CRASHES = 20;

    private static final float CRASH_REST_SECONDS = 10;

    static void onCrash(DevSession session, String id, Throwable thrown, String what, String where, String source,
            int line) {
        SessionMod mod = session.mods.get(id);
        if (mod == null) {
            return;
        }
        long now = System.currentTimeMillis();
        boolean first;
        String kind;
        String place;
        int count;
        synchronized (mod) {
            DevCrash like = where.isEmpty() ? findLike(mod, what) : null;
            kind = like == null ? what : like.what();
            place = like == null ? where : like.where();
            String file = like == null ? source : like.source();
            int at = like == null ? line : like.line();
            String key = kind + " @ " + place;
            DevCrash before = mod.crashes.remove(key);
            first = before == null;
            count = before == null ? 1 : before.count() + 1;
            mod.crashes.put(key, before == null ? new DevCrash(kind, place, 1, now, now, file, at)
                    : new DevCrash(kind, place, before.count() + 1, before.firstAt(), now, file, at));
            while (mod.crashes.size() > MOST_CRASHES) {
                mod.crashes.remove(mod.crashes.keySet().iterator().next());
            }
            if (first) {
                DevSession.add(mod, new DevEvent(now, DevEvent.Kind.CRASH, Lang.get("hotwire.dev.crash",
                        mod.mod.name()),
                        List.of(kind, place)));
            }
        }
        if (first) {
            LOGGER.error("[Hotwire] {} threw at {}; the game keeps going, so fix it and build again", id, place,
                    thrown);
        }

        int build = mod.runningNumber;
        Integer parent;
        boolean back;
        synchronized (mod) {
            mod.throwsByBuild.computeIfAbsent(build, ignored -> new HashMap<>()).merge(
                    Lang.get("hotwire.dev.crash.at", kind, place), 1, Integer::sum);
            parent = mod.parents.get(build);
            Integer to = parent;
            back = session.engine.isAutoRollBackOn() && to != null && build != 0
                    && mod.saved.stream().anyMatch(saved -> saved.number() == to) && mod.autoRolled.add(build);
        }

        if (back && parent != null) {
            Notices.show(Notice.ofAsk(Lang.get("hotwire.dev.auto_back", mod.mod.name(), parent),
                    Lang.get("hotwire.dev.auto_back.why", build, kind, place)));
            LOGGER.warn("[Hotwire] {} build {} threw at {}: rolled back to build {}", id, build, place, parent);
            session.revertTo(id, parent);
            return;
        }
        Notice card = findCrashCard(mod, count == 1 ? Lang.get("hotwire.dev.crash.at", kind, place)
                : Lang.get("hotwire.dev.crash.times", kind, place, count));
        if (mod.crashTold) {
            Notices.replace(card);
        } else {
            mod.crashTold = true;
            Notices.show(card);
        }
    }

    static Map<Integer, List<String>> findThrows(SessionMod mod) {
        Map<Integer, List<String>> found = new HashMap<>();
        synchronized (mod) {
            mod.throwsByBuild.forEach((build, seen) -> found.put(build, seen.entrySet().stream()
                    .map(each -> each.getValue() == 1 ? each.getKey()
                            : Lang.get("hotwire.dev.report.times", each.getKey(), each.getValue()))
                    .toList()));
        }
        return found;
    }

    private static @Nullable DevCrash findLike(SessionMod mod, String what) {
        for (DevCrash seen : List.copyOf(mod.crashes.values()).reversed()) {
            if (seen.what().equals(what) || seen.what().startsWith(what + ":")) {
                return seen;
            }
        }
        return null;
    }

    static Notice findCrashCard(SessionMod mod, String detail) {
        return Notice.ofBad(Lang.get("hotwire.dev.crash", mod.mod.name()), detail).makeResting(CRASH_REST_SECONDS);
    }

    static void clearCrashCard(SessionMod mod) {
        mod.crashTold = false;
        Notices.drop(findCrashCard(mod, ""));
    }

    private DevCrashes() {
    }
}
