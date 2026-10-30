package dev.moon.hotwire.modrinth;

import java.util.List;

public final class StoreCategories {
    public record Group(String name, List<String> parts) {
        public Group {
            parts = List.copyOf(parts);
        }

        static Group of(String name) {
            return new Group(name, List.of());
        }
    }

    private StoreCategories() {
    }

    public static List<Group> of(ModrinthClient.Kind kind) {
        Group all = Group.of("");
        return switch (kind) {
            case MOD, DATA_PACK -> List.of(all, Group.of("optimization"),
                    new Group("gameplay", List.of("adventure", "game-mechanics", "mobs", "magic", "minigame", "food",
                            "equipment")),
                    new Group("world", List.of("worldgen", "decoration", "storage", "technology", "transportation")),
                    new Group("utility", List.of("utility", "management", "social", "economy")),
                    Group.of("library"), Group.of("cursed"));
            case RESOURCE_PACK -> List.of(all,
                    new Group("style", List.of("vanilla-like", "simplistic", "realistic", "themed", "modded",
                            "cursed")),
                    Group.of("tweaks"), Group.of("gui"), Group.of("audio"), Group.of("models"),
                    new Group("features", List.of("blocks", "entities", "items", "equipment", "environment", "fonts",
                            "core-shaders", "locale", "combat", "decoration", "utility")),
                    new Group("resolution", List.of("8x-", "16x", "32x", "48x", "64x", "128x", "256x", "512x+")));
            case SHADER -> List.of(all,
                    new Group("style", List.of("vanilla-like", "semi-realistic", "realistic", "fantasy", "cartoon",
                            "cursed")),
                    new Group("features", List.of("atmosphere", "bloom", "colored-lighting", "foliage", "path-tracing",
                            "pbr", "reflections", "shadows")),
                    new Group("performance", List.of("potato", "low", "medium", "high", "screenshot")));
        };
    }
}
