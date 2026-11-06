package dev.moon.hotwire.dev;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

public final class IdeOpener {
    private static final Set<String> SKIPPED = Set.of("build", "out", "bin", "run", ".gradle", ".git", ".idea",
            "node_modules");
    private static final int DEEPEST = 12;
    private static final int FIRST_PORT = 63_342;
    private static final int PORTS = 10;

    private IdeOpener() {
    }

    public static @Nullable Path findSource(List<Path> projects, String binary) throws IOException {
        int inner = binary.indexOf('$');
        String outer = (inner < 0 ? binary : binary.substring(0, inner)).replace('.', '/');
        List<String> names = List.of("/" + outer + ".java", "/" + outer + ".kt");
        for (Path project : projects) {
            Path[] found = new Path[1];
            Files.walkFileTree(project, EnumSet.noneOf(FileVisitOption.class), DEEPEST, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attributes) {
                    Path name = dir.getFileName();
                    return !dir.equals(project) && name != null && SKIPPED.contains(name.toString())
                            ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                    String path = file.toString().replace('\\', '/');
                    for (String name : names) {
                        if (path.endsWith(name)) {
                            found[0] = file;
                            return FileVisitResult.TERMINATE;
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
            if (found[0] != null) {
                return found[0];
            }
        }
        return null;
    }

    public static boolean open(Path file, int line) {
        String query = "file=" + URLEncoder.encode(file.toAbsolutePath().toString(), StandardCharsets.UTF_8)
                + "&line=" + line;
        try (HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(300)).build()) {
            for (int port = FIRST_PORT; port < FIRST_PORT + PORTS; port++) {
                HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/file?"
                        + query)).timeout(Duration.ofSeconds(2)).GET().build();
                try {
                    if (http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() == 200) {
                        return true;
                    }
                } catch (IOException e) {
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return false;
    }
}
