package dev.moon.hotwire.dev;

import java.util.Set;

public record DevDelta(Set<String> put, Set<String> gone) {
    public DevDelta {
        put = Set.copyOf(put);
        gone = Set.copyOf(gone);
    }

    public boolean touches(String entry) {
        return this.put.contains(entry) || this.gone.contains(entry);
    }
}
