package dev.moon.hotwire.fabric.dev;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

final class SourceGit {
    private static final long WAIT_SECONDS = 3;

    record State(String at, boolean uncommitted) {
    }

    private SourceGit() {
    }

    static @Nullable State read(@Nullable Path project) {
        String out = run(project, "status", "--porcelain=v2", "--branch", "--untracked-files=no");
        return out == null ? null : parse(out);
    }

    static String readBranch(@Nullable Path project) {
        for (Path at = project; at != null; at = at.getParent()) {
            Path git = at.resolve(".git");
            if (!Files.exists(git)) {
                continue;
            }
            try {
                if (Files.isRegularFile(git)) {
                    String line = Files.readString(git).strip();
                    if (!line.startsWith("gitdir: ")) {
                        return "";
                    }
                    git = at.resolve(line.substring("gitdir: ".length()));
                }
                String head = Files.readString(git.resolve("HEAD")).strip();
                return head.startsWith("ref: refs/heads/") ? head.substring("ref: refs/heads/".length())
                        : head.substring(0, Math.min(7, head.length()));
            } catch (IOException e) {
                return "";
            }
        }
        return "";
    }

    static @Nullable Set<String> readChanged(@Nullable Path project) {
        String out = run(project, "diff", "--name-only", "--relative", "HEAD");
        return out == null ? null : out.lines().map(String::strip).filter(line -> !line.isEmpty())
                .collect(Collectors.toSet());
    }

    static @Nullable List<String> readCommitted(@Nullable Path project, String relative) {
        String out = run(project, "show", "HEAD:./" + relative);
        return out == null ? null : out.lines().toList();
    }

    private static @Nullable String run(@Nullable Path project, String... args) {
        if (project == null || !isInGit(project)) {
            return null;
        }
        List<String> command = new ArrayList<>(List.of("git", "-C", project.toString()));
        command.addAll(List.of(args));
        try {
            Path out = Files.createTempFile("hotwire-git", ".txt");
            try {
                Process git = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD)
                        .redirectOutput(out.toFile()).start();
                git.getOutputStream().close();
                if (!git.waitFor(WAIT_SECONDS, TimeUnit.SECONDS)) {
                    git.destroyForcibly();
                    return null;
                }
                return git.exitValue() != 0 ? null : new String(Files.readAllBytes(out), StandardCharsets.UTF_8);
            } finally {
                Files.deleteIfExists(out);
            }
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private static boolean isInGit(Path project) {
        for (Path at = project; at != null; at = at.getParent()) {
            if (Files.exists(at.resolve(".git"))) {
                return true;
            }
        }
        return false;
    }

    static @Nullable State parse(String out) {
        String commit = "";
        String branch = "";
        boolean changed = false;
        for (String line : out.lines().toList()) {
            if (line.startsWith("# branch.oid ")) {
                String oid = line.substring("# branch.oid ".length()).strip();
                commit = oid.length() > 7 ? oid.substring(0, 7) : oid;
            } else if (line.startsWith("# branch.head ")) {
                branch = line.substring("# branch.head ".length()).strip();
            } else if (!line.isBlank() && !line.startsWith("#")) {
                changed = true;
            }
        }
        if (commit.isEmpty() || commit.equals("(initial")) {
            return null;
        }
        return new State(branch.equals("(detached)") ? commit : branch + " " + commit, changed);
    }
}
