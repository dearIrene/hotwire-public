package dev.moon.hotwire.fabric;

import dev.moon.hotwire.config.HotwireSettings;
import dev.moon.hotwire.engine.EarlyModsReader;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import org.jspecify.annotations.Nullable;

record Early(@Nullable Instrumentation instrumentation, @Nullable ClassFileTransformer watcher,
        HotwireSettings settings, EarlyModsReader mods) {
}
