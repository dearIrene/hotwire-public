package dev.moon.hotwire.seam;

import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

public record SharedMod(String mod, boolean on, String version, String project) {
    public static final int LONGEST = 64;
    private static final Pattern MOD = Pattern.compile("[a-z][a-z0-9_-]{1,63}");
    private static final Pattern VERSION = Pattern.compile("[A-Za-z0-9._+-]{1,64}");
    private static final Pattern MODRINTH = Pattern.compile("[A-Za-z0-9]{8}");

    public static @Nullable SharedMod of(String mod, boolean on, String version, String project) {
        boolean known = project.isEmpty() || MODRINTH.matcher(project).matches();
        return MOD.matcher(mod).matches() && VERSION.matcher(version).matches() && known
                ? new SharedMod(mod, on, version, project) : null;
    }
}
