package dev.moon.hotwire.fabric.add;

import dev.moon.hotwire.jar.AccessWidenerText;
import java.util.function.Predicate;
import net.fabricmc.loader.impl.FabricLoaderImpl;
import net.fabricmc.loader.impl.launch.FabricLauncherBase;
import net.fabricmc.loader.impl.lib.classtweaker.api.ClassTweaker;
import net.fabricmc.loader.impl.lib.classtweaker.api.ClassTweakerReader;
import net.fabricmc.loader.impl.lib.classtweaker.reader.ClassTweakerFormatException;

public final class LoaderClassTweaker {
    private LoaderClassTweaker() {
    }

    static void check(AccessWidenerText text, String modName) {
        read(ClassTweaker.newInstance(), text, text.getBytes(), modName);
    }

    static void apply(AccessWidenerText text, String modName, Predicate<String> isLoaded) {
        read(FabricLoaderImpl.INSTANCE.getClassTweaker(), text, text.getBytesWithout(isLoaded), modName);
    }

    private static void read(ClassTweaker into, AccessWidenerText text, byte[] bytes, String modName) {
        String namespace = FabricLauncherBase.getLauncher().getMappingConfiguration().getRuntimeNamespace();
        try {
            ClassTweakerReader.create(into).read(bytes, namespace);
        } catch (ClassTweakerFormatException e) {
            throw new IllegalArgumentException(modName + "'s access widener " + text.getPath()
                    + " cannot be read by the loader: " + e.getMessage(), e);
        }
    }
}
