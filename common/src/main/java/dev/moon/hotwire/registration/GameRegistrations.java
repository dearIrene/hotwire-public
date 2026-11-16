package dev.moon.hotwire.registration;

public final class GameRegistrations {
    private static final RegistrationJournal JOURNAL = new RegistrationJournal();

    private GameRegistrations() {
    }

    public static RegistrationJournal getJournal() {
        return JOURNAL;
    }
}
