package dev.moon.hotwire.fabric;

import dev.moon.hotwire.fabric.add.AddFirstLook;
import dev.moon.hotwire.fabric.add.ModAdder;
import dev.moon.hotwire.jar.FileHashes;
import dev.moon.hotwire.jar.ModJarException;
import dev.moon.hotwire.jar.ModJarReader;
import dev.moon.hotwire.jar.ModManifest;
import dev.moon.hotwire.ui.AddPreview;
import dev.moon.hotwire.ui.Lang;
import dev.moon.hotwire.ui.UpdateMethod;
import dev.moon.hotwire.ui.UpdatePreview;
import dev.moon.hotwire.update.UpdateFolders;
import dev.moon.hotwire.update.UpdateQueue;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

final class NextStartQueue {
    private final InstalledMods installed;
    private final UpdateQueue queue;
    private final UpdateFolders folders;

    NextStartQueue(InstalledMods installed, UpdateQueue queue, UpdateFolders folders) {
        this.installed = installed;
        this.queue = queue;
        this.folders = folders;
    }

    ModAdder.Result queue(Path jar) {
        Optional<String> plain = AddFirstLook.findRefusal(jar);
        if (plain.isPresent()) {
            return new ModAdder.Result.Refused(plain.get());
        }
        try {
            ModManifest manifest = ModJarReader.read(jar).manifest();

            Path folder = this.installed.findFolderJarOf(manifest.id());
            Path running = ModPolicy.isHotwire(manifest.id()) ? null
                    : folder != null ? folder : this.installed.findJarOf(manifest.id());
            String from = running == null ? "" : this.installed.find(manifest.id()).map(ModManifest::version)
                    .orElse("");
            if (running != null && from.equals(manifest.version())) {
                return new ModAdder.Result.Refused(Lang.get("hotwire.update.same", manifest.name()));
            }
            Files.createDirectories(this.folders.staging());
            Path staged = this.folders.staging().resolve(String.valueOf(jar.getFileName()));
            if (!staged.toAbsolutePath().normalize().equals(jar.toAbsolutePath().normalize())) {
                Files.copy(jar, staged, StandardCopyOption.REPLACE_EXISTING);
            }
            this.queue.add(new UpdateQueue.Entry(manifest.id(), manifest.name(), from, manifest.version(),
                    running == null ? "" : String.valueOf(running.getFileName()),
                    running == null ? "" : FileHashes.findSha256(running), staged.toAbsolutePath().toString(),
                    FileHashes.findSha256(staged), "", System.currentTimeMillis()));
            return new ModAdder.Result.Queued(manifest.id(), Lang.get(running == null
                    ? "hotwire.next_start.add" : "hotwire.next_start.update", manifest.name(), manifest.version()));
        } catch (ModJarException | IOException e) {
            return new ModAdder.Result.Failed(jar.getFileName() + " could not be queued: " + e.getMessage(), e);
        }
    }

    AddPreview previewAdd(Path jar) {
        Optional<String> plain = AddFirstLook.findRefusal(jar);
        ModManifest manifest = this.read(jar);
        if (plain.isPresent() || manifest == null) {
            return AddPreview.ofRefusal(String.valueOf(jar.getFileName()), plain.orElse(Lang.get(
                    "hotwire.update.unreadable_jar")));
        }
        return AddPreview.ofLater(manifest.name(), manifest.version(), List.of(Lang.get("hotwire.next_start.why")));
    }

    UpdatePreview previewUpdate(Path jar) {
        ModManifest manifest = this.read(jar);
        if (manifest == null) {
            return new UpdatePreview.Unreadable(String.valueOf(jar.getFileName()));
        }
        Optional<ModManifest> running = this.installed.find(manifest.id());
        if (running.isEmpty() || ModPolicy.isHotwire(manifest.id())) {
            return new UpdatePreview.Refused(manifest.name(), Lang.get("hotwire.update.not_installed",
                    manifest.id()));
        }
        if (running.get().version().equals(manifest.version())) {
            return new UpdatePreview.SameVersion(manifest.name(), manifest.version());
        }
        return UpdatePreview.Ready.of(manifest.name(), null, running.get().version(), manifest.version(),
                String.valueOf(jar.getFileName()), UpdateMethod.NEXT_START,
                List.of(Lang.get("hotwire.next_start.why")));
    }

    private @Nullable ModManifest read(Path jar) {
        try {
            return ModJarReader.read(jar).manifest();
        } catch (ModJarException e) {
            return null;
        }
    }
}
