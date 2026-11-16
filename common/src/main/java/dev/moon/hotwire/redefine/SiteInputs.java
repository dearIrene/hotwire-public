package dev.moon.hotwire.redefine;

import dev.moon.hotwire.config.Flags;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Set;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class SiteInputs {
    private static final Logger LOGGER = LoggerFactory.getLogger(SiteInputs.class);
    private static final @Nullable Path FOLDER = Flags.DUMP_SITES == null ? null
            : Path.of(Flags.DUMP_SITES);

    private SiteInputs() {
    }

    static void writeMixed(String internalName, byte[] bytes) {
        if (FOLDER != null && ClassBytes.mentions(bytes, HandlerGuards.MERGED_BYTES)) {
            write("mixed/" + internalName + ".class", bytes);
        }
    }

    static Function<String, byte @Nullable []> writeAsked(Function<String, byte @Nullable []> reader) {
        if (FOLDER == null) {
            return reader;
        }
        return name -> {
            byte[] bytes = reader.apply(name);
            if (bytes != null) {
                write("shipped/" + name + ".class", bytes);
            }
            return bytes;
        };
    }

    static Function<String, Set<String>> writeTold(Function<String, Set<String>> reader) {
        if (FOLDER == null) {
            return reader;
        }
        return method -> {
            Set<String> mixins = reader.apply(method);
            if (!mixins.isEmpty()) {
                append("overwriters.txt", method + "\t" + String.join(",", mixins));
            }
            return mixins;
        };
    }

    static void writeNeverSwitched(Set<String> mods, Set<String> mixins) {
        mods.forEach(mod -> append("never.txt", "mod\t" + mod));
        mixins.forEach(mixin -> append("never.txt", "mixin\t" + mixin));
    }

    static void writeClasses(String mod, Set<String> classes) {
        if (FOLDER != null) {
            append("classes.txt", mod + "\t" + String.join(",", classes));
        }
    }

    private static synchronized void append(String name, String line) {
        if (FOLDER == null) {
            return;
        }
        try {
            Files.createDirectories(FOLDER);
            Files.writeString(FOLDER.resolve(name), line + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (IOException e) {
            LOGGER.warn("[Hotwire] {} was not written for the site check: {}", name, e.toString());
        }
    }

    private static void write(String name, byte[] bytes) {
        if (FOLDER == null) {
            return;
        }
        try {
            Path file = FOLDER.resolve(name);
            Files.createDirectories(file.getParent());
            Files.write(file, bytes);
        } catch (IOException | RuntimeException e) {
            LOGGER.warn("[Hotwire] {} was not written for the site check: {}", name, e.toString());
        }
    }
}
