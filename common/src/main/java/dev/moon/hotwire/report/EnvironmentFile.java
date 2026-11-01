package dev.moon.hotwire.report;

import java.util.List;

public final class EnvironmentFile {
    static final String HEAD = "# Hotwire environment 1";
    static final String COLUMNS = "id, version, file, bytes, sha1, state";

    public record Jar(String id, String version, String file, long bytes, String sha1, String state) {
    }

    private EnvironmentFile() {
    }

    public static String write(List<String> details, List<Jar> atStart, List<Jar> since, String settings) {
        StringBuilder out = new StringBuilder(HEAD).append('\n');
        details.forEach(detail -> out.append(detail).append('\n'));
        writeJars(out, "mods at the start", atStart);
        writeJars(out, "jars added or updated since", since);
        out.append("\n== settings.json\n").append(settings.strip()).append('\n');
        return out.toString();
    }

    private static void writeJars(StringBuilder out, String what, List<Jar> jars) {
        out.append("\n== ").append(what).append(" (").append(jars.size()).append("): ").append(COLUMNS).append('\n');
        for (Jar jar : jars) {
            out.append(jar.id()).append('\t').append(jar.version()).append('\t').append(jar.file()).append('\t')
                    .append(jar.bytes()).append('\t').append(jar.sha1()).append('\t').append(jar.state()).append('\n');
        }
    }
}
