package dev.moon.hotwire.redefine;

import java.lang.invoke.CallSite;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.MutableCallSite;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class TripwireSites {
    private static final Logger LOGGER = LoggerFactory.getLogger(TripwireSites.class);
    private static final Handle BOOTSTRAP = new Handle(Opcodes.H_INVOKESTATIC,
            TripwireSites.class.getName().replace('.', '/'), "bootstrap",
            MethodType.methodType(CallSite.class, MethodHandles.Lookup.class, String.class, MethodType.class,
                    String.class).toMethodDescriptorString(), false);
    private static final MethodHandle NOTHING = MethodHandles.empty(MethodType.methodType(void.class));
    private static final MethodHandle HIT;

    static {
        try {
            HIT = MethodHandles.lookup().findStatic(Tripwire.class, "hit",
                    MethodType.methodType(void.class, String.class));
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static final Map<String, List<MutableCallSite>> SITES = new HashMap<>();
    private static final Set<String> OFF = new HashSet<>();
    private static final Object LOCK = new Object();

    private static final Map<String, String> ARMED = new ConcurrentHashMap<>();

    private static final Map<String, String> MOD_BY_CLASS = new ConcurrentHashMap<>();

    private TripwireSites() {
    }

    public static void registerClasses(String mod, Set<String> classes) {
        classes.forEach(name -> MOD_BY_CLASS.putIfAbsent(name, mod));
    }

    public static @Nullable String findModOfClass(String internalName) {
        return MOD_BY_CLASS.get(internalName);
    }

    public static byte @Nullable [] arm(String internalName, byte[] classBytes, String mod) {
        if (new ClassReader(classBytes).readUnsignedShort(6) < Opcodes.V1_7) {
            return null;
        }
        ClassNode node = new ClassNode();
        new ClassReader(classBytes).accept(node, 0);
        boolean armed = false;
        for (MethodNode method : node.methods) {
            if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0 || method.instructions.size() == 0) {
                continue;
            }
            method.instructions.insert(new InvokeDynamicInsnNode("hit", "()V", BOOTSTRAP, mod));
            armed = true;
        }
        if (!armed) {
            return null;
        }

        ClassWriter writer = new ClassWriter(new ClassReader(classBytes), ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        ARMED.put(internalName, mod);
        return writer.toByteArray();
    }

    public static boolean isArmed(String internalName) {
        return ARMED.containsKey(internalName);
    }

    public static void forget(String internalName) {
        ARMED.remove(internalName);
    }

    public static int countArmed(String mod) {
        int count = 0;
        for (String of : ARMED.values()) {
            if (of.equals(mod)) {
                count++;
            }
        }
        return count;
    }

    public static CallSite bootstrap(MethodHandles.Lookup caller, String name, MethodType type, String mod) {
        synchronized (LOCK) {
            MutableCallSite site = new MutableCallSite(OFF.contains(mod) ? HIT.bindTo(mod) : NOTHING);
            SITES.computeIfAbsent(mod, m -> new ArrayList<>()).add(site);
            return site;
        }
    }

    public static int setOff(String mod, boolean off) {
        List<MutableCallSite> changed;
        synchronized (LOCK) {
            if (off ? !OFF.add(mod) : !OFF.remove(mod)) {
                return 0;
            }
            changed = List.copyOf(SITES.getOrDefault(mod, List.of()));
            MethodHandle target = off ? HIT.bindTo(mod) : NOTHING;
            changed.forEach(site -> site.setTarget(target));
        }
        if (!changed.isEmpty()) {
            MutableCallSite.syncAll(changed.toArray(MutableCallSite[]::new));
        }
        LOGGER.info("[Hotwire] {} is watched by {} sites in {} classes: {}", mod, changed.size(), countArmed(mod),
                off ? "on" : "off");
        return changed.size();
    }
}
