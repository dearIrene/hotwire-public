package dev.moon.hotwire.registration;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

public final class ReloadedEntryNeeds {
    private static final List<String> SHADER_FILES = List.of(".vsh", ".fsh", ".json");

    private ReloadedEntryNeeds() {
    }

    public static Set<String> find(Collection<String> ids, Set<String> off, Map<String, String> onlyIn) {
        Set<String> needs = new TreeSet<>();
        for (String id : ids) {
            int colon = id.indexOf(':');
            if (colon < 0) {
                continue;
            }
            String namespace = id.substring(0, colon);
            if (off.contains(namespace)) {
                needs.add(namespace);
            }
            String file = namespace + ":shaders/" + id.substring(colon + 1);
            for (String extension : SHADER_FILES) {
                String mod = onlyIn.get(file + extension);
                if (mod != null) {
                    needs.add(mod);
                }
            }
        }
        return needs;
    }
}
