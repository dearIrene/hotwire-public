package dev.moon.hotwire.update;

import java.util.List;

public record UpdateOffer(String id, String name, String installedVersion, String newVersion, String changelog,
        String projectId, String versionId, String fileName, String url, String sha512, long size,
        List<String> requiredProjects) {
    public UpdateOffer {
        requiredProjects = List.copyOf(requiredProjects);
    }
}
