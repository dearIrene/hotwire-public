package dev.moon.hotwire.fabric;

import dev.moon.hotwire.fabric.add.LoaderModList;
import dev.moon.hotwire.fabric.add.WorldReopen;
import dev.moon.hotwire.fabric.registration.GameRegistrations;
import dev.moon.hotwire.plan.ModGraph;
import dev.moon.hotwire.redefine.Tripwire;
import dev.moon.hotwire.ui.Lang;
import dev.moon.hotwire.ui.ModScreen;
import dev.moon.hotwire.ui.ReopenPanel;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class OffCodeNotice {
    private static final Logger LOGGER = LoggerFactory.getLogger(OffCodeNotice.class);
    private static final Set<String> CALL_PLUMBING = Set.of("java.lang.invoke", "java.lang.reflect",
            "jdk.internal.reflect");

    private OffCodeNotice() {
    }

    static void install(HotwireEngine engine, InstalledMods installed) {
        Tripwire.setOwners(GameRegistrations::findModOrShipper);
        Tripwire.setOffCheck(LoaderModList::isHidden);
        Tripwire.setGameThreads(() -> {
            Minecraft client = Minecraft.getInstance();
            MinecraftServer server = client.getSingleplayerServer();
            return client.isSameThread() || (server != null && server.isSameThread());
        });
        Tripwire.setLibraries(mod -> installed.getGraph().getUses(mod));
        Tripwire.setGameIds(ModPolicy.BUILT_IN);

        //? if >=1.21.2 {
        Tripwire.onFirstCall(mod -> Minecraft.getInstance().schedule(() -> onOffCodeCalled(engine, installed, mod)));
        //?} else {
        /*Tripwire.onFirstCall(mod -> Minecraft.getInstance().tell(() -> onOffCodeCalled(engine, installed, mod)));
        *///?}
    }

    private static void onOffCodeCalled(HotwireEngine engine, InstalledMods installed, String mod) {
        if (engine.isOn(mod)) {
            return;
        }
        List<String> first = Tripwire.peek(mod).first();
        boolean ran = Tripwire.hasRun(mod);
        if (ran && isOwnThreadAlone(mod, first)) {
            LOGGER.info("[Hotwire] {}'s own thread went on with work it had begun before the off: {}", mod,
                    String.join(" < ", first));
            return;
        }
        if (ran) {
            LOGGER.warn("[Hotwire] {} is off, yet its code ran: {}", mod, String.join(" < ", first));
        } else {
            LOGGER.info("[Hotwire] {} is off and was called all the same, and answered with nothing: {}", mod,
                    String.join(" < ", first));
        }

        String user = findUser(engine, installed, mod, first);
        SeenUses seen = ModPolicy.getSeenUses();
        if (user != null && seen != null) {
            if (seen.add(user, mod)) {
                LOGGER.info("[Hotwire] {} runs {}'s code by its own hand: from now on it goes off with it", user, mod);
            }
            if (ran) {
                engine.showMessage(Lang.get("hotwire.off_code.used_by", installed.nameOf(mod), installed.nameOf(user)),
                        true);
            }
            return;
        }
        if (!ran) {
            return;
        }
        ModScreen current = engine.findScreen();
        boolean fromWorld = engine.findJvmParts().map(parts -> parts.classes().isReachedFromWorld(mod, first))
                .orElse(false);
        if (current != null && fromWorld && WorldReopen.findRefusal().isEmpty()) {
            current.offerReopen(new ReopenPanel.Offer(true, List.of(), installed.nameOf(mod),
                    Lang.get("hotwire.off_code.world")));
            return;
        }

        String clears = Lang.get(fromWorld ? "hotwire.off_code.reopen" : "hotwire.off_code.restart");
        engine.showMessage(Lang.get("hotwire.off_code.ran", installed.nameOf(mod), clears), true);
    }

    private static boolean isOwnThreadAlone(String mod, List<String> frames) {
        boolean any = false;
        for (String frame : frames) {
            int paren = frame.indexOf('(');
            String method = paren < 0 ? frame : frame.substring(0, paren);
            int slash = method.lastIndexOf('/');
            int dot = method.lastIndexOf('.');
            if (dot <= slash) {
                return false;
            }
            Class<?> type;
            try {
                type = Class.forName(method.substring(slash + 1, dot), false, OffCodeNotice.class.getClassLoader());
            } catch (ClassNotFoundException | LinkageError e) {
                return false;
            }
            ClassLoader loader = type.getClassLoader();
            if (loader == null || loader.equals(ClassLoader.getPlatformClassLoader())) {
                continue;
            }
            if (!mod.equals(GameRegistrations.findModOrShipper(type))) {
                return false;
            }
            any = true;
        }
        return any;
    }

    private static @Nullable String findUser(HotwireEngine engine, InstalledMods installed, String mod,
            List<String> frames) {
        @Nullable Class<?> entered = null;
        String enteredMethod = "";
        for (String frame : frames) {
            int paren = frame.indexOf('(');
            String method = paren < 0 ? frame : frame.substring(0, paren);
            int slash = method.lastIndexOf('/');
            int dot = method.lastIndexOf('.');
            if (dot <= slash) {
                continue;
            }
            Class<?> type;
            try {
                type = Class.forName(method.substring(slash + 1, dot), false, OffCodeNotice.class.getClassLoader());
            } catch (ClassNotFoundException | LinkageError e) {
                continue;
            }

            if (CALL_PLUMBING.contains(type.getPackageName())) {
                continue;
            }

            String owner = GameRegistrations.findModOrShipper(type);
            if (mod.equals(owner)) {
                entered = type;
                enteredMethod = method.substring(dot + 1);
                continue;
            }
            boolean isUser = owner != null && ModPolicy.isSwitchable(owner) && engine.isOn(owner)
                    && !isLibraryOf(installed.getGraph(), mod, owner)
                    && (entered == null || !isDeclaredOutside(entered, enteredMethod, mod));
            return isUser ? owner : null;
        }
        return null;
    }

    private static boolean isLibraryOf(ModGraph graph, String mod, String owner) {
        Set<String> seen = new HashSet<>();
        Deque<String> next = new ArrayDeque<>(List.of(mod));
        while (!next.isEmpty()) {
            String one = next.poll();
            Set<String> reached = new HashSet<>(graph.getUses(one));
            reached.addAll(graph.getContainers(one));
            for (String other : reached) {
                if (other.equals(owner)) {
                    return true;
                }
                if (seen.add(other)) {
                    next.add(other);
                }
            }
        }
        return false;
    }

    private static boolean isDeclaredOutside(Class<?> type, String method, String mod) {
        Deque<Class<?>> next = new ArrayDeque<>(List.of(type));
        Set<Class<?>> seen = new HashSet<>();
        while (!next.isEmpty()) {
            Class<?> one = next.poll();
            if (!seen.add(one)) {
                continue;
            }
            if (one != type && !mod.equals(GameRegistrations.findModOf(one))) {
                for (Method declared : one.getDeclaredMethods()) {
                    if (declared.getName().equals(method)) {
                        return true;
                    }
                }
            }
            if (one.getSuperclass() != null) {
                next.add(one.getSuperclass());
            }
            next.addAll(List.of(one.getInterfaces()));
        }
        return false;
    }
}
