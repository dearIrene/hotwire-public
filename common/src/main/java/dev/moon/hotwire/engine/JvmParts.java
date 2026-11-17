package dev.moon.hotwire.engine;

import dev.moon.hotwire.add.AddSupport;
import dev.moon.hotwire.switching.SwitchApplier;
import dev.moon.hotwire.update.UpdateSupport;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;

public record JvmParts(SwitchRunner runner, AddSupport adds, UpdateSupport updates, Instrumentation instrumentation,
        ClassFileTransformer loadTimeMixer, SwitchApplier.Game classes) {
}
