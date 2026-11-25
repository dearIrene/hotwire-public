package dev.moon.hotwire.store;

import dev.moon.hotwire.config.AtomicFiles;
import dev.moon.hotwire.engine.ModsFolder;
import dev.moon.hotwire.modrinth.ModrinthClient;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.fabricmc.loader.api.FabricLoader;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class PrismIndex {
    private static final Logger LOGGER = LoggerFactory.getLogger(PrismIndex.class);
    private static final Pattern TABLE = Pattern.compile("^\\s*\\[\\s*([^\\]]+?)\\s*]\\s*$");
    private static final Pattern ENTRY = Pattern.compile("^(\\s*([A-Za-z0-9_-]+)\\s*=\\s*)(.*?)\\s*$");
    private static final String MODRINTH = "update.modrinth";

    private PrismIndex() {
    }

    public static void follow(ModrinthClient.Version version) {
        try {
            Path index = ModsFolder.find(FabricLoader.getInstance()).resolve(".index");
            if (Files.isDirectory(index)) {
                follow(index, version);
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.info("[Hotwire] Prism's note of {} stays as it is: {}", version.fileName(), e.toString());
        }
    }

    static void follow(Path index, ModrinthClient.Version version) throws IOException {
        try (DirectoryStream<Path> notes = Files.newDirectoryStream(index, "*.pw.toml")) {
            for (Path note : notes) {
                String text = Files.readString(note, StandardCharsets.UTF_8);
                String now = rewrite(text, version);
                if (now != null && !now.equals(text)) {
                    AtomicFiles.writeString(note, now);
                    LOGGER.info("[Hotwire] Prism's note {} names {} now", note.getFileName(), version.fileName());
                }
            }
        }
    }

    static @Nullable String rewrite(String text, ModrinthClient.Version version) {
        Map<String, String> wanted = Map.of(
                "/filename", version.fileName(),
                "/x-prismlauncher-version-number", version.number(),
                "/x-prismlauncher-release-type", version.type(),
                "download/url", version.url(),
                "download/hash", version.sha512(),
                MODRINTH + "/version", version.id());
        List<String> out = new ArrayList<>();
        List<String> written = new ArrayList<>();
        String table = "";
        String project = null;
        String format = null;
        for (String line : text.split("\n", -1)) {
            String end = line.endsWith("\r") ? "\r" : "";
            String plain = line.substring(0, line.length() - end.length());
            Matcher header = TABLE.matcher(plain);
            Matcher entry = ENTRY.matcher(plain);
            if (header.matches()) {
                table = header.group(1);
            } else if (entry.matches()) {
                String key = table + "/" + entry.group(2);
                String value = read(entry.group(3));
                if (key.equals(MODRINTH + "/mod-id")) {
                    project = value;
                } else if (key.equals("download/hash-format")) {
                    format = value;
                }
                String next = wanted.get(key);
                if (next != null) {
                    if (value == null) {
                        return null;
                    }
                    written.add(key);
                    out.add(entry.group(1) + quote(next) + end);
                    continue;
                }
            }
            out.add(line);
        }

        boolean whole = written.containsAll(
                List.of("/filename", "download/url", "download/hash", MODRINTH + "/version"));
        return version.projectId().equals(project) && "sha512".equals(format) && whole ? String.join("\n", out) : null;
    }

    private static @Nullable String read(String value) {
        if (value.length() < 2) {
            return null;
        }
        String inner = value.substring(1, value.length() - 1);
        if (value.startsWith("'") && value.endsWith("'")) {
            return inner.contains("'") ? null : inner;
        }
        if (value.startsWith("\"") && value.endsWith("\"")) {
            return inner.contains("\\") || inner.contains("\"") ? null : inner;
        }
        return null;
    }

    private static String quote(String value) {
        if (!value.contains("'") && value.chars().noneMatch(Character::isISOControl)) {
            return "'" + value + "'";
        }
        StringBuilder out = new StringBuilder("\"");
        for (int index = 0; index < value.length(); index++) {
            char at = value.charAt(index);
            if (at == '"' || at == '\\') {
                out.append('\\').append(at);
            } else if (Character.isISOControl(at)) {
                out.append(String.format("\\u%04X", (int) at));
            } else {
                out.append(at);
            }
        }
        return out.append('"').toString();
    }
}
