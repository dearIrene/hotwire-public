package dev.moon.hotwire.mixin_copy;

import org.jspecify.annotations.Nullable;

public final class MixinCopyException extends Exception {
    private static final long serialVersionUID = 1L;

    public MixinCopyException(@Nullable String message, @Nullable Throwable cause) {
        super(message, cause);
    }
}
