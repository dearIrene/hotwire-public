package dev.moon.hotwire.fabric.registration;

import com.mojang.brigadier.CommandDispatcher;
import dev.moon.hotwire.fabric.LifecycleListeners;
import dev.moon.hotwire.plan.SwitchPlan;
import dev.moon.hotwire.registration.RegistrationJournal;
import dev.moon.hotwire.switching.ModSwitcher;
import java.lang.ref.WeakReference;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
//? if >=26.1 {
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLevelEvents;
//?} else {
/*import net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents;
*///?}
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.impl.command.client.ClientCommandInternals;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ModLifecycle {
    private static final Logger LOGGER = LoggerFactory.getLogger(ModLifecycle.class);
    private static final Duration WAIT = Duration.ofSeconds(10);

    private static final Map<String, CompletableFuture<?>> STUCK = new ConcurrentHashMap<>();

    private record LastStop(@Nullable WeakReference<MinecraftServer> server, boolean ofServer, boolean ofPlayers,
            boolean ofClient, @Nullable Set<String> serverParts) {
        @SuppressWarnings("ReferenceEquality")
        boolean isOf(@Nullable MinecraftServer now) {
            return now != null && this.server != null && this.server.get() == now;
        }
    }

    private static final LastStop EVERYTHING = new LastStop(null, true, true, true, null);
    private static final Map<String, LastStop> LAST_STOPS = new ConcurrentHashMap<>();

    private ModLifecycle() {
    }

    public static List<String> stop(String id, RegistrationJournal journal, Minecraft client) {
        List<String> ofPlayers = new ArrayList<>();
        List<String> ofServer = new ArrayList<>();
        List<String> ofClient = new ArrayList<>();
        MinecraftServer server = client.getSingleplayerServer();
        Set<String> serverParts = ConcurrentHashMap.newKeySet();
        Predicate<Object> noted = listener -> {
            serverParts.add(findPartOf(id, listener));
            return true;
        };

        if (listensTo(id, journal, ClientPlayConnectionEvents.DISCONNECT)) {
            onThread(work -> GameHold.onGameBeforeNextFrame(work, client), () -> {
                ClientPacketListener connection = client.getConnection();
                if (connection != null) {
                    callEach(id, journal, ClientPlayConnectionEvents.DISCONNECT, ofClient, listener ->
                            ((ClientPlayConnectionEvents.Disconnect) listener).onPlayDisconnect(connection, client));
                }
            }, "client");
        }
        if (server != null && !server.isStopped() && listensTo(id, journal, ServerPlayConnectionEvents.DISCONNECT,
                ServerLifecycleEvents.SERVER_STOPPING, ServerLifecycleEvents.SERVER_STOPPED, findLevelEvent(false),
                ArchitecturyEventRegistration.findLifecycleEvent("SERVER_STOPPING"),
                ArchitecturyEventRegistration.findLifecycleEvent("SERVER_STOPPED"))) {
            onThread(work -> GameHold.onServer(server, work), () -> {
                for (ServerPlayer player : List.copyOf(server.getPlayerList().getPlayers())) {
                    callEach(id, journal, ServerPlayConnectionEvents.DISCONNECT, ofPlayers, listener ->
                            ((ServerPlayConnectionEvents.Disconnect) listener).onPlayDisconnect(player.connection,
                                    server));
                }
                callEach(id, journal, ServerLifecycleEvents.SERVER_STOPPING, ofServer, listener ->
                        ((ServerLifecycleEvents.ServerStopping) listener).onServerStopping(server), noted);
                callArchitectury(id, journal, "SERVER_STOPPING", ofServer, server);

                for (ServerLevel level : server.getAllLevels()) {
                    callEach(id, journal, findLevelEvent(false), ofServer, listener ->
                            callLevel(listener, server, level, false), noted);
                }
                callEach(id, journal, ServerLifecycleEvents.SERVER_STOPPED, ofServer, listener ->
                        ((ServerLifecycleEvents.ServerStopped) listener).onServerStopped(server), noted);
                callArchitectury(id, journal, "SERVER_STOPPED", ofServer, server);
            }, "server");
        }
        LAST_STOPS.put(id, new LastStop(server == null ? null : new WeakReference<>(server), !ofServer.isEmpty(),
                !ofPlayers.isEmpty(), !ofClient.isEmpty(), Set.copyOf(serverParts)));
        List<String> ran = new ArrayList<>(ofPlayers);
        ran.addAll(ofServer);
        ran.addAll(ofClient);
        return ran;
    }

    public static List<String> start(String id, RegistrationJournal journal, Minecraft client) {
        List<String> ran = new ArrayList<>();
        MinecraftServer server = client.getSingleplayerServer();
        LastStop before = LAST_STOPS.remove(id);
        LastStop last = before != null && before.isOf(server) ? before : EVERYTHING;

        if (server != null && server.isRunning() && listensTo(id, journal, ServerLifecycleEvents.SERVER_STARTING,
                ServerLifecycleEvents.SERVER_STARTED, ServerPlayConnectionEvents.JOIN, findLevelEvent(true),
                ArchitecturyEventRegistration.findLifecycleEvent("SERVER_STARTING"),
                ArchitecturyEventRegistration.findLifecycleEvent("SERVER_STARTED"))) {
            Set<String> parts = last.serverParts();
            Predicate<Object> stopped = parts == null ? listener -> true
                    : listener -> parts.contains(findPartOf(id, listener));
            onThread(work -> GameHold.onServer(server, work), () -> {
                if (last.ofServer()) {
                    callEach(id, journal, ServerLifecycleEvents.SERVER_STARTING, ran, listener ->
                            ((ServerLifecycleEvents.ServerStarting) listener).onServerStarting(server), stopped);
                    callArchitectury(id, journal, "SERVER_STARTING", ran, server);

                    for (ServerLevel level : server.getAllLevels()) {
                        callEach(id, journal, findLevelEvent(true), ran, listener ->
                                callLevel(listener, server, level, true), stopped);
                    }
                    callEach(id, journal, ServerLifecycleEvents.SERVER_STARTED, ran, listener ->
                            ((ServerLifecycleEvents.ServerStarted) listener).onServerStarted(server), stopped);
                    callArchitectury(id, journal, "SERVER_STARTED", ran, server);
                }
                for (ServerPlayer player : last.ofPlayers() ? List.copyOf(server.getPlayerList().getPlayers())
                        : List.<ServerPlayer>of()) {
                    callEach(id, journal, ServerPlayConnectionEvents.JOIN, ran, listener ->
                            ((ServerPlayConnectionEvents.Join) listener).onPlayReady(player.connection,
                                    ServerPlayNetworking.getSender(player), server));
                }
            }, "server");
        }

        if (!last.ofClient() || !listensTo(id, journal, ClientPlayConnectionEvents.JOIN,
                ClientCommandRegistrationCallback.EVENT)) {
            return ran;
        }
        onThread(work -> GameHold.onGameBeforeNextFrame(work, client), () -> {
            ClientPacketListener connection = client.getConnection();
            if (connection == null) {
                return;
            }
            CommandDispatcher<FabricClientCommandSource> commands = ClientCommandInternals.getActiveDispatcher();
            if (commands != null) {
                CommandBuildContext context = CommandBuildContext.simple(connection.registryAccess(),
                        connection.enabledFeatures());
                int already = ran.size();
                callEach(id, journal, ClientCommandRegistrationCallback.EVENT, ran, listener ->
                        ((ClientCommandRegistrationCallback) listener).register(commands, context));
                if (ran.size() > already) {
                    CommandRegistration.resendCommands(true);
                }
            }
            callEach(id, journal, ClientPlayConnectionEvents.JOIN, ran, listener ->
                    ((ClientPlayConnectionEvents.Join) listener).onPlayReady(connection,
                            ClientPlayNetworking.getSender(), client));
        }, "client");
        return ran;
    }

    private static Object findLevelEvent(boolean load) {
        //? if >=26.1 {
        return load ? ServerLevelEvents.LOAD : ServerLevelEvents.UNLOAD;
        //?} else {
        /*return load ? ServerWorldEvents.LOAD : ServerWorldEvents.UNLOAD;
        *///?}
    }

    private static void callLevel(Object listener, MinecraftServer server, ServerLevel level, boolean load) {
        //? if >=26.1 {
        if (load) {
            ((ServerLevelEvents.Load) listener).onLevelLoad(server, level);
        } else {
            ((ServerLevelEvents.Unload) listener).onLevelUnload(server, level);
        }
        //?} else {
        /*if (load) {
            ((ServerWorldEvents.Load) listener).onWorldLoad(server, level);
        } else {
            ((ServerWorldEvents.Unload) listener).onWorldUnload(server, level);
        }
        *///?}
    }

    private static boolean listensTo(String id, RegistrationJournal journal, @Nullable Object... events) {
        return journal.getLive(id).stream().anyMatch(entry -> Arrays.stream(events).filter(Objects::nonNull).anyMatch(
                event -> entry.registration() instanceof EventRegistration fabric
                        ? fabric.listensTo(event)
                        : entry.registration() instanceof ArchitecturyEventRegistration architectury
                                && architectury.listensTo(event)));
    }

    private static void callEach(String id, RegistrationJournal journal, Object event, List<String> ran,
            Consumer<Object> call) {
        callEach(id, journal, event, ran, call, listener -> true);
    }

    private static void callEach(String id, RegistrationJournal journal, Object event, List<String> ran,
            Consumer<Object> call, Predicate<Object> runs) {
        for (RegistrationJournal.Entry entry : journal.getLive(id)) {
            if (!(entry.registration() instanceof EventRegistration registration) || !registration.listensTo(event)) {
                continue;
            }
            Object listener = registration.findListener();
            if (listener == null || !runs.test(listener)) {
                continue;
            }
            try {
                call.accept(listener);
                ran.add(registration.describe());
            } catch (RuntimeException | LinkageError e) {
                LOGGER.warn("[Hotwire] {}'s listener {} threw while the mod was stopped or started", id,
                        registration.describe(), e);
            }
        }
    }

    private static String findPartOf(String id, Object listener) {
        Class<?> type = listener.getClass();
        int lambda = type.isHidden() ? type.getName().indexOf("$$Lambda") : -1;
        if (lambda >= 0) {
            try {
                type = Class.forName(type.getName().substring(0, lambda), false, type.getClassLoader());
            } catch (ClassNotFoundException | LinkageError e) {
                return id;
            }
        }
        String nested = GameRegistrations.findNestedModOf(type);
        return nested != null ? nested : id;
    }

    private static void callArchitectury(String id, RegistrationJournal journal, String name, List<String> ran,
            Object... arguments) {
        Object event = ArchitecturyEventRegistration.findLifecycleEvent(name);
        if (event == null) {
            return;
        }
        for (RegistrationJournal.Entry entry : journal.getLive(id)) {
            if (entry.registration() instanceof ArchitecturyEventRegistration registration
                    && registration.listensTo(event)) {
                try {
                    registration.call(arguments);
                    ran.add("Architectury's " + name);
                } catch (RuntimeException | LinkageError e) {
                    LOGGER.warn("[Hotwire] {}'s listener on Architectury's {} threw as the mod stopped or started", id,
                            name, e);
                }
            }
        }
    }

    public static void onGameStopping(Set<String> off, RegistrationJournal journal, Minecraft client) {
        for (String id : off) {
            List<String> ran = onGameStopping(id, journal, client);
            if (!ran.isEmpty()) {
                LOGGER.info("[Hotwire] {}, off, stopped with the game: {}", id, ran);
            }
        }
    }

    private static List<String> onGameStopping(String id, RegistrationJournal journal, Minecraft client) {
        List<String> ran = new ArrayList<>();
        for (RegistrationJournal.Entry entry : journal.getEntries(id)) {
            if (entry.registration() instanceof EventRegistration registration
                    && registration.listensTo(ClientLifecycleEvents.CLIENT_STOPPING)
                    && !journal.isLive(entry.sequence())
                    && registration.findListener() instanceof ClientLifecycleEvents.ClientStopping listener) {
                try {
                    listener.onClientStopping(client);
                    ran.add(registration.describe());
                } catch (RuntimeException | LinkageError e) {
                    LOGGER.warn("[Hotwire] {}'s listener {} threw as the game closed", id, registration.describe(), e);
                }
            }
        }
        return ran;
    }

    public static List<String> runSetup(String id, RegistrationJournal journal, Minecraft client) {
        List<String> ran = new ArrayList<>();
        onThread(work -> GameHold.onGameBeforeNextFrame(work, client), () -> {
            callEach(id, journal, ClientLifecycleEvents.CLIENT_STARTED, ran, listener ->
                    ((ClientLifecycleEvents.ClientStarted) listener).onClientStarted(client));
            callArchitectury(id, journal, "SETUP", ran);
        }, "client");
        return ran;
    }

    private static void onThread(Function<Runnable, CompletableFuture<?>> submit, Runnable work,
            String thread) {
        boolean paused = thread.equals("server") && GameHold.isServerPaused();

        CompletableFuture<?> running = paused ? GameHold.runOnPausedServer(work) : submit.apply(work);
        CompletableFuture<?> stuck = STUCK.get(thread);
        if (stuck != null && !stuck.isDone()) {
            return;
        }
        STUCK.remove(thread);
        try {
            GameHold.await(running, WAIT.toSeconds(), thread.equals("server") ? "Server thread" : "Render thread",
                    "a mod's lifecycle listeners");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException e) {
            LOGGER.warn("[Hotwire] the mod's lifecycle listeners failed on the {} thread", thread, e.getCause());
        } catch (TimeoutException e) {
            STUCK.put(thread, running);
            LOGGER.warn("[Hotwire] the mod's lifecycle listeners did not finish on the {} thread in {} s; the rest"
                    + " run there without waiting until it answers", thread, WAIT.toSeconds());
        }
    }

    public static ModSwitcher.Lifecycle makeLifecycle(RegistrationJournal journal,
            Minecraft client) {
        return new ModSwitcher.Lifecycle() {
            @Override
            public void onStopping(String id) {
                List<String> ran = stop(id, journal, client);
                if (!ran.isEmpty()) {
                    LOGGER.info("[Hotwire] {} stopped through its own listeners: {}", id, ran);
                }
            }

            @Override
            public void onStarted(String id) {
                List<String> ran = start(id, journal, client);
                if (!ran.isEmpty()) {
                    LOGGER.info("[Hotwire] {} started through its own listeners: {}", id, ran);
                }
            }

            @Override
            public void prepareSwitch(SwitchPlan plan) {
                LifecycleListeners.begin();
                if (plan.direction() == SwitchPlan.Direction.OFF) {
                    LifecycleListeners.prepareOff(plan.order());
                }
            }

            @Override
            public void finishSwitch(SwitchPlan plan, boolean done, Predicate<String> isOn) {
                if (done && plan.direction() == SwitchPlan.Direction.ON) {
                    LifecycleListeners.markOn(plan.order());
                }
                LifecycleListeners.end(isOn);
            }
        };
    }
}
