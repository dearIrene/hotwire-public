package dev.moon.hotwire.fabric.dev;

import dev.moon.hotwire.concurrent.Tasks;
import dev.moon.hotwire.config.AtomicFiles;
import dev.moon.hotwire.ui.CacheFill;
import dev.moon.hotwire.ui.Lang;
import dev.moon.hotwire.ui.Notice;
import dev.moon.hotwire.ui.Notices;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class ClearedBuilds {
    private static final Logger LOGGER = LoggerFactory.getLogger(ClearedBuilds.class);

    private final Path folder;
    private final Tasks tasks;

    private final Consumer<SessionMod> saved;

    ClearedBuilds(Path folder, Tasks tasks, Consumer<SessionMod> saved) {
        this.folder = folder;
        this.tasks = tasks;
        this.saved = saved;
    }

    @SuppressWarnings("FutureReturnValueIgnored")
    void clear(SessionMod mod, List<Integer> builds) {
        String id = mod.mod.id();
        this.tasks.submit("clearing builds of " + id, () -> {
            synchronized (mod.taking) {
                Path bin = this.folder.resolve(id).resolve("cleared");
                int done = 0;
                long freed = 0;
                for (int build : builds) {
                    SavedBuild saved;
                    synchronized (mod) {
                        saved = mod.saved.stream().filter(one -> one.number() == build).findFirst().orElse(null);
                    }
                    if (saved == null || mod.isInUse(build) || saved.jar().equals(mod.startJar)) {
                        continue;
                    }
                    Path to = bin.resolve(saved.jar().getFileName());
                    try {
                        moveBuild(saved.jar(), to);
                        if (mod.buildSources != null) {
                            mod.buildSources.hide(build);
                        }
                        synchronized (mod) {
                            freed += mod.sizes.getOrDefault(build, 0L);
                            mod.saved.remove(saved);
                            mod.sizes.remove(build);
                            mod.cleared.addFirst(new SessionMod.ClearedBuild(new SavedBuild(build, to),
                                    mod.infos.remove(build), mod.deltas.remove(build), mod.names.remove(build),
                                    mod.pinned.remove(build)));
                        }
                        done++;
                    } catch (IOException e) {
                        LOGGER.warn("[Hotwire] build {} of {} could not be cleared", build, id, e);
                        try {
                            moveBuild(to, saved.jar());
                        } catch (IOException back) {
                            LOGGER.warn("[Hotwire] build {} of {} is left half cleared", build, id, back);
                        }
                    }
                }
                this.saved.accept(mod);
                if (done > 0) {
                    Notices.show(Notice.ofOn(Lang.get("hotwire.dev.cleared", done, CacheFill.describeBytes(freed)),
                            Lang.get("hotwire.dev.cleared.back")));
                }
            }
            return null;
        });
    }

    @SuppressWarnings("FutureReturnValueIgnored")
    void restore(SessionMod mod) {
        String id = mod.mod.id();
        this.tasks.submit("bringing back builds of " + id, () -> {
            synchronized (mod.taking) {
                List<SessionMod.ClearedBuild> back;
                synchronized (mod) {
                    back = List.copyOf(mod.cleared);
                    mod.cleared.clear();
                }
                for (SessionMod.ClearedBuild one : back) {
                    int build = one.build().number();
                    Path home = this.folder.resolve(id).resolve(one.build().jar().getFileName());
                    try {
                        moveBuild(one.build().jar(), home);
                        if (mod.buildSources != null) {
                            mod.buildSources.show(build);
                        }
                    } catch (IOException e) {
                        LOGGER.warn("[Hotwire] build {} of {} could not be brought back", build, id, e);
                        synchronized (mod) {
                            mod.cleared.add(one);
                        }
                        continue;
                    }
                    synchronized (mod) {
                        List<SavedBuild> all = new ArrayList<>(mod.saved);
                        all.add(new SavedBuild(build, home));
                        all.sort(Comparator.comparingInt(SavedBuild::number).reversed());
                        mod.saved.clear();
                        mod.saved.addAll(all);
                        putIfThere(mod.infos, build, one.info());
                        putIfThere(mod.deltas, build, one.delta());
                        putIfThere(mod.names, build, one.name());
                        if (one.pinned()) {
                            mod.pinned.add(build);
                        }
                    }
                }
                this.saved.accept(mod);
            }
            return null;
        });
    }

    private static <V> void putIfThere(Map<Integer, V> map, int key, @Nullable V value) {
        if (value != null) {
            map.put(key, value);
        }
    }

    private static void moveBuild(Path jar, Path to) throws IOException {
        Files.createDirectories(to.getParent());
        if (Files.isRegularFile(jar)) {
            AtomicFiles.moveInto(jar, to);
        }
        Path index = jar.resolveSibling(jar.getFileName() + ".idx");
        if (Files.isRegularFile(index)) {
            AtomicFiles.moveInto(index, to.resolveSibling(to.getFileName() + ".idx"));
        }
    }

    void delete(SessionMod mod) {
        Path bin = this.folder.resolve(mod.mod.id()).resolve("cleared");
        try {
            if (Files.isDirectory(bin)) {
                try (DirectoryStream<Path> files = Files.newDirectoryStream(bin)) {
                    for (Path file : files) {
                        Files.deleteIfExists(file);
                    }
                }
            }
            if (mod.buildSources != null) {
                mod.buildSources.deleteCleared();
            }
        } catch (IOException e) {
            LOGGER.warn("[Hotwire] the cleared builds of {} could not be removed", mod.mod.id(), e);
        }
    }
}
