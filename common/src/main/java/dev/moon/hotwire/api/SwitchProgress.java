package dev.moon.hotwire.api;

public record SwitchProgress(boolean running, double fraction, String step, int done, int total, String subject) {
}
