package dev.moon.hotwire.update;

import dev.moon.hotwire.jar.FileHashes;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class JarSwapper {
    private static final Logger LOGGER = LoggerFactory.getLogger(JarSwapper.class);
    private static final String OTHER_FAILED = "another queued jar could not be put in place, so none was";

    private static final String DISABLED = ".disabled";

    private record Move(Path from, Path to) {
    }

    private JarSwapper() {
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        Path queueFile = Path.of(args[0]);
        Path mods = Path.of(args[1]);
        Path replaced = Path.of(args[2]);
        long pid = Long.parseLong(args[3]);
        Optional<ProcessHandle> game = ProcessHandle.of(pid);
        if (game.isPresent()) {
            game.get().onExit().join();
        }
        UpdateQueue queue = new UpdateQueue(queueFile);
        swap(queue, mods, replaced);

        if (args.length > 4 && queue.getEntries().isEmpty()) {
            PendingChanges.clean(Path.of(args[4]), Set.of());
        }
    }

    static void swap(UpdateQueue queue, Path mods, Path replaced) throws IOException {
        List<UpdateQueue.Entry> entries = queue.getEntries();
        List<UpdateQueue.Entry> left = new ArrayList<>();
        boolean changed = false;
        for (UpdateQueue.Entry entry : entries) {
            String reason = findChange(entry, mods);
            changed |= !reason.isEmpty();
            left.add(entry.withReason(reason.isEmpty() ? OTHER_FAILED : reason));
        }
        if (!changed) {
            left = moveAll(queue, entries, mods, replaced);
        }
        if (!left.isEmpty()) {
            queue.replace(left);
        }
        PendingChanges.trimReplaced(queue, replaced);
    }

    private static List<UpdateQueue.Entry> moveAll(UpdateQueue queue, List<UpdateQueue.Entry> entries, Path mods,
            Path replaced) {
        List<Move> moved = new ArrayList<>();
        List<UpdateQueue.ReplacedJar> records = new ArrayList<>(queue.getReplaced());
        try {
            Files.createDirectories(replaced);
            for (UpdateQueue.Entry entry : entries) {
                Path staged = Path.of(entry.stagedJar()).toAbsolutePath();
                String aside = moveOne(entry, mods, replaced, moved);
                if (replaced.toAbsolutePath().equals(staged.getParent())) {
                    records.removeIf(record -> record.file().equals(String.valueOf(staged.getFileName())));
                }
                if (aside != null) {
                    records.removeIf(record -> record.file().equals(aside));
                    records.add(new UpdateQueue.ReplacedJar(entry.modId(), entry.name(), entry.fromVersion(), aside));
                }
            }
            queue.replaceAll(List.of(), records);
            return List.of();
        } catch (IOException e) {
            putBack(moved);
            String reason = "the jars could not be moved: " + e.getMessage();
            return entries.stream().map(entry -> entry.withReason(reason)).toList();
        }
    }

    private static String findChange(UpdateQueue.Entry entry, Path mods) {
        Path staged = Path.of(entry.stagedJar());
        Path running = findRunning(entry, mods);
        try {
            if (!Files.exists(staged)) {
                return "the new jar is no longer at " + staged;
            }
            if (!FileHashes.findSha256(staged).equals(entry.stagedSha256())) {
                return "the new jar changed after it was queued";
            }
            if (running != null && !FileHashes.findSha256(running).equals(entry.runningSha256())) {
                return "the running jar changed after the update was queued";
            }

            Path into = mods.resolve(staged.getFileName());
            if (Files.isRegularFile(into) && (running == null || !Files.isSameFile(into, running))
                    && !FileHashes.findSha256(into).equals(entry.stagedSha256())) {
                return "another jar named " + into.getFileName() + " is in the mods folder";
            }
            return "";
        } catch (IOException e) {
            return "the jars could not be read: " + e.getMessage();
        }
    }

    private static @Nullable Path findRunning(UpdateQueue.Entry entry, Path mods) {
        if (entry.runningJar().isEmpty()) {
            return null;
        }
        Path running = mods.resolve(stripDisabled(Path.of(entry.runningJar()).getFileName()));
        Path off = running.resolveSibling(running.getFileName() + DISABLED);
        if (Files.exists(running)) {
            return running;
        }
        return Files.exists(off) ? off : null;
    }

    private static String stripDisabled(Path file) {
        String name = String.valueOf(file);
        return name.endsWith(DISABLED) ? name.substring(0, name.length() - DISABLED.length()) : name;
    }

    private static @Nullable String moveOne(UpdateQueue.Entry entry, Path mods, Path replaced, List<Move> moved)
            throws IOException {
        Path staged = Path.of(entry.stagedJar());
        Path running = findRunning(entry, mods);
        Path into = mods.resolve(staged.getFileName());
        if (running == null) {
            move(staged, into, moved);
            return null;
        }
        Path aside = replaced.resolve(stripDisabled(running.getFileName()));
        if (String.valueOf(running.getFileName()).endsWith(DISABLED)) {
            into = mods.resolve(staged.getFileName() + DISABLED);
        }
        if (aside.toAbsolutePath().equals(staged.toAbsolutePath())) {
            Path swap = staged.resolveSibling(staged.getFileName() + ".swap");
            move(staged, swap, moved);
            staged = swap;
        }
        move(running, aside, moved);
        move(staged, into, moved);
        return String.valueOf(aside.getFileName());
    }

    private static void move(Path from, Path to, List<Move> moved) throws IOException {
        Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        moved.add(new Move(from, to));
    }

    private static void putBack(List<Move> moved) {
        for (Move move : moved.reversed()) {
            try {
                Files.move(move.to(), move.from());
            } catch (IOException e) {
                LOGGER.warn("[Hotwire] {} could not go back to {}", move.to(), move.from(), e);
            }
        }
    }
}
