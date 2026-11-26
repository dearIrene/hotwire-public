package dev.moon.hotwire.update;

import dev.moon.hotwire.jar.FileHashes;
import dev.moon.hotwire.jar.JarLookup;
import dev.moon.hotwire.jar.ModManifest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

public final class JarSwapper {
    private static void say(String line) {
        System.out.println(LocalTime.now(ZoneId.systemDefault()).withNano(0) + " " + line);
    }

    static final int MOVE_TRIES = 5;

    private static final String DISABLED = ".disabled";

    private record Move(Path from, Path to, boolean linked) {
    }

    private JarSwapper() {
    }

    static final String FOLDERS = "folders-";

    public static void main(String[] args) throws IOException, InterruptedException {
        long pid = Long.parseLong(args[0]);

        say("waiting for the game " + pid);
        List<String> folders = Files.readAllLines(Path.of(FOLDERS + pid + ".txt"));
        Path queueFile = Path.of(folders.get(0));
        Path mods = Path.of(folders.get(1));
        Path replaced = Path.of(folders.get(2));
        Optional<ProcessHandle> game = ProcessHandle.of(pid);
        if (game.isPresent()) {
            game.get().onExit().join();
        }
        UpdateQueue queue = new UpdateQueue(queueFile);
        say("the game ended; " + queue.getEntries().size() + " queued for " + mods);
        boolean isStuck = swap(queue, mods, replaced);
        for (int tried = 1; isStuck && tried < MOVE_TRIES; tried++) {
            Thread.sleep(1000);
            isStuck = swap(queue, mods, replaced);
        }
        if (isStuck) {
            say("the jars could not be moved after " + MOVE_TRIES + " tries");
        }
        say(queue.getEntries().size() + " still queued");

        if (queue.getEntries().isEmpty()) {
            PendingChanges.clean(Path.of(folders.get(3)), Set.of());
        }
    }

    static boolean swap(UpdateQueue queue, Path mods, Path replaced) throws IOException {
        List<UpdateQueue.Entry> entries = dropUndoable(queue, mods);
        Map<String, String> stuck = new LinkedHashMap<>();
        for (UpdateQueue.Entry entry : entries) {
            String reason = findChange(entry, mods);
            if (!reason.isEmpty()) {
                say(entry.name() + " " + entry.toVersion() + " waits: " + reason);
                stuck.put(entry.modId(), reason);
            }
        }
        if (!stuck.isEmpty()) {
            holdTied(entries, stuck);
        }
        List<UpdateQueue.Entry> left = new ArrayList<>();
        List<UpdateQueue.Entry> free = new ArrayList<>();
        for (UpdateQueue.Entry entry : entries) {
            String reason = stuck.get(entry.modId());
            if (reason == null) {
                free.add(entry);
            } else {
                left.add(entry.withReason(reason));
            }
        }
        List<UpdateQueue.Entry> failed = free.isEmpty() ? List.of() : moveAll(queue, free, left, mods, replaced);
        if (free.isEmpty() || !failed.isEmpty()) {
            left.addAll(failed);
            queue.replace(left);
        }
        PendingChanges.trimReplaced(queue, replaced);
        return !failed.isEmpty();
    }

    static List<UpdateQueue.Entry> dropUndoable(UpdateQueue queue, Path mods) throws IOException {
        List<UpdateQueue.Entry> still = new ArrayList<>();
        for (UpdateQueue.Entry entry : queue.getEntries()) {
            Path staged = Path.of(entry.stagedJar());
            Path running = findRunning(entry, mods);
            boolean gone = !Files.exists(staged);
            boolean done = !gone && running != null && FileHashes.findSha256(running).equals(entry.stagedSha256());
            if (gone || done) {
                say(entry.name() + " " + entry.toVersion() + " leaves the queue: " + (gone
                        ? "its new jar is gone (" + staged + ")" : "the mods folder has it already"));
            } else {
                still.add(entry);
            }
        }
        if (still.size() != queue.getEntries().size()) {
            queue.replace(still);
        }
        return still;
    }

    private static void holdTied(List<UpdateQueue.Entry> entries, Map<String, String> stuck) {
        Map<String, Set<String>> needs = new HashMap<>();
        for (UpdateQueue.Entry entry : entries) {
            ModManifest manifest = JarLookup.findManifest(Path.of(entry.stagedJar()));
            needs.put(entry.modId(), manifest == null ? Set.of()
                    : manifest.getRelation(ModManifest.Relation.DEPENDS).keySet());
        }
        boolean grew = true;
        while (grew) {
            grew = false;
            for (UpdateQueue.Entry entry : entries) {
                String id = entry.modId();
                if (stuck.containsKey(id)) {
                    continue;
                }
                for (String other : List.copyOf(stuck.keySet())) {
                    if (needs.getOrDefault(id, Set.of()).contains(other)
                            || needs.getOrDefault(other, Set.of()).contains(id)) {
                        stuck.put(id, "it goes in together with " + other + ", whose jar could not be put in place");
                        grew = true;
                        break;
                    }
                }
            }
        }
    }

    private static List<UpdateQueue.Entry> moveAll(UpdateQueue queue, List<UpdateQueue.Entry> entries,
            List<UpdateQueue.Entry> stuck, Path mods, Path replaced) {
        List<Move> moved = new ArrayList<>();
        List<UpdateQueue.ReplacedJar> records = new ArrayList<>(queue.getReplaced());
        try {
            Files.createDirectories(replaced);
            for (UpdateQueue.Entry entry : entries) {
                Path staged = Path.of(entry.stagedJar()).toAbsolutePath();
                String aside = moveOne(entry, mods, replaced, moved);
                say(entry.name() + " " + entry.toVersion() + " is in" + (aside == null ? "" : ", " + aside
                        + " moved aside"));
                if (replaced.toAbsolutePath().equals(staged.getParent())) {
                    records.removeIf(record -> record.file().equals(String.valueOf(staged.getFileName())));
                }
                if (aside != null) {
                    records.removeIf(record -> record.file().equals(aside));
                    records.add(new UpdateQueue.ReplacedJar(entry.modId(), entry.name(), entry.fromVersion(), aside));
                }
            }
            queue.replaceAll(stuck, records);
            return List.of();
        } catch (IOException e) {
            say("moving the jars failed, each goes back: " + e);
            putBack(moved);
            String reason = "the jars could not be moved: " + e.getMessage();
            return entries.stream().map(entry -> entry.withReason(reason)).toList();
        }
    }

    private static Path findTarget(Path staged, @Nullable Path running, Path mods) {
        if (running != null && isAppProfile(mods)) {
            return running;
        }
        boolean off = running != null && String.valueOf(running.getFileName()).endsWith(DISABLED);
        return mods.resolve(staged.getFileName() + (off ? DISABLED : ""));
    }

    private static boolean isAppProfile(Path mods) {
        Path profile = mods.toAbsolutePath().getParent();
        Path profiles = profile == null ? null : profile.getParent();
        return profiles != null && profiles.getParent() != null && profiles.endsWith("profiles")
                && Files.isRegularFile(profiles.resolveSibling("app.db"));
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

            Path into = findTarget(staged, running, mods);
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
        Path into = findTarget(staged, running, mods);
        if (running == null) {
            move(staged, into, moved);
            return null;
        }
        Path aside = replaced.resolve(stripDisabled(running.getFileName()));
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
        boolean linked = Files.exists(to) && Files.isSameFile(from, to);
        if (linked) {
            Files.delete(from);
        } else {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
        moved.add(new Move(from, to, linked));
    }

    private static void putBack(List<Move> moved) {
        for (Move move : moved.reversed()) {
            try {
                if (move.linked()) {
                    Files.copy(move.to(), move.from());
                } else {
                    Files.move(move.to(), move.from());
                }
            } catch (IOException e) {
                say(move.to() + " could not go back to " + move.from() + ": " + e);
            }
        }
    }
}
