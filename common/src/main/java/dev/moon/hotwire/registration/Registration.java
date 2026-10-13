package dev.moon.hotwire.registration;

public interface Registration {
    String getKind();

    String describe();

    void remove();

    void restore();

    default boolean isInGame() {
        return true;
    }
}
