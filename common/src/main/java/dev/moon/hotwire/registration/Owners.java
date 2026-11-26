package dev.moon.hotwire.registration;

import dev.moon.hotwire.classfile.GameNames;
import dev.moon.hotwire.dev.DevMod;
import dev.moon.hotwire.engine.ModPolicy;
import dev.moon.hotwire.jar.JarPaths;
import dev.moon.hotwire.redefine.Tripwire;
import dev.moon.hotwire.seam.Games;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModDependency;
import net.fabricmc.loader.api.metadata.ModOrigin;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.Type;

public final class Owners {
    private static final String NEOFORGE = "neoforge";
    private static final String DISABLED = ".disabled";
    private static volatile @Nullable CallerOwner callers;
    private static volatile @Nullable Jars jars;
    private static volatile Set<String> never = Set.of();
    private static volatile Set<String> alone = Set.of();

    private static volatile Set<String> shared = Set.of();

    private Owners() {
    }

    private static final class Kind {
        private volatile @Nullable String mod;
        private volatile @Nullable String nested;
        private volatile @Nullable String shipper;

        private volatile int learned = -1;

        private volatile LoadedClasses.@Nullable Kind scanned;
        private volatile Fields.@Nullable FieldCache fields;
    }

    private static final class Jars {
        private final Map<Path, String> byJar = new HashMap<>();

        private final Map<Path, String> shipperOf = new HashMap<>();

        private final Map<Path, String> nestedOf = new HashMap<>();

        private final AtomicInteger learned = new AtomicInteger();

        private final Map<CodeSource, Optional<Path>> jarOf = Collections.synchronizedMap(new WeakHashMap<>());

        private final ClassValue<Kind> answers = new ClassValue<>() {
            @Override
            protected Kind computeValue(Class<?> type) {
                return new Kind();
            }
        };

        Jars(FabricLoader loader, Set<String> nestedOwned) {
            for (DevMod dev : DevMod.find()) {
                for (Path folder : dev.folders()) {
                    for (Path key : JarPaths.findKeysOf(folder)) {
                        this.byJar.putIfAbsent(key, dev.id());
                    }
                }
            }
            for (ModContainer mod : loader.getAllMods()) {
                if (mod.getOrigin().getKind() == ModOrigin.Kind.PATH) {
                    for (Path path : mod.getOrigin().getPaths()) {
                        for (Path key : JarPaths.findKeysOf(path)) {
                            this.byJar.putIfAbsent(key, mod.getMetadata().getId());
                        }
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
                    for (Path key : jar == null ? List.<Path>of() : JarPaths.findKeysOf(jar)) {
                        this.shipperOf.put(key, top.getMetadata().getId());
                        this.nestedOf.put(key, mod.getMetadata().getId());
                    }
                }

                for (Path root : nestedOwned.contains(mod.getMetadata().getId()) ? mod.getRootPaths()
                        : List.<Path>of()) {
                    Path jar = findJarOf(root);
                    for (Path key : jar == null ? List.<Path>of() : JarPaths.findKeysOf(jar)) {
                        this.byJar.putIfAbsent(key, mod.getMetadata().getId());
                    }
                }
            }
        }

        private void answer(Class<?> type, Kind into) {
            int before = this.learned.get();
            CodeSource source = type.getProtectionDomain().getCodeSource();
            Path jar = source == null ? null : this.jarOf.computeIfAbsent(source, Owners::findJarOf).orElse(null);
            String mod = jar == null ? null : this.lookUp(jar);

            if (NEOFORGE.equals(mod) && GameNames.isGame(type.getName())) {
                mod = GameNames.MINECRAFT;
            }
            Path name = jar == null || mod == null ? null : jar.getFileName();
            boolean game = name != null && GameLibraries.has(Type.getInternalName(type), name.toString());
            into.nested = jar == null ? null : this.nestedOf.get(jar);
            into.shipper = jar == null ? null : this.shipperOf.get(jar);
            into.mod = game ? null : mod;
            into.learned = before;
        }

        synchronized void learn(Path jar, String modId) {
            for (Path key : JarPaths.findKeysOf(jar)) {
                this.byJar.put(key, modId);
            }
            this.learned.incrementAndGet();
        }

        Kind find(Class<?> type) {
            Kind answer = this.answers.get(type);
            if (answer.mod == null && answer.learned != this.learned.get()) {
                this.answer(type, answer);
            }
            return answer;
        }

        private synchronized @Nullable String lookUp(Path jar) {
            String mod = this.byJar.get(jar);

            String name = String.valueOf(jar.getFileName());
            return mod != null || !name.endsWith(DISABLED) ? mod
                    : this.byJar.get(jar.resolveSibling(name.substring(0, name.length() - DISABLED.length())));
        }
    }

    public static CallerOwner getCallers() {
        CallerOwner current = callers;
        if (current != null) {
            return current;
        }
        synchronized (Owners.class) {
            current = callers;
            if (current == null) {
                FabricLoader loader = FabricLoader.getInstance();
                never = findNeverSwitched(loader);
                alone = findRunAlone(loader);
                jars = new Jars(loader, never);
                current = new CallerOwner(Owners::findCodeMod, never, findLibraries(loader),
                        Owners::isOfNestedLibrary);
                callers = current;
            }
            return current;
        }
    }

    private static Kind of(Class<?> type) {
        getCallers();
        return Objects.requireNonNull(jars).find(type);
    }

    static Fields.FieldCache findFields(Class<?> type) {
        getCallers();
        Kind kind = Objects.requireNonNull(jars).answers.get(type);
        Fields.FieldCache cache = kind.fields;
        if (cache == null) {
            synchronized (kind) {
                cache = kind.fields;
                if (cache == null) {
                    cache = new Fields.FieldCache();
                    kind.fields = cache;
                }
            }
        }
        return cache;
    }

    static LoadedClasses.Kind findScanned(Class<?> type, Function<Class<?>, LoadedClasses.Kind> note) {
        Kind kind = of(type);
        LoadedClasses.Kind noted = kind.scanned;
        if (noted == null) {
            noted = note.apply(type);
            kind.scanned = noted;
        }
        return noted;
    }

    static long findVersion() {
        Jars current = jars;
        return (current == null ? 0 : current.learned.get()) * 31L + Games.getNow().findRegistrySizes();
    }

    public static boolean isGameOwn(Class<?> type) {
        return GameNames.isGame(type.getName()) && (!type.isHidden() || ValueOwners.findLambdaOwner(type) == null);
    }

    public static @Nullable String findModOf(Class<?> type) {
        return of(type).mod;
    }

    public static @Nullable String findShippedBy(Class<?> type) {
        return of(type).shipper;
    }

    public static @Nullable String findNestedModOf(Class<?> type) {
        return of(type).nested;
    }

    public static boolean isOfOffMod(Class<?> type) {
        String mod = findModOf(type);
        return (mod != null && Tripwire.isWatched(mod)) || Tripwire.isOfOffMod(type);
    }

    private static boolean isOfNestedLibrary(Class<?> type) {
        Kind kind = of(type);
        return kind.mod == null && kind.nested != null
                && (findOwnNested(kind) == null || shared.contains(kind.nested));
    }

    public static @Nullable String findCodeMod(Class<?> type) {
        return findModOrShipper(type);
    }

    private static @Nullable String findOwnNested(Kind kind) {
        String nested = kind.nested;
        return nested != null && isRecordedMod(nested) && (alone.contains(nested) || shared.contains(nested)) ? nested
                : null;
    }

    public static void useShared(Set<String> ids) {
        shared = Set.copyOf(ids);
    }

    private static Set<String> findRunAlone(FabricLoader loader) {
        Set<String> ids = new HashSet<>();
        Map<String, String> tops = new HashMap<>();
        for (ModContainer mod : loader.getAllMods()) {
            ModContainer top = mod;
            while (top.getContainingMod().isPresent()) {
                top = top.getContainingMod().get();
            }
            tops.put(mod.getMetadata().getId(), top.getMetadata().getId());
            if (top.equals(mod)) {
                ids.add(mod.getMetadata().getId());
            }
        }
        for (ModContainer mod : loader.getAllMods()) {
            for (ModDependency dependency : mod.getMetadata().getDependencies()) {
                String top = tops.get(dependency.getModId());
                if (dependency.getKind() == ModDependency.Kind.DEPENDS && top != null
                        && !top.equals(tops.get(mod.getMetadata().getId()))) {
                    ids.add(dependency.getModId());
                }
            }
        }
        return ids;
    }

    public static @Nullable String findModOrShipper(Class<?> type) {
        Kind kind = of(type);
        String mod = kind.mod;
        if (mod == null) {
            mod = findOwnNested(kind);
        }
        if (mod == null) {
            mod = kind.shipper;
        }

        int lambda = mod == null && type.isHidden() ? type.getName().indexOf("$$Lambda") : -1;
        if (lambda < 0) {
            return mod;
        }
        try {
            return findModOrShipper(Class.forName(type.getName().substring(0, lambda), false, type.getClassLoader()));
        } catch (ClassNotFoundException | LinkageError e) {
            return null;
        }
    }

    static boolean isNestedLibrary(Class<?> type) {
        String name = type.getName();
        return Objects.equals(type.getClassLoader(), Owners.class.getClassLoader())
                && !GameNames.isGame(name)
                && !name.startsWith("dev.moon.hotwire.");
    }

    static boolean isLibrary(String owner) {
        return !isRecordedMod(owner) && !ModPolicy.isBuiltIn(owner) && !ModPolicy.isHotwire(owner);
    }

    public static boolean isRecordedMod(@Nullable String mod) {
        getCallers();
        return mod != null && !never.contains(mod);
    }

    public static CallerOwner.@Nullable Owner findOwnerOf(List<CallerOwner.Frame> frames) {
        return getCallers().findFrames(frames);
    }

    public static void learnJar(Path jar, String modId) {
        getCallers();
        Objects.requireNonNull(jars).learn(jar, modId);
        synchronized (Owners.class) {
            FabricLoader loader = FabricLoader.getInstance();
            callers = new CallerOwner(Owners::findCodeMod, findNeverSwitched(loader), findLibraries(loader),
                    Owners::isOfNestedLibrary);
        }
    }

    private static Set<String> findLibraries(FabricLoader loader) {
        Set<String> ids = new HashSet<>();
        for (ModContainer mod : loader.getAllMods()) {
            for (ModDependency dependency : mod.getMetadata().getDependencies()) {
                if (dependency.getKind() == ModDependency.Kind.DEPENDS) {
                    ids.add(dependency.getModId());
                }
            }
        }
        return ids;
    }

    private static Set<String> findNeverSwitched(FabricLoader loader) {
        Set<String> ids = new HashSet<>(ModPolicy.BUILT_IN);
        ids.add(GameNames.HOTWIRE);
        for (ModContainer mod : loader.getAllMods()) {
            String id = mod.getMetadata().getId();

            if (ModPolicy.isFabricApi(id) || ModPolicy.isNeverTouched(id) || ModPolicy.isHotwire(id)) {
                ids.add(id);
            }
        }
        return ids;
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

    static Optional<Path> findJarOf(CodeSource source) {
        URL location = source.getLocation();
        try {
            return location == null || !"file".equals(location.getProtocol()) ? Optional.empty()
                    : Optional.of(Path.of(location.toURI()).toAbsolutePath().normalize());
        } catch (URISyntaxException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
