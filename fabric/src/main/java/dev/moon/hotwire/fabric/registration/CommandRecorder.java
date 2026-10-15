package dev.moon.hotwire.fabric.registration;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.tree.ArgumentCommandNode;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.RootCommandNode;
import dev.moon.hotwire.registration.CallerOwner;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.fabricmc.fabric.impl.command.client.ClientCommandInternals;
import org.jspecify.annotations.Nullable;

public final class CommandRecorder {
    private static final int DEPTH = 8;

    private CommandRecorder() {
    }

    @SuppressWarnings("ReferenceEquality")
    public static void onAdded(RootCommandNode<?> root, CommandNode<?> node) {
        if (root.getChild(node.getName()) != node || !RecorderSupport.isFirst(node)) {
            return;
        }
        CallerOwner.Owner caller = RecorderSupport.findOwner();
        String owner = findOwner(findModsRun(node), caller == null ? null : caller.mod());
        if (owner != null) {
            CommandDispatcher<?> client = ClientCommandInternals.getActiveDispatcher();
            RecorderSupport.recordAs(owner, node, new CommandRegistration(root, node.getName(), owner,
                    client != null && client.getRoot() == root));
        }
    }

    private static @Nullable String findOwner(Set<String> runs, @Nullable String caller) {
        List<String> libraries = new ArrayList<>();
        for (String mod : runs) {
            if (!GameRegistrations.isSwitchable(mod) || mod.equals(caller)) {
                continue;
            }
            if (!GameRegistrations.getOwners().isLibrary(mod)) {
                return mod;
            }
            libraries.add(mod);
        }
        return caller != null || libraries.isEmpty() ? caller : libraries.getFirst();
    }

    public static Set<String> findModsRun(CommandNode<?> node) {
        Set<String> mods = new LinkedHashSet<>();
        collect(node, DEPTH, mods, Collections.newSetFromMap(new IdentityHashMap<>()));
        return mods;
    }

    private static void collect(CommandNode<?> node, int depth, Set<String> mods, Set<CommandNode<?>> seen) {
        if (depth == 0 || !seen.add(node)) {
            return;
        }
        List<@Nullable Object> code = new ArrayList<>(List.of(node.getRequirement()));
        code.add(node.getCommand());
        code.add(node.getRedirectModifier());
        if (node instanceof ArgumentCommandNode<?, ?> argument) {
            code.add(argument.getCustomSuggestions());
            code.add(argument.getType());
        }
        for (Object one : code) {
            String mod = one == null ? null : GameRegistrations.findModOf(one.getClass());
            if (mod != null) {
                mods.add(mod);
            }
        }
        CommandNode<?> redirect = node.getRedirect();
        if (redirect != null && !(redirect instanceof RootCommandNode<?>)) {
            collect(redirect, depth - 1, mods, seen);
        }
        for (CommandNode<?> child : node.getChildren()) {
            collect(child, depth - 1, mods, seen);
        }
    }
}
