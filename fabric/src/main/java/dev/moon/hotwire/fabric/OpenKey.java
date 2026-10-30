package dev.moon.hotwire.fabric;

import com.mojang.blaze3d.platform.InputConstants;
import dev.moon.hotwire.report.GameLog;
import dev.moon.hotwire.ui.Lang;
import dev.moon.hotwire.ui.Notice;
import dev.moon.hotwire.ui.Notices;
import java.util.ArrayList;
import java.util.List;
//? if >=26.1 {
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
//?} else {
/*import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
*///?}
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

public final class OpenKey {
    private static final Identifier CATEGORY = Identifier.fromNamespaceAndPath(HotwireClient.ID,
            HotwireClient.ID);
    private static @Nullable KeyMapping key;
    private static Runnable open = () -> { };
    private static boolean told;

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
    static void tellConflicts() {
        KeyMapping made = key;
        if (made == null || told) {
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
        told = true;
        String keyName = made.getTranslatedKeyMessage().getString();
        GameLog.LOGGER.info("[Hotwire] {} opens Hotwire and {} too; said in a card", keyName, others);
        Notices.show(Notice.ofAsk(Lang.get("hotwire.key.taken", keyName),
                Lang.get("hotwire.key.taken_by", keyName, joinPlainly(others))).makeResting(15));
    }

    private static String joinPlainly(List<String> names) {
        if (names.size() == 1) {
            return names.getFirst();
        }
        return String.join(", ", names.subList(0, names.size() - 1)) + " " + Lang.get("hotwire.key.and") + " "
                + names.getLast();
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
