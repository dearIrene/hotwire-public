package dev.moon.hotwire.fabric;

import dev.moon.hotwire.jvm.ClassPathIndex;
import dev.moon.hotwire.jvm.JvmAccess;
import dev.moon.hotwire.jvm.JvmAccessException;
import dev.moon.hotwire.report.StartTimes;
import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.ClassDefinition;
import java.lang.instrument.Instrumentation;
import java.lang.instrument.UnmodifiableClassException;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.jar.JarFile;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class GameClassIndex {
    private static final Logger LOGGER = LoggerFactory.getLogger(GameClassIndex.class);
    private static final String KNOT = "net.fabricmc.loader.impl.launch.knot.KnotClassLoader";
    private static final String AGENT = "dev/moon/hotwire/jvm/AgentMain";
    private static final String LOOKUP = "(Ljava/lang/String;)Ljava/net/URL;";
    private static final String PARENT = "originalLoader";
    private static final int CHECKED = 2000;

    private GameClassIndex() {
    }

    static void begin(Instrumentation instrumentation, ClassLoader game) {
        if (!Boolean.parseBoolean(System.getProperty("hotwire.classIndex", "true"))
                || !game.getClass().getName().equals(KNOT) || !(game.getParent() instanceof URLClassLoader jars)) {
            return;
        }
        Thread.ofPlatform().daemon().name("hotwire-class-index").start(() -> {
            long began = System.nanoTime();
            try {
                URL[] path = jars.getURLs();
                ClassPathIndex index = ClassPathIndex.of(path);
                if (index.countNames() == 0) {
                    index.close();
                    return;
                }

                Map<String, String> answers = new LinkedHashMap<>();
                index.getSamples().forEach(name -> answers.put(name, String.valueOf(jars.findResource(name))));
                Map<Path, JarFile> opened;
                try {
                    opened = findOpenJars(jars);
                } catch (JvmAccessException | ReflectiveOperationException | RuntimeException e) {
                    index.close();
                    throw e;
                }
                index.useFiles(opened::get);
                for (Map.Entry<String, String> sample : answers.entrySet()) {
                    if (!sample.getValue().equals(String.valueOf(index.apply(sample.getKey())))) {
                        LOGGER.info("[Hotwire] the game's loader finds {} at {} and the index of its jars at {}: the"
                                + " loader goes on asking every jar", sample.getKey(), sample.getValue(),
                                index.apply(sample.getKey()));
                        return;
                    }
                }
                byte[] patched = patch(read(game.getClass()));

                Class<?> agent = Class.forName(AGENT.replace('/', '.'), false, ClassLoader.getSystemClassLoader());
                AtomicInteger checked = new AtomicInteger();
                Runnable stop = () -> {
                    try {
                        agent.getMethod("setResources", Function.class, Predicate.class).invoke(null, null, null);
                    } catch (ReflectiveOperationException e) {
                        checked.set(Integer.MIN_VALUE);
                    }
                };
                Function<String, @Nullable URL> finder = name -> {
                    URL answered = index.apply(name);
                    if (answered == null || isTrusted(checked)) {
                        return answered;
                    }
                    URL own = jars.findResource(name);
                    if (!String.valueOf(own).equals(String.valueOf(answered))) {
                        stop.run();
                        LOGGER.warn("[Hotwire] the game's loader finds {} at {} and the index of its jars at {}: the"
                                + " loader asks every jar again from here", name, own, answered);
                    }
                    return own;
                };
                List<?> live = index.countJars() == path.length ? findLivePath(jars) : null;
                Predicate<String> absent = name -> {
                    if (live == null || live.size() != path.length || !index.isAbsent(name)) {
                        return false;
                    }
                    if (isTrusted(checked)) {
                        return true;
                    }
                    URL own = jars.findResource(name);
                    if (own != null) {
                        stop.run();
                        LOGGER.warn("[Hotwire] the game's loader finds {} at {} and the index of its jars nowhere: the"
                                + " loader asks every jar again from here", name, own);
                    }
                    return own == null;
                };
                agent.getMethod("setResources", Function.class, Predicate.class).invoke(null, finder, absent);
                index.activate();
                instrumentation.redefineClasses(new ClassDefinition(game.getClass(), patched));
                StartTimes.add("class index (own thread)", began);
                LOGGER.info("[Hotwire] the game's loader finds a class's jar through an index of {} names in {} jars,"
                        + " made in {} ms{}", index.countNames(), index.countJars(),
                        (System.nanoTime() - began) / 1_000_000, live == null ? "; a class no jar holds is the"
                        + " loader's to answer" : "");
            } catch (ReflectiveOperationException | UnmodifiableClassException | IOException | JvmAccessException
                    | RuntimeException | LinkageError e) {
                LOGGER.info("[Hotwire] the game's loader goes on asking every jar for a class: {}", e.toString());
            }
        });
    }

    private static boolean isTrusted(AtomicInteger checked) {
        return checked.get() >= CHECKED || checked.incrementAndGet() > CHECKED;
    }

    private static @Nullable List<?> findLivePath(URLClassLoader jars) {
        try {
            MethodHandles.Lookup trusted = JvmAccess.findTrustedLookup();
            Class<?> pathType = Class.forName("jdk.internal.loader.URLClassPath");
            Object classPath = trusted.findVarHandle(URLClassLoader.class, "ucp", pathType).get(jars);
            return (ArrayList<?>) trusted.findVarHandle(pathType, "path", ArrayList.class).get(classPath);
        } catch (JvmAccessException | ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    private static Map<Path, JarFile> findOpenJars(URLClassLoader jars)
            throws JvmAccessException, ReflectiveOperationException {
        MethodHandles.Lookup trusted = JvmAccess.findTrustedLookup();
        Class<?> pathType = Class.forName("jdk.internal.loader.URLClassPath");
        Class<?> jarLoader = Class.forName("jdk.internal.loader.URLClassPath$JarLoader");
        Object classPath = trusted.findVarHandle(URLClassLoader.class, "ucp", pathType).get(jars);
        VarHandle file = trusted.findVarHandle(jarLoader, "jar", JarFile.class);
        Map<Path, JarFile> opened = new HashMap<>();
        synchronized (classPath) {
            for (Object loader : (ArrayList<?>) trusted.findVarHandle(pathType, "loaders", ArrayList.class)
                    .get(classPath)) {
                if (jarLoader.isInstance(loader) && file.get(loader) instanceof JarFile jar) {
                    opened.put(Path.of(jar.getName()).toAbsolutePath().normalize(), jar);
                }
            }
        }
        return opened;
    }

    private static byte[] read(Class<?> loader) throws IOException {
        ClassLoader from = loader.getClassLoader();
        String file = loader.getName().replace('.', '/') + ".class";
        try (InputStream in = from == null ? ClassLoader.getSystemResourceAsStream(file)
                : from.getResourceAsStream(file)) {
            if (in == null) {
                throw new IOException(file + " is not to be read");
            }
            return in.readAllBytes();
        }
    }

    private static byte[] patch(byte[] bytes) throws IOException {
        ClassReader reader = new ClassReader(bytes);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        int[] patched = {0};
        boolean[] parent = {false};
        String self = reader.getClassName();
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public FieldVisitor visitField(int access, String name, String descriptor, String signature,
                    Object value) {
                parent[0] |= name.equals(PARENT) && descriptor.equals("Ljava/lang/ClassLoader;")
                        && (access & Opcodes.ACC_STATIC) == 0;
                return super.visitField(access, name, descriptor, signature, value);
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature,
                    String[] exceptions) {
                MethodVisitor method = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (!descriptor.equals(LOOKUP) || (access & Opcodes.ACC_STATIC) != 0 || !(name.equals("getResource")
                        || name.equals("findResource") || name.equals("findResourceFwd"))) {
                    return method;
                }
                patched[0]++;
                boolean falls = name.equals("getResource");
                return new MethodVisitor(Opcodes.ASM9, method) {
                    @Override
                    public void visitCode() {
                        super.visitCode();
                        Label on = new Label();
                        super.visitVarInsn(Opcodes.ALOAD, 1);
                        super.visitMethodInsn(Opcodes.INVOKESTATIC, AGENT, "findResource", LOOKUP, false);
                        super.visitInsn(Opcodes.DUP);
                        super.visitJumpInsn(Opcodes.IFNULL, on);
                        super.visitInsn(Opcodes.ARETURN);
                        super.visitLabel(on);
                        super.visitFrame(Opcodes.F_SAME1, 0, null, 1, new Object[] {"java/net/URL"});
                        super.visitInsn(Opcodes.POP);

                        if (falls && !parent[0]) {
                            return;
                        }
                        Label search = new Label();
                        super.visitVarInsn(Opcodes.ALOAD, 1);
                        super.visitMethodInsn(Opcodes.INVOKESTATIC, AGENT, "isAbsent", "(Ljava/lang/String;)Z", false);
                        super.visitJumpInsn(Opcodes.IFEQ, search);
                        if (falls) {
                            super.visitVarInsn(Opcodes.ALOAD, 0);
                            super.visitFieldInsn(Opcodes.GETFIELD, self, PARENT, "Ljava/lang/ClassLoader;");
                            super.visitVarInsn(Opcodes.ALOAD, 1);
                            super.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/lang/ClassLoader", "getResource",
                                    LOOKUP, false);
                        } else {
                            super.visitInsn(Opcodes.ACONST_NULL);
                        }
                        super.visitInsn(Opcodes.ARETURN);
                        super.visitLabel(search);
                        super.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
                    }
                };
            }
        }, 0);
        if (patched[0] != 3) {
            throw new IOException("the loader has " + patched[0] + " of the three lookups by name");
        }
        return writer.toByteArray();
    }
}
