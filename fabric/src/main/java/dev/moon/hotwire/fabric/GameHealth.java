package dev.moon.hotwire.fabric;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluids;

final class GameHealth {
    private GameHealth() {
    }

    static List<String> findProblems() {
        List<String> problems = new ArrayList<>();
        check(BuiltInRegistries.BLOCK, Blocks.STONE, "block", problems);
        check(BuiltInRegistries.ITEM, Items.STONE, "item", problems);
        check(BuiltInRegistries.FLUID, Fluids.WATER, "fluid", problems);
        return problems;
    }

    @SuppressWarnings("ReferenceEquality")
    private static <T> void check(Registry<T> registry, T entry, String what, List<String> problems) {
        ResourceKey<T> key = registry.getResourceKey(entry).orElse(null);
        if (key == null) {
            problems.add("the " + what + " registry no longer knows " + entry);
            return;
        }
        //? if >=1.21.11 {
        ResourceKey<T> made = ResourceKey.create(registry.key(), key.identifier());
        //?} else {
        /*ResourceKey<T> made = ResourceKey.create(registry.key(), key.location());
        *///?}
        if (made != key) {
            problems.add("a new key for " + key + " is not the one the " + what + " registry holds");
        }
        //? if >=1.21.2 {
        T found = registry.getValue(made);
        //?} else {
        /*T found = registry.get(made);
        *///?}
        if (found != entry) {
            problems.add("the " + what + " registry no longer finds " + key + " by its key");
        }
    }
}
