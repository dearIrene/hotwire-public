package dev.moon.hotwire.rebuild;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLConnection;
import java.security.CodeSource;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.impl.FabricLoaderImpl;
import net.fabricmc.loader.impl.game.patch.GameTransformer;
import net.fabricmc.loader.impl.launch.FabricLauncher;
import net.fabricmc.loader.impl.launch.FabricLauncherBase;
import net.fabricmc.loader.impl.transformer.FabricTransformer;
import org.jspecify.annotations.Nullable;

public final class PreMixinReads {
    private final GameTransformer patches;
    private final boolean development;
    private final EnvType environment;

    private PreMixinReads(GameTransformer patches, boolean development, EnvType environment) {
        this.patches = patches;
        this.development = development;
        this.environment = environment;
    }

    static @Nullable PreMixinReads bind() {
        try {
            FabricLauncher launcher = FabricLauncherBase.getLauncher();
            return new PreMixinReads(FabricLoaderImpl.INSTANCE.getGameProvider().getEntrypointTransformer(),
                    launcher.isDevelopment(), launcher.getEnvironmentType());
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    byte @Nullable [] read(String internal, Class<?> loaded) {
        String name = internal.replace('/', '.');
        if (name.startsWith("org.apache.logging.log4j")) {
            return null;
        }
        URL file = findFile(internal, loaded);
        if (file == null) {
            return null;
        }
        try {
            byte[] input = this.patches.transform(name);
            if (input == null) {
                input = readWhole(file);
            }
            return FabricTransformer.transform(this.development, this.environment, name, input);
        } catch (IOException | RuntimeException | LinkageError e) {
            return null;
        }
    }

    public static byte @Nullable [] readFile(String internal, Class<?> loaded) {
        URL file = findFile(internal, loaded);
        if (file == null) {
            return null;
        }
        try {
            return readWhole(file);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    static byte[] readWhole(URL file) throws IOException {
        URLConnection from = file.openConnection();
        int length = from.getContentLength();
        try (InputStream in = from.getInputStream()) {
            if (length < 0) {
                return in.readAllBytes();
            }
            byte[] whole = new byte[length];
            int read = in.readNBytes(whole, 0, length);
            int next = read < length ? -1 : in.read();
            if (read == length && next < 0) {
                return whole;
            }

            ByteArrayOutputStream all = new ByteArrayOutputStream(Math.max(read + 1, 32));
            all.write(whole, 0, read);
            if (next >= 0) {
                all.write(next);
                in.transferTo(all);
            }
            return all.toByteArray();
        }
    }

    private static @Nullable URL findFile(String internal, Class<?> loaded) {
        CodeSource source = loaded.getProtectionDomain().getCodeSource();
        URL location = source == null ? null : source.getLocation();
        if (location == null || !"file".equals(location.getProtocol())) {
            return null;
        }
        try {
            URI where = location.toURI();
            String entry = internal + ".class";
            return where.getPath().endsWith("/") ? where.resolve(entry).toURL()
                    : URI.create("jar:" + where + "!/" + entry).toURL();
        } catch (URISyntaxException | IllegalArgumentException | MalformedURLException e) {
            return null;
        }
    }
}
