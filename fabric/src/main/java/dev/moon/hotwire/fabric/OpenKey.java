package dev.moon.hotwire.fabric;

import com.mojang.blaze3d.platform.InputConstants;
import dev.moon.hotwire.config.HotwireSettings;
import dev.moon.hotwire.fabric.registration.GameRegistrations;
import dev.moon.hotwire.fabric.screen.ChooserGameScreen;
import dev.moon.hotwire.fabric.screen.HotwireScreen;
import dev.moon.hotwire.fabric.screen.StoreGameScreen;
import dev.moon.hotwire.report.GameLog;
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
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

public final class OpenKey {
    private static final Identifier CATEGORY = Identifier.fromNamespaceAndPath(HotwireClient.ID,
            HotwireClient.ID);
    private static @Nullable KeyMapping key;
    private static Runnable open = () -> { };

    private OpenKey() {
    }

    static void register(Runnable opener) {
        KeyMapping made = make();
        //? if >=26.1 {
        KeyMappingHelper.registerKeyMapping(made);
        //?} else {
        /*KeyBindingHelper.registerKeyBinding(made);
        *///?}
        open = opener;
        key = made;
    }

    @SuppressWarnings("ReferenceEquality")
    static void tellConflicts(HotwireSettings settings) {
        KeyMapping made = key;
        if (made == null || settings.isKeyCardShown()) {
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
        GameLog.LOGGER.info("[Hotwire] {} opens Hotwire and {} too; said in a card", keyName, others);
        Notices.show(Notice.ofAsk(Lang.get("hotwire.key.taken", keyName),
                Lang.get("hotwire.key.taken_by", keyName, joinPlainly(others))).makeResting(10));
    }

    static void tellIfBlocked() {
        KeyMapping made = key;
        Screen shown = ModViews.openScreen();
        if (made == null || shown == null || shown instanceof HotwireScreen || shown instanceof ChooserGameScreen
                || shown instanceof StoreGameScreen) {
            return;
        }
        String keyName = made.getTranslatedKeyMessage().getString();
        String mod = GameRegistrations.findModOf(shown.getClass());
        String name = mod == null ? shown.getClass().getSimpleName() : FabricLoader.getInstance().getModContainer(mod)
                .map(container -> container.getMetadata().getName()).orElse(mod);
        GameLog.LOGGER.info("[Hotwire] {} opens {}'s {} over Hotwire; said in a card", keyName, name,
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
        Minecraft minecraft = Minecraft.getInstance();
        Screen shown = ModViews.openScreen();
        if (made == null || minecraft.level != null || shown == null || shown instanceof HotwireScreen
                || shown instanceof ChooserGameScreen || shown instanceof StoreGameScreen
                || shown.getFocused() instanceof EditBox || shown.getFocused() instanceof MultiLineEditBox
                || !pressed.test(made)) {
            return false;
        }
        HotwireClient.openFromScreen(shift);
        return true;
    }

    public static void onPressed() {
        KeyMapping made = key;
        if (made != null && made.consumeClick()) {
            open.run();
        }
    }

    private static KeyMapping make() {
        //? if >=1.21.10 {
        return new KeyMapping("key.hotwire.open", InputConstants.KEY_H, KeyMapping.Category.register(CATEGORY));
        //?} else {
        /*return new KeyMapping("key.hotwire.open", InputConstants.KEY_H, "key.category." + CATEGORY.toLanguageKey());
        *///?}
    }
}
