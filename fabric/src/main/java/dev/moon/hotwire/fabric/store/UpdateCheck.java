package dev.moon.hotwire.fabric.store;

import dev.moon.hotwire.fabric.InstalledMods;
import dev.moon.hotwire.fabric.update.ModUpdater;
import dev.moon.hotwire.jar.ModManifest;
import dev.moon.hotwire.modrinth.ModrinthClient;
import dev.moon.hotwire.update.UpdateOffer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.Version;
import net.fabricmc.loader.api.VersionParsingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class UpdateCheck {
    private static final Logger LOGGER = LoggerFactory.getLogger(UpdateCheck.class);

    private final InstalledMods installed;
    private final ModrinthClient client = ModStore.makeClient();
    private final Path downloads = FabricLoader.getInstance().getGameDir().resolve("hotwire").resolve("store");

    private volatile Set<String> installedProjects = Set.of();
    private volatile Map<String, String> idByProject = Map.of();

    public UpdateCheck(InstalledMods installed) {
        this.installed = installed;
    }

    @SuppressWarnings("ThreadPriorityCheck")
    public void start(Consumer<List<UpdateOffer>> then) {
        Thread.ofPlatform().name("hotwire-updates").daemon().priority(Thread.MIN_PRIORITY).start(() -> {
            List<UpdateOffer> offers = new ArrayList<>();
            try {
                offers.addAll(this.find());
            } catch (IOException | RuntimeException e) {
                LOGGER.info("[Hotwire] the update check could not ask Modrinth: {}", e.toString());
            }
            then.accept(offers);
        });
    }

    public List<UpdateOffer> findNow() throws IOException {
        return this.find();
    }

    private List<UpdateOffer> find() throws IOException {
        Map<String, Path> jars = this.installed.getJarsByTopLevel();
        Map<String, String> hashByName = JarHashes.of(jars.values());
        if (hashByName.isEmpty()) {
            return List.of();
        }
        Map<String, String> idByName = new HashMap<>();
        jars.forEach((id, jar) -> idByName.put(String.valueOf(jar.getFileName()), id));
        Map<String, ModrinthClient.Version> newest = this.client.findNewest(hashByName);
        Set<String> known = new HashSet<>();
        Map<String, String> ids = new HashMap<>();
        newest.forEach((name, version) -> {
            known.add(version.projectId());
            ids.put(version.projectId(), idByName.getOrDefault(name, name));
        });
        this.installedProjects = known;
        this.idByProject = ids;
        List<UpdateOffer> offers = new ArrayList<>();
        newest.forEach((name, version) -> {
            if (version.sha512().equalsIgnoreCase(hashByName.get(name)) || version.url().isEmpty()) {
                return;
            }
            String id = idByName.getOrDefault(name, name);
            if (id.equals(ModUpdater.ESSENTIAL_CONTAINER)) {
                id = ModUpdater.ESSENTIAL;
                String running = this.installed.find(id).map(ModManifest::version).orElse("");
                if (running.isEmpty() || !isNewer(version.number(), running)) {
                    return;
                }
            }
            ModManifest manifest = this.installed.find(id).orElse(null);
            offers.add(new UpdateOffer(id, manifest == null ? id : manifest.name(),
                    manifest == null ? "" : manifest.version(), version.number(), version.changelog(),
                    version.projectId(), version.id(), version.fileName(), version.url(), version.sha512(),
                    version.size(), version.requiredProjects()));
        });
        offers.sort((a, b) -> a.name().compareToIgnoreCase(b.name()));
        LOGGER.info("[Hotwire] Modrinth knows {} of {} mods; {} have a newer version", newest.size(),
                hashByName.size(), offers.size());
        return offers;
    }

    public List<UpdateOffer> orderByNeeds(List<UpdateOffer> picked, List<UpdateOffer> all) {
        Map<String, UpdateOffer> byId = new LinkedHashMap<>();
        all.forEach(offer -> byId.put(offer.id(), offer));
        List<UpdateOffer> out = new ArrayList<>();
        Set<String> placed = new HashSet<>();
        for (UpdateOffer offer : picked) {
            this.place(offer, byId, placed, out, new HashSet<>());
        }
        return out;
    }

    private void place(UpdateOffer offer, Map<String, UpdateOffer> byId, Set<String> placed, List<UpdateOffer> out,
            Set<String> walking) {
        if (placed.contains(offer.id()) || !walking.add(offer.id())) {
            return;
        }
        for (String project : offer.requiredProjects()) {
            String neededId = this.idByProject.get(project);
            UpdateOffer needed = neededId == null ? null : byId.get(neededId);
            if (needed != null) {
                this.place(needed, byId, placed, out, walking);
            }
        }
        placed.add(offer.id());
        out.add(offer);
    }

    public List<Path> fetchById(String id, List<UpdateOffer> offers) throws IOException {
        boolean ofTheGame = id.equals("fabricloader") || id.equals("minecraft") || id.equals("java");
        boolean partOfFabricApi = id.startsWith("fabric-") && !id.equals("fabric-api");
        if (ofTheGame || partOfFabricApi) {
            return List.of();
        }
        for (UpdateOffer offer : offers) {
            if (offer.id().equals(id)) {
                return this.fetchWithNeeds(offer);
            }
        }
        List<ModrinthClient.Version> versions;
        try {
            versions = this.client.findVersions(id);
        } catch (IOException e) {
            LOGGER.info("[Hotwire] Modrinth has no project {}: {}", id, e.getMessage());
            return List.of();
        }
        if (versions.isEmpty()) {
            return List.of();
        }
        List<Path> jars = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String needed : versions.getFirst().requiredProjects()) {
            this.fetchNeeded(needed, seen, jars);
        }
        jars.add(this.write(versions.getFirst()));
        return jars;
    }

    private static boolean isNewer(String candidate, String running) {
        try {
            return Version.parse(candidate).compareTo(Version.parse(running)) > 0;
        } catch (VersionParsingException e) {
            return !candidate.equals(running);
        }
    }

    public List<Path> fetchWithNeeds(UpdateOffer offer) throws IOException {
        List<Path> jars = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String needed : offer.requiredProjects()) {
            this.fetchNeeded(needed, seen, jars);
        }
        jars.add(this.fetch(offer));
        return jars;
    }

    private void fetchNeeded(String projectId, Set<String> seen, List<Path> into) throws IOException {
        if (!seen.add(projectId) || this.installedProjects.contains(projectId)
                || this.installed.isInstalled(projectId) || FabricLoader.getInstance().isModLoaded(projectId)) {
            return;
        }
        List<ModrinthClient.Version> versions = this.client.findVersions(projectId);
        if (versions.isEmpty()) {
            throw new IOException("a mod it needs has no version for this game: " + projectId);
        }
        ModrinthClient.Version newest = versions.getFirst();
        for (String deeper : newest.requiredProjects()) {
            this.fetchNeeded(deeper, seen, into);
        }
        into.add(this.write(newest));
    }

    public Path fetch(UpdateOffer offer) throws IOException {
        ModrinthClient.Version version = new ModrinthClient.Version(offer.versionId(), offer.projectId(),
                offer.newVersion(), "", offer.fileName(), offer.url(), offer.sha512(), offer.size(), List.of(),
                List.of(), offer.changelog());
        return this.write(version);
    }

    private Path write(ModrinthClient.Version version) throws IOException {
        byte[] bytes = this.client.fetch(version);
        Files.createDirectories(this.downloads);
        Path jar = this.downloads.resolve(version.fileName().replaceAll("[^A-Za-z0-9._+ -]", "_"));
        Files.write(jar, bytes);
        return jar;
    }
}
