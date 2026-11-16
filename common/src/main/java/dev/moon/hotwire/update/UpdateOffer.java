package dev.moon.hotwire.update;

import java.util.List;

public record UpdateOffer(String id, String name, String installedVersion, String newVersion, String changelog,
        String projectId, String versionId, String fileName, String url, String sha512, long size,
        List<String> requiredProjects, String loaderNeeded) {
    public UpdateOffer {
        requiredProjects = List.copyOf(requiredProjects);
    }

    public UpdateOffer withLoaderNeeded(String wanted) {
        return new UpdateOffer(this.id, this.name, this.installedVersion, this.newVersion, this.changelog,
                this.projectId, this.versionId, this.fileName, this.url, this.sha512, this.size, this.requiredProjects,
                wanted);
    }
}
