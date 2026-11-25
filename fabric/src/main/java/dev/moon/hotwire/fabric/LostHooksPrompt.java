package dev.moon.hotwire.fabric;

import dev.moon.hotwire.fabric.screen.WebLinks;
import dev.moon.hotwire.fabric.version.Spellings;
import dev.moon.hotwire.ui.Lang;
import dev.moon.hotwire.ui.pages.SettingsPage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

final class LostHooksPrompt {
    private static final long ASK_EVERY_MS = 200;
    private static boolean telling;
    private static volatile boolean told;

    private LostHooksPrompt() {
    }

    static void tellWhenReady(Minecraft started) {
        LostHooks.setOnLost(() -> tell(started));
        if (LostHooks.findLost() != null) {
            tell(started);
        }
    }

    private static synchronized void tell(Minecraft started) {
        if (telling) {
            return;
        }
        telling = true;
        Thread waiter = new Thread(() -> {
            while (!told) {
                started.execute(LostHooksPrompt::showWhenReady);
                try {
                    Thread.sleep(ASK_EVERY_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }, "hotwire-lost-hooks");
        waiter.setDaemon(true);
        waiter.start();
    }

    private static void showWhenReady() {
        Screen under = ModViews.openScreen();
        if (told || ModViews.openOverlay() != null || under == null) {
            return;
        }
        told = true;
        Spellings.show(new ConfirmScreen(join -> {
            if (join) {
                WebLinks.open(SettingsPage.DISCORD);
            }
            Spellings.show(under);
        }, Component.literal(Lang.get("hotwire.lost_hooks.title")),
                Component.literal(Lang.get("hotwire.lost_hooks.body")),
                Component.literal(Lang.get("hotwire.lost_hooks.join")),
                Component.literal(Lang.get("hotwire.lost_hooks.close"))));
    }
}
