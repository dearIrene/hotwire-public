package dev.moon.hotwire.classfile;

public final class GameNames {
    public static final String HOTWIRE = "hotwire";

    public static final String MINECRAFT = "minecraft";
    private GameNames() {
    }

    public static boolean isGame(String binaryName) {
        return binaryName.startsWith("net.minecraft.") || binaryName.startsWith("com.mojang.");
    }

    public static boolean isGameInternal(String internalName) {
        return internalName.startsWith("net/minecraft/") || internalName.startsWith("com/mojang/");
    }

    public static boolean isMinecraft(String binaryName) {
        return binaryName.startsWith("net.minecraft.");
    }

    public static boolean isMinecraftInternal(String internalName) {
        return internalName.startsWith("net/minecraft/");
    }
}
