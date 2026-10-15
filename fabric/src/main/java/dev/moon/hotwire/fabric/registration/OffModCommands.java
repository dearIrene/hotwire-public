package dev.moon.hotwire.fabric.registration;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.tree.CommandNode;
import dev.moon.hotwire.fabric.mixin.core.brigadier.tree.CommandNodeAccessor;
import dev.moon.hotwire.plan.ModGraph;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class OffModCommands {
    private static final Logger LOGGER = LoggerFactory.getLogger(OffModCommands.class);

    private record Removal(CommandDispatcher<?> dispatcher, CommandNode<?> parent, CommandNode<?> node,
            Set<String> mods, boolean client) {
    }

    private static final List<Removal> REMOVED = new ArrayList<>();
    private static volatile @Nullable Supplier<ModGraph> mods;

    private OffModCommands() {
    }

    public static synchronized void takeOut(Predicate<String> off) {
        for (boolean client : List.of(false, true)) {
            CommandDispatcher<?> dispatcher = CommandRegistration.findActiveDispatcher(client);
            if (dispatcher == null) {
                continue;
            }
            List<String> removedNames = new ArrayList<>();
            for (CommandNode<?> command : List.copyOf(dispatcher.getRoot().getChildren())) {
                Set<String> mods = findMods(command);
                if (mods.isEmpty()) {
                    continue;
                }
                if (mods.stream().allMatch(off)) {
                    take(dispatcher, dispatcher.getRoot(), command, mods, client, removedNames);
                    continue;
                }
                if (mods.stream().anyMatch(off)) {
                    LOGGER.info("[Hotwire] /{} runs {}, of which {} stay on, so it stays", command.getName(), mods,
                            mods.stream().filter(off.negate()).toList());
                }
                for (CommandNode<?> part : List.copyOf(command.getChildren())) {
                    Set<String> partMods = findMods(part);
                    if (!partMods.isEmpty() && partMods.stream().allMatch(off)) {
                        take(dispatcher, command, part, partMods, client, removedNames);
                    }
                }
            }
            if (!removedNames.isEmpty()) {
                LOGGER.info("[Hotwire] the {} commands {} run only off mods' code and go out",
                        client ? "client" : "server", removedNames);
                CommandRegistration.resendCommands(client);
            }
        }
    }

    @SuppressWarnings("ReferenceEquality")
    public static synchronized void putBack(Predicate<String> on) {
        List<Boolean> resend = new ArrayList<>();
        REMOVED.removeIf(removal -> {
            if (removal.dispatcher() != CommandRegistration.findActiveDispatcher(removal.client())) {
                return true;
            }
            if (!removal.mods().stream().allMatch(on)) {
                return false;
            }
            if (removal.parent().getChild(removal.node().getName()) == null) {
                addChild(removal.parent(), removal.node());
                resend.add(removal.client());
            }
            return true;
        });
        resend.stream().distinct().forEach(CommandRegistration::resendCommands);
    }

    @SuppressWarnings("ReferenceEquality")
    private static void take(CommandDispatcher<?> dispatcher, CommandNode<?> parent, CommandNode<?> node,
            Set<String> mods, boolean client, Collection<String> removedNames) {
        CommandNodeAccessor from = (CommandNodeAccessor) parent;
        from.hotwire$getChildren().remove(node.getName());
        from.hotwire$getLiterals().remove(node.getName());
        REMOVED.add(new Removal(dispatcher, parent, node, mods, client));
        removedNames.add((parent == dispatcher.getRoot() ? "/" : "/" + parent.getName() + " ") + node.getName());
    }

    private static Set<String> findMods(CommandNode<?> node) {
        Set<String> mods = CommandRecorder.findModsRun(node).stream().filter(GameRegistrations::isSwitchable)
                .collect(Collectors.toSet());
        Set<String> owners = mods.stream().filter(mod -> mods.stream().noneMatch(user -> isDependentOf(user, mod)))
                .collect(Collectors.toSet());
        return owners.isEmpty() ? mods : owners;
    }

    public static void readUsesFrom(Supplier<ModGraph> graph) {
        mods = graph;
    }

    private static boolean isDependentOf(String user, String library) {
        Supplier<ModGraph> graph = mods;
        return graph != null && graph.get().getUses(user).contains(library);
    }

    @SuppressWarnings("unchecked")
    private static <S> void addChild(CommandNode<S> parent, CommandNode<?> node) {
        parent.addChild((CommandNode<S>) node);
    }
}
