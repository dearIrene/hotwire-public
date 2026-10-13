package dev.moon.hotwire.fabric.registration;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

final class SuperclassNames {
    private static final ClassValue<List<String>> NAMES = new ClassValue<>() {
        @Override
        protected List<String> computeValue(Class<?> type) {
            List<String> names = new ArrayList<>();
            for (Class<?> at = type; at != null && at != Object.class; at = at.getSuperclass()) {
                names.add(at.getName().replace('.', '/'));
            }
            return List.copyOf(names);
        }
    };

    private SuperclassNames() {
    }

    static boolean containsAny(Class<?> type, Set<String> names) {
        for (String name : NAMES.get(type)) {
            if (names.contains(name)) {
                return true;
            }
        }
        return false;
    }
}
