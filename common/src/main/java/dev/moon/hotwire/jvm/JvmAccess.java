package dev.moon.hotwire.jvm;

import dev.moon.hotwire.jvm.JvmAccessException.Reason;
import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandleProxies;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.UndeclaredThrowableException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import org.jspecify.annotations.Nullable;

public final class JvmAccess {
    static final String AGENT = AgentMain.class.getName();

    private JvmAccess() {
    }

    public static synchronized Instrumentation getInstrumentation(Path jar) throws JvmAccessException {
        Instrumentation running = findRunningInstrumentation();
        if (running != null) {
            return running;
        }
        checkAgentJar(jar);
        String forLoadAgent = toLoadAgentPath(jar, Path.of("").toAbsolutePath(), getPlatformCharset());
        MethodHandles.Lookup trusted = findTrustedLookup();
        ClassLoader system = ClassLoader.getSystemClassLoader();
        String path = jar.toAbsolutePath().toString();
        MethodHandle append;
        try {
            append = trusted.findVirtual(system.getClass(), "appendToClassPathForInstrumentation",
                    MethodType.methodType(void.class, String.class)).bindTo(system);
        } catch (ReflectiveOperationException e) {
            throw new JvmAccessException(Reason.CANNOT_EXTEND_CLASS_PATH, system.getClass().getName(), e);
        }
        call(append, path, Reason.CANNOT_EXTEND_CLASS_PATH);
        MethodHandle load;
        try {
            load = trusted.findStatic(Class.forName("sun.instrument.InstrumentationImpl"), "loadAgent",
                    MethodType.methodType(void.class, String.class));
        } catch (ReflectiveOperationException e) {
            throw new JvmAccessException(Reason.NO_INSTRUMENT_MODULE, "sun.instrument.InstrumentationImpl.loadAgent",
                    e);
        }
        call(load, forLoadAgent, Reason.AGENT_DID_NOT_START);
        running = findRunningInstrumentation();
        if (running == null) {
            throw new JvmAccessException(Reason.AGENT_DID_NOT_START, "loadAgent returned but " + AGENT
                    + " was not called",
                    null);
        }
        if (!running.isRedefineClassesSupported()) {
            throw new JvmAccessException(Reason.NO_REDEFINITION, "this JVM cannot redefine classes", null);
        }
        return running;
    }

    @SuppressWarnings("unchecked")
    private static void call(MethodHandle handle, String path, Reason reason) throws JvmAccessException {
        Consumer<String> consumer = MethodHandleProxies.asInterfaceInstance(Consumer.class, handle);
        try {
            consumer.accept(path);
        } catch (UndeclaredThrowableException e) {
            throw new JvmAccessException(reason, path, e.getCause());
        } catch (RuntimeException e) {
            throw new JvmAccessException(reason, path, e);
        }
    }

    static String toLoadAgentPath(Path jar, Path workingDirectory, Charset platform) throws JvmAccessException {
        CharsetEncoder encoder = platform.newEncoder();
        Path absolute = jar.toAbsolutePath().normalize();
        if (encoder.canEncode(absolute.toString())) {
            return absolute.toString();
        }
        try {
            String relative = workingDirectory.toAbsolutePath().normalize().relativize(absolute).toString();
            if (encoder.canEncode(relative)) {
                return relative;
            }
        } catch (IllegalArgumentException e) {
        }
        throw new JvmAccessException(Reason.PATH_NOT_ENCODABLE, absolute + " cannot be written in " + platform
                + ", the charset the JDK opens it with, from here or from " + workingDirectory, null);
    }

    static Charset getPlatformCharset() {
        try {
            return Charset.forName(System.getProperty("sun.jnu.encoding", "UTF-8"));
        } catch (IllegalArgumentException e) {
            return StandardCharsets.UTF_8;
        }
    }

    static @Nullable Instrumentation findRunningInstrumentation() {
        try {
            Class<?> agent = Class.forName(AGENT, false, ClassLoader.getSystemClassLoader());
            return (Instrumentation) agent.getMethod("getInstrumentation").invoke(null);
        } catch (ClassNotFoundException e) {
            return null;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(AGENT + " as the system class loader sees it cannot be asked for its"
                    + " instrumentation", e);
        }
    }

    static void checkAgentJar(Path jar) throws JvmAccessException {
        if (!Files.isRegularFile(jar)) {
            throw new JvmAccessException(Reason.NOT_AN_AGENT_JAR, jar + " is not a file", null);
        }
        Manifest manifest;
        try (JarFile file = new JarFile(jar.toFile())) {
            manifest = file.getManifest();
        } catch (IOException e) {
            throw new JvmAccessException(Reason.NOT_AN_AGENT_JAR, jar + " cannot be read as a jar", e);
        }
        Attributes main = manifest == null ? new Attributes() : manifest.getMainAttributes();
        if (!AGENT.equals(main.getValue("Launcher-Agent-Class"))) {
            throw new JvmAccessException(Reason.NOT_AN_AGENT_JAR,
                    jar + " does not name " + AGENT + " as its Launcher-Agent-Class", null);
        }
        if (!"true".equals(main.getValue("Can-Redefine-Classes"))) {
            throw new JvmAccessException(Reason.NOT_AN_AGENT_JAR, jar + " does not allow redefining classes", null);
        }
    }

    private static MethodHandles.Lookup findTrustedLookup() throws JvmAccessException {
        try {
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            Field instance = unsafeClass.getDeclaredField("theUnsafe");
            instance.setAccessible(true);
            Object unsafe = instance.get(null);
            Field implLookup = MethodHandles.Lookup.class.getDeclaredField("IMPL_LOOKUP");
            Object base = unsafeClass.getMethod("staticFieldBase", Field.class).invoke(unsafe, implLookup);
            long offset = (long) unsafeClass.getMethod("staticFieldOffset", Field.class).invoke(unsafe, implLookup);
            return (MethodHandles.Lookup) unsafeClass.getMethod("getObject", Object.class, long.class)
                    .invoke(unsafe, base, offset);
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new JvmAccessException(Reason.NO_TRUSTED_LOOKUP, "sun.misc.Unsafe", e);
        }
    }
}
