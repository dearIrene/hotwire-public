package dev.moon.hotwire.fabric;

import com.mojang.blaze3d.platform.InputConstants;
import dev.moon.hotwire.config.HotwireSettings;
import dev.moon.hotwire.fabric.registration.GameRegistrations;
import dev.moon.hotwire.fabric.screen.HotwireScreen;
import dev.moon.hotwire.ui.Lang;
import dev.moon.hotwire.ui.Notice;
import dev.moon.hotwire.ui.Notices;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class OpenKey {
    private static final Logger LOGGER = LoggerFactory.getLogger(OpenKey.class);
    private static @Nullable KeyMapping key;

    private static @Nullable KeyMapping storeKey;
    private static @Nullable Class<?> homeScreen;
    private static Runnable open = () -> { };
    private static Runnable openStore = () -> { };

    private static @Nullable KeyMapping devKey;
    private static @Nullable KeyMapping devReloadKey;
    private static @Nullable KeyMapping devBackKey;
    private static @Nullable KeyMapping devFlipKey;
    private static @Nullable DevKeys devKeys;

    record DevKeys(Runnable open, Runnable reload, Runnable back, Runnable flip) {
    }

    private OpenKey() {
    }

    static void register(Runnable opener, Runnable storeOpener, @Nullable DevKeys dev) {
        int unbound = InputConstants.UNKNOWN.getValue();
        Map<String, Integer> defaults = new LinkedHashMap<>();
        defaults.put("key.hotwire.open", InputConstants.KEY_H);
        defaults.put("key.hotwire.store", unbound);
        if (dev != null) {
            defaults.put("key.hotwire.developer", InputConstants.KEY_F8);
            defaults.put("key.hotwire.dev_reload", unbound);
            defaults.put("key.hotwire.dev_back", unbound);
            defaults.put("key.hotwire.dev_flip", unbound);
        }
        List<KeyMapping> made = ModViews.registerKeys(defaults);
        if (dev != null) {
            devKey = made.get(2);
            devReloadKey = made.get(3);
            devBackKey = made.get(4);
            devFlipKey = made.get(5);
        }
        devKeys = dev;
        open = opener;
        openStore = storeOpener;
        key = made.get(0);
        storeKey = made.get(1);
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
        if (made == null || !settings.isKeyAlertOn() || shown == null || shown instanceof HotwireScreen) {
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

    public static boolean openAtScreen(Predicate<KeyMapping> pressed) {
        KeyMapping made = key;
        KeyMapping store = storeKey;
        Minecraft minecraft = Minecraft.getInstance();
        Screen shown = ModViews.openScreen();
        if (made == null || store == null || minecraft.level != null || shown == null
                || !(shown instanceof TitleScreen || shown.getClass() == homeScreen) || shown instanceof HotwireScreen
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
        HotwireClient.openFromScreen();
        return true;
    }

    static void noteHome() {
        if (homeScreen == null) {
            Screen shown = ModViews.openScreen();
            homeScreen = shown == null ? null : shown.getClass();
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
        DevKeys dev = devKeys;
        if (dev != null) {
            runIfPressed(devKey, dev.open());
            runIfPressed(devReloadKey, dev.reload());
            runIfPressed(devBackKey, dev.back());
            runIfPressed(devFlipKey, dev.flip());
        }
    }

    public static String describeFlipKey() {
        KeyMapping flip = devFlipKey;
        return flip == null || flip.isUnbound() ? "" : flip.getTranslatedKeyMessage().getString();
    }

    public static void setFlipKey(int code) {
        KeyMapping flip = devFlipKey;
        if (flip == null) {
            return;
        }
        Minecraft game = Minecraft.getInstance();
        game.execute(() -> {
            ModViews.setKey(flip, code);
            KeyMapping.resetMapping();
            game.options.save();
        });
    }

    private static void runIfPressed(@Nullable KeyMapping mapping, Runnable action) {
        if (mapping != null && mapping.consumeClick()) {
            action.run();
        }
    }
}
