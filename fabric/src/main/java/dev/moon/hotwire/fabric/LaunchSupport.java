package dev.moon.hotwire.fabric;

public final class LaunchSupport {
    private LaunchSupport() {
    }

    public static boolean isFabricOwn() {
        try {
            Class.forName("net.fabricmc.loader.impl.launch.knot.Knot", false, LaunchSupport.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }
}
