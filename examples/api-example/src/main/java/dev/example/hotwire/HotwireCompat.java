package dev.example.hotwire;

import dev.moon.hotwire.api.HotwireApi;
import dev.moon.hotwire.api.ModState;
import dev.moon.hotwire.api.SwitchPreview;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class HotwireCompat {
    private static final Logger LOGGER = LoggerFactory.getLogger("hotwire-api-example");

    private HotwireCompat() {
    }

    static void listen() {
        HotwireApi.getInstance().addListener(result -> LOGGER.info("a switch ended {}: {}", result.kind(),
                result.switched()));
    }

    static void toggle(String modId, Consumer<String> say) {
        HotwireApi api = HotwireApi.getInstance();

        if (api.showPrompt()) {
            return;
        }
        Optional<ModState> mod = api.findMod(modId);
        if (mod.isEmpty()) {
            say.accept(modId + " is not installed.");
            return;
        }
        if (!mod.get().switchable()) {
            say.accept(mod.get().blocked());
            return;
        }
        boolean off = mod.get().on();
        SwitchPreview preview = off ? api.previewOff(List.of(modId)) : api.previewOn(List.of(modId));
        if (!preview.possible()) {
            say.accept(preview.message());
            return;
        }

        (off ? api.turnOff(List.of(modId)) : api.turnOn(List.of(modId))).thenAccept(result -> say.accept(
                result.isDone() ? (off ? "Turned off " : "Turned on ") + result.switched() : result.message()));
    }
}
