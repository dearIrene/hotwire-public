package dev.moon.hotwire.fabric.screen;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
//? if >=26.3 {
import java.nio.ByteBuffer;
import org.lwjgl.sdl.SDLDialog;
import org.lwjgl.sdl.SDL_DialogFileCallback;
import org.lwjgl.sdl.SDL_DialogFileFilter;
import org.lwjgl.system.MemoryUtil;
//?} else {
/*import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;
*///?}
import org.jspecify.annotations.Nullable;

public final class ModFileChooser {
    private ModFileChooser() {
    }

    public static void choose(Consumer<Path> picked) {
        String start = findStartFolder();
        //? if >=26.3 {
        ByteBuffer name = MemoryUtil.memUTF8("Mod jars");
        ByteBuffer pattern = MemoryUtil.memUTF8("jar");
        SDL_DialogFileFilter.Buffer filters = SDL_DialogFileFilter.calloc(1);
        filters.get(0).name(name).pattern(pattern);
        SDL_DialogFileCallback[] callback = new SDL_DialogFileCallback[1];
        callback[0] = SDL_DialogFileCallback.create((userdata, files, filter) -> {
            String path = files == 0 ? null : MemoryUtil.memUTF8Safe(MemoryUtil.memGetAddress(files));
            filters.free();
            MemoryUtil.memFree(name);
            MemoryUtil.memFree(pattern);
            callback[0].free();
            deliver(path, picked);
        });
        SDLDialog.SDL_ShowOpenFileDialog(callback[0], 0, Minecraft.getInstance().getWindow().handle(), filters, start,
                false);
        //?} else {
        /*// tinyfd waits for the player: it asks on a thread of its own, and the game draws on.
        Thread asking = new Thread(() -> {
            String path;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                PointerBuffer patterns = stack.mallocPointer(1);
                patterns.put(stack.UTF8("*.jar")).flip();
                path = TinyFileDialogs.tinyfd_openFileDialog("Add a mod", start, patterns, "Mod jars", false);
            }
            deliver(path, picked);
        }, "hotwire-file-chooser");
        asking.setDaemon(true);
        asking.start();
        *///?}
    }

    private static void deliver(@Nullable String path, Consumer<Path> picked) {
        if (path != null && !path.isBlank()) {
            Path jar = Path.of(path);
            Minecraft.getInstance().execute(() -> picked.accept(jar));
        }
    }

    private static String findStartFolder() {
        Path home = Path.of(System.getProperty("user.home"));
        Path downloads = home.resolve("Downloads");
        return (Files.isDirectory(downloads) ? downloads : home) + File.separator;
    }
}
