package dev.moon.hotwire.fabric;

import dev.moon.hotwire.engine.HotwireEngine;
import dev.moon.hotwire.engine.WorldContentRecord;
import dev.moon.hotwire.ui.Lang;
import it.unimi.dsi.fastutil.booleans.BooleanConsumer;
import java.nio.file.Path;
import java.util.List;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class WorldLossWarning extends ConfirmScreen {
    private static final Logger LOGGER = LoggerFactory.getLogger(WorldLossWarning.class);

    private static volatile @Nullable String allowed;

    private static final int ABOVE_BUTTONS = 28;

    private WorldLossWarning(BooleanConsumer answer, Component title, Component text) {
        super(answer, title, text, Component.literal(Lang.get("hotwire.world_loss.open")),
                Component.literal(Lang.get("hotwire.world_loss.back")));
    }

    @Override
    protected void init() {
        super.init();
        Component open = Component.literal(Lang.get("hotwire.world_loss.open"));
        Button openAnyway = null;
        int buttonsTop = this.height;
        for (GuiEventListener child : this.children()) {
            if (child instanceof Button button) {
                buttonsTop = Math.min(buttonsTop, button.getY());
                if (button.getMessage().getString().equals(open.getString())) {
                    openAnyway = button;
                }
            }
        }
        if (openAnyway == null) {
            return;
        }
        Button gated = openAnyway;
        gated.active = false;
        Checkbox understood = Checkbox.builder(Component.literal(Lang.get("hotwire.world_loss.understand")),
                this.font).onValueChange((box, ticked) -> gated.active = ticked).build();
        understood.setX((this.width - understood.getWidth()) / 2);
        understood.setY(buttonsTop - ABOVE_BUTTONS);
        this.addRenderableWidget(understood);
    }

    public static void allowOnce(String level) {
        allowed = level;
    }

    public static boolean isAsking(String level) {
        if (level.equals(allowed)) {
            allowed = null;
            return false;
        }
        Minecraft client = Minecraft.getInstance();
        HotwireEngine engine = HotwireClient.findEngine();
        FabricLoader loader = FabricLoader.getInstance();
        Path world = client.getLevelSource().getBaseDir().resolve(level);
        List<WorldContentRecord.Entry> missing = WorldContentRecord.findMissing(world,
                id -> loader.isModLoaded(id) && (engine == null || engine.isOn(id)));
        if (missing.isEmpty()) {
            return false;
        }
        List<String> lines = missing.stream().map(one -> Lang.get(loader.isModLoaded(one.id())
                ? "hotwire.world_loss.mod_off" : "hotwire.world_loss.mod_gone", one.name())).toList();
        LOGGER.warn("[Hotwire] the world {} holds content of mods not on now, so its open waits for the player: {}",
                level, String.join(", ", lines));
        Screen back = ModViews.openScreen();
        ModViews.show(new WorldLossWarning(open -> {
            if (!open) {
                ModViews.show(back);
                return;
            }
            WorldContentRecord.History history = WorldContentNotes.openHistory(level);
            missing.forEach(one -> history.add("x", one, "opened anyway without it"));
            LOGGER.warn("[Hotwire] the player opened {} anyway, without {}", level, String.join(", ", lines));
            allowOnce(level);
            client.createWorldOpenFlows().openWorld(level, () -> ModViews.show(back));
        }, Component.literal(Lang.get("hotwire.world_loss.title")).withStyle(ChatFormatting.RED),
                Component.literal(Lang.get("hotwire.world_loss.text", String.join("\n", lines)))));
        return true;
    }

    public static boolean isShown(@Nullable Screen screen) {
        return screen instanceof WorldLossWarning;
    }
}
