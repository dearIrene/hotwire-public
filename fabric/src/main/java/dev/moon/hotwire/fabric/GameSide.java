package dev.moon.hotwire.fabric;

import dev.moon.hotwire.fabric.add.WorldReopen;
import dev.moon.hotwire.fabric.mixin.core.fabric.base.event.ArrayBackedEventAccessor;
import dev.moon.hotwire.fabric.mixin.core.world.item.CreativeModeTabsAccessor;
import dev.moon.hotwire.fabric.rebuild.GameClasses;
import dev.moon.hotwire.fabric.registration.GameHold;
import dev.moon.hotwire.fabric.registration.InternedKeys;
import dev.moon.hotwire.fabric.registration.LevelEntities;
import dev.moon.hotwire.fabric.registration.ModSounds;
import dev.moon.hotwire.fabric.registration.NetworkHandlers;
import dev.moon.hotwire.fabric.registration.RegisteredContent;
import dev.moon.hotwire.fabric.screen.HoldFrame;
import dev.moon.hotwire.fabric.screen.WebLinks;
import dev.moon.hotwire.seam.Game;
import dev.moon.hotwire.seam.Reloads;
import io.netty.channel.Channel;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.level.Level;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.transformer.ClassInfo;

public final class GameSide implements Game {
    private final GameReloads reloads = new GameReloads();
    @Override
    public int findBitsOf(Class<?> type) {
        int bits = 0;
        if (Registry.class.isAssignableFrom(type)) {
            bits |= REGISTRY;
        }

        if (ModelManager.class.isAssignableFrom(type) || ArrayBackedEventAccessor.class.isAssignableFrom(type)
                || Holder.Reference.class.isAssignableFrom(type) || ClassInfo.class.isAssignableFrom(type)) {
            bits |= LEFT_TO_OTHERS;
        }
        if (Identifier.class.isAssignableFrom(type)) {
            bits |= ID;
        }
        if (ResourceKey.class.isAssignableFrom(type)) {
            bits |= KEY;
        }
        if (Entity.class.isAssignableFrom(type)) {
            bits |= ENTITY;
        }
        if (type == CreativeModeTab.class) {
            bits |= CREATIVE_TAB;
        }
        if (Level.class.isAssignableFrom(type) || Entity.class.isAssignableFrom(type)
                || Minecraft.class.isAssignableFrom(type) || MinecraftServer.class.isAssignableFrom(type)
                || Channel.class.isAssignableFrom(type)) {
            bits |= NOT_ENTERED;
        }
        return bits;
    }

    @Override
    public @Nullable String findNamespaceOf(Object named) {
        if (named instanceof Identifier id) {
            return id.getNamespace();
        }
        return named instanceof ResourceKey<?> key ? InternedKeys.findIdOf(key).getNamespace() : null;
    }

    @Override
    public Object getRoot() {
        return Minecraft.getInstance();
    }

    @Override
    public @Nullable Object getWorld() {
        return Minecraft.getInstance().level;
    }

    @Override
    public <T> T reopenAround(Supplier<T> work) throws InterruptedException {
        return WorldReopen.runAround(work);
    }

    @Override
    public Object getModelManager() {
        return Minecraft.getInstance().getModelManager();
    }

    @Override
    public List<Object> getEntities() {
        return LevelEntities.findAll();
    }

    @Override
    public List<Object> getPipelineObjects() {
        return NetworkHandlers.findPipelineObjects();
    }

    @Override
    public Set<Object> getRegistered() {
        return RegisteredContent.find();
    }

    @Override
    public long findRegistrySizes() {
        return RegisteredContent.findSizes();
    }

    @Override
    public boolean isStoppedSound(@Nullable Object sound) {
        return ModSounds.isStopped(sound);
    }

    @Override
    public Iterable<?> getCreativeTabs() {
        return BuiltInRegistries.CREATIVE_MODE_TAB;
    }

    @Override
    public void forgetCreativeTabItems() {
        CreativeModeTabsAccessor.hotwire$setCachedParameters(null);
    }

    @Override
    public boolean isOnGameThread() {
        return Minecraft.getInstance().isSameThread();
    }

    @Override
    public void runOnGame(Runnable work) {
        Minecraft.getInstance().execute(work);
    }

    @Override
    public void copyToClipboard(String text) {
        Minecraft game = Minecraft.getInstance();
        game.execute(() -> game.keyboardHandler.setClipboard(text));
    }

    @Override
    public int getFrameRate() {
        return Minecraft.getInstance().getFps();
    }

    @Override
    public double findServerTickMillis() {
        MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
        return server == null ? 0 : server.getAverageTickTimeNanos() / 1_000_000.0;
    }

    @Override
    public void openFolder(Path folder) {
        WebLinks.openFolder(folder);
    }

    @Override
    public String describeFlipKey() {
        return OpenKey.describeFlipKey();
    }

    @Override
    public void setFlipKey(int code) {
        OpenKey.setFlipKey(code);
    }

    @Override
    public void setOriginalsFallback(Function<String, byte @Nullable []> reader) {
        GameClasses.setOriginalsFallback(reader);
    }

    @Override
    public boolean isShippedUnderOtherNames() {
        //? if >=26.1 {
        return false;
        //?} else {
        /*return true;
        *///?}
    }

    @Override
    public void runOnGameAndWait(Runnable work) {
        GameHold.onGameAndWait(work);
    }

    @Override
    public Runnable hold() {
        return GameHold.begin(Minecraft.getInstance())::release;
    }

    @Override
    public void runUnheld(Runnable work) {
        GameHold.runUnheld(work);
    }

    @Override
    public boolean isHoldOverBudget() {
        return GameHold.isOverBudget();
    }

    @Override
    public <T> T runUnbudgeted(Supplier<T> work) {
        return GameHold.runUnbudgeted(work);
    }

    @Override
    public Runnable runAtNextHold(Runnable work) {
        GameHold.runAtNextHold(work);
        return GameHold::dropNextHold;
    }

    @Override
    public Reloads getReloads() {
        return this.reloads;
    }

    @Override
    public void drawHoldFrame() {
        HoldFrame.drawIfDue();
    }

    @Override
    public boolean isGamePaused() {
        return GameHold.isGamePaused();
    }

    @Override
    public long countFrames() {
        return GameHold.countFrames();
    }

    @Override
    public void runBeforeFrameAndWait(Runnable work, String what) {
        GameHold.onGameBeforeFrameAndWait(work, what);
    }

    @Override
    public void runSoon(Runnable work) {
        ModViews.runSoon(work);
    }

    @Override
    public Optional<String> findReopenRefusal() {
        return WorldReopen.findRefusal();
    }
}
