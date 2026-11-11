package dev.moon.hotwire.dev;

public record DevExtras(boolean tick, boolean memory, boolean copyRows, boolean sizes) {
    public static final DevExtras ALL = new DevExtras(true, true, true, true);
}
