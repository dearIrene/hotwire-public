package dev.moon.hotwire.api;

public record ApiStatus(State state, double progress, String message) {
    public enum State {
        STARTING,
        READY,

        BUSY,

        UNAVAILABLE
    }
}
