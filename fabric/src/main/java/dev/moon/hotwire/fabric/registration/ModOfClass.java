package dev.moon.hotwire.fabric.registration;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModOrigin;
import org.jspecify.annotations.Nullable;

public final class ModOfClass {
    private final Map<Path, String> byJar = new HashMap<>();

    private final Map<Path, String> shipperOf = new HashMap<>();

    private final AtomicInteger learned = new AtomicInteger();
    private final ClassValue<Answer> answers = new ClassValue<>() {
        @Override
        protected Answer computeValue(Class<?> type) {
            int before = ModOfClass.this.learned.get();
            return new Answer(ModOfClass.this.lookUp(type), before);
        }
    };

    private record Answer(@Nullable String mod, int learned) {
    }

    private final ClassValue<Optional<String>> shippers = new ClassValue<>() {
        @Override
        protected Optional<String> computeValue(Class<?> type) {
            Path jar = findJarOf(type);
            return Optional.ofNullable(jar == null ? null : ModOfClass.this.shipperOf.get(jar));
        }
    };

    public ModOfClass(FabricLoader loader, Predicate<String> nestedOwned) {
        for (ModContainer mod : loader.getAllMods()) {
            if (mod.getOrigin().getKind() == ModOrigin.Kind.PATH) {
                for (Path path : mod.getOrigin().getPaths()) {
                    this.byJar.putIfAbsent(path.toAbsolutePath().normalize(), mod.getMetadata().getId());
                }
            }
            ModContainer top = mod;
            while (top.getOrigin().getKind() == ModOrigin.Kind.NESTED) {
                ModContainer parent = loader.getModContainer(top.getOrigin().getParentModId()).orElse(null);
                if (parent == null) {
                    break;
                }
                top = parent;
            }
            for (Path root : top.equals(mod) ? List.<Path>of() : mod.getRootPaths()) {
                Path jar = findJarOf(root);
                if (jar != null) {
                    this.shipperOf.put(jar, top.getMetadata().getId());
                }
            }

            for (Path root : nestedOwned.test(mod.getMetadata().getId()) ? mod.getRootPaths() : List.<Path>of()) {
                Path jar = findJarOf(root);
                if (jar != null) {
                    this.byJar.putIfAbsent(jar, mod.getMetadata().getId());
                }
            }
        }
    }

    public static @Nullable Path findJarOf(Path root) {
        URI uri = root.toUri();
        if (!"jar".equals(uri.getScheme())) {
            return null;
        }
        String inner = uri.getRawSchemeSpecificPart();
        int bang = inner.indexOf("!/");
        try {
            return Path.of(URI.create(bang < 0 ? inner : inner.substring(0, bang))).toAbsolutePath().normalize();
        } catch (IllegalArgumentException | FileSystemNotFoundException e) {
            return null;
        }
    }

    synchronized void learn(Path jar, String modId) {
        this.byJar.put(jar.toAbsolutePath().normalize(), modId);
        this.learned.incrementAndGet();
    }

    @Nullable String find(Class<?> type) {
        Answer answer = this.answers.get(type);
        if (answer.mod() == null && answer.learned() != this.learned.get()) {
            this.answers.remove(type);
            answer = this.answers.get(type);
        }
        return answer.mod();
    }

    @Nullable String findShippedBy(Class<?> type) {
        return this.shippers.get(type).orElse(null);
    }

    private static @Nullable Path findJarOf(Class<?> type) {
        CodeSource source = type.getProtectionDomain().getCodeSource();
        URL location = source == null ? null : source.getLocation();
        try {
            return location == null || !"file".equals(location.getProtocol()) ? null
                    : Path.of(location.toURI()).toAbsolutePath().normalize();
        } catch (URISyntaxException | IllegalArgumentException e) {
            return null;
        }
    }

    private synchronized @Nullable String lookUp(Class<?> type) {
        CodeSource source = type.getProtectionDomain().getCodeSource();
        URL location = source == null ? null : source.getLocation();
        if (location == null || !"file".equals(location.getProtocol())) {
            return null;
        }
        try {
            return this.byJar.get(Path.of(location.toURI()).toAbsolutePath().normalize());
        } catch (URISyntaxException | IllegalArgumentException e) {
            return null;
        }
    }
}
