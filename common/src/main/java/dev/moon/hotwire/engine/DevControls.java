package dev.moon.hotwire.engine;

public interface DevControls extends AutoCloseable {
    void reloadAll();

    void stepBack();

    void flip();

    void openWindow();

    @Override
    void close();
}
