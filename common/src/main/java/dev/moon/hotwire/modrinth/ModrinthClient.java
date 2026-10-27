package dev.moon.hotwire.modrinth;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayInputStream;
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
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

public final class ModrinthClient {
    public static final String API = "https://api.modrinth.com/v2";
    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    static final String USER_AGENT_PREFIX = "moonshee/hotwire/";
    static final String CONTACT = "https://github.com/moonshee/hotwire";

    static final int GONE = 410;

    public record Hit(String projectId, String slug, String title, String description, String author,
            long downloads, String iconUrl, List<String> categories) {
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

        default byte[] fetchPicture(String url) throws IOException {
            throw new IOException("no pictures here");
        }
    }

    public static final int MOST_PICTURE_BYTES = 8 * 1024 * 1024;

    private final Transport transport;
    private final String loader;
    private final String gameVersion;
    private final LocalJars local;

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
    }

    public Search search(String words, String category, int offset, int limit) throws IOException {
        List<List<String>> facets = new ArrayList<>();
        facets.add(List.of("project_type:mod"));
        facets.add(List.of("categories:" + this.loader));
        facets.add(List.of("versions:" + this.gameVersion));
        if (!category.isEmpty()) {
            facets.add(List.of("categories:" + category));
        }
        StringBuilder facetText = new StringBuilder("[");
        for (int i = 0; i < facets.size(); i++) {
            facetText.append(i == 0 ? "" : ",").append("[\"").append(facets.get(i).getFirst()).append("\"]");
        }
        facetText.append(']');
        String url = API + "/search?query=" + encode(words) + "&facets=" + encode(facetText.toString())
                + "&index=" + (words.isBlank() ? "downloads" : "relevance") + "&offset=" + offset + "&limit="
                + limit;
        JsonObject root = JsonParser.parseString(this.transport.get(url)).getAsJsonObject();
        List<Hit> hits = new ArrayList<>();
        for (JsonElement element : root.getAsJsonArray("hits")) {
            JsonObject hit = element.getAsJsonObject();
            hits.add(new Hit(readText(hit, "project_id"), readText(hit, "slug"), readText(hit, "title"),
                    readText(hit, "description"), readText(hit, "author"), readNumber(hit, "downloads"),
                    readText(hit, "icon_url"),
                    readStrings(hit.get("categories"))));
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

    public List<Version> findVersions(String projectId) throws IOException {
        String url = API + "/project/" + encode(projectId) + "/version?loaders=" + encode("[\"" + this.loader
                + "\"]") + "&game_versions=" + encode("[\"" + this.gameVersion + "\"]");
        List<Version> out = new ArrayList<>();
        for (JsonElement element : JsonParser.parseString(this.transport.get(url)).getAsJsonArray()) {
            out.add(readVersion(element.getAsJsonObject()));
        }
        return out;
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

    public byte[] fetch(Version version) throws IOException {
        byte[] copy = this.local.find(version.fileName(), version.size(), version.sha512());
        if (copy != null) {
            return copy;
        }
        byte[] bytes = this.transport.fetchBytes(version.url());
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

    private static Version readVersion(JsonObject version) {
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
            if (!url.startsWith("https://cdn.modrinth.com/")) {
                throw new IOException("a download from somewhere other than Modrinth's CDN was refused: " + url);
            }
            return this.send(HttpRequest.newBuilder(URI.create(url)).GET(), HttpResponse.BodyHandlers.ofByteArray(),
                    HttpResponse::body);
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
                long wait = this.openAgainAt - System.nanoTime();
                if (wait > 0) {
                    Thread.sleep(Math.min(wait / 1_000_000, 60_000));
                }
                HttpResponse<T> response = HTTP.send(request.header("User-Agent", this.agent)
                        .timeout(TIMEOUT).build(), handler);
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
