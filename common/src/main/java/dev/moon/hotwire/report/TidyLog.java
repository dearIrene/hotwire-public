package dev.moon.hotwire.report;

import java.util.List;

public final class TidyLog {
    public record Mod(String name, String version, State state) {
    }

    public enum State {
        ON("[✔]", ""), OFF("[✘]", " (off)"), ADDED("[+]", " (added this session)");

        private final String mark;
        private final String note;

        State(String mark, String note) {
            this.mark = mark;
            this.note = note;
        }
    }

    static final String HEAD = "Tidied by Hotwire (Thanks prism launcher for the style :3, use it for all ur logs to"
            + " look this pretty!)";

    private TidyLog() {
    }

    public static String write(String why, String when, List<String> facts, List<Mod> mods, String log) {
        StringBuilder out = new StringBuilder();
        out.append(HEAD).append('\n');
        out.append("Names, logins and addresses are taken out.\n\n");
        out.append("Why: ").append(why).append('\n');
        out.append("When: ").append(when).append("\n\n");
        facts.forEach(fact -> out.append(fact).append('\n'));
        long off = mods.stream().filter(mod -> mod.state() == State.OFF).count();
        out.append("\nMods (").append(mods.size()).append(off == 0 ? "" : ", " + off + " off").append("):\n");
        for (Mod mod : mods) {
            out.append("  ").append(mod.state().mark).append(' ').append(mod.name()).append(' ')
                    .append(mod.version()).append(mod.state().note).append('\n');
        }
        out.append("\n---- The game's log ----\n").append(log);
        return out.toString();
    }
}
