package dev.moon.hotwire.fabric.add;

import dev.moon.hotwire.add.AddGame;
import dev.moon.hotwire.add.ModAdder;
import dev.moon.hotwire.fabric.rebuild.MixinConfigs;
import dev.moon.hotwire.fabric.registration.ModLifecycle;
import dev.moon.hotwire.jar.ModJar;
import dev.moon.hotwire.jar.ModManifest;
import dev.moon.hotwire.mixin_copy.MixinCopy;
import dev.moon.hotwire.registration.RegistrationJournal;
import dev.moon.hotwire.switching.ModMixins;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
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
            Function<String, @Nullable Class<?>> findLoaded) {
        return AddStart.queue(ids, grew, backedOut, side, catchUp, findLoaded);
    }

    @Override
    public List<String> runStartListeners(String id, RegistrationJournal journal) {
        return ModLifecycle.startAdded(id, journal, Minecraft.getInstance());
    }

    @Override
    public void refuseWorldsFor(String name) {
        WorldReopen.refuseWorldsFor(name);
    }

    @Override
    public ModAdder.Result addAroundReopen(Supplier<ModAdder.Result> add) {
        return WorldReopen.add(add);
    }

    @Override
    public MixinCopy.Decorations readDecorations(List<ModJar> jars) {
        return MixinConfigs.readDecorations(ModMixins.readAll(jars));
    }
}
