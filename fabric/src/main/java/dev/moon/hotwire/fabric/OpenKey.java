package dev.moon.hotwire.fabric;

import com.mojang.blaze3d.platform.InputConstants;
import dev.moon.hotwire.config.HotwireSettings;
import dev.moon.hotwire.fabric.registration.GameRegistrations;
import dev.moon.hotwire.fabric.screen.ChooserGameScreen;
import dev.moon.hotwire.fabric.screen.HotwireScreen;
import dev.moon.hotwire.fabric.screen.StoreGameScreen;
import dev.moon.hotwire.ui.Lang;
import dev.moon.hotwire.ui.Notice;
import dev.moon.hotwire.ui.Notices;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
//? if >=26.1 {
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
//?} else {
/*import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
*///?}
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class OpenKey {
    private static final Logger LOGGER = LoggerFactory.getLogger(OpenKey.class);
    private static final Identifier CATEGORY = Identifier.fromNamespaceAndPath(HotwireClient.ID,
            HotwireClient.ID);
    private static @Nullable KeyMapping key;

    private static @Nullable KeyMapping storeKey;
    private static @Nullable Class<?> homeScreen;
    private static Runnable open = () -> { };
    private static Runnable openStore = () -> { };

    private static @Nullable KeyMapping devKey;
    private static Runnable openDev = () -> { };

    private OpenKey() {
    }

    static void register(Runnable opener, Runnable storeOpener, @Nullable Runnable devOpener) {
        //? if >=1.21.10 {
        KeyMapping.Category category = KeyMapping.Category.register(CATEGORY);
        //?} else {
        /*String category = "key.category." + CATEGORY.toLanguageKey();
        *///?}
        KeyMapping made = make("key.hotwire.open", InputConstants.KEY_H, category);
        KeyMapping store = make("key.hotwire.store", InputConstants.UNKNOWN.getValue(), category);
        KeyMapping dev = devOpener == null ? null : make("key.hotwire.developer", InputConstants.KEY_F8, category);
        //? if >=26.1 {
        KeyMappingHelper.registerKeyMapping(made);
        KeyMappingHelper.registerKeyMapping(store);
        if (dev != null) {
            KeyMappingHelper.registerKeyMapping(dev);
        }
        //?} else {
        /*KeyBindingHelper.registerKeyBinding(made);
        KeyBindingHelper.registerKeyBinding(store);
        if (dev != null) {
            KeyBindingHelper.registerKeyBinding(dev);
        }
        *///?}
        devKey = dev;
        if (devOpener != null) {
            openDev = devOpener;
        }
        ScreenEvents.BEFORE_INIT.register((game, screen, width, height) -> onScreenShown(screen));
        open = opener;
        openStore = storeOpener;
        key = made;
        storeKey = store;
    }

    @SuppressWarnings("ReferenceEquality")
    static void tellConflicts(HotwireSettings settings) {
        KeyMapping made = key;
        if (made == null || settings.isKeyCardShown() || !settings.isKeyAlertOn()) {
            return;
        }
        List<String> others = new ArrayList<>();
        for (KeyMapping other : Minecraft.getInstance().options.keyMappings) {
            if (other != made && other.same(made) && !other.isUnbound()) {
                others.add(Component.translatable(other.getName()).getString());
            }
        }
        if (others.isEmpty()) {
            return;
        }
        settings.markKeyCardShown();
        String keyName = made.getTranslatedKeyMessage().getString();
        LOGGER.info("[Hotwire] {} opens Hotwire and {} too; said in a card", keyName, others);
        Notices.show(Notice.ofAsk(Lang.get("hotwire.key.taken", keyName),
                Lang.get("hotwire.key.taken_by", keyName, joinPlainly(others))).makeResting(10));
    }

    static void tellIfBlocked(HotwireSettings settings) {
        KeyMapping made = key;
        Screen shown = ModViews.openScreen();
        if (made == null || !settings.isKeyAlertOn() || shown == null || shown instanceof HotwireScreen
                || shown instanceof ChooserGameScreen || shown instanceof StoreGameScreen) {
            return;
        }
        String keyName = made.getTranslatedKeyMessage().getString();
        String mod = GameRegistrations.findModOf(shown.getClass());
        String name = mod == null ? shown.getClass().getSimpleName() : FabricLoader.getInstance().getModContainer(mod)
                .map(container -> container.getMetadata().getName()).orElse(mod);
        LOGGER.info("[Hotwire] {} opens {}'s {} over Hotwire; said in a card", keyName, name,
                shown.getClass().getName());
        Notices.show(Notice.ofAsk(Lang.get("hotwire.key.taken", keyName),
                Lang.get("hotwire.key.blocked_by", name, keyName)).makeResting(10));
    }

    private static String joinPlainly(List<String> names) {
        if (names.size() == 1) {
            return names.getFirst();
        }
        return String.join(", ", names.subList(0, names.size() - 1)) + " " + Lang.get("hotwire.key.and") + " "
                + names.getLast();
    }

    static String findKeyName() {
        KeyMapping made = key;
        return made == null ? "H" : made.getTranslatedKeyMessage().getString();
    }

    public static boolean openAtScreen(Predicate<KeyMapping> pressed, boolean shift) {
        KeyMapping made = key;
        KeyMapping store = storeKey;
        Minecraft minecraft = Minecraft.getInstance();
        Screen shown = ModViews.openScreen();
        if (made == null || store == null || minecraft.level != null || shown == null
                || !(shown instanceof TitleScreen || shown.getClass() == homeScreen) || shown instanceof HotwireScreen
                || shown instanceof ChooserGameScreen || shown instanceof StoreGameScreen
                || shown.getFocused() instanceof EditBox || shown.getFocused() instanceof MultiLineEditBox) {
            return false;
        }
        if (pressed.test(store)) {
            openStore.run();
            return true;
        }
        if (!pressed.test(made)) {
            return false;
        }
        HotwireClient.openFromScreen(shift);
        return true;
    }

    private static void onScreenShown(Screen screen) {
        if (homeScreen == null && !(screen instanceof HotwireScreen)
                && !(screen instanceof ChooserGameScreen) && !(screen instanceof StoreGameScreen)) {
            homeScreen = screen.getClass();
        }
    }

    public static void onPressed() {
        KeyMapping made = key;
        if (made != null && made.consumeClick()) {
            open.run();
        }
        KeyMapping store = storeKey;
        if (store != null && store.consumeClick()) {
            openStore.run();
        }
        KeyMapping dev = devKey;
        if (dev != null && dev.consumeClick()) {
            openDev.run();
        }
    }

    //? if >=1.21.10 {
    private static KeyMapping make(String name, int defaultKey, KeyMapping.Category category) {
        return new KeyMapping(name, defaultKey, category);
    }
    //?} else {
    /*private static KeyMapping make(String name, int defaultKey, String category) {
        return new KeyMapping(name, defaultKey, category);
    }
    *///?}
}
