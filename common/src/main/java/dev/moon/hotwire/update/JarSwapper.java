package dev.moon.hotwire.update;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

public final class JarSwapper {
    private static final Duration GAME_WAIT = Duration.ofMinutes(2);

    private JarSwapper() {
    }

    public static void main(String[] args) throws IOException, InterruptedException {
        Path queueFile = Path.of(args[0]);
        Path mods = Path.of(args[1]);
        Path replaced = Path.of(args[2]);
        long pid = Long.parseLong(args[3]);
        Optional<ProcessHandle> game = ProcessHandle.of(pid);
        if (game.isPresent()) {
            game.get().onExit().orTimeout(GAME_WAIT.toSeconds(), TimeUnit.SECONDS)
                    .exceptionally(timedOut -> null).join();
        }
        swap(new UpdateQueue(queueFile), mods, replaced);
    }

    static void swap(UpdateQueue queue, Path mods, Path replaced) throws IOException {
        List<UpdateQueue.Entry> left = new ArrayList<>();
        for (UpdateQueue.Entry entry : queue.getEntries()) {
            String reason = swapOne(entry, mods, replaced);
            if (!reason.isEmpty()) {
                left.add(entry.withReason(reason));
                continue;
            }

            queue.remove(entry.modId());
            Path staged = Path.of(entry.stagedJar()).toAbsolutePath();
            if (replaced.toAbsolutePath().equals(staged.getParent())) {
                queue.removeReplaced(String.valueOf(staged.getFileName()));
            }
            if (!entry.runningJar().isEmpty()) {
                queue.addReplaced(new UpdateQueue.ReplacedJar(entry.modId(), entry.name(), entry.fromVersion(),
                        entry.runningJar()));
            }
        }
        queue.replace(left);
        PendingChanges.trimReplaced(queue, replaced);
    }

    private static String swapOne(UpdateQueue.Entry entry, Path mods, Path replaced) {
        Path running = mods.resolve(entry.runningJar());
        Path staged = Path.of(entry.stagedJar());
        try {
            if (!Files.exists(staged)) {
                return "the new jar is no longer at " + staged;
            }
            if (!UpdateQueue.hashSha256(staged).equals(entry.stagedSha256())) {
                return "the new jar changed after it was queued";
            }
            boolean replacing = !entry.runningJar().isEmpty();
            if (replacing && Files.exists(running) && !UpdateQueue.hashSha256(running).equals(entry.runningSha256())) {
                return "the running jar changed after the update was queued";
            }
            Files.createDirectories(replaced);
            Path into = mods.resolve(staged.getFileName());
            Path aside = replaced.resolve(entry.runningJar());
            if (replacing && Files.exists(running)) {
                if (aside.toAbsolutePath().equals(staged.toAbsolutePath())) {
                    Path swap = staged.resolveSibling(staged.getFileName() + ".swap");
                    Files.move(staged, swap, StandardCopyOption.REPLACE_EXISTING);
                    staged = swap;
                }
                Files.move(running, aside, StandardCopyOption.REPLACE_EXISTING);
            }
            Files.move(staged, into, StandardCopyOption.REPLACE_EXISTING);
            return "";
        } catch (IOException e) {
            return "the jars could not be moved: " + e.getMessage();
        }
    }
}
