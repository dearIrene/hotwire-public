package dev.moon.hotwire.jvm;

import org.jspecify.annotations.Nullable;

public final class FamilySlices {
    private FamilySlices() {
    }

    public static @Nullable String findRunning() {
        try {
            Object slice = Class.forName("dev.moon.hotwire.family.Start").getMethod("findSlice").invoke(null);
            return slice == null ? "" : (String) slice;
        } catch (ClassNotFoundException e) {
            return null;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Hotwire's release jar cannot say which slice runs", e);
        }
    }
}
