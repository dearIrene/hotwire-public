package dev.moon.hotwire.fabric.screen;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
//? if >=26.3 {
import java.nio.IntBuffer;
import org.lwjgl.sdl.SDLMessageBox;
import org.lwjgl.sdl.SDL_MessageBoxButtonData;
import org.lwjgl.sdl.SDL_MessageBoxData;
import org.lwjgl.system.MemoryStack;
//?} else {
/*import org.lwjgl.util.tinyfd.TinyFileDialogs;
*///?}

public final class CrashPopup {
    private static final Logger LOGGER = LoggerFactory.getLogger(CrashPopup.class);

    private CrashPopup() {
    }

    public static boolean show(String title, String message, String showFile, String close, String ask) {
        String shown = message.replace('\'', '’').replace('"', '”');
        try {
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
        } catch (RuntimeException | LinkageError e) {
            LOGGER.warn("[Hotwire] the crash's message box could not be shown: {}", e.toString());
            return false;
        }
    }
}
