package dev.moon.hotwire.fabric;

import dev.moon.hotwire.add.LoaderModList;
import dev.moon.hotwire.fabric.mixin.core.client.resources.language.LanguageManagerAccessor;
import dev.moon.hotwire.fabric.version.Spellings;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.ClientLanguage;
import net.minecraft.client.resources.language.LanguageInfo;
import net.minecraft.client.resources.language.LanguageManager;
import net.minecraft.locale.Language;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class OffWords {
    private static final Logger LOGGER = LoggerFactory.getLogger(OffWords.class);
    private static final String ENGLISH = "en_us";

    private OffWords() {
    }

    public static List<Resource> leaveOutOff(List<Resource> stack) {
        Set<String> off = LoaderModList.getHidden();
        if (off.isEmpty()) {
            return stack;
        }
        List<Resource> left = stack.stream().filter(resource -> !off.contains(resource.sourcePackId())).toList();
        return left.size() == stack.size() ? stack : left;
    }

    @SuppressWarnings("ReferenceEquality")
    static void loadAgain() {
        Minecraft game = Minecraft.getInstance();
        LanguageManager manager = game.getLanguageManager();
        ResourceManager resources = game.getResourceManager();
        Language was = Language.getInstance();
        long began = System.nanoTime();
        CompletableFuture.supplyAsync(() -> load(manager, resources)).thenAcceptAsync(made -> {
            if (Language.getInstance() == was) {
                Spellings.injectLanguage(made);
                ((LanguageManagerAccessor) manager).hotwire$getReloadCallback().accept(made);
                LOGGER.info("[Hotwire] the language table was made again in {} ms, the game drawing",
                        (System.nanoTime() - began) / 1_000_000);
            }
        }, game).exceptionally(error -> {
            LOGGER.warn("[Hotwire] the language table could not be made again", error);
            return null;
        });
    }

    private static ClientLanguage load(LanguageManager manager, ResourceManager resources) {
        String chosen = manager.getSelected();
        List<String> codes = new ArrayList<>(2);
        codes.add(ENGLISH);
        boolean bidirectional = false;
        if (!chosen.equals(ENGLISH)) {
            LanguageInfo info = manager.getLanguage(chosen);
            if (info != null) {
                codes.add(chosen);
                bidirectional = info.bidirectional();
            }
        }
        return ClientLanguage.loadFrom(resources, codes, bidirectional);
    }
}
