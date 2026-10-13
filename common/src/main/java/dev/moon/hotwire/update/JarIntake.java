package dev.moon.hotwire.update;

import dev.moon.hotwire.jar.ModManifest;
import java.util.Comparator;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

public final class JarIntake {
    private static final Set<String> REFUSED = Set.of("ixeris");

    private JarIntake() {
    }

    public enum Kind {
        ADD,
        UPDATE,
        DOWNGRADE,
        SAME_VERSION,
        NOT_A_MOD,
        REFUSED
    }

    public static Kind classify(@Nullable ModManifest jar, Map<String, String> installed,
            Comparator<String> versionOrder) {
        if (jar == null) {
            return Kind.NOT_A_MOD;
        }
        if (REFUSED.contains(jar.id())) {
            return Kind.REFUSED;
        }
        String running = installed.get(jar.id());
        if (running == null) {
            return Kind.ADD;
        }
        int order = versionOrder.compare(jar.version(), running);
        if (order == 0) {
            return Kind.SAME_VERSION;
        }
        return order > 0 ? Kind.UPDATE : Kind.DOWNGRADE;
    }
}
