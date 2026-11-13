package dev.moon.hotwire.report;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class Diagnosis {
    public static final String FILE = "DIAGNOSIS.txt";
    private static final int MOST_LEADS = 80;
    private static final int MOST_FIRST = 120;
    private static final int MOST_LAST = 280;

    private static final List<String> LEADS = List.of("not run again", "go without hooks", "gained hooks for objects",
            "builds its objects another way", "could not", "cannot", "is left as", "waits for the next start",
            "yet its code ran", "fault", "refused", "threw", "failed", "no longer holds", "was not", "is not ",
            "/WARN]", "/ERROR]");
    private static final List<String> NOT_NAMED = List.of("java.", "javax.", "jdk.", "sun.", "com.sun.",
            "dev.moon.hotwire.", "org.spongepowered.", "com.llamalad7.", "io.netty.", "it.unimi.", "com.google.");

    private Diagnosis() {
    }

    public static String write(String why, Collection<String> messages, Collection<String> frames,
            Collection<String> mods, Collection<String> spans, String hotwireLog) {
        Set<String> terms = findTerms(frames, mods);
        List<String> about = new ArrayList<>();
        List<String> leads = new ArrayList<>();
        for (String line : hotwireLog.lines().toList()) {
            boolean lead = isLead(line);
            if (terms.isEmpty() ? lead : names(line, terms) || isWithin(line, spans)) {
                about.add(line);
                if (lead) {
                    leads.add(line);
                }
            }
        }
        StringBuilder out = new StringBuilder("Why this was written\n  ").append(why).append('\n');
        if (!messages.isEmpty()) {
            out.append("\nThe throw and its causes\n");
            messages.forEach(message -> out.append("  ").append(message).append('\n'));
        }
        if (terms.isEmpty()) {
            out.append("\nNo throw began this report: every line of Hotwire's this session that says something went"
                    + " another way than planned (").append(about.size()).append(")\n");
            append(out, about);
            return out.toString();
        }
        out.append("\nOn its path\n  mods: ").append(mods.isEmpty() ? "none known" : String.join(", ", mods))
                .append("\n  classes: ").append(frames.stream().filter(Diagnosis::isNamed).distinct().limit(40)
                        .toList()).append('\n');
        out.append("\nLeads: lines about them that say something went another way than a launch (")
                .append(leads.size()).append(")\n");
        leads.stream().limit(MOST_LEADS).forEach(line -> out.append("  ").append(line).append('\n'));
        if (leads.size() > MOST_LEADS) {
            out.append("  ... ").append(leads.size() - MOST_LEADS).append(" more, all in the list below\n");
        }
        out.append("\nEvery line of Hotwire's about them, in order (").append(about.size()).append(")\n");
        append(out, about);
        return out.toString();
    }

    public static List<String> readFrames(String trace) {
        List<String> frames = new ArrayList<>();
        for (String line : trace.lines().toList()) {
            String frame = line.strip();
            int end = frame.indexOf('(');
            if (frame.startsWith("at ") && end > 0) {
                String method = frame.substring(Math.max(3, frame.lastIndexOf('/', end) + 1), end);
                int dot = method.lastIndexOf('.');
                if (dot > 0) {
                    frames.add(method.substring(0, dot));
                }
            }
        }
        return frames;
    }

    public static List<String> readMessages(String trace) {
        return trace.lines().map(String::strip).filter(line -> !line.startsWith("at ")
                && (line.startsWith("Caused by: ") || line.matches("[\\w.$]+(Exception|Error)(: .*)?")))
                .distinct().limit(12).toList();
    }

    private static void append(StringBuilder out, List<String> lines) {
        if (lines.size() <= MOST_FIRST + MOST_LAST) {
            lines.forEach(line -> out.append("  ").append(line).append('\n'));
            return;
        }
        lines.subList(0, MOST_FIRST).forEach(line -> out.append("  ").append(line).append('\n'));
        out.append("  ... ").append(lines.size() - MOST_FIRST - MOST_LAST)
                .append(" lines between are in hotwire-latest.log\n");
        lines.subList(lines.size() - MOST_LAST, lines.size()).forEach(line -> out.append("  ").append(line)
                .append('\n'));
    }

    private static Set<String> findTerms(Collection<String> frames, Collection<String> mods) {
        Set<String> terms = new LinkedHashSet<>();
        for (String frame : frames) {
            if (isNamed(frame)) {
                int nested = frame.indexOf('$');
                String outer = nested < 0 ? frame : frame.substring(0, nested);
                terms.add(outer);
                terms.add(outer.replace('.', '/'));
            }
        }
        for (String mod : mods) {
            terms.add(" " + mod + " ");
            terms.add(" " + mod + "'");
            terms.add(" " + mod + ",");
            terms.add(" " + mod + ":");
            terms.add("[" + mod + "]");
            terms.add("[" + mod + ",");
            terms.add(mod + "$");
        }
        return terms;
    }

    private static boolean isNamed(String frame) {
        return NOT_NAMED.stream().noneMatch(frame::startsWith) && frame.indexOf('.') > 0;
    }

    private static boolean names(String line, Set<String> terms) {
        for (String term : terms) {
            if (line.contains(term)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isWithin(String line, Collection<String> spans) {
        if (spans.isEmpty() || line.length() < 10 || line.charAt(0) != '[' || line.charAt(9) != ']') {
            return false;
        }
        String at = line.substring(1, 9);
        for (String span : spans) {
            String from = span.substring(0, 8);
            String to = span.substring(9);

            if (from.compareTo(to) <= 0 ? at.compareTo(from) >= 0 && at.compareTo(to) <= 0
                    : at.compareTo(from) >= 0 || at.compareTo(to) <= 0) {
                return true;
            }
        }
        return false;
    }

    private static boolean isLead(String line) {
        return LEADS.stream().anyMatch(line::contains);
    }
}
