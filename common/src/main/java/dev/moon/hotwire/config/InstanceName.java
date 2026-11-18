package dev.moon.hotwire.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

public final class InstanceName {
    private static final Set<String> GAME_FOLDERS = Set.of("minecraft", ".minecraft");
    private static final Set<String> INSTANCE_FOLDERS = Set.of("instances", "profiles");
    private static final String NAMED_IN = "instance.cfg";
    private static final String NAME_KEY = "name=";
    private static final int LONGEST = 40;

    private InstanceName() {
    }

    public static Optional<String> find(Path gameFolder) {
        Path instance = gameFolder.toAbsolutePath().normalize();
        if (isNamed(instance, GAME_FOLDERS) && instance.getParent() != null) {
            instance = instance.getParent();
        }
        Optional<String> written = readWritten(instance.resolve(NAMED_IN));
        if (written.isPresent()) {
            return written;
        }
        Path all = instance.getParent();
        if (all == null || !isNamed(all, INSTANCE_FOLDERS) || instance.getFileName() == null) {
            return Optional.empty();
        }
        return clean(instance.getFileName().toString());
    }

    private static boolean isNamed(Path folder, Set<String> names) {
        Path name = folder.getFileName();
        return name != null && names.contains(name.toString().toLowerCase(Locale.ROOT));
    }

    private static Optional<String> readWritten(Path file) {
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try (Stream<String> lines = Files.lines(file, StandardCharsets.UTF_8)) {
            return lines.filter(line -> line.startsWith(NAME_KEY)).findFirst()
                    .flatMap(line -> clean(line.substring(NAME_KEY.length())));
        } catch (IOException | RuntimeException unread) {
            return Optional.empty();
        }
    }

    private static Optional<String> clean(String name) {
        String shown = name.replaceAll("\\p{Cntrl}", "").strip();
        if (shown.length() > LONGEST) {
            shown = shown.substring(0, LONGEST).strip();
        }
        return shown.isEmpty() ? Optional.empty() : Optional.of(shown);
    }
}
