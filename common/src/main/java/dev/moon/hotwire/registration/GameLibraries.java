package dev.moon.hotwire.registration;

import dev.moon.hotwire.config.Flags;
import dev.moon.hotwire.jvm.ClassPathListing;
import java.net.URL;
import java.nio.file.Path;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class GameLibraries {
    private static final boolean VERIFY = Flags.VERIFY_LIBRARIES;
    private static final Logger LOGGER = LoggerFactory.getLogger(GameLibraries.class);

    private GameLibraries() {
    }

    public static boolean has(String internalName, @Nullable String modJar) {
        int nested = internalName.indexOf('$');
        String file = (nested < 0 ? internalName : internalName.substring(0, nested)) + ".class";
        if (!ClassPathListing.canHave(file)) {
            if (VERIFY && ClassLoader.getSystemClassLoader().getResource(file) != null) {
                LOGGER.warn("[Hotwire] the class path listing missed {}", file);
            }
            return false;
        }
        Path first = ClassPathListing.findFirstJar(file);
        if (first != null) {
            return modJar == null || !first.toString().contains(modJar);
        }
        URL own = ClassLoader.getSystemClassLoader().getResource(file);
        return own != null && (modJar == null || !own.toString().contains(modJar));
    }
}
