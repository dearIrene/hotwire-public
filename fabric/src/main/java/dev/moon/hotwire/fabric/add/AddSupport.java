package dev.moon.hotwire.fabric.add;

import dev.moon.hotwire.fabric.InstalledMods;
import dev.moon.hotwire.fabric.ModPolicy;
import dev.moon.hotwire.fabric.rebuild.GameClasses;
import dev.moon.hotwire.fabric.rebuild.MixinConfigs;
import dev.moon.hotwire.fabric.report.GameReports;
import dev.moon.hotwire.fabric.screen.ModIcons;
import dev.moon.hotwire.jar.ModJar;
import dev.moon.hotwire.jar.ModJarException;
import dev.moon.hotwire.jar.ModJarReader;
import dev.moon.hotwire.jar.ModManifest;
import dev.moon.hotwire.mixin_copy.MixinCopy;
import dev.moon.hotwire.plan.ModGraph;
import dev.moon.hotwire.plan.SwitchPlanner;
import dev.moon.hotwire.report.Report;
import dev.moon.hotwire.switching.ModSwitcher;
import dev.moon.hotwire.switching.SwitchApplier;
import dev.moon.hotwire.ui.AddMode;
import dev.moon.hotwire.ui.AddPreview;
import dev.moon.hotwire.update.UpdateFolders;
import dev.moon.hotwire.update.UpdateQueue;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.Function;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class AddSupport implements ModAdder.Engine {
    private static final Logger LOGGER = LoggerFactory.getLogger(AddSupport.class);
    private final InstalledMods installed;
    private final SwitchApplier applier;
    private final ModSwitcher switcher;
    private final GameClasses classes;
    private final GameReports reports;
    private final Path addedJarsFolder;
    private final UpdateFolders folders;
    private final UpdateQueue queue;
    private final ModAdder adder;
    private final ModIcons icons;
    private final Function<List<String>, ModSwitcher.Result> takeOut;

    public AddSupport(InstalledMods installed, SwitchApplier applier, ModSwitcher switcher, GameClasses classes,
            GameReports reports, Path gameDir, UpdateFolders folders, UpdateQueue queue, ModIcons icons,
            Function<List<String>, ModSwitcher.Result> takeOut) {
        this.installed = installed;
        this.takeOut = takeOut;
        this.icons = icons;
        this.applier = applier;
        this.switcher = switcher;
        this.classes = classes;
        this.reports = reports;
        this.addedJarsFolder = gameDir.resolve("hotwire").resolve("added");
        this.folders = folders;
        this.queue = queue;
        this.adder = new ModAdder(this);
    }

    public AddPreview preview(Path jar) {
        AddPreview preview = this.adder.preview(jar);
        return preview.mode() == AddMode.REFUSED ? preview : preview.withIcon(this.icons.ofJar(jar));
    }

    public String queue(Path jar) {
        try {
            ModManifest manifest = this.queueJar(jar);
            return manifest.name() + " " + manifest.version() + " is queued as a pending task: it goes into your"
                    + " mods folder when you close the game. Cancel it any time from Pending tasks.";
        } catch (ModJarException | IOException e) {
            return jar.getFileName() + " could not be queued: " + e.getMessage();
        }
    }

    @Override
    public void showAddStep(int at) {
        this.switcher.getProgress().stepAdding(at);
    }

    public String copyIntoMods(Path jar) {
        Path mods = this.folders.mods().toAbsolutePath().normalize();
        if (jar.toAbsolutePath().normalize().startsWith(mods)) {
            return "";
        }
        try {
            Path target = mods.resolve(String.valueOf(jar.getFileName()));
            if (Files.exists(target) && !Files.isSameFile(target, jar)) {
                return " A file named " + jar.getFileName() + " is in your mods folder already, so its jar was not"
                        + " copied there: copy it there yourself to have the mod at the next start.";
            }
            Files.copy(jar, target);
            return " Its jar is in your mods folder now.";
        } catch (IOException e) {
            return " Its jar could not be copied into your mods folder (" + e.getMessage() + "): copy it there to have"
                    + " the mod at the next start.";
        }
    }

    private ModManifest queueJar(Path jar) throws ModJarException, IOException {
        ModManifest manifest = ModJarReader.read(jar).manifest();
        Files.createDirectories(this.folders.staging());
        Path staged = this.folders.staging().resolve(String.valueOf(jar.getFileName()));
        Files.copy(jar, staged, StandardCopyOption.REPLACE_EXISTING);
        this.queue.add(new UpdateQueue.Entry(manifest.id(), manifest.name(), "", manifest.version(), "", "",
                staged.toAbsolutePath().toString(), UpdateQueue.hashSha256(staged), "",
                System.currentTimeMillis()));
        return manifest;
    }

    public ModAdder.Result add(Path jar, Function<String, String> nameOf) {
        long start = System.nanoTime();
        this.reports.onBegun(Report.Operation.ADD, String.valueOf(jar.getFileName()));
        ModAdder.Result result;
        try {
            result = this.adder.add(jar, nameOf);
        } finally {
            this.reports.onEnded();
        }
        this.reports.onAdded(result, (System.nanoTime() - start) / 1_000_000);
        if (result instanceof ModAdder.Result.Added added) {
            try {
                this.installed.onAdded(jar, ModJarReader.read(jar), added.ids());
            } catch (ModJarException e) {
                throw new IllegalStateException(e);
            }
        }
        return result;
    }

    @Override
    public boolean isInstalled(String id) {
        return this.installed.isInstalled(id) || FabricLoader.getInstance().isModLoaded(id);
    }

    @Override
    public @Nullable Path findJarOf(ModJar part) {
        Path known = this.installed.findJarOf(part.manifest().id());
        if (known != null) {
            return known;
        }
        Path added = this.addedJarsFolder.resolve(part.name());
        return Files.exists(added) ? added : null;
    }

    @Override
    public ModGraph makeGraphWith(List<ModJar> added) {
        return this.installed.makeGraphWith(added);
    }

    @Override
    public MixinCopy.Decorations getDecorationsFor(List<ModJar> added) {
        return MixinConfigs.readDecorations(MixinConfigs.of(added));
    }

    @Override
    public Path getAddedJarsFolder() {
        return this.addedJarsFolder;
    }

    @Override
    public SwitchPlanner makePlannerFor(ModGraph graph) {
        return ModPolicy.makePlannerFor(graph);
    }

    @Override
    public SwitchApplier getApplier() {
        return this.applier;
    }

    @Override
    public ModSwitcher getSwitcher() {
        return this.switcher;
    }

    @Override
    public ModSwitcher.Result takeOut(List<String> ids) {
        return this.takeOut.apply(ids);
    }

    @Override
    public boolean isLoaded(String internalName) {
        return this.classes.findLoaded(internalName) != null;
    }

    @Override
    public void forgetClassReads() {
        this.classes.forgetOriginalBytes();
        this.applier.getRebuilder().copy().forgetReads();
    }

    @Override
    public @Nullable Class<?> findLoaded(String internalName) {
        return this.classes.findLoaded(internalName);
    }

    @Override
    public int getModifiers(String internalName) {
        ClassReader reader = new ClassReader(Objects.requireNonNull(this.classes.findOriginalBytes(internalName),
                internalName));
        return reader.getAccess() | ("java/lang/Record".equals(reader.getSuperName()) ? Opcodes.ACC_RECORD : 0);
    }

    @Override
    public ModManifest.Environment getSide() {
        return ModPolicy.getSide();
    }

    @Override
    public boolean isInWorld() {
        Minecraft client = Minecraft.getInstance();
        return client.level != null || client.hasSingleplayerServer();
    }

    @Override
    public OptionalInt findModifiers(String owner, String name, String desc) {
        byte[] bytes = this.classes.findOriginalBytes(owner);
        if (bytes == null) {
            return OptionalInt.empty();
        }
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        for (MethodNode method : node.methods) {
            if (method.name.equals(name) && method.desc.equals(desc)) {
                return OptionalInt.of(method.access);
            }
        }
        for (FieldNode field : node.fields) {
            if (field.name.equals(name) && field.desc.equals(desc)) {
                return OptionalInt.of(field.access);
            }
        }
        return OptionalInt.empty();
    }

    @Override
    public @Nullable String findSuperOf(String owner) {
        byte[] bytes = this.classes.findOriginalBytes(owner);
        return bytes == null ? null : new ClassReader(bytes).getSuperName();
    }

    static Optional<String> findAgentRefusal(String name, @Nullable Path jar) {
        if (jar == null) {
            return Optional.empty();
        }
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            for (ZipEntry entry : Collections.list(zip.entries())) {
                if (entry.getName().endsWith(".class") && isInstrumenting(zip.getInputStream(entry).readAllBytes())) {
                    return Optional.of(name + " changes the game's classes in the JVM itself, which nothing takes back"
                            + " out while the game runs.");
                }
            }
        } catch (IOException e) {
            LOGGER.warn("[Hotwire] {} could not be read for JVM agents", jar.getFileName(), e);
        }
        return Optional.empty();
    }

    private static boolean isInstrumenting(byte[] bytes) {
        String pool = new String(bytes, StandardCharsets.ISO_8859_1);
        return pool.contains("com/sun/tools/attach/VirtualMachine")
                || pool.contains("net/bytebuddy/agent/ByteBuddyAgent");
    }
}
