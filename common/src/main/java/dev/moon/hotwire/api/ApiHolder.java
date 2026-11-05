package dev.moon.hotwire.api;

import dev.moon.hotwire.jvm.FamilySlices;
import java.util.ServiceLoader;

final class ApiHolder {
    static final HotwireApi INSTANCE = find();

    private ApiHolder() {
    }

    private static HotwireApi find() {
        String slice = FamilySlices.findRunning();
        if (slice != null && !slice.isEmpty()) {
            try {
                return Class.forName(slice + ".HotwireApiImpl").asSubclass(HotwireApi.class).getConstructor()
                        .newInstance();
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Hotwire's slice " + slice + " has no HotwireApi", e);
            }
        }
        return ServiceLoader.load(HotwireApi.class, HotwireApi.class.getClassLoader()).findFirst()
                .orElseThrow(() -> new IllegalStateException("Hotwire's jar names no HotwireApi service"));
    }
}
