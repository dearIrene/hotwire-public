package dev.moon.hotwire.fabric.registration;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.imageio.spi.IIORegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ImageReaders {
    private static final Logger LOGGER = LoggerFactory.getLogger(ImageReaders.class);

    private record Removal(Object provider, Class<?> category) {
    }

    private static final Map<String, List<Removal>> REMOVED = new ConcurrentHashMap<>();

    private ImageReaders() {
    }

    public static void takeOut(Collection<String> mods) {
        IIORegistry registry = IIORegistry.getDefaultInstance();
        for (Iterator<Class<?>> categories = registry.getCategories(); categories.hasNext(); ) {
            Class<?> category = categories.next();
            List<Object> providers = new ArrayList<>();
            registry.getServiceProviders(category, false).forEachRemaining(providers::add);
            for (Object provider : providers) {
                String mod = GameRegistrations.findCodeMod(provider.getClass());
                if (mod != null && mods.contains(mod)) {
                    takeOutOf(registry, provider, category);
                    REMOVED.computeIfAbsent(mod, any -> new ArrayList<>()).add(new Removal(provider, category));
                    LOGGER.info("[Hotwire] {}'s {} is taken out of Java's image registry", mod,
                            provider.getClass().getSimpleName());
                }
            }
        }
    }

    public static void putBack(Collection<String> mods) {
        IIORegistry registry = IIORegistry.getDefaultInstance();
        for (String mod : mods) {
            for (Removal one : REMOVED.getOrDefault(mod, List.of())) {
                putInto(registry, one.provider(), one.category());
            }
            REMOVED.remove(mod);
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> void takeOutOf(IIORegistry registry, Object provider, Class<T> category) {
        registry.deregisterServiceProvider((T) provider, category);
    }

    @SuppressWarnings("unchecked")
    private static <T> void putInto(IIORegistry registry, Object provider, Class<T> category) {
        registry.registerServiceProvider((T) provider, category);
    }
}
