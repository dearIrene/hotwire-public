package dev.moon.hotwire.redefine;

import java.lang.invoke.CallSite;
import java.lang.invoke.ConstantCallSite;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
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
        boolean inserted = false;
        for (MethodNode method : node.methods) {
            if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0 || method.instructions.size() == 0) {
                continue;
            }

            if (hasSite(method)) {
                armed = true;
                continue;
            }
            method.instructions.insert(new InvokeDynamicInsnNode("hit", "()V", BOOTSTRAP, mod));
            armed = true;
            inserted = true;
        }
        if (!armed) {
            return null;
        }
        if (!inserted) {
            ARMED.put(internalName, mod);
            return classBytes;
        }

        ClassWriter writer = new ClassWriter(new ClassReader(classBytes), ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        ARMED.put(internalName, mod);
        return writer.toByteArray();
    }

    private static boolean hasSite(MethodNode method) {
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof InvokeDynamicInsnNode site) {
                return site.name.equals("hit") && site.bsm.equals(BOOTSTRAP);
            }
            if (insn.getOpcode() >= 0) {
                return false;
            }
        }
        return false;
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

    private static final class Watch {
        private final String mod;
        private volatile boolean off;

        Watch(String mod) {
            this.mod = mod;
        }
    }

    private static final Map<String, Watch> WATCHES = new ConcurrentHashMap<>();
    private static final MethodHandle PROBE;

    static {
        try {
            PROBE = MethodHandles.lookup().findStatic(TripwireSites.class, "probe",
                    MethodType.methodType(void.class, Watch.class));
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static Watch findWatch(String mod) {
        return WATCHES.computeIfAbsent(mod, Watch::new);
    }

    @SuppressWarnings("UnusedMethod")
    private static void probe(Watch watch) {
        if (watch.off) {
            Tripwire.hit(watch.mod);
        }
    }

    public static CallSite bootstrap(MethodHandles.Lookup caller, String name, MethodType type, String mod) {
        return new ConstantCallSite(PROBE.bindTo(findWatch(mod)));
    }

    public static int setOff(String mod, boolean off) {
        Watch watch = findWatch(mod);
        if (watch.off == off) {
            return 0;
        }
        watch.off = off;
        int classes = countArmed(mod);
        LOGGER.info("[Hotwire] {} is watched by sites in {} classes: {}", mod, classes, off ? "on" : "off");
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("[Hotwire] the watch of {} was set by {}", mod, StackWalker.getInstance().walk(frames ->
                    frames.skip(1).map(frame -> frame.getClassName() + "." + frame.getMethodName()).limit(4)
                            .collect(Collectors.joining(" < "))));
        }
        return classes;
    }
}
