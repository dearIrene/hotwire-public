package dev.moon.hotwire.fabric.report;

import dev.moon.hotwire.fabric.HotwireClient;
import dev.moon.hotwire.fabric.InstalledMods;
import dev.moon.hotwire.fabric.LoadedMods;
import dev.moon.hotwire.jar.FileHashes;
import dev.moon.hotwire.jar.ModManifest;
import dev.moon.hotwire.plan.ModGraph;
import dev.moon.hotwire.report.ActionJournal;
import dev.moon.hotwire.report.EnvironmentFile;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class GameEnvironment {
    private static final Logger LOGGER = LoggerFactory.getLogger(GameEnvironment.class);
    private static final long MIB = 1024L * 1024L;
    private static final long HASH_DELAY_SECONDS = 60;

    private final InstalledMods installed;
    private final Path settings;

    private final Map<String, Path> atStart;
    private final ModGraph graphAtStart;
    private volatile String hashed = "not yet, as that is done a minute after the start";

    private final long listNanos;

    public GameEnvironment(InstalledMods installed, Path settings) {
        long began = System.nanoTime();
        this.installed = installed;
        this.settings = settings;
        this.atStart = Map.copyOf(installed.getJarsByTopLevel());
        this.graphAtStart = installed.getGraph();
        this.listNanos = System.nanoTime() - began;
    }

    public void hashSoon() {
        CompletableFuture.delayedExecutor(HASH_DELAY_SECONDS, TimeUnit.SECONDS).execute(
                () -> this.hashJars("on a thread of the shared pool"));
    }

    private void hashJars(String where) {
        long began = System.nanoTime();
        long bytes = 0;
        int count = 0;
        boolean first = this.hashed.startsWith("not yet");
        List<Path> all = new ArrayList<>(this.atStart.values());
        all.addAll(this.installed.getJarsByTopLevel().values());
        for (Path jar : all) {
            try {
                if (FileHashes.findHashedSha1(jar) == null) {
                    FileHashes.findSha1(jar);
                    bytes += Files.size(jar);
                    count++;
                }
            } catch (IOException e) {
                LOGGER.info("[Hotwire] {} could not be hashed for the reports: {}", jar.getFileName(), e.toString());
            }
        }

        if (first || count > 0) {
            this.hashed = String.format(Locale.ROOT, "%d jars, %d MB in %d ms, %s", count, bytes / MIB,
                    (System.nanoTime() - began) / 1_000_000, where);
            LOGGER.info("[Hotwire] the jars are hashed for the reports: {}", this.hashed);
        }
    }

    public String describe() {
        if (!Minecraft.getInstance().isSameThread()) {
            this.hashJars("as a report was written");
        }
        List<String> details = List.of("Minecraft: " + LoadedMods.findVersion("minecraft", "unknown"),
                "Hotwire: " + LoadedMods.findVersion(HotwireClient.ID, "unknown"),
                "Fabric Loader: " + LoadedMods.findVersion("fabricloader", "unknown"),
                "Java: " + System.getProperty("java.version") + ", " + System.getProperty("java.vendor"),
                "OS: " + System.getProperty("os.name") + " (" + System.getProperty("os.arch") + ")",
                "Journal: " + ActionJournal.get().describeCost(), "Jar hashes: " + this.hashed,
                String.format(Locale.ROOT, "Jar list: %d jars at the start, taken in %.3f ms on the game's thread",
                        this.atStart.size(), this.listNanos / 1e6));
        List<EnvironmentFile.Jar> start = new ArrayList<>();
        new TreeMap<>(this.atStart).forEach((id, jar) -> start.add(readJar(id, jar, this.graphAtStart.find(id)
                .map(ModManifest::version).orElseGet(() -> this.findVersion(id)),
                isDisabled(jar) ? "disabled" : "on")));
        List<EnvironmentFile.Jar> since = new ArrayList<>();
        new TreeMap<>(this.installed.getJarsByTopLevel()).forEach((id, jar) -> {
            if (!jar.equals(this.atStart.get(id)) && !this.installed.isDisabled(id)) {
                since.add(readJar(id, jar, this.findVersion(id), this.findChange(id)));
            }
        });
        return EnvironmentFile.write(details, start, since, this.readSettings());
    }

    private String findVersion(String id) {
        return this.installed.find(id).or(() -> this.installed.findDisabled(id)).map(ModManifest::version)
                .orElse("");
    }

    private String findChange(String id) {
        if (!this.atStart.containsKey(id)) {
            return "added";
        }
        Path before = this.atStart.get(id);
        return before != null && isDisabled(before) ? "enabled" : "updated";
    }

    private static boolean isDisabled(Path jar) {
        return String.valueOf(jar.getFileName()).endsWith(".disabled");
    }

    private static EnvironmentFile.Jar readJar(String id, Path jar, String version, String state) {
        long bytes;
        try {
            bytes = Files.size(jar);
        } catch (IOException e) {
            bytes = -1;
        }
        String sha1 = FileHashes.findHashedSha1(jar);
        return new EnvironmentFile.Jar(id, version, String.valueOf(jar.getFileName()), bytes, sha1 == null ? ""
                : sha1, state);
    }

    private String readSettings() {
        try {
            return Files.isRegularFile(this.settings) ? Files.readString(this.settings, StandardCharsets.UTF_8)
                    : "(none saved: every choice is the default)";
        } catch (IOException e) {
            return "(could not be read: " + e + ")";
        }
    }
}
