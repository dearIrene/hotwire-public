package dev.moon.hotwire.store;

import dev.moon.hotwire.modrinth.ModrinthClient;
import dev.moon.hotwire.update.UpdateOffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

final class NamedVersions {
    private NamedVersions() {
    }

    static List<UpdateOffer> hold(List<UpdateOffer> offers, Collection<ModrinthClient.Version> installed,
            Function<String, ModrinthClient.@Nullable Version> versionById, Function<String, String> nameOf) {
        List<UpdateOffer> out = new ArrayList<>(offers);
        for (ModrinthClient.Version mod : installed) {
            if (mod.requiredVersions().isEmpty()) {
                continue;
            }

            UpdateOffer own = find(offers, mod.projectId());
            ModrinthClient.Version coming = own == null ? null : versionById.apply(own.versionId());
            ModrinthClient.Version after = coming == null ? mod : coming;
            after.requiredVersions().forEach((project, named) -> {
                UpdateOffer offer = find(out, project);
                if (offer == null || offer.versionId().equals(named)) {
                    return;
                }
                boolean has = installed.stream().anyMatch(other -> other.id().equals(named));
                ModrinthClient.Version version = has ? null : versionById.apply(named);
                out.set(out.indexOf(offer), version == null || version.url().isEmpty()
                        ? offer.withMadeFor(nameOf.apply(mod.projectId()) + " " + after.number())
                        : offer.withVersion(version.number(), version.id(), version.fileName(), version.url(),
                                version.sha512(), version.size(), version.requiredProjects(), version.changelog()));
            });
        }
        return out;
    }

    private static @Nullable UpdateOffer find(List<UpdateOffer> offers, String project) {
        return offers.stream().filter(offer -> offer.projectId().equals(project)).findFirst().orElse(null);
    }
}
