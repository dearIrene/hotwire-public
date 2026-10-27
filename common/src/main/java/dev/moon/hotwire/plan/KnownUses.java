package dev.moon.hotwire.plan;

import java.util.Set;

public interface KnownUses {
    KnownUses NONE = new KnownUses() {
        @Override
        public Set<String> findUsersOf(String used) {
            return Set.of();
        }

        @Override
        public Set<String> findUsedBy(String user) {
            return Set.of();
        }
    };

    Set<String> findUsersOf(String used);

    Set<String> findUsedBy(String user);
}
