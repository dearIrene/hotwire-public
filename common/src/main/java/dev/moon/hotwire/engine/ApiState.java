package dev.moon.hotwire.engine;

import dev.moon.hotwire.api.ModState;
import dev.moon.hotwire.switching.CallerBlocks;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

final class ApiState {
    final AtomicInteger switching = new AtomicInteger();

    final AtomicInteger changing = new AtomicInteger();

    final Map<CallerBlocks.Callers, ModRowCache> rowsSeen = new ConcurrentHashMap<>();

    record ModRowCache(long at, long calls, List<ModState> mods) {
    }
}
