package dev.moon.hotwire.seam;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

public interface Game {
    int REGISTRY = 1;
    int LEFT_TO_OTHERS = 2;
    int ID = 4;
    int KEY = 8;
    int ENTITY = 16;
    int CREATIVE_TAB = 32;

    int NOT_ENTERED = 64;

    int findBitsOf(Class<?> type);

    @Nullable String findNamespaceOf(Object named);

    Object getRoot();

    @Nullable Object getWorld();

    <T> T reopenAround(Supplier<T> work) throws InterruptedException;

    Object getModelManager();

    List<Object> getEntities();

    List<Object> getPipelineObjects();

    Set<Object> getRegistered();

    long findRegistrySizes();

    boolean isStoppedSound(@Nullable Object sound);

    Iterable<?> getCreativeTabs();

    void forgetCreativeTabItems();

    boolean isOnGameThread();

    void runOnGame(Runnable work);

    void copyToClipboard(String text);

    int getFrameRate();

    double findServerTickMillis();

    void openFolder(Path folder);

    String describeFlipKey();

    void setFlipKey(int code);

    void setOriginalsFallback(Function<String, byte @Nullable []> reader);

    boolean isShippedUnderOtherNames();

    void runOnGameAndWait(Runnable work);

    Runnable hold();

    void runUnheld(Runnable work);

    boolean isHoldOverBudget();

    <T> T runUnbudgeted(Supplier<T> work);

    Runnable runAtNextHold(Runnable work);

    Reloads getReloads();

    boolean isGamePaused();

    long countFrames();

    void runBeforeFrameAndWait(Runnable work, String what);

    void runSoon(Runnable work);

    void drawHoldFrame();

    void showScreen(@Nullable Object screen);

    boolean isHotwireShown();

    boolean isServerSaving();

    boolean hasOwnServer();

    void openLink(String link);

    boolean isPlaying();

    Optional<String> findReopenRefusal();
}
