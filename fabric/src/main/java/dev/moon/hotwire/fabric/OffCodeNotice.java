package dev.moon.hotwire.fabric;

import dev.moon.hotwire.add.LoaderModList;
import dev.moon.hotwire.classfile.ClassNodes;
import dev.moon.hotwire.engine.HotwireEngine;
import dev.moon.hotwire.engine.InstalledMods;
import dev.moon.hotwire.engine.JvmParts;
import dev.moon.hotwire.engine.ModPolicy;
import dev.moon.hotwire.engine.SeenUses;
import dev.moon.hotwire.fabric.add.WorldReopen;
import dev.moon.hotwire.fabric.rebuild.GameClasses;
import dev.moon.hotwire.fabric.version.Spellings;
import dev.moon.hotwire.plan.ModGraph;
import dev.moon.hotwire.redefine.SidecarReflection;
import dev.moon.hotwire.redefine.Tripwire;
import dev.moon.hotwire.registration.Owners;
import dev.moon.hotwire.ui.Lang;
import dev.moon.hotwire.ui.ModScreen;
import dev.moon.hotwire.ui.panels.ReopenPanel;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class OffCodeNotice {
    private static final Logger LOGGER = LoggerFactory.getLogger(OffCodeNotice.class);
    private static final Set<String> CALL_PLUMBING = Set.of("java.lang.invoke", "java.lang.reflect",
            "jdk.internal.reflect");

    private final Map<String, Object> told = new ConcurrentHashMap<>();

    private final Map<String, FirstRun> ranOnce = new ConcurrentHashMap<>();

    private record FirstRun(@Nullable Object watch, long calls) {
    }

    private OffCodeNotice() {
    }

    static void install(HotwireEngine engine, InstalledMods installed) {
        Tripwire.setOwners(Owners::findModOrShipper);
        Tripwire.setOffCheck(LoaderModList::isHidden);
        Tripwire.setGameThreads(() -> {
            Minecraft client = Minecraft.getInstance();
            MinecraftServer server = client.getSingleplayerServer();
            return client.isSameThread() || (server != null && server.isSameThread());
        });
        Tripwire.setLibraries(mod -> installed.getGraph().getUses(mod));
        Tripwire.setGameIds(ModPolicy.BUILT_IN);

        OffCodeNotice notice = new OffCodeNotice();
        Tripwire.onFirstCall(mod -> Spellings.runSoon(() -> notice.onOffCodeCalled(engine, installed, mod)));
    }

    private void onOffCodeCalled(HotwireEngine engine, InstalledMods installed, String mod) {
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

        JvmParts parts = engine.findParts();
        if (parts != null) {
            parts.runner().takeStillCalled(mod, first);
        }
        Object watch = Tripwire.findWatch(mod);
        if (ran && !this.hasRunAgain(mod, watch)) {
            return;
        }
        if (ran && watch != null && this.isToldFor(mod, watch)) {
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
        ModScreen current = engine.getScreen();
        boolean fromWorld = parts != null && ((GameClasses) parts.classes()).isReachedFromWorld(mod, first);
        if (current != null && fromWorld && WorldReopen.findRefusal().isEmpty()) {
            current.offerReopen(new ReopenPanel.Offer(true, List.of(), installed.nameOf(mod),
                    Lang.get("hotwire.off_code.world")));
            return;
        }

        String clears = Lang.get(fromWorld ? "hotwire.off_code.reopen" : "hotwire.off_code.restart");
        engine.showMessage(Lang.get("hotwire.off_code.ran", installed.nameOf(mod), clears), true);
    }

    @SuppressWarnings("ReferenceEquality")
    private boolean hasRunAgain(String mod, @Nullable Object watch) {
        long calls = Tripwire.peek(mod).calls();
        FirstRun once = this.ranOnce.get(mod);
        if (once != null && once.watch() == watch) {
            return calls > once.calls();
        }
        this.ranOnce.put(mod, new FirstRun(watch, calls));
        LOGGER.info("[Hotwire] {} is off and its code ran as something built with it shut down ({} calls); said if"
                + " it runs again: {}", mod, calls, String.join(" < ", Tripwire.peek(mod).first()));
        Tripwire.listenAgain(mod);
        return false;
    }

    @SuppressWarnings("ReferenceEquality")
    private boolean isToldFor(String mod, Object watch) {
        return this.told.put(mod, watch) == watch;
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
            if (!mod.equals(Owners.findModOrShipper(type))) {
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

            if (CALL_PLUMBING.contains(type.getPackageName()) || type == SidecarReflection.class) {
                continue;
            }

            String owner = Owners.findModOrShipper(type);
            if (mod.equals(owner)) {
                entered = type;
                enteredMethod = method.substring(dot + 1);
                continue;
            }

            String nested = Owners.findNestedModOf(type);
            boolean isUser = owner != null && ModPolicy.isSwitchable(owner) && engine.isOn(owner)
                    && !isLibraryOf(installed.getGraph(), mod, owner)
                    && (nested == null || !isLibraryOf(installed.getGraph(), mod, nested))
                    && (entered == null || !isDeclaredOutside(entered, enteredMethod, mod))
                    && !isCallingOthersOnly(type, frame, mod);
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
            Set<String> shippedIn = graph.getContainers(one);
            if (!shippedIn.contains(mod)) {
                reached.addAll(shippedIn);
            }
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

    private static boolean isCallingOthersOnly(Class<?> type, String frame, String mod) {
        int colon = frame.lastIndexOf(':');
        int close = frame.lastIndexOf(')');
        int line;
        try {
            line = colon < 0 || close < colon ? -1 : Integer.parseInt(frame.substring(colon + 1, close));
        } catch (NumberFormatException e) {
            return false;
        }
        ClassLoader loader = type.getClassLoader();
        if (line < 0 || loader == null) {
            return false;
        }
        byte[] bytes;
        try (InputStream in = loader.getResourceAsStream(type.getName().replace('.', '/') + ".class")) {
            if (in == null) {
                return false;
            }
            bytes = in.readAllBytes();
        } catch (IOException e) {
            return false;
        }
        boolean any = false;
        for (MethodNode method : ClassNodes.read(bytes, ClassReader.SKIP_FRAMES).methods) {
            int at = -1;
            for (AbstractInsnNode insn : method.instructions) {
                if (insn instanceof LineNumberNode number) {
                    at = number.line;
                } else if (at == line && insn instanceof MethodInsnNode call) {
                    any = true;
                    try {
                        Class<?> called = Class.forName(call.owner.replace('/', '.'), false, loader);
                        if (mod.equals(Owners.findModOrShipper(called))) {
                            return false;
                        }
                    } catch (ClassNotFoundException | LinkageError e) {
                        return false;
                    }
                }
            }
        }
        return any;
    }

    private static boolean isDeclaredOutside(Class<?> type, String method, String mod) {
        Deque<Class<?>> next = new ArrayDeque<>(List.of(type));
        Set<Class<?>> seen = new HashSet<>();
        while (!next.isEmpty()) {
            Class<?> one = next.poll();
            if (!seen.add(one)) {
                continue;
            }
            if (one != type && !mod.equals(Owners.findModOf(one))) {
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
