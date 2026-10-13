package dev.moon.hotwire.fabric.registration;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.tree.CommandNode;
import net.fabricmc.fabric.impl.command.client.ClientCommandInternals;

public final class CommandRecorder {
    private CommandRecorder() {
    }

    @SuppressWarnings("ReferenceEquality")
    public static <S> void onRegistered(CommandDispatcher<S> dispatcher, CommandNode<S> node) {
        if (dispatcher.getRoot().getChild(node.getName()) != node || RecorderSupport.findOwner() == null) {
            return;
        }
        if (RecorderSupport.isFirst(node)) {
            RecorderSupport.record(node, new CommandRegistration<>(dispatcher, node,
                    dispatcher == ClientCommandInternals.getActiveDispatcher()));
        }
    }
}
