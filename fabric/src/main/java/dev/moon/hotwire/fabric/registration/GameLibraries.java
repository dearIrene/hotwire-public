package dev.moon.hotwire.fabric.registration;

import java.net.URL;
import org.jspecify.annotations.Nullable;

public final class GameLibraries {
    private GameLibraries() {
    }

    public static boolean has(String internalName, @Nullable String modJar) {
        int nested = internalName.indexOf('$');
        String top = nested < 0 ? internalName : internalName.substring(0, nested);
        URL own = ClassLoader.getSystemClassLoader().getResource(top + ".class");
        return own != null && (modJar == null || !own.toString().contains(modJar));
    }
}
