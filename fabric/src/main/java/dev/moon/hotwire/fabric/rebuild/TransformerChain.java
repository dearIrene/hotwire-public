package dev.moon.hotwire.fabric.rebuild;

import dev.moon.hotwire.jvm.PrivateField;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Predicate;
import net.fabricmc.loader.impl.launch.FabricLauncher;
import net.fabricmc.loader.impl.launch.FabricLauncherBase;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class TransformerChain {
    private static final Logger LOGGER = LoggerFactory.getLogger(TransformerChain.class);

    private static final ThreadLocal<@Nullable String> ALONE = new ThreadLocal<>();

    private final List<Object> wrappers;
    private final List<String> owners;
    private final Object mixins;
    private final Method transformBytes;
    private final Set<String> bypassed = ConcurrentHashMap.newKeySet();

    private final Map<String, byte[]> loadedWhileOff = new ConcurrentHashMap<>();

    private TransformerChain(List<Object> wrappers, List<String> owners, Object mixins, Method transformBytes) {
        this.wrappers = List.copyOf(wrappers);
        this.owners = List.copyOf(owners);
        this.mixins = mixins;
        this.transformBytes = transformBytes;
    }

    public static @Nullable TransformerChain bind(Function<Class<?>, @Nullable String> modOf) {
        try {
            FabricLauncher launcher = FabricLauncherBase.getLauncher();
            Object knot = PrivateField.of(launcher.getClass(), "classLoader").get(launcher, Object.class);
            Field head = knot.getClass().getDeclaredField("mixinTransformer");
            head.setAccessible(true);
            List<Object> wrappers = new ArrayList<>();
            List<Field> inners = new ArrayList<>();
            List<String> owners = new ArrayList<>();
            Object at = Objects.requireNonNull(head.get(knot), "no transformer in Knot");
            while (!MixinInternals.isMixins(at)) {
                Field inner = MixinInternals.findInnerField(at);
                if (inner == null || wrappers.size() == 4) {
                    LOGGER.warn("[Hotwire] Knot's transformer {} wraps no Mixin Hotwire can find; a mod's transformer"
                            + " runs as it is", at.getClass().getName());
                    return null;
                }
                wrappers.add(at);
                inners.add(inner);
                owners.add(Objects.requireNonNullElse(modOf.apply(at.getClass()), ""));
                at = Objects.requireNonNull(inner.get(at), "a wrapper wraps nothing");
            }
            if (wrappers.isEmpty()) {
                return null;
            }
            Class<?> face = head.getType();
            TransformerChain chain = new TransformerChain(wrappers, owners, at, face.getMethod("transformClassBytes",
                    String.class, String.class, byte[].class));
            head.set(knot, chain.link(0, face));
            for (int index = 0; index < wrappers.size(); index++) {
                inners.get(index).set(wrappers.get(index), chain.link(index + 1, face));
            }
            LOGGER.info("[Hotwire] Mixin runs inside the transformers of {}", owners);
            return chain;
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.warn("[Hotwire] Knot's transformers could not be reached; a mod's transformer stays as it is: {}",
                    e.toString());
            return null;
        }
    }

    List<String> getOwners() {
        return this.owners;
    }

    public byte[] apply(String internal, byte[] mixed, Predicate<String> on) {
        String name = internal.replace('/', '.');
        byte[] bytes = mixed;
        for (int index = this.wrappers.size() - 1; index >= 0; index--) {
            String owner = this.owners.get(index);
            if (!owner.isEmpty() && !on.test(owner)) {
                continue;
            }
            ALONE.set(name);
            try {
                bytes = (byte[]) this.transformBytes.invoke(this.wrappers.get(index), name, name, bytes);
            } catch (ReflectiveOperationException | RuntimeException e) {
                LOGGER.warn("[Hotwire] {}'s transformer would not run on {} again; it is mixed without it",
                        this.owners.get(index), name, e);
            } finally {
                ALONE.remove();
            }
        }
        return bytes;
    }

    public void passBy(Collection<String> mods) {
        for (String owner : this.owners) {
            if (mods.contains(owner)) {
                this.bypassed.add(owner);
            }
        }
    }

    public void runAgain(Collection<String> mods) {
        this.bypassed.removeAll(mods);
        if (this.bypassed.isEmpty()) {
            this.loadedWhileOff.clear();
        }
    }

    boolean wraps(Collection<String> mods) {
        for (String owner : this.owners) {
            if (mods.contains(owner)) {
                return true;
            }
        }
        return false;
    }

    Map<String, byte[]> getLoadedWhileOff() {
        return Map.copyOf(this.loadedWhileOff);
    }

    private Object link(int from, Class<?> face) {
        return Proxy.newProxyInstance(face.getClassLoader(), new Class<?>[] {face}, new Link(from));
    }

    private final class Link implements InvocationHandler {
        private final int from;

        Link(int from) {
            this.from = from;
        }

        @Override
        @SuppressWarnings("ReferenceEquality")
        public @Nullable Object invoke(Object proxy, Method method, @Nullable Object[] args) throws Throwable {
            switch (method.getName()) {
                case "equals" -> {
                    return args != null && proxy == args[0];
                }
                case "hashCode" -> {
                    return System.identityHashCode(proxy);
                }
                case "toString" -> {
                    return "Hotwire's link to " + this.getNext();
                }
                default -> {
                }
            }
            boolean transforms = method.getName().startsWith("transformClass") && args != null && args.length == 3;
            if (transforms && Objects.equals(args[method.getName().equals("transformClassBytes") ? 0 : 1],
                    ALONE.get())) {
                if (method.getReturnType() == boolean.class) {
                    return false;
                }
                return args[2];
            }
            Object next = this.getNext();
            Object result;
            try {
                result = method.invoke(next, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
            if (next == TransformerChain.this.mixins && transforms && !TransformerChain.this.bypassed.isEmpty()
                    && method.getName().equals("transformClassBytes") && result instanceof byte[] mixed) {
                TransformerChain.this.loadedWhileOff.put((String) args[0], mixed);
            }
            return result;
        }

        private Object getNext() {
            for (int index = this.from; index < TransformerChain.this.wrappers.size(); index++) {
                if (!TransformerChain.this.bypassed.contains(TransformerChain.this.owners.get(index))) {
                    return TransformerChain.this.wrappers.get(index);
                }
            }
            return TransformerChain.this.mixins;
        }
    }
}
