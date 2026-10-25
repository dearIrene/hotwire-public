package dev.moon.hotwire.api.v1;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public interface HotwireApi {
    static HotwireApi getInstance() {
        return ApiHolder.INSTANCE;
    }

    int getRevision();

    List<ModState> getMods();

    Optional<ModState> findMod(String id);

    CompletableFuture<SwitchResult> turnOff(Collection<String> ids);

    CompletableFuture<SwitchResult> turnOn(Collection<String> ids);
}
