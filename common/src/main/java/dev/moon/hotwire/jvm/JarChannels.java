package dev.moon.hotwire.jvm;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.nio.channels.FileChannel;
import java.nio.file.spi.FileSystemProvider;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

public final class JarChannels {
    private JarChannels() {
    }

    public static void open(Instrumentation instrumentation) {
        FileSystemProvider jars = findJarProvider();
        if (jars == null) {
            return;
        }
        Set<Module> self = Set.of(JarChannels.class.getModule());
        instrumentation.redefineModule(jars.getClass().getModule(), Set.of(), Map.of(),
                Map.of(jars.getClass().getPackageName(), self), Set.of(), Map.of());
        instrumentation.redefineModule(Object.class.getModule(), Set.of(), Map.of("sun.nio.ch", self), Map.of(),
                Set.of(), Map.of());
    }

    public static int makeUninterruptible() {
        FileSystemProvider jars = findJarProvider();
        if (jars == null) {
            return 0;
        }
        try {
            Field systems = jars.getClass().getDeclaredField("filesystems");
            systems.setAccessible(true);
            Map<?, ?> open = (Map<?, ?>) systems.get(jars);
            List<Object> all;
            synchronized (open) {
                all = new ArrayList<>(open.values());
            }
            int made = 0;
            for (Object system : all) {
                Field channel = system.getClass().getDeclaredField("ch");
                channel.setAccessible(true);
                if (channel.get(system) instanceof FileChannel file && file.isOpen()) {
                    file.getClass().getMethod("setUninterruptible").invoke(file);
                    made++;
                }
            }
            return made;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return 0;
        }
    }

    private static @Nullable FileSystemProvider findJarProvider() {
        for (FileSystemProvider provider : FileSystemProvider.installedProviders()) {
            if ("jar".equals(provider.getScheme())) {
                return provider;
            }
        }
        return null;
    }
}
