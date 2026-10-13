package dev.moon.hotwire.jar;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public record ModManifest(
        String id,
        String version,
        String name,
        String description,
        List<String> authors,
        Optional<String> icon,
        Environment environment,
        Map<String, List<Entrypoint>> entrypoints,
        List<MixinConfigRef> mixinConfigs,
        Optional<String> accessWidener,
        Map<Relation, Map<String, List<String>>> relations,
        List<String> provides,
        List<String> nestedJars,
        Optional<String> menuParent,
        boolean library) {
    public ModManifest {
        authors = List.copyOf(authors);
        entrypoints = Map.copyOf(entrypoints);
        mixinConfigs = List.copyOf(mixinConfigs);
        relations = Map.copyOf(relations);
        provides = List.copyOf(provides);
        nestedJars = List.copyOf(nestedJars);
    }

    public Map<String, List<String>> getRelation(Relation relation) {
        return this.relations.getOrDefault(relation, Map.of());
    }

    public enum Environment {
        CLIENT, SERVER, BOTH
    }

    public enum Relation {
        DEPENDS("depends"), RECOMMENDS("recommends"), SUGGESTS("suggests"), BREAKS("breaks"), CONFLICTS("conflicts");

        private final String key;

        Relation(String key) {
            this.key = key;
        }

        String getKey() {
            return this.key;
        }
    }

    public record Entrypoint(String value, String adapter) {
        static final String DEFAULT_ADAPTER = "default";
    }

    public record MixinConfigRef(String config, Environment environment) {
    }
}
