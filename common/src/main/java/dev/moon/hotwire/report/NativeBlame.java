package dev.moon.hotwire.report;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

public final class NativeBlame {
    private static final String HOTWIRE = "dev.moon.hotwire";

    private static final List<String> HOTWIRE_ON_BEHALF = List.of(".rebuild.TransformerChain", ".harness.");

    private static final List<String> ON_BEHALF = List.of("java.", "javax.", "jdk.", "sun.", "com.sun.", "org.lwjgl.",
            "net.minecraft.", "com.mojang.", "net.fabricmc.loader.", "org.spongepowered.", "com.llamalad7.mixinextras.",
            "it.unimi.dsi.", "com.google.", "io.netty.");
    private static final List<String> LIBRARIES_ON_BEHALF = List.of("jvm", "libjvm", "java", "libjava", "lwjgl",
            "liblwjgl", "glfw", "libglfw", "jemalloc", "ntdll", "kernel32", "kernelbase", "ucrtbase", "msvcrt",
            "vcruntime", "msvcp", "libc", "libpthread", "libsystem", "libdyld");
    private static final List<String> DRIVERS = List.of("ig4icd", "ig7icd", "ig75icd", "ig8icd", "ig9icd", "ig11icd",
            "ig12icd", "igxelp", "igvk", "igc", "nvoglv", "nvogl", "nv-vk", "libnvidia", "nvidia", "atio", "atig",
            "aticfx", "amdvlk", "amdxc", "vulkan-1", "libvulkan", "opengl32", "libgl", "libegl", "libgallium",
            "radeonsi", "iris_dri", "mesa", "moltenvk", "libmoltenvk");

    private static final Pattern HANDLER = Pattern.compile("\\$[a-z]{3}\\d{3}\\$([a-z0-9_-]+)\\$");

    public enum Part {
        HOTWIRE, UNKNOWN, NOT_HOTWIRE
    }

    public record Decision(Part part, String proof, @Nullable Where where) {
    }

    public record Where(@Nullable String library, boolean driver, @Nullable String type, @Nullable String mod) {
    }

    private NativeBlame() {
    }

    public static Decision of(NativeCrash crash, boolean idle) {
        Where where = null;
        String own = null;
        for (NativeCrash.Frame frame : crash.frames()) {
            Where at = findWhere(frame);
            String type = frame.type();
            if (type != null && isHotwires(type)) {
                own = frame.method();
                break;
            }
            if (at != null) {
                where = at;
                break;
            }
        }
        if (idle) {
            return new Decision(Part.NOT_HOTWIRE, "Hotwire was switched off for that start and changed nothing of the"
                    + " game.", where);
        }
        if (crash.redefining()) {
            return new Decision(Part.HOTWIRE, "The JVM was redefining classes for Hotwire as it crashed.", where);
        }
        if (own != null) {
            return new Decision(Part.HOTWIRE, "Hotwire's own code was running what crashed: " + own + ".", null);
        }
        String under = findHotwireUnder(crash);
        String since = crash.sinceChange().isEmpty() ? "no class change among the JVM's last records"
                : String.format(Locale.ROOT, "%.1f s after Hotwire last changed a class", crash.sinceChange()
                        .getAsDouble());
        return new Decision(Part.UNKNOWN, "No proof either way: " + since + (under == null ? ""
                : ", under Hotwire's " + under) + ".", where);
    }

    private static @Nullable Where findWhere(NativeCrash.Frame frame) {
        String library = frame.library();
        if (library != null) {
            String name = library.toLowerCase(Locale.ROOT);
            if (LIBRARIES_ON_BEHALF.stream().anyMatch(name::startsWith)) {
                return null;
            }
            return new Where(library, DRIVERS.stream().anyMatch(name::startsWith), null, null);
        }
        String method = frame.method();
        String type = frame.type();
        if (method == null || type == null || type.isEmpty()) {
            return null;
        }
        Matcher handler = HANDLER.matcher(method);
        if (handler.find()) {
            return handler.group(1).startsWith("hotwire") ? null : new Where(null, false, type, handler.group(1));
        }
        return ON_BEHALF.stream().anyMatch(type::startsWith) || type.startsWith(HOTWIRE) ? null
                : new Where(null, false, type, null);
    }

    private static boolean isHotwires(String type) {
        return type.startsWith(HOTWIRE) && HOTWIRE_ON_BEHALF.stream().noneMatch(type::contains);
    }

    private static @Nullable String findHotwireUnder(NativeCrash crash) {
        for (NativeCrash.Frame frame : crash.frames()) {
            String type = frame.type();
            if (type != null && isHotwires(type)) {
                return frame.method();
            }
        }
        return null;
    }
}
