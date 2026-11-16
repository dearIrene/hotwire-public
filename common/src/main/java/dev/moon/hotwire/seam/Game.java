package dev.moon.hotwire.seam;

import java.util.List;
import java.util.Set;
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

    Object getModelManager();

    List<Object> getEntities();

    List<Object> getPipelineObjects();

    Set<Object> getRegistered();

    long findRegistrySizes();

    boolean isStoppedSound(@Nullable Object sound);

    Iterable<?> getCreativeTabs();

    void forgetCreativeTabItems();

    boolean isOnGameThread();

    void runOnGameAndWait(Runnable work);

    Runnable hold();

    void runUnheld(Runnable work);

    boolean isHoldOverBudget();

    void drawHoldFrame();
}
