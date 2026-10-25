package dev.moon.hotwire.fabric;

import dev.moon.hotwire.api.v1.HotwireApi;
import dev.moon.hotwire.api.v1.ModState;
import dev.moon.hotwire.api.v1.SwitchResult;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;

public final class HotwireApiImpl implements HotwireApi {
    private static final int REVISION = 1;

    @Override
    public int getRevision() {
        return REVISION;
    }

    @Override
    public List<ModState> getMods() {
        HotwireEngine engine = HotwireClient.findEngine();
        if (engine == null) {
            return List.of();
        }
        return engine.getMods().stream().map(mod -> new ModState(mod.id(), mod.name(), mod.version(), mod.on(),
                ModPolicy.isSwitchable(mod.id()))).toList();
    }

    @Override
    public Optional<ModState> findMod(String id) {
        return this.getMods().stream().filter(mod -> mod.id().equals(id)).findFirst();
    }

    @Override
    public CompletableFuture<SwitchResult> turnOff(Collection<String> ids) {
        return switchFor(true, ids);
    }

    @Override
    public CompletableFuture<SwitchResult> turnOn(Collection<String> ids) {
        return switchFor(false, ids);
    }

    @SuppressWarnings("FutureReturnValueIgnored")
    private static CompletableFuture<SwitchResult> switchFor(boolean off, Collection<String> ids) {
        List<String> asked = List.copyOf(ids);
        CompletableFuture<SwitchResult> ended = new CompletableFuture<>();
        Minecraft.getInstance().execute(() -> {
            HotwireEngine engine = HotwireClient.findEngine();
            if (engine == null) {
                ended.complete(new SwitchResult(SwitchResult.Kind.REFUSED, List.of(), "Hotwire is still starting."));
                return;
            }
            engine.switchFor(off, asked).whenComplete((result, error) -> {
                if (error != null) {
                    ended.completeExceptionally(error);
                } else {
                    ended.complete(result);
                }
            });
        });
        return ended;
    }
}
