package dev.moon.hotwire.fabric.mixin.core.brigadier.tree;

import com.mojang.brigadier.tree.CommandNode;
import java.util.Map;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = CommandNode.class, remap = false)
public interface CommandNodeAccessor {
    @Accessor("children")
    Map<String, Object> hotwire$getChildren();

    @Accessor("literals")
    Map<String, Object> hotwire$getLiterals();
}
