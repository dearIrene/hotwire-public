package dev.moon.hotwire.seam;

import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

public record SharedChange(Kind kind, String mod, String project, String version) {
    public enum Kind {
        ADD(0), ON(1), OFF(2);

        private final int number;

        Kind(int number) {
            this.number = number;
        }

        public int getNumber() {
            return this.number;
        }
    }

    public static final int MOST = 32;

    public static final int LONGEST = 64;

    private static final Pattern MOD = Pattern.compile("[a-z][a-z0-9_-]{1,63}");
    private static final Pattern MODRINTH = Pattern.compile("[A-Za-z0-9]{8}");

    public static @Nullable SharedChange of(int number, String mod, String project, String version) {
        for (Kind kind : Kind.values()) {
            if (kind.number == number) {
                return of(kind, mod, project, version);
            }
        }
        return null;
    }

    public static @Nullable SharedChange of(Kind kind, String mod, String project, String version) {
        boolean add = kind == Kind.ADD;
        boolean named = add ? MODRINTH.matcher(project).matches() : project.isEmpty();
        boolean versioned = version.isEmpty() || (add && MODRINTH.matcher(version).matches());
        return MOD.matcher(mod).matches() && named && versioned ? new SharedChange(kind, mod, project, version)
                : null;
    }
}
