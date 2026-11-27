package dev.moon.hotwire.add;

import com.google.errorprone.annotations.CanIgnoreReturnValue;
import dev.moon.hotwire.jar.ModJar;
import dev.moon.hotwire.jar.ModManifest;
import dev.moon.hotwire.mixin_copy.MixinCopy;
import dev.moon.hotwire.registration.RegistrationJournal;
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
import org.jspecify.annotations.Nullable;

public interface AddGame {
    interface Hold {
        Runnable begin();

        void releaseGame();

        @CanIgnoreReturnValue
        boolean pauseGame();

        void release();
    }

    boolean isNetworkClass(Class<?> loaded);

    void runPlugins(ModManifest manifest, Path jar);

    Hold makeHold();

    void openPacks(Collection<ModAdder.Part> parts);

    void reloadForAssets(Collection<ModAdder.Part> parts);

    CompletableFuture<Map<String, List<String>>> queueEntrypoints(List<String> ids, List<String> grew,
            CountDownLatch backedOut, ModManifest.Environment side, IntSupplier catchUp,
            Function<String, @Nullable Class<?>> findLoaded, Set<String> mixed);

    List<String> runStartListeners(String id, RegistrationJournal journal);

    void refuseWorldsFor(String name);

    ModAdder.Result addAroundReopen(Supplier<ModAdder.Result> add, boolean byPlayer,
            BiFunction<ModAdder.Result.Added, String, ModAdder.Result> takeBack);

    MixinCopy.Decorations readDecorations(List<ModJar> jars);
}
