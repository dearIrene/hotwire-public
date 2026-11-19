package dev.moon.hotwire.fabric.add;

import com.llamalad7.mixinextras.service.MixinExtrasService;
import com.llamalad7.mixinextras.service.MixinExtrasServiceImpl;
import dev.moon.hotwire.jvm.PrivateField;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class OwnExtras {
    private static final Logger LOGGER = LoggerFactory.getLogger(OwnExtras.class);
    private static final String VERSION = "/mixinextras/service/MixinExtrasVersion.class";
    private static final PrivateField STARTED = PrivateField.of(MixinExtrasServiceImpl.class, "initialized");

    private OwnExtras() {
    }

    static boolean isOffered(Path jar) {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                if (name.endsWith(VERSION) && !name.startsWith("com/llamalad7/")) {
                    Class<?> version = Class.forName(name.substring(0, name.length() - ".class".length())
                            .replace('/', '.'), true, OwnExtras.class.getClassLoader());
                    Object latest = version.getField("LATEST").get(null);
                    return (int) version.getMethod("getNumber").invoke(latest)
                            <= MixinExtrasService.getInstance().getVersion();
                }
            }
        } catch (IOException | ReflectiveOperationException | RuntimeException | LinkageError e) {
            LOGGER.debug("[Hotwire] {}'s own MixinExtras could not be read for its version", jar.getFileName(), e);
        }
        return false;
    }

    static void runOffered(Runnable load) {
        MixinExtrasServiceImpl service = MixinExtrasService.getInstance();
        if (!STARTED.get(service, Boolean.class)) {
            load.run();
            return;
        }
        STARTED.set(service, false);
        try {
            load.run();
        } finally {
            STARTED.set(service, true);
        }
    }
}
