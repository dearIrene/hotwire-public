package dev.moon.hotwire.modrinth;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongConsumer;
import java.util.zip.GZIPInputStream;

public final class ModrinthClient {
    public static final String API = "https://api.modrinth.com/v2";
    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    static final String USER_AGENT_PREFIX = "moonshee/hotwire/";
    static final String CONTACT = "https://github.com/moonshee/hotwire";

    static final int GONE = 410;

    public record Hit(String projectId, String slug, String title, String description, String author,
            long downloads, String iconUrl, List<String> categories, String modified) {
    }

    public record Search(List<Hit> hits, int total) {
    }

    public record Project(String id, String slug, String title, String description, String body, String license,
            String licenseUrl, String sourceUrl, long downloads, long followers, String iconUrl) {
    }

    public record Version(String id, String projectId, String number, String name, String fileName, String url,
            String sha512, long size, List<String> gameVersions, List<String> requiredProjects, String changelog) {
    }

    public interface Transport {
        String get(String url) throws IOException;

        String post(String url, String json) throws IOException;

        byte[] fetchBytes(String url) throws IOException;

        default byte[] fetchBytes(String url, LongConsumer onBytes) throws IOException {
            return this.fetchBytes(url);
        }

        default byte[] fetchPicture(String url) throws IOException {
            throw new IOException("no pictures here");
        }
    }

    public static final int MOST_PICTURE_BYTES = 8 * 1024 * 1024;

    public enum Kind {
        MOD("mod"), RESOURCE_PACK("resourcepack"), SHADER("shader"), DATA_PACK("datapack");

        private final String projectType;

        Kind(String projectType) {
            this.projectType = projectType;
        }

        public String getProjectType() {
            return this.projectType;
        }
    }

    private final Transport transport;
    private final String loader;
    private final String gameVersion;
    private final LocalJars local;

    private volatile String browseLoader;
    private volatile String browseVersion;

    public ModrinthClient(String hotwireVersion, String loader, String gameVersion, LocalJars local) {
        this(new LiveTransport(hotwireVersion), loader, gameVersion, local);
    }

    public ModrinthClient(Transport transport, String loader, String gameVersion) {
        this(transport, loader, gameVersion, LocalJars.NONE);
    }

    public ModrinthClient(Transport transport, String loader, String gameVersion, LocalJars local) {
        this.transport = transport;
        this.loader = loader;
        this.gameVersion = gameVersion;
        this.local = local;
        this.browseLoader = loader;
        this.browseVersion = gameVersion;
    }

    public void setBrowsing(String loader, String gameVersion) {
        this.browseLoader = loader;
        this.browseVersion = gameVersion;
    }

    public boolean isBrowsingOwn() {
        return this.browseLoader.equals(this.loader) && this.browseVersion.equals(this.gameVersion);
    }

    public String getBrowseLoader() {
        return this.browseLoader;
    }

    public String getBrowseVersion() {
        return this.browseVersion;
    }

    public String getLoader() {
        return this.loader;
    }

    public String getGameVersion() {
        return this.gameVersion;
    }

    private String findVersionFor(Kind kind) {
        return kind == Kind.DATA_PACK ? this.gameVersion : this.browseVersion;
    }

    public List<String> findGameVersions() throws IOException {
        List<String> out = new ArrayList<>();
        for (JsonElement element : JsonParser.parseString(this.transport.get(API + "/tag/game_version"))
                .getAsJsonArray()) {
            JsonObject version = element.getAsJsonObject();
            if ("release".equals(readText(version, "version_type"))) {
                out.add(readText(version, "version"));
            }
        }
        return out;
    }

    public Search search(Kind kind, String words, String category, int offset, int limit) throws IOException {
        List<List<String>> facets = new ArrayList<>();
        facets.add(List.of("project_type:" + kind.getProjectType()));
        facets.add(this.findLoaders(kind).stream().map(loader -> "categories:" + loader).toList());
        facets.add(List.of("versions:" + this.findVersionFor(kind)));
        if (!category.isEmpty()) {
            facets.add(List.of("categories:" + category));
        }

        JsonArray facetJson = new JsonArray();
        for (List<String> any : facets) {
            JsonArray inner = new JsonArray();
            any.forEach(inner::add);
            facetJson.add(inner);
        }
        String facetText = facetJson.toString();
        String url = API + "/search?query=" + encode(words) + "&facets=" + encode(facetText.toString())
                + "&index=" + (words.isBlank() ? "downloads" : "relevance") + "&offset=" + offset + "&limit="
                + limit;
        JsonObject root = JsonParser.parseString(this.transport.get(url)).getAsJsonObject();
        List<Hit> hits = new ArrayList<>();
        for (JsonElement element : root.getAsJsonArray("hits")) {
            JsonObject hit = element.getAsJsonObject();
            hits.add(new Hit(readText(hit, "project_id"), readText(hit, "slug"), readText(hit, "title"),
                    readText(hit, "description"), readText(hit, "author"), readNumber(hit, "downloads"),
                    readText(hit, "icon_url"), readStrings(hit.get("categories")), readText(hit, "date_modified")));
        }
        return new Search(hits, (int) readNumber(root, "total_hits"));
    }

    public Project findProject(String projectId) throws IOException {
        JsonObject root = JsonParser.parseString(this.transport.get(API + "/project/" + encode(projectId)))
                .getAsJsonObject();
        JsonElement license = root.get("license");
        JsonObject licenseObject = license != null && license.isJsonObject() ? license.getAsJsonObject() : null;
        return new Project(readText(root, "id"), readText(root, "slug"), readText(root, "title"),
                readText(root, "description"), readText(root, "body"),
                licenseObject == null ? "" : readText(licenseObject, "name").isEmpty()
                        ? readText(licenseObject, "id") : readText(licenseObject, "name"),
                licenseObject == null ? "" : readText(licenseObject, "url"), readText(root, "source_url"),
                readNumber(root, "downloads"), readNumber(root, "followers"), readText(root, "icon_url"));
    }

    public List<Version> findVersions(Kind kind, String projectId) throws IOException {
        JsonArray loaders = new JsonArray();
        this.findLoaders(kind).forEach(loaders::add);
        String url = API + "/project/" + encode(projectId) + "/version?loaders=" + encode(loaders.toString())
                + "&game_versions=" + encode("[\"" + this.findVersionFor(kind) + "\"]");
        List<Version> out = new ArrayList<>();
        for (JsonElement element : JsonParser.parseString(this.transport.get(url)).getAsJsonArray()) {
            out.add(readVersion(element.getAsJsonObject()));
        }
        return out;
    }

    private List<String> findLoaders(Kind kind) {
        return switch (kind) {
            case MOD -> List.of(this.browseLoader);
            case RESOURCE_PACK -> List.of("minecraft");
            case SHADER -> List.of("iris", "optifine");
            case DATA_PACK -> List.of("datapack");
        };
    }

    public Map<String, Version> findNewest(Map<String, String> sha512ByName) throws IOException {
        JsonObject body = new JsonObject();
        JsonArray hashes = new JsonArray();
        sha512ByName.values().forEach(hashes::add);
        body.add("hashes", hashes);
        body.addProperty("algorithm", "sha512");
        JsonArray loaders = new JsonArray();
        loaders.add(this.loader);
        body.add("loaders", loaders);
        JsonArray versions = new JsonArray();
        versions.add(this.gameVersion);
        body.add("game_versions", versions);
        JsonObject root = JsonParser.parseString(this.transport.post(API + "/version_files/update", body.toString()))
                .getAsJsonObject();
        Map<String, Version> out = new LinkedHashMap<>();
        sha512ByName.forEach((name, hash) -> {
            JsonElement found = root.get(hash);
            if (found != null && found.isJsonObject()) {
                out.put(name, readVersion(found.getAsJsonObject()));
            }
        });
        return out;
    }

    public Map<String, Version> findByHashes(Map<String, String> sha512ByName) throws IOException {
        JsonObject body = new JsonObject();
        JsonArray hashes = new JsonArray();
        sha512ByName.values().forEach(hashes::add);
        body.add("hashes", hashes);
        body.addProperty("algorithm", "sha512");
        JsonObject root = JsonParser.parseString(this.transport.post(API + "/version_files", body.toString()))
                .getAsJsonObject();
        Map<String, Version> out = new LinkedHashMap<>();
        sha512ByName.forEach((name, hash) -> {
            JsonElement found = root.get(hash);
            if (found != null && found.isJsonObject()) {
                out.put(name, readVersion(found.getAsJsonObject()));
            }
        });
        return out;
    }

    public byte[] fetch(Version version) throws IOException {
        return this.fetch(version, done -> { });
    }

    public byte[] fetch(Version version, LongConsumer onBytes) throws IOException {
        byte[] copy = this.local.find(version.fileName(), version.size(), version.sha512());
        if (copy != null) {
            return copy;
        }
        byte[] bytes = this.transport.fetchBytes(version.url(), onBytes);
        String hash = hashOf(bytes);
        if (!hash.equalsIgnoreCase(version.sha512())) {
            throw new IOException("the download of " + version.fileName() + " does not match Modrinth's hash");
        }
        return bytes;
    }

    public byte[] fetchFromCdn(String url) throws IOException {
        return this.transport.fetchBytes(url);
    }

    public byte[] fetchPicture(String url) throws IOException {
        return this.transport.fetchPicture(url);
    }

    public static String hashOf(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-512").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public Version findVersion(String versionId) throws IOException {
        return readVersion(JsonParser.parseString(this.transport.get(API + "/version/" + encode(versionId)))
                .getAsJsonObject());
    }

    public String getScope() {
        return this.browseLoader + ":" + this.browseVersion;
    }

    public Map<String, String> findChanges(Collection<String> projectIds) throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        List<String> ids = List.copyOf(projectIds);

        for (int at = 0; at < ids.size(); at += 400) {
            JsonArray page = new JsonArray();
            ids.subList(at, Math.min(ids.size(), at + 400)).forEach(page::add);
            for (JsonElement element : JsonParser.parseString(this.transport.get(API + "/projects?ids="
                    + encode(page.toString()))).getAsJsonArray()) {
                JsonObject project = element.getAsJsonObject();
                out.put(readText(project, "id"), readText(project, "updated"));
            }
        }
        return out;
    }

    static Version readVersion(JsonObject version) {
        JsonObject file = null;
        for (JsonElement element : version.getAsJsonArray("files")) {
            JsonObject candidate = element.getAsJsonObject();
            if (file == null || (candidate.has("primary") && candidate.get("primary").getAsBoolean())) {
                file = candidate;
            }
        }
        List<String> required = new ArrayList<>();
        JsonElement dependencies = version.get("dependencies");
        if (dependencies != null && dependencies.isJsonArray()) {
            for (JsonElement element : dependencies.getAsJsonArray()) {
                JsonObject dependency = element.getAsJsonObject();
                if ("required".equals(readText(dependency, "dependency_type")) && dependency.has("project_id")
                        && !dependency.get("project_id").isJsonNull()) {
                    required.add(readText(dependency, "project_id"));
                }
            }
        }
        JsonObject hashes = file == null ? new JsonObject() : file.getAsJsonObject("hashes");
        return new Version(readText(version, "id"), readText(version, "project_id"),
                readText(version, "version_number"), readText(version, "name"),
                file == null ? "" : readText(file, "filename"), file == null ? "" : readText(file, "url"),
                readText(hashes, "sha512"), file == null ? 0 : readNumber(file, "size"),
                readStrings(version.get("game_versions")), required, readText(version, "changelog"));
    }

    private static String readText(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value == null || value.isJsonNull() ? "" : value.getAsString();
    }

    private static long readNumber(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value == null || value.isJsonNull() ? 0 : value.getAsLong();
    }

    private static List<String> readStrings(JsonElement array) {
        List<String> out = new ArrayList<>();
        if (array != null && array.isJsonArray()) {
            array.getAsJsonArray().forEach(element -> out.add(element.getAsString()));
        }
        return out;
    }

    private static String encode(String text) {
        return URLEncoder.encode(text, StandardCharsets.UTF_8);
    }

    private static final class LiveTransport implements Transport {
        private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL).build();
        private final String agent;

        private volatile long openAgainAt;

        LiveTransport(String hotwireVersion) {
            this.agent = USER_AGENT_PREFIX + hotwireVersion + " (" + CONTACT + ")";
        }

        @Override
        public String get(String url) throws IOException {
            return this.send(HttpRequest.newBuilder(URI.create(url)).header("Accept-Encoding", "gzip").GET(),
                    HttpResponse.BodyHandlers.ofByteArray(), LiveTransport::readText);
        }

        @Override
        public String post(String url, String json) throws IOException {
            return this.send(HttpRequest.newBuilder(URI.create(url)).header("Content-Type", "application/json")
                    .header("Accept-Encoding", "gzip").POST(HttpRequest.BodyPublishers.ofString(json)),
                    HttpResponse.BodyHandlers.ofByteArray(), LiveTransport::readText);
        }

        private static String readText(HttpResponse<byte[]> response) throws IOException {
            byte[] body = response.body();
            boolean zipped = response.headers().firstValue("Content-Encoding").orElse("").contains("gzip");
            if (zipped) {
                try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(body))) {
                    body = in.readAllBytes();
                }
            }
            return new String(body, StandardCharsets.UTF_8);
        }

        @Override
        public byte[] fetchBytes(String url) throws IOException {
            return this.fetchBytes(url, done -> { });
        }

        @Override
        public byte[] fetchBytes(String url, LongConsumer onBytes) throws IOException {
            if (!url.startsWith("https://cdn.modrinth.com/")) {
                throw new IOException("a download from somewhere other than Modrinth's CDN was refused: " + url);
            }
            return this.send(HttpRequest.newBuilder(URI.create(url)).GET(), HttpResponse.BodyHandlers.ofInputStream(),
                    response -> {
                        try (InputStream in = response.body()) {
                            ByteArrayOutputStream out = new ByteArrayOutputStream();
                            byte[] buffer = new byte[64 * 1024];
                            int read;
                            while ((read = in.read(buffer)) > 0) {
                                out.write(buffer, 0, read);
                                onBytes.accept(out.size());
                            }
                            return out.toByteArray();
                        }
                    });
        }

        @Override
        public byte[] fetchPicture(String url) throws IOException {
            if (!url.startsWith("https://")) {
                throw new IOException("a picture not served by HTTPS was refused: " + url);
            }
            return this.send(HttpRequest.newBuilder(URI.create(url)).GET(), HttpResponse.BodyHandlers.ofInputStream(),
                    response -> {
                        try (InputStream in = response.body()) {
                            byte[] bytes = in.readNBytes(MOST_PICTURE_BYTES + 1);
                            if (bytes.length > MOST_PICTURE_BYTES) {
                                throw new IOException("the picture is larger than 8 MB: " + url);
                            }
                            return bytes;
                        }
                    });
        }

        private interface Reader<T, R> {
            R read(HttpResponse<T> response) throws IOException;
        }

        private <T, R> R send(HttpRequest.Builder request, HttpResponse.BodyHandler<T> handler,
                Reader<T, R> read) throws IOException {
            try {
                HttpRequest built = request.header("User-Agent", this.agent).timeout(TIMEOUT).build();
                long wait = this.openAgainAt - System.nanoTime();

                if (wait > 0 && !built.uri().getHost().startsWith("cdn.")) {
                    Thread.sleep(Math.min(wait / 1_000_000, 60_000));
                }
                HttpResponse<T> response = HTTP.send(built, handler);
                this.readRateLimit(response);
                if (response.statusCode() == GONE) {
                    throw new IOException("Modrinth has retired the API this Hotwire speaks; a newer Hotwire is"
                            + " needed");
                }
                if (response.statusCode() / 100 != 2) {
                    throw new IOException("Modrinth answered " + response.statusCode());
                }
                return read.read(response);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted", e);
            }
        }

        private void readRateLimit(HttpResponse<?> response) {
            long remaining = response.headers().firstValueAsLong("X-Ratelimit-Remaining").orElse(1);
            long reset = response.headers().firstValueAsLong("X-Ratelimit-Reset").orElse(0);
            if (remaining <= 0 && reset > 0) {
                this.openAgainAt = System.nanoTime() + reset * 1_000_000_000L;
            }
        }
    }
}
