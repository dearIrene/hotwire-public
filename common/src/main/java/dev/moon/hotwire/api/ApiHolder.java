package dev.moon.hotwire.api;

import java.util.ServiceLoader;

final class ApiHolder {
    static final HotwireApi INSTANCE = ServiceLoader.load(HotwireApi.class, HotwireApi.class.getClassLoader())
            .findFirst().orElseThrow(() -> new IllegalStateException("Hotwire's jar names no HotwireApi service"));

    private ApiHolder() {
    }
}
