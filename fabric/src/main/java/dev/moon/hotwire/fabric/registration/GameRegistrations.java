package dev.moon.hotwire.fabric.registration;

import dev.moon.hotwire.registration.RegistrationJournal;

public final class GameRegistrations {
    private static final RegistrationJournal JOURNAL = new RegistrationJournal();

    private GameRegistrations() {
    }

    public static RegistrationJournal getJournal() {
        return JOURNAL;
    }
}
