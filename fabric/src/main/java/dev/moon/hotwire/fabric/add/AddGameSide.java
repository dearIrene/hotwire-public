package dev.moon.hotwire.fabric.add;

import dev.moon.hotwire.add.AddGame;
import dev.moon.hotwire.add.ModAdder;
import dev.moon.hotwire.fabric.GameParts;
import dev.moon.hotwire.fabric.rebuild.MixinConfigs;
import dev.moon.hotwire.jar.ModJar;
import dev.moon.hotwire.jar.ModManifest;
import dev.moon.hotwire.mixin_copy.MixinCopy;
import dev.moon.hotwire.registration.RegistrationJournal;
import dev.moon.hotwire.switching.ModMixins;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;

public final class AddGameSide implements AddGame {
    @Override
    public boolean isNetworkClass(Class<?> loaded) {
        return NetworkSetups.isOf(loaded);
    }

    @Override
    public void runPlugins(ModManifest manifest, Path jar) {
        LivePlugins.run(manifest, jar);
    }

    @Override
    public Hold makeHold() {
        return new LateHold();
    }

    @Override
    public void openPacks(Collection<ModAdder.Part> parts) {
        AddAssets.openPacks(parts);
    }

    @Override
    public void reloadForAssets(Collection<ModAdder.Part> parts) {
        AddAssets.reloadForAssets(parts);
    }

    @Override
    public CompletableFuture<Map<String, List<String>>> queueEntrypoints(List<String> ids, List<String> grew,
            CountDownLatch backedOut, ModManifest.Environment side, IntSupplier catchUp,
            Function<String, @Nullable Class<?>> findLoaded, Set<String> mixed) {
        return AddStart.queue(ids, grew, backedOut, side, catchUp, findLoaded, mixed);
    }

    @Override
    public List<String> runStartListeners(String id, RegistrationJournal journal) {
        return GameParts.getLifecycle().startAdded(id, journal, Minecraft.getInstance());
    }

    @Override
    public void refuseWorldsFor(String name) {
        WorldReopen.refuseWorldsFor(name);
    }

    @Override
    public ModAdder.Result addAroundReopen(Supplier<ModAdder.Result> add, boolean byPlayer,
            BiFunction<ModAdder.Result.Added, String, ModAdder.Result> takeBack) {
        return WorldReopen.add(add, byPlayer, takeBack);
    }

    @Override
    public MixinCopy.Decorations readDecorations(List<ModJar> jars) {
        return MixinConfigs.readDecorations(ModMixins.readAll(jars));
    }
}
