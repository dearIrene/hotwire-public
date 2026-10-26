package dev.moon.hotwire.report;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

public record CrashSection(List<Detail> details) {
    public static final String TITLE = "Hotwire";
    private static final String OWN_PACKAGE = "dev.moon.hotwire.";
    private static final String OUT_OF_MEMORY = OutOfMemoryError.class.getName();
    private static final Pattern OWN_MIXIN = Pattern.compile("from mod (hotwire[\\w-]*)");

    public record Detail(String name, String value) {
    }

    public CrashSection {
        details = List.copyOf(details);
    }

    public static CrashSection of(Collection<String> off, Collection<String> added, Collection<String> updated,
            Collection<String> redefined, @Nullable Report last, Function<String, String> nameOf) {
        return of(off, added, updated, redefined, last, nameOf, List.of());
    }

    public static CrashSection of(Collection<String> off, Collection<String> added, Collection<String> updated,
            Collection<String> redefined, @Nullable Report last, Function<String, String> nameOf,
            List<String> story) {
        return new CrashSection(List.of(
                new Detail("Session", story.isEmpty() ? "no switch, add or update yet" : String.join("; ", story)),
                new Detail("Mods turned off", joinNames(off, nameOf)),
                new Detail("Mods added", joinNames(added, nameOf)),
                new Detail("Mods updated", joinNames(updated, nameOf)),
                new Detail("Classes redefined", redefined.isEmpty() ? "none"
                        : redefined.size() + ": " + String.join(", ", redefined.stream().sorted().toList())),
                new Detail("Last operation", last == null ? "none" : describeLastOperation(last))));
    }

    public static boolean isCleared(String verdict) {
        return verdict.startsWith("Hotwire involved? NO");
    }

    public static Detail describeTrace(Collection<String> frames, Function<String, @Nullable String> rebuiltBy,
            Set<String> switched, Function<String, @Nullable String> modOf, Function<String, String> nameOf) {
        return describeTrace(frames, List.of(), rebuiltBy, switched, modOf, nameOf);
    }

    public static Detail describeTrace(Collection<String> frames, Collection<String> messages,
            Function<String, @Nullable String> rebuiltBy, Set<String> switched,
            Function<String, @Nullable String> modOf, Function<String, String> nameOf) {
        Set<String> own = new TreeSet<>();
        for (String frame : frames) {
            if (frame.startsWith(OWN_PACKAGE)) {
                own.add(frame);
            }
        }
        String mixin = null;
        for (String message : messages) {
            Matcher found = OWN_MIXIN.matcher(message);
            if (found.find()) {
                mixin = found.group(1);
                break;
            }
        }
        Set<String> touched = new TreeSet<>();
        Set<String> by = new TreeSet<>();
        Set<String> mods = new TreeSet<>();
        for (String frame : frames) {
            String line = rebuiltBy.apply(frame.replace('.', '/'));
            if (line != null) {
                touched.add(frame);
                by.add(line);
            }
            String mod = modOf.apply(frame);
            if (mod != null && switched.contains(mod)) {
                mods.add(nameOf.apply(mod));
            }
        }

        if (own.isEmpty() && mixin == null && messages.stream().anyMatch(one -> one.startsWith(OUT_OF_MEMORY))) {
            return new Detail("This crash", "Hotwire involved? UNKNOWN. The game ran out of memory, and the trace"
                    + " shows only where, not what filled it. To tell: play the same way without switching; still"
                    + " runs out, not Hotwire's; does not, send the newest zip under hotwire/reports.");
        }
        if (touched.isEmpty() && mods.isEmpty() && own.isEmpty() && mixin == null) {
            return new Detail("This crash", "Hotwire involved? NO. No class Hotwire redefined, no mod it switched"
                    + " and none of its own code is in the trace.");
        }
        StringBuilder out = new StringBuilder("Hotwire involved? YES.");
        if (mixin != null) {
            out.append(" A mixin of ").append(mixin).append(" failed to apply.");
        }
        if (!own.isEmpty()) {
            out.append(" Hotwire's own code is in the trace: ").append(String.join(", ", own)).append('.');
        }
        if (touched.isEmpty() && mods.isEmpty()) {
            return new Detail("This crash", out + " This one is Hotwire's: send the newest zip under"
                    + " hotwire/reports.");
        }
        if (!touched.isEmpty()) {
            out.append(" Redefined classes in the trace: ").append(String.join(", ", touched)).append(" (by '")
                    .append(String.join("', '", by)).append("').");
        }
        if (!mods.isEmpty()) {
            out.append(" Switched mods in the trace: ").append(String.join(", ", mods)).append('.');
        }
        return new Detail("This crash", out + " To tell whose bug: reproduce without that switch; still"
                + " crashes, the mod's; does not, Hotwire's, send the newest zip under hotwire/reports.");
    }

    private static String describeLastOperation(Report last) {
        String what = last.operation().getWords() + " " + joinNames(last.plan().order(), last::nameOf);
        if (last.plan().isRefused()) {
            return what + ", refused";
        }
        if (!last.failures().isEmpty()) {
            Report.Cause first = last.failures().getFirst().causes().getFirst();
            String message = first.message();
            return what + ", failed: " + (message == null ? first.type() : message);
        }
        return what + ", done, " + last.rebuilt().size() + " classes rebuilt, off check " + switch (last.offCheck()) {
            case Report.OffCheck.NotRun notRun -> "not run";
            case Report.OffCheck.AllOff allOff -> "passed";
            case Report.OffCheck.PartlyOff partly -> "found " + String.join(", ",
                    partly.stillLive().keySet().stream().sorted().map(last::nameOf).toList()) + " still live";
        };
    }

    private static String joinNames(Collection<String> ids, Function<String, String> nameOf) {
        return ids.isEmpty() ? "none" : String.join(", ", ids.stream().sorted().map(nameOf).toList());
    }
}
