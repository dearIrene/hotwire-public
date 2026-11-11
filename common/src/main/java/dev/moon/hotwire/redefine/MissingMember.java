package dev.moon.hotwire.redefine;

public final class MissingMember extends NoSuchMethodError {
    private static final long serialVersionUID = 1L;

    MissingMember(String message) {
        super(message);
    }
}
