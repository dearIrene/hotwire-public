package dev.moon.hotwire.fabric.registration;

import dev.moon.hotwire.redefine.ClassRebuilder;
import dev.moon.hotwire.redefine.RebuildException;
import java.io.FilterOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.lang.reflect.InaccessibleObjectException;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.LockSupport;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.async.AsyncLogger;
import org.apache.logging.log4j.core.async.AsyncLoggerContext;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.filter.CompositeFilter;
import org.apache.logging.log4j.core.filter.Filterable;
import org.apache.logging.log4j.core.jmx.RingBufferAdmin;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class AsyncLogging {
    private static final Logger LOGGER = LoggerFactory.getLogger(AsyncLogging.class);

    private static final String LOG_MESSAGE = "logMessage(Ljava/lang/String;Lorg/apache/logging/log4j/Level;"
            + "Lorg/apache/logging/log4j/Marker;Lorg/apache/logging/log4j/message/Message;Ljava/lang/Throwable;)V";
    private static final String LOG = "log(Lorg/apache/logging/log4j/Level;Lorg/apache/logging/log4j/Marker;"
            + "Ljava/lang/String;Ljava/lang/StackTraceElement;Lorg/apache/logging/log4j/message/Message;"
            + "Ljava/lang/Throwable;)V";
    private static final long DRAIN_MILLIS = 2_000;

    private record Removal(String mod, List<RemovedFilter> filters, List<Stream> streams) {
    }

    private record RemovedFilter(Filterable on, Filter filter) {
    }

    private record Stream(boolean out, PrintStream wrapper, PrintStream original) {
    }

    private final ClassRebuilder rebuilder;
    private final Instrumentation instrumentation;
    private @Nullable Removal removals;
    private byte[] running = new byte[0];

    public AsyncLogging(ClassRebuilder rebuilder, Instrumentation instrumentation) {
        this.rebuilder = rebuilder;
        this.instrumentation = instrumentation;
    }

    public synchronized void takeOut(Collection<String> mods) {
        String mod = findAsyncMod();
        if (mod == null || !mods.contains(mod) || this.removals != null) {
            return;
        }
        LoggerContext context = (LoggerContext) LogManager.getContext(false);
        try {
            byte[] running = this.rebuilder.getLoadedBytes().readCurrent(List.of(AsyncLogger.class))
                    .get(Type.getInternalName(AsyncLogger.class));
            if (running == null) {
                LOGGER.warn("[Hotwire] {} keeps the game's logging: AsyncLogger's bytes could not be read", mod);
                return;
            }
            byte[] synchronous = rewriteSynchronous(running);
            if (synchronous == null) {
                LOGGER.warn("[Hotwire] {} keeps the game's logging: this Log4j's AsyncLogger has other ways in", mod);
                return;
            }
            this.rebuilder.redefine(Map.of(AsyncLogger.class, synchronous));

            List<RemovedFilter> filters = new ArrayList<>();
            List<Stream> streams = new ArrayList<>();
            this.running = running;
            this.removals = new Removal(mod, filters, streams);
            drain((AsyncLoggerContext) context);
            filters.addAll(removeFilters(context.getConfiguration(), mod));
            streams.addAll(this.removeStreams(mod));
            LOGGER.info("[Hotwire] the game logs without {} now: its loggers write at once, {} filters and {} streams"
                    + " of it are out", mod, filters.size(), streams.size());
        } catch (RebuildException | RuntimeException e) {
            if (this.removals == null) {
                LOGGER.warn("[Hotwire] {} keeps the game's logging: {}", mod, e.toString());
            } else {
                LOGGER.warn("[Hotwire] the game logs without {} now, though not all of it came out: {}", mod,
                        e.toString());
            }
        }
    }

    @SuppressWarnings("ReferenceEquality")
    public synchronized void putBack(Collection<String> mods) {
        Removal back = this.removals;
        if (back == null || !mods.contains(back.mod())) {
            return;
        }
        this.removals = null;
        for (Stream stream : back.streams()) {
            if ((stream.out() ? System.out : System.err) == stream.original()) {
                if (stream.out()) {
                    System.setOut(stream.wrapper());
                } else {
                    System.setErr(stream.wrapper());
                }
            }
        }
        back.filters().forEach(one -> one.on().addFilter(one.filter()));
        try {
            this.rebuilder.redefine(Map.of(AsyncLogger.class, this.running));
        } catch (RebuildException e) {
            LOGGER.warn("[Hotwire] the game's loggers could not go back to {}", back.mod(), e);
        }
    }

    private static @Nullable String findAsyncMod() {
        if (!(LogManager.getContext(false) instanceof AsyncLoggerContext)) {
            return null;
        }
        try {
            Class<?> ring = Class.forName("com.lmax.disruptor.RingBuffer", false,
                    AsyncLogger.class.getClassLoader());
            return GameRegistrations.findModOf(ring);
        } catch (ClassNotFoundException | LinkageError e) {
            return null;
        }
    }

    private static byte @Nullable [] rewriteSynchronous(byte[] running) {
        ClassNode node = new ClassNode();
        new ClassReader(running).accept(node, 0);
        int replaced = 0;
        for (MethodNode method : node.methods) {
            String shape = method.name + method.desc;
            if (!shape.equals(LOG_MESSAGE) && !shape.equals(LOG)) {
                continue;
            }
            InsnList body = new InsnList();
            body.add(new VarInsnNode(Opcodes.ALOAD, 0));
            int slot = 1;
            for (Type argument : Type.getArgumentTypes(method.desc)) {
                body.add(new VarInsnNode(argument.getOpcode(Opcodes.ILOAD), slot));
                slot += argument.getSize();
            }
            body.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, node.superName, method.name, method.desc, false));
            body.add(new InsnNode(Opcodes.RETURN));
            method.instructions = body;
            method.tryCatchBlocks = new ArrayList<>();
            method.localVariables = null;
            replaced++;
        }
        if (replaced != 2) {
            return null;
        }
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        return writer.toByteArray();
    }

    private static void drain(AsyncLoggerContext context) {
        RingBufferAdmin admin = context.createRingBufferAdmin();
        long until = System.currentTimeMillis() + DRAIN_MILLIS;
        while (admin.getRemainingCapacity() < admin.getBufferSize() && System.currentTimeMillis() < until) {
            LockSupport.parkNanos(1_000_000);
        }
    }

    private static List<RemovedFilter> removeFilters(Configuration config, String mod) {
        List<RemovedFilter> off = new ArrayList<>();
        List<Filterable> holders = new ArrayList<>();
        holders.add(config);
        holders.add(config.getRootLogger());
        holders.addAll(config.getLoggers().values());
        for (Filterable holder : Set.copyOf(holders)) {
            Filter filter = holder.getFilter();
            List<Filter> each = filter instanceof CompositeFilter composite ? List.of(composite.getFiltersArray())
                    : filter == null ? List.of() : List.of(filter);
            for (Filter one : each) {
                if (mod.equals(GameRegistrations.findModOf(one.getClass()))) {
                    holder.removeFilter(one);
                    off.add(new RemovedFilter(holder, one));
                }
            }
        }
        return off;
    }

    @SuppressWarnings("ReferenceEquality")
    private List<Stream> removeStreams(String mod) {
        List<Stream> off = new ArrayList<>();
        for (boolean out : new boolean[] {true, false}) {
            PrintStream wrapper = out ? System.out : System.err;
            PrintStream original = wrapper;
            while (mod.equals(GameRegistrations.findModOf(original.getClass()))) {
                PrintStream inner = this.findWrapped(original);
                if (inner == null) {
                    break;
                }
                original = inner;
            }
            if (original != wrapper && !mod.equals(GameRegistrations.findModOf(original.getClass()))) {
                if (out) {
                    System.setOut(original);
                } else {
                    System.setErr(original);
                }
                off.add(new Stream(out, wrapper, original));
            }
        }
        return off;
    }

    private @Nullable PrintStream findWrapped(PrintStream stream) {
        for (Class<?> type = stream.getClass(); type != null && type != PrintStream.class;
                type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (field.getType() == PrintStream.class && !Modifier.isStatic(field.getModifiers())) {
                    try {
                        field.setAccessible(true);
                        if (field.get(stream) instanceof PrintStream retained) {
                            return retained;
                        }
                    } catch (IllegalAccessException | InaccessibleObjectException | SecurityException e) {
                    }
                }
            }
        }
        try {
            this.instrumentation.redefineModule(OutputStream.class.getModule(), Set.of(), Map.of(),
                    Map.of("java.io", Set.of(AsyncLogging.class.getModule())), Set.of(), Map.of());
            Field inner = FilterOutputStream.class.getDeclaredField("out");
            inner.setAccessible(true);
            return inner.get(stream) instanceof PrintStream print ? print : null;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }
}
