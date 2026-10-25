package dev.moon.hotwire.fabric.registration;

//? if >=1.21.9 {
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.platform.cursor.CursorType;
import dev.moon.hotwire.redefine.FieldSlots;
//?}
import java.util.Set;
//? if >=1.21.9 {
import net.minecraft.client.Minecraft;
//?}

public final class WindowCursor {
    private WindowCursor() {
    }

    public static void chooseAgainFor(Set<String> patched) {
        //? if >=1.21.9 {
        if (patched.stream().noneMatch(name -> name.startsWith("com/mojang/blaze3d/platform/Window")
                || name.startsWith("com/mojang/blaze3d/platform/cursor/"))) {
            return;
        }
        Minecraft game = Minecraft.getInstance();
        game.execute(() -> {
            Window window = game.getWindow();
            for (FieldSlots.Slot field : Fields.getDeclared(Window.class)) {
                try {
                    if (!field.isStatic() && field.getType() == CursorType.class) {
                        field.set(window, null);
                    }
                } catch (IllegalAccessException | RuntimeException e) {
                }
            }
            window.selectCursor(CursorType.DEFAULT);
        });
        //?}
    }
}
