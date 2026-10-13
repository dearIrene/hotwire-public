package dev.moon.hotwire.fabric.registration;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.tree.CommandNode;
import dev.moon.hotwire.fabric.mixin.core.brigadier.tree.CommandNodeAccessor;
import dev.moon.hotwire.registration.Registration;
import java.util.Map;
import net.fabricmc.fabric.impl.command.client.ClientCommandInternals;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.server.IntegratedServer;
import org.jspecify.annotations.Nullable;

final class CommandRegistration<S> implements Registration {
    private final CommandDispatcher<S> dispatcher;
    private final CommandNode<S> node;
    private final boolean client;

    CommandRegistration(CommandDispatcher<S> dispatcher, CommandNode<S> node, boolean client) {
        this.dispatcher = dispatcher;
        this.node = node;
        this.client = client;
    }

    @Override
    public String getKind() {
        return this.client ? "client command" : "command";
    }

    @Override
    public String describe() {
        return "/" + this.node.getName();
    }

    @Override
    @SuppressWarnings("ReferenceEquality")
    public boolean isInGame() {
        return this.dispatcher == findActiveDispatcher(this.client)
                && this.dispatcher.getRoot().getChild(this.node.getName()) == this.node;
    }

    @Override
    @SuppressWarnings("ReferenceEquality")
    public void remove() {
        CommandNodeAccessor root = (CommandNodeAccessor) this.dispatcher.getRoot();
        Map<String, Object> children = root.hotwire$getChildren();
        if (children.get(this.node.getName()) != this.node) {
            return;
        }
        children.remove(this.node.getName());
        root.hotwire$getLiterals().remove(this.node.getName());
        this.resendCommands();
    }

    @Override
    @SuppressWarnings("ReferenceEquality")
    public void restore() {
        CommandNode<S> current = this.dispatcher.getRoot().getChild(this.node.getName());
        if (current == this.node) {
            return;
        }
        if (current != null) {
            throw new IllegalStateException("Another command took /" + this.node.getName() + " while it was off.");
        }
        this.dispatcher.getRoot().addChild(this.node);
        this.resendCommands();
    }

    static @Nullable CommandDispatcher<?> findActiveDispatcher(boolean client) {
        if (client) {
            return ClientCommandInternals.getActiveDispatcher();
        }
        IntegratedServer server = Minecraft.getInstance().getSingleplayerServer();
        return server == null ? null : server.getCommands().getDispatcher();
    }

    @SuppressWarnings("ReferenceEquality")
    private void resendCommands() {
        if (this.dispatcher != findActiveDispatcher(this.client)) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (this.client) {
            ClientPacketListener connection = mc.getConnection();
            if (connection != null) {
                mc.execute(((ClientPacketListenerExtension) connection)::hotwire$resendCommands);
            }
            return;
        }
        IntegratedServer server = mc.getSingleplayerServer();
        if (server != null) {
            server.execute(() -> server.getPlayerList().getPlayers().forEach(server.getCommands()::sendCommands));
        }
    }
}
