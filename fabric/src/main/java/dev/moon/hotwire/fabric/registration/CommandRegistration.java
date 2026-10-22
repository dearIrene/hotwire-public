package dev.moon.hotwire.fabric.registration;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.RootCommandNode;
import dev.moon.hotwire.fabric.mixin.core.brigadier.tree.CommandNodeAccessor;
import dev.moon.hotwire.registration.Registration;
import java.lang.ref.WeakReference;
import java.util.concurrent.atomic.AtomicBoolean;
import net.fabricmc.fabric.impl.command.client.ClientCommandInternals;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.server.IntegratedServer;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class CommandRegistration implements Registration {
    private static final Logger LOGGER = LoggerFactory.getLogger(CommandRegistration.class);
    private static final AtomicBoolean CLIENT_RESEND_DUE = new AtomicBoolean();
    private static final AtomicBoolean SERVER_RESEND_DUE = new AtomicBoolean();

    private final WeakReference<RootCommandNode<?>> addedTo;
    private final String name;
    private final String mod;
    private final boolean client;

    private @Nullable CommandDispatcher<?> outOf;
    private @Nullable CommandNode<?> removed;

    CommandRegistration(RootCommandNode<?> addedTo, String name, String mod, boolean client) {
        this.addedTo = new WeakReference<>(addedTo);
        this.name = name;
        this.mod = mod;
        this.client = client;
    }

    @Override
    public String getKind() {
        return this.client ? "client command" : "command";
    }

    @Override
    public String describe() {
        return "/" + this.name;
    }

    @Override
    public boolean isInGame() {
        RootCommandNode<?> root = this.addedTo.get();
        CommandDispatcher<?> dispatcher = findActiveDispatcher(this.client);
        return (root != null && root.getChild(this.name) != null)
                || (dispatcher != null && this.findIn(dispatcher) != null);
    }

    @Override
    public void remove() {
        CommandDispatcher<?> dispatcher = findActiveDispatcher(this.client);
        CommandNode<?> node = dispatcher == null ? null : this.findIn(dispatcher);
        if (dispatcher == null || node == null) {
            CommandNode<?> there = dispatcher == null ? null : dispatcher.getRoot().getChild(this.name);
            if (there != null) {
                LOGGER.info("[Hotwire] /{} of {} is left in: the command of that name in use runs {}", this.name,
                        this.mod, CommandRecorder.findModsRun(there));
            }
            return;
        }
        CommandNodeAccessor root = (CommandNodeAccessor) dispatcher.getRoot();
        root.hotwire$getChildren().remove(this.name);
        root.hotwire$getLiterals().remove(this.name);
        this.outOf = dispatcher;
        this.removed = node;
        resendCommands(this.client);
    }

    @Override
    @SuppressWarnings("ReferenceEquality")
    public void restore() {
        CommandNode<?> node = this.removed;
        CommandDispatcher<?> dispatcher = this.outOf;
        this.removed = null;
        this.outOf = null;

        if (node == null || dispatcher == null || dispatcher != findActiveDispatcher(this.client)) {
            return;
        }
        CommandNode<?> current = dispatcher.getRoot().getChild(this.name);
        if (current == node || (current != null && this.findIn(dispatcher) != null)) {
            return;
        }
        if (current != null) {
            throw new IllegalStateException("Another command took /" + this.name + " while it was off.");
        }
        addChild(dispatcher, node);
        resendCommands(this.client);
    }

    private @Nullable CommandNode<?> findIn(CommandDispatcher<?> dispatcher) {
        CommandNode<?> node = dispatcher.getRoot().getChild(this.name);
        return node != null && CommandRecorder.findModsRun(node).contains(this.mod) ? node : null;
    }

    @SuppressWarnings("unchecked")
    private static <S> void addChild(CommandDispatcher<S> dispatcher, CommandNode<?> node) {
        dispatcher.getRoot().addChild((CommandNode<S>) node);
    }

    static @Nullable CommandDispatcher<?> findActiveDispatcher(boolean client) {
        if (client) {
            return ClientCommandInternals.getActiveDispatcher();
        }
        IntegratedServer server = Minecraft.getInstance().getSingleplayerServer();
        return server == null ? null : server.getCommands().getDispatcher();
    }

    static void resendCommands(boolean client) {
        Minecraft mc = Minecraft.getInstance();
        AtomicBoolean due = client ? CLIENT_RESEND_DUE : SERVER_RESEND_DUE;
        if (client) {
            ClientPacketListener connection = mc.getConnection();
            if (connection != null && due.compareAndSet(false, true)) {
                mc.execute(() -> {
                    due.set(false);
                    ((ClientPacketListenerExtension) connection).hotwire$resendCommands();
                });
            }
            return;
        }
        IntegratedServer server = mc.getSingleplayerServer();
        if (server != null && due.compareAndSet(false, true)) {
            server.execute(() -> {
                due.set(false);
                server.getPlayerList().getPlayers().forEach(server.getCommands()::sendCommands);
            });
        }
    }
}
