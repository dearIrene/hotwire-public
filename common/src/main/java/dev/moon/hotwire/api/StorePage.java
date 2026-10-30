package dev.moon.hotwire.api;

import java.util.List;

public record StorePage(List<StoreMod> mods, int total) {
    public StorePage {
        mods = List.copyOf(mods);
    }
}
