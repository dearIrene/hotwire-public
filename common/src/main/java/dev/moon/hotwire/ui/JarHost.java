package dev.moon.hotwire.ui;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

public interface JarHost {
    void onJarPicked(Path jar, boolean update);

    void offerAdd(Path jar, CompletableFuture<AddPreview> preview);

    void showMessage(String text, boolean bad, String hint);
}
