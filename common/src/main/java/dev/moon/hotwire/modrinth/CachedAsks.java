package dev.moon.hotwire.modrinth;

import java.io.IOException;
import java.util.List;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

public final class CachedAsks {
    private final StoreCache cache;

    public CachedAsks(StoreCache cache) {
        this.cache = cache;
    }

    public ModrinthClient.Search search(ModrinthClient client, ModrinthClient.Kind kind, String words, String category,
            int offset, int page) throws IOException {
        String key = client.getScope() + ":" + kind.getProjectType() + ":" + words + ":" + category + ":" + offset
                + (page == StoreModel.PAGE ? "" : ":" + page);
        return this.ask(key, SearchJson::read, SearchJson::write,
                () -> client.search(kind, words, category, offset, page));
    }

    public ModrinthClient.Project findProject(ModrinthClient client, String idOrSlug) throws IOException {
        return this.ask("project:" + idOrSlug, SearchJson::readProject, SearchJson::writeProject,
                () -> client.findProject(idOrSlug));
    }

    public List<ModrinthClient.Version> findVersions(ModrinthClient client, ModrinthClient.Kind kind,
            String projectId) throws IOException {
        return this.ask("versions:" + client.getScope() + ":" + kind.getProjectType() + ":" + projectId,
                SearchJson::readVersions, SearchJson::writeVersions, () -> client.findVersions(kind, projectId));
    }

    public List<String> findGameVersions(ModrinthClient client) throws IOException {
        return this.ask("game-versions", json -> json.lines().toList(), versions -> String.join("\n", versions),
                client::findGameVersions);
    }

    private interface Fetch<T> {
        T get() throws IOException;
    }

    private <T> T ask(String key, Function<String, T> read, Function<T, String> write, Fetch<T> fetch)
            throws IOException {
        StoreCache.Entry saved = this.cache.findTextEntry(StoreCache.Kind.SEARCH, key).orElse(null);
        T cached = saved == null ? null : tryRead(read, saved.text());
        if (saved != null && cached != null && saved.fresh()) {
            return cached;
        }
        try {
            T found = fetch.get();
            this.cache.saveText(StoreCache.Kind.SEARCH, key, write.apply(found));
            return found;
        } catch (IOException e) {
            if (cached != null) {
                return cached;
            }
            throw e;
        }
    }

    private static <T> @Nullable T tryRead(Function<String, T> read, String json) {
        try {
            return read.apply(json);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
