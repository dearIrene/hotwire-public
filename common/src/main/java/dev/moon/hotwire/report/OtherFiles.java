package dev.moon.hotwire.report;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

public final class OtherFiles {
    public static final String FILE = "OTHER-FILES.txt";
    private static final int MOST = 12;
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    private record FileNote(Instant written, String name, long bytes) {
    }

    private OtherFiles() {
    }

    public static String describe(Path game, Path reports, Instant began) {
        StringBuilder out = new StringBuilder("Other reports and crash files of this game, newest first\n")
                .append("This session began ").append(WHEN.format(began)).append(". A file from before it is of an")
                .append(" earlier session: if the problem happened then, that file is the one to send.\n");
        describeFolder(out, "Hotwire's reports (hotwire/reports)", reports, "", began);
        describeFolder(out, "The game's crash reports (crash-reports)", game.resolve("crash-reports"), "", began);
        describeFolder(out, "Java's own crash files (hs_err in the game folder)", game, "hs_err", began);
        return out.toString();
    }

    private static void describeFolder(StringBuilder out, String title, Path folder, String prefix, Instant began) {
        List<FileNote> found = new ArrayList<>();
        if (Files.isDirectory(folder)) {
            try (Stream<Path> files = Files.list(folder)) {
                for (Path file : files.toList()) {
                    String name = String.valueOf(file.getFileName());
                    if (Files.isRegularFile(file) && name.startsWith(prefix)) {
                        found.add(new FileNote(Files.getLastModifiedTime(file).toInstant(), name, Files.size(file)));
                    }
                }
            } catch (IOException e) {
                out.append('\n').append(title).append(": could not be listed (").append(e).append(")\n");
                return;
            }
        }
        found.sort(Comparator.comparing(FileNote::written).reversed());
        out.append('\n').append(title).append(": ").append(found.isEmpty() ? "none" : String.valueOf(found.size()))
                .append('\n');
        for (FileNote file : found.subList(0, Math.min(MOST, found.size()))) {
            out.append("  ").append(WHEN.format(file.written())).append("  ")
                    .append(file.written().isBefore(began) ? "earlier session" : "this session   ").append("  ")
                    .append(file.name()).append("  (").append(Math.max(1, file.bytes() / 1024)).append(" KB)\n");
        }
        if (found.size() > MOST) {
            out.append("  and ").append(found.size() - MOST).append(" older\n");
        }
    }
}
