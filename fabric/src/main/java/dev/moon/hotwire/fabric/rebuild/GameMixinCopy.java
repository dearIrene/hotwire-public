package dev.moon.hotwire.fabric.rebuild;

import com.google.common.collect.ImmutableList;
import com.google.gson.Gson;
import dev.moon.hotwire.fabric.add.FailedAddJars;
import dev.moon.hotwire.mixin_copy.ExtensionFolder;
import dev.moon.hotwire.mixin_copy.MixinCopy;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import net.fabricmc.loader.api.FabricLoader;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.commons.Remapper;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.util.CheckClassAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.MixinEnvironment;

public final class GameMixinCopy {
    private static final Logger LOGGER = LoggerFactory.getLogger(GameMixinCopy.class);

    private static final List<String> SHARED = List.of("net.fabricmc.loader.api.", "net.fabricmc.api.",
            "net.fabricmc.loader.impl.launch.FabricLauncher", "org.apache.logging.log4j.", "org.slf4j.");

    private static final List<String> EXTENSIONS = List.of("com.llamalad7.mixinextras.MixinExtrasBootstrap",
            "com.bawnorton.mixinsquared.MixinSquaredBootstrap",

            "gg.essential.lib.mixinextras.MixinExtrasBootstrap");

    private GameMixinCopy() {
    }

    public static MixinCopy create(MixinInternals mixin, MixinCopy.Decorations decorations,
            Function<String, byte @Nullable []> originalOf) {
        ClassLoader game = GameMixinCopy.class.getClassLoader();

        MixinCopy copy = new MixinCopy(findJars(), game, SHARED, mixin.getSessionId(),
                internal -> {
                    if (FailedAddJars.isHiding(internal)) {
                        return null;
                    }
                    byte[] bytes = originalOf.apply(internal);
                    return FailedAddJars.removeHiddenInterfaces(bytes != null ? bytes : mixin.findMadeClass(internal));
                },
                FabricLoader.getInstance().getEnvironmentType().name(), decorations);
        copy.foldInto(new ExtensionFolder(findLaunchedExtensions()));
        LivePluginShapes.bind(mixin);
        copy.runPluginsLiveWith(LivePluginShapes::findByPackage, LivePluginShapes::findMixin);
        copy.remapWith(mixin.findObfuscationContext());
        copy.setLevel(MixinEnvironment.getCompatibilityLevel().name());
        LOGGER.info("[Hotwire] Mixin's copy resolves refmaps in the game's context {}", mixin.findObfuscationContext());
        return copy;
    }

    static List<Path> findJars() {
        Set<Path> jars = new LinkedHashSet<>();
        for (Class<?> type : List.of(MixinEnvironment.class, ClassReader.class, ClassNode.class, Remapper.class,
                Analyzer.class, CheckClassAdapter.class, Gson.class, ImmutableList.class)) {
            jars.add(findJarOf(type));
        }
        for (String extension : EXTENSIONS) {
            try {
                jars.add(findJarOf(Class.forName(extension, false, GameMixinCopy.class.getClassLoader())));
            } catch (ClassNotFoundException absent) {
            }
        }
        return new ArrayList<>(jars);
    }

    private static List<String> findLaunchedExtensions() {
        List<String> launched = new ArrayList<>();
        for (String extension : EXTENSIONS) {
            try {
                Class.forName(extension, false, GameMixinCopy.class.getClassLoader());
                launched.add(extension.substring(0, extension.lastIndexOf('.') + 1).replace('.', '/'));
            } catch (ClassNotFoundException absent) {
            }
        }
        return launched;
    }

    private static Path findJarOf(Class<?> type) {
        CodeSource source = type.getProtectionDomain().getCodeSource();
        if (source == null) {
            throw new IllegalStateException("[Hotwire] cannot tell which jar " + type.getName() + " came from");
        }
        try {
            return Path.of(source.getLocation().toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException("[Hotwire] " + type.getName() + " came from " + source.getLocation(), e);
        }
    }
}
