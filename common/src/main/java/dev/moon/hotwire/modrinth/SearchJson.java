package dev.moon.hotwire.modrinth;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;

final class SearchJson {
    private SearchJson() {
    }

    static String write(ModrinthClient.Search search) {
        JsonObject root = new JsonObject();
        root.addProperty("total", search.total());
        JsonArray hits = new JsonArray();
        for (ModrinthClient.Hit hit : search.hits()) {
            JsonObject one = new JsonObject();
            one.addProperty("id", hit.projectId());
            one.addProperty("slug", hit.slug());
            one.addProperty("title", hit.title());
            one.addProperty("description", hit.description());
            one.addProperty("author", hit.author());
            one.addProperty("downloads", hit.downloads());
            one.addProperty("icon", hit.iconUrl());
            JsonArray categories = new JsonArray();
            hit.categories().forEach(categories::add);
            one.add("categories", categories);
            one.addProperty("modified", hit.modified());
            hits.add(one);
        }
        root.add("hits", hits);
        return root.toString();
    }

    static ModrinthClient.Search read(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        List<ModrinthClient.Hit> hits = new ArrayList<>();
        for (JsonElement element : root.getAsJsonArray("hits")) {
            JsonObject one = element.getAsJsonObject();
            List<String> categories = new ArrayList<>();
            one.getAsJsonArray("categories").forEach(category -> categories.add(category.getAsString()));
            hits.add(new ModrinthClient.Hit(one.get("id").getAsString(), one.get("slug").getAsString(),
                    one.get("title").getAsString(), one.get("description").getAsString(),
                    one.get("author").getAsString(), one.get("downloads").getAsLong(), one.get("icon").getAsString(),
                    categories, one.has("modified") ? one.get("modified").getAsString() : ""));
        }
        return new ModrinthClient.Search(hits, root.get("total").getAsInt());
    }

    static String writeVersions(List<ModrinthClient.Version> versions) {
        JsonArray out = new JsonArray();
        for (ModrinthClient.Version version : versions) {
            JsonObject one = new JsonObject();
            one.addProperty("id", version.id());
            one.addProperty("project_id", version.projectId());
            one.addProperty("version_number", version.number());
            one.addProperty("name", version.name());
            one.addProperty("changelog", version.changelog());
            JsonArray games = new JsonArray();
            version.gameVersions().forEach(games::add);
            one.add("game_versions", games);
            JsonArray dependencies = new JsonArray();
            for (String project : version.requiredProjects()) {
                JsonObject dependency = new JsonObject();
                dependency.addProperty("project_id", project);
                dependency.addProperty("dependency_type", "required");
                dependencies.add(dependency);
            }
            one.add("dependencies", dependencies);
            JsonObject file = new JsonObject();
            file.addProperty("filename", version.fileName());
            file.addProperty("url", version.url());
            file.addProperty("size", version.size());
            file.addProperty("primary", true);
            JsonObject hashes = new JsonObject();
            hashes.addProperty("sha512", version.sha512());
            file.add("hashes", hashes);
            JsonArray files = new JsonArray();
            files.add(file);
            one.add("files", files);
            out.add(one);
        }
        return out.toString();
    }

    static List<ModrinthClient.Version> readVersions(String json) {
        List<ModrinthClient.Version> out = new ArrayList<>();
        for (JsonElement element : JsonParser.parseString(json).getAsJsonArray()) {
            out.add(ModrinthClient.readVersion(element.getAsJsonObject()));
        }
        return out;
    }

    static String writeProject(ModrinthClient.Project project) {
        JsonObject root = new JsonObject();
        root.addProperty("id", project.id());
        root.addProperty("slug", project.slug());
        root.addProperty("title", project.title());
        root.addProperty("description", project.description());
        root.addProperty("body", project.body());
        root.addProperty("license", project.license());
        root.addProperty("licenseUrl", project.licenseUrl());
        root.addProperty("sourceUrl", project.sourceUrl());
        root.addProperty("downloads", project.downloads());
        root.addProperty("followers", project.followers());
        root.addProperty("icon", project.iconUrl());
        return root.toString();
    }

    static ModrinthClient.Project readProject(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        return new ModrinthClient.Project(root.get("id").getAsString(), root.get("slug").getAsString(),
                root.get("title").getAsString(), root.get("description").getAsString(),
                root.get("body").getAsString(), root.get("license").getAsString(),
                root.get("licenseUrl").getAsString(), root.get("sourceUrl").getAsString(),
                root.get("downloads").getAsLong(), root.get("followers").getAsLong(), root.get("icon").getAsString());
    }
}
