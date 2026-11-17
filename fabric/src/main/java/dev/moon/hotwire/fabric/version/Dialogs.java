package dev.moon.hotwire.fabric.version;

import dev.moon.hotwire.ui.Lang;
import java.net.URI;
import java.nio.file.Path;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;
//? if >=26.3 {
import com.mojang.blaze3d.Blaze3D;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import org.lwjgl.sdl.SDLDialog;
import org.lwjgl.sdl.SDLMessageBox;
import org.lwjgl.sdl.SDL_DialogFileCallback;
import org.lwjgl.sdl.SDL_DialogFileFilter;
import org.lwjgl.sdl.SDL_MessageBoxButtonData;
import org.lwjgl.sdl.SDL_MessageBoxData;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
//?} else if >=1.21.11 {
/*import net.minecraft.util.Util;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;
*///?} else {
/*import net.minecraft.Util;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;
*///?}

public final class Dialogs {
    private Dialogs() {
    }

    public static void openUri(URI uri) {
        //? if >=26.3 {
        Blaze3D.openUri(uri);
        //?} else {
        /*Util.getPlatform().openUri(uri);
        *///?}
    }

    public static void openPath(Path folder) {
        //? if >=26.3 {
        Blaze3D.openPath(folder);
        //?} else {
        /*Util.getPlatform().openPath(folder);
        *///?}
    }

    public static boolean showCrashBox(String title, String shown, String showFile, String close, String ask) {
        //? if >=26.3 {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            SDL_MessageBoxButtonData.Buffer buttons = SDL_MessageBoxButtonData.calloc(2, stack);
            buttons.get(0).flags(SDLMessageBox.SDL_MESSAGEBOX_BUTTON_RETURNKEY_DEFAULT).buttonID(1)
                    .text(stack.UTF8(showFile));
            buttons.get(1).flags(SDLMessageBox.SDL_MESSAGEBOX_BUTTON_ESCAPEKEY_DEFAULT).buttonID(0)
                    .text(stack.UTF8(close));
            SDL_MessageBoxData box = SDL_MessageBoxData.calloc(stack).flags(SDLMessageBox.SDL_MESSAGEBOX_ERROR)
                    .window(0).title(stack.UTF8(title)).message(stack.UTF8(shown)).buttons(buttons);
            IntBuffer pressed = stack.mallocInt(1);
            return SDLMessageBox.SDL_ShowMessageBox(box, pressed) && pressed.get(0) == 1;
        }
        //?} else if >=26.1 {
        /*return TinyFileDialogs.tinyfd_messageBox(title, shown + "\n\n" + ask, "yesno", "error", 1) == 1;
        *///?} else {
        /*return TinyFileDialogs.tinyfd_messageBox(title, shown + "\n\n" + ask, "yesno", "error", true);
        *///?}
    }

    public static void pickJar(String start, Consumer<@Nullable String> answered, Runnable unavailable) {
        //? if >=26.3 {
        ByteBuffer name = MemoryUtil.memUTF8(Lang.get("hotwire.picker.filter"));
        ByteBuffer pattern = MemoryUtil.memUTF8("jar");
        SDL_DialogFileFilter.Buffer filters = SDL_DialogFileFilter.calloc(1);
        filters.get(0).name(name).pattern(pattern);
        SDL_DialogFileCallback[] callback = new SDL_DialogFileCallback[1];
        callback[0] = SDL_DialogFileCallback.create((userdata, files, filter) -> {
            if (files == 0) {
                Minecraft.getInstance().execute(unavailable);
            }
            String path = files == 0 ? null : MemoryUtil.memUTF8Safe(MemoryUtil.memGetAddress(files));
            filters.free();
            MemoryUtil.memFree(name);
            MemoryUtil.memFree(pattern);
            callback[0].free();
            answered.accept(path);
        });
        SDLDialog.SDL_ShowOpenFileDialog(callback[0], 0, Minecraft.getInstance().getWindow().handle(), filters, start,
                false);
        //?} else {
        /*// tinyfd waits for the player: it asks on a thread of its own, and the game draws on.
        Thread asking = new Thread(() -> {
            if (!hasWindow()) {
                Minecraft.getInstance().execute(unavailable);
                return;
            }
            String path;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                PointerBuffer patterns = stack.mallocPointer(1);
                patterns.put(stack.UTF8("*.jar")).flip();
                path = TinyFileDialogs.tinyfd_openFileDialog(Lang.get("hotwire.picker.title"), start, patterns,
                        Lang.get("hotwire.picker.filter"), false);
            }
            answered.accept(path);
        }, "hotwire-file-chooser");
        asking.setDaemon(true);
        asking.start();
        *///?}
    }

    //? if >=26.1 && <26.3 {
    /*// Whether tinyfd has a window to show: a message box titled "tinyfd_query" opens nothing and answers 0 when no
    private static volatile @Nullable Boolean window;

    private static boolean hasWindow() {
        Boolean known = window;
        if (known == null) {
            known = TinyFileDialogs.tinyfd_messageBox("tinyfd_query", "", "ok", "info", 1) != 0;
            window = known;
        }
        return known;
    }
    *///?} else if <26.1 {
    /*// Whether tinyfd has a window to show: a message box titled "tinyfd_query" opens nothing and answers 0 when no
    private static volatile @Nullable Boolean window;

    private static boolean hasWindow() {
        Boolean known = window;
        if (known == null) {
            known = TinyFileDialogs.tinyfd_messageBox("tinyfd_query", "", "ok", "info", true);
            window = known;
        }
        return known;
    }
    *///?}
}
