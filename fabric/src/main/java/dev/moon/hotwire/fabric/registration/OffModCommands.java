package dev.moon.hotwire.fabric.registration;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.tree.CommandNode;
import dev.moon.hotwire.fabric.GameParts;
import dev.moon.hotwire.fabric.mixin.core.brigadier.tree.CommandNodeAccessor;
import dev.moon.hotwire.plan.ModGraph;
import dev.moon.hotwire.registration.ClientPacketListenerExtension;
import dev.moon.hotwire.registration.GameRegistrations;
import dev.moon.hotwire.registration.Owners;
import dev.moon.hotwire.registration.RegistrationJournal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModDependency;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.server.IntegratedServer;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class OffModCommands {
    private static final Logger LOGGER = LoggerFactory.getLogger(OffModCommands.class);

    private static final int DEPTH = 8;

    private record Removal(CommandDispatcher<?> dispatcher, CommandNode<?> parent, CommandNode<?> node,
            Set<String> mods, boolean client) implements RegistrationJournal.Take {
        @Override
        public void restore() {
            if (this.parent.getChild(this.node.getName()) == null) {
                addChild(this.parent, this.node);
            }
        }

        @Override
        public String describe() {
            return "/" + this.node.getName() + " of " + this.mods;
        }

        @Override
        public Set<String> getNeeds() {
            return this.mods;
        }

        @Override
        @SuppressWarnings("ReferenceEquality")
        public boolean isStale() {
            return this.dispatcher != CommandRegistration.findActiveDispatcher(this.client);
        }

        @Override
        public Runnable getAfterBatch() {
            return this.client ? Resend.CLIENT : Resend.SERVER;
        }
    }

    private enum Resend implements Runnable {
        CLIENT(true), SERVER(false);

        private final boolean client;

        Resend(boolean client) {
            this.client = client;
        }

        @Override
        public void run() {
            GameParts.getCommands().resendCommands(this.client);
        }
    }
    private volatile @Nullable Supplier<ModGraph> mods;

    private volatile Map<String, Set<String>> dependencies = Map.of();

    private volatile Predicate<String> offNow = id -> false;
    private final Set<String> saidStaying = ConcurrentHashMap.newKeySet();

    private final AtomicBoolean clientResendDue = new AtomicBoolean();
    private final AtomicBoolean serverResendDue = new AtomicBoolean();

    public void readOffFrom(Predicate<String> off) {
        this.offNow = off;
    }

    public synchronized void takeOutBeforeSending(boolean client) {
        CommandDispatcher<?> dispatcher = CommandRegistration.findActiveDispatcher(client);
        if (dispatcher != null) {
            this.takeOutOf(dispatcher, client);
        }
    }

    public synchronized void takeOutOf(CommandDispatcher<?> dispatcher, boolean client) {
        Predicate<String> off = this.offNow;
        List<String> removedNames = new ArrayList<>();
        for (CommandNode<?> command : List.copyOf(dispatcher.getRoot().getChildren())) {
            this.takeOut(dispatcher, dispatcher.getRoot(), command, off, client, removedNames, DEPTH);

            Set<String> all = CommandRecorder.findModsRun(command);
            if (dispatcher.getRoot().getChild(command.getName()) != null
                    && this.saidStaying.add(command.getName() + " " + all)) {
                LOGGER.info("[Hotwire] /{} stays: it runs {} (off now: {}), its requirement {}", command.getName(),
                        all, all.stream().filter(off).toList(), command.getRequirement().getClass().getName());
            }
            if (dispatcher.getRoot().getChild(command.getName()) != null && all.stream().anyMatch(off)
                    && this.saidStaying.add(command.getName())) {
                LOGGER.info("[Hotwire] /{} runs off mods' code and stays: it runs {}, of which off {}",
                        command.getName(), all, all.stream().filter(off).toList());
            }
        }
        if (!removedNames.isEmpty()) {
            LOGGER.info("[Hotwire] before the {} commands were sent, {} went out: they run only off mods' code",
                    client ? "client" : "server", removedNames);
        }
    }

    private volatile Predicate<String> goingOff = id -> false;

    public boolean isGoingOff(String mod) {
        return this.goingOff.test(mod);
    }

    public void onWatched() {
        this.goingOff = id -> false;
    }

    public void markGoingOff(Predicate<String> off) {
        this.goingOff = off;
    }

    public synchronized void takeOut(Predicate<String> off) {
        this.goingOff = off;
        for (boolean client : List.of(false, true)) {
            CommandDispatcher<?> dispatcher = CommandRegistration.findActiveDispatcher(client);
            if (dispatcher == null) {
                continue;
            }
            List<String> removedNames = new ArrayList<>();
            for (CommandNode<?> command : List.copyOf(dispatcher.getRoot().getChildren())) {
                this.takeOut(dispatcher, dispatcher.getRoot(), command, off, client, removedNames, DEPTH);
            }
            if (!removedNames.isEmpty()) {
                LOGGER.info("[Hotwire] the {} commands {} run only off mods' code and go out",
                        client ? "client" : "server", removedNames);
                this.resendCommands(client);
            }
        }
    }

    @SuppressWarnings("ReferenceEquality")
    private void takeOut(CommandDispatcher<?> dispatcher, CommandNode<?> parent, CommandNode<?> node,
            Predicate<String> off, boolean client, Collection<String> removedNames, int depth) {
        Set<String> mods = this.findMods(node);
        if (depth == 0 || mods.isEmpty()) {
            return;
        }
        if (mods.stream().allMatch(off)) {
            take(dispatcher, parent, node, mods, client, removedNames);
            return;
        }
        if (parent == dispatcher.getRoot() && mods.stream().anyMatch(off)) {
            LOGGER.info("[Hotwire] /{} runs {}, of which {} stay on, so it stays", node.getName(), mods,
                    mods.stream().filter(off.negate()).toList());
        }
        for (CommandNode<?> part : List.copyOf(node.getChildren())) {
            this.takeOut(dispatcher, node, part, off, client, removedNames, depth - 1);
        }
    }

    public void rebuildClientCommands() {
        this.resendCommands(true);
    }

    public void resendCommands(boolean client) {
        Minecraft mc = Minecraft.getInstance();

        if (client && mc.getSingleplayerServer() != null) {
            this.resendCommands(false);
            return;
        }
        AtomicBoolean due = client ? this.clientResendDue : this.serverResendDue;
        if (client) {
            ClientPacketListener connection = mc.getConnection();
            if (connection != null && due.compareAndSet(false, true)) {
                mc.execute(() -> {
                    due.set(false);
                    this.takeOutBeforeSending(true);
                    ((ClientPacketListenerExtension) connection).hotwire$resendCommands();
                });
            }
            return;
        }
        IntegratedServer server = mc.getSingleplayerServer();
        if (server != null && due.compareAndSet(false, true)) {
            server.execute(() -> {
                due.set(false);
                this.takeOutBeforeSending(false);
                server.getPlayerList().getPlayers().forEach(server.getCommands()::sendCommands);
            });
        }
    }

    @SuppressWarnings("ReferenceEquality")
    private static void take(CommandDispatcher<?> dispatcher, CommandNode<?> parent, CommandNode<?> node,
            Set<String> mods, boolean client, Collection<String> removedNames) {
        CommandNodeAccessor from = (CommandNodeAccessor) parent;
        from.hotwire$getChildren().remove(node.getName());
        from.hotwire$getLiterals().remove(node.getName());
        GameRegistrations.getJournal().addTake(mods.iterator().next(),
                new Removal(dispatcher, parent, node, mods, client));
        removedNames.add((parent == dispatcher.getRoot() ? "/" : "/" + parent.getName() + " ") + node.getName());
    }

    private Set<String> findMods(CommandNode<?> node) {
        Set<String> mods = CommandRecorder.findModsRun(node).stream().filter(Owners::isRecordedMod)
                .collect(Collectors.toSet());
        Set<String> owners = mods.stream().filter(mod -> mods.stream().noneMatch(user -> this.isDependentOf(user,
                mod))).collect(Collectors.toSet());
        return owners.isEmpty() ? mods : owners;
    }

    public void readUsesFrom(Supplier<ModGraph> graph) {
        this.mods = graph;
        Map<String, Set<String>> declared = new HashMap<>();
        for (ModContainer mod : FabricLoader.getInstance().getAllMods()) {
            declared.put(mod.getMetadata().getId(), mod.getMetadata().getDependencies().stream()
                    .filter(dependency -> dependency.getKind() == ModDependency.Kind.DEPENDS)
                    .map(ModDependency::getModId).collect(Collectors.toUnmodifiableSet()));
        }
        this.dependencies = Map.copyOf(declared);
    }

    private boolean isDependentOf(String user, String library) {
        Supplier<ModGraph> graph = this.mods;
        if (graph != null && graph.get().getUses(user).contains(library)) {
            return true;
        }
        return this.dependencies.getOrDefault(user, Set.of()).contains(library);
    }

    @SuppressWarnings("unchecked")
    private static <S> void addChild(CommandNode<S> parent, CommandNode<?> node) {
        parent.addChild((CommandNode<S>) node);
    }
}
