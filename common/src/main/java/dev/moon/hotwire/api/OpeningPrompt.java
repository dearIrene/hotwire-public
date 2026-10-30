package dev.moon.hotwire.api;

import java.util.List;

public interface OpeningPrompt {
    String getTitle();

    List<String> getLines();

    String getAcceptText();

    String getOpenHotwireText();

    int getWaitSeconds();

    void accept();

    void acceptAndOpenHotwire();
}
