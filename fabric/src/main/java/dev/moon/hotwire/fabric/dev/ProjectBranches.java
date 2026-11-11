package dev.moon.hotwire.fabric.dev;

import dev.moon.hotwire.concurrent.Tasks;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

final class ProjectBranches {
    private static final long READ_EVERY_MILLIS = 2_000;
    private final Tasks tasks;
    private final Map<String, String> branches = new ConcurrentHashMap<>();
    private final Map<String, Long> readAt = new ConcurrentHashMap<>();

    ProjectBranches(Tasks tasks) {
        this.tasks = tasks;
    }

    @SuppressWarnings("FutureReturnValueIgnored")
    String find(String id, List<Path> folders) {
        long now = System.currentTimeMillis();
        Long read = this.readAt.get(id);
        if (!folders.isEmpty() && (read == null || now - read > READ_EVERY_MILLIS)) {
            this.readAt.put(id, now);
            this.tasks.submit("reading the branch of " + id, () -> {
                this.branches.put(id, SourceGit.readBranch(DevMod.findProject(folders.getFirst())));
                return null;
            });
        }
        return this.branches.getOrDefault(id, "");
    }
}
