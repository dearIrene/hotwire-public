package dev.moon.hotwire.fabric.store;

import dev.moon.hotwire.fabric.CacheLimit;
import dev.moon.hotwire.fabric.InstalledMods;
import dev.moon.hotwire.fabric.update.ModUpdater;
import dev.moon.hotwire.jar.FileHashes;
import dev.moon.hotwire.jar.ModManifest;
import dev.moon.hotwire.modrinth.Downloads;
import dev.moon.hotwire.modrinth.ModrinthClient;
import dev.moon.hotwire.modrinth.UpdateMemory;
import dev.moon.hotwire.ui.Lang;
import dev.moon.hotwire.update.UpdateOffer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
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
    private final Path folder = FabricLoader.getInstance().getGameDir().resolve("hotwire").resolve("store");

    private volatile Set<String> installedProjects = Set.of();
    private volatile Map<String, String> idByProject = Map.of();

    private volatile Downloads downloads = new Downloads();

    private final Path memoryFile = CacheLimit.findStoreFolder().resolve("updates-"
            + this.client.getScope().replaceAll("[^A-Za-z0-9.-]", "_") + ".gz");
    private volatile int newlyFound;

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
        Map<String, String> hashByName = FileHashes.findSha512ByName(jars.values());
        if (hashByName.isEmpty()) {
            return List.of();
        }
        Map<String, String> idByName = new HashMap<>();
        jars.forEach((id, jar) -> idByName.put(String.valueOf(jar.getFileName()), id));
        UpdateMemory memory = UpdateMemory.read(this.memoryFile);
        memory.retain(hashByName.values());
        Map<String, ModrinthClient.Version> newest = this.recall(memory, hashByName);
        Set<String> known = new HashSet<>();
        Map<String, String> ids = new HashMap<>();
        newest.forEach((name, version) -> {
            known.add(version.projectId());
            ids.put(version.projectId(), idByName.getOrDefault(name, name));
        });
        hashByName.forEach((name, hash) -> {
            UpdateMemory.Entry entry = memory.find(hash);
            if (entry != null && !entry.isUnknown()) {
                known.add(entry.projectId());
                ids.putIfAbsent(entry.projectId(), idByName.getOrDefault(name, name));
            }
        });
        this.installedProjects = known;
        this.idByProject = ids;
        List<UpdateOffer> found = this.makeOffers(newest, hashByName, idByName);

        memory.retainTold(found.stream().map(UpdateOffer::versionId).toList());
        int fresh = 0;
        for (UpdateOffer offer : found) {
            fresh += memory.tell(offer.versionId()) ? 1 : 0;
        }
        this.newlyFound = fresh;
        memory.write();
        return found;
    }

    private Map<String, ModrinthClient.Version> recall(UpdateMemory memory, Map<String, String> hashByName)
            throws IOException {
        Set<String> projects = memory.findProjects();
        Map<String, String> stamps = projects.isEmpty() ? Map.of() : this.client.findChanges(projects);
        Map<String, String> ask = memory.findToAsk(hashByName, stamps, Instant.now());
        if (!ask.isEmpty()) {
            Map<String, ModrinthClient.Version> answered = this.client.findNewest(ask);
            Set<String> unstamped = new HashSet<>();
            answered.values().forEach(version -> {
                if (!stamps.containsKey(version.projectId())) {
                    unstamped.add(version.projectId());
                }
            });
            Map<String, String> more = unstamped.isEmpty() ? Map.of() : this.client.findChanges(unstamped);
            long now = System.currentTimeMillis();
            ask.forEach((name, hash) -> {
                ModrinthClient.Version version = answered.get(name);
                if (version == null) {
                    memory.put(hash, new UpdateMemory.Entry("", "", null, now));
                    return;
                }
                String project = version.projectId();
                boolean same = version.sha512().equalsIgnoreCase(hash) || version.url().isEmpty();
                memory.put(hash, new UpdateMemory.Entry(project, stamps.getOrDefault(project,
                        more.getOrDefault(project, "")), same ? null : UpdateMemory.Newer.of(version), now));
            });
            LOGGER.info("[Hotwire] the update check asked Modrinth about {} of {} jars; the rest were as last time",
                    ask.size(), hashByName.size());
        }
        Map<String, ModrinthClient.Version> newest = new LinkedHashMap<>();
        hashByName.forEach((name, hash) -> {
            UpdateMemory.Entry entry = memory.find(hash);
            UpdateMemory.Newer newer = entry == null ? null : entry.newer();
            if (entry != null && newer != null) {
                newest.put(name, newer.toVersion(entry.projectId()));
            }
        });
        return newest;
    }

    public int getNewlyFound() {
        return this.newlyFound;
    }

    private List<UpdateOffer> makeOffers(Map<String, ModrinthClient.Version> newest, Map<String, String> hashByName,
            Map<String, String> idByName) {
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
        LOGGER.info("[Hotwire] {} of {} mods have a newer version", offers.size(), hashByName.size());
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

    public List<Path> fetchById(String id, List<UpdateOffer> offers, Consumer<String> saying) throws IOException {
        boolean ofTheGame = id.equals("fabricloader") || id.equals("minecraft") || id.equals("java");
        boolean partOfFabricApi = id.startsWith("fabric-") && !id.equals("fabric-api");
        if (ofTheGame || partOfFabricApi) {
            return List.of();
        }
        for (UpdateOffer offer : offers) {
            if (offer.id().equals(id)) {
                return this.fetchWithNeeds(offer, saying);
            }
        }
        List<ModrinthClient.Version> versions;
        try {
            versions = this.client.findVersions(ModrinthClient.Kind.MOD, id);
        } catch (IOException e) {
            LOGGER.info("[Hotwire] Modrinth has no project {}: {}", id, e.getMessage());
            return List.of();
        }
        if (versions.isEmpty()) {
            return List.of();
        }
        List<Path> jars = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        ModrinthClient.Version newest = versions.getFirst();
        for (String needed : newest.requiredProjects()) {
            this.fetchNeeded(needed, newest.name().isBlank() ? id : newest.name(), seen, jars, saying);
        }
        saying.accept(Lang.get("hotwire.updates.getting_for_update", newest.name().isBlank() ? id : newest.name()));
        jars.add(this.write(newest));
        return jars;
    }

    private static boolean isNewer(String candidate, String running) {
        try {
            return Version.parse(candidate).compareTo(Version.parse(running)) > 0;
        } catch (VersionParsingException e) {
            return !candidate.equals(running);
        }
    }

    public List<Path> fetchWithNeeds(UpdateOffer offer, Consumer<String> saying) throws IOException {
        List<Path> jars = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String needed : offer.requiredProjects()) {
            this.fetchNeeded(needed, offer.name(), seen, jars, saying);
        }
        saying.accept(Lang.get("hotwire.updates.getting", offer.name(), offer.newVersion()));
        jars.add(this.fetch(offer));
        return jars;
    }

    private void fetchNeeded(String projectId, String neededBy, Set<String> seen, List<Path> into,
            Consumer<String> saying) throws IOException {
        if (!seen.add(projectId) || this.isInstalled(projectId)) {
            return;
        }
        ModrinthClient.Project project = this.client.findProject(projectId);
        if (this.isInstalled(project)) {
            return;
        }
        List<ModrinthClient.Version> versions = this.client.findVersions(ModrinthClient.Kind.MOD, projectId);
        if (versions.isEmpty()) {
            throw new IOException("a mod it needs has no version for this game: " + projectId);
        }
        ModrinthClient.Version newest = versions.getFirst();
        for (String deeper : newest.requiredProjects()) {
            this.fetchNeeded(deeper, project.title(), seen, into, saying);
        }
        saying.accept(Lang.get("hotwire.updates.getting_needed", project.title(), neededBy));
        into.add(this.write(newest));
    }

    private boolean isInstalled(String projectId) {
        return this.installedProjects.contains(projectId) || this.installed.isInstalled(projectId)
                || FabricLoader.getInstance().isModLoaded(projectId);
    }

    private boolean isInstalled(ModrinthClient.Project project) {
        if (this.installed.isInstalled(project.slug()) || FabricLoader.getInstance().isModLoaded(project.slug())) {
            return true;
        }
        return FabricLoader.getInstance().getAllMods().stream()
                .anyMatch(mod -> mod.getMetadata().getName().equalsIgnoreCase(project.title()));
    }

    public Path fetch(UpdateOffer offer) throws IOException {
        ModrinthClient.Version version = new ModrinthClient.Version(offer.versionId(), offer.projectId(),
                offer.newVersion(), "", offer.fileName(), offer.url(), offer.sha512(), offer.size(), List.of(),
                List.of(), offer.changelog());
        try {
            return this.write(version);
        } catch (IOException e) {
            LOGGER.info("[Hotwire] {} was not at {}; asking Modrinth where it is", offer.fileName(), offer.url());
            return this.write(this.client.findVersion(offer.versionId()));
        }
    }

    public void reportTo(Downloads downloads) {
        this.downloads = downloads;
    }

    private Path write(ModrinthClient.Version version) throws IOException {
        Downloads told = this.downloads;
        int job = told.begin(version.fileName(), version.size());
        told.start(job);
        try {
            byte[] bytes = this.client.fetch(version, done -> told.advance(job, done));
            Files.createDirectories(this.folder);
            Path jar = this.folder.resolve(version.fileName().replaceAll("[^A-Za-z0-9._+ -]", "_"));
            Files.write(jar, bytes);
            told.end(job, true);
            return jar;
        } catch (IOException | RuntimeException e) {
            told.end(job, false);
            throw e;
        }
    }
}
