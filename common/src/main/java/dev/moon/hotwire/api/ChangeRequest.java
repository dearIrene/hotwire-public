package dev.moon.hotwire.api;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public record ChangeRequest(List<String> off, List<String> on, List<String> update, List<Add> add, List<Path> jars,
        String tag) {
    public record Add(String idOrSlug, String versionId) {
    }

    public ChangeRequest {
        off = List.copyOf(off);
        on = List.copyOf(on);
        update = List.copyOf(update);
        add = List.copyOf(add);
        jars = List.copyOf(jars);
    }

    public ChangeRequest() {
        this(List.of(), List.of(), List.of(), List.of(), List.of(), "");
    }

    public ChangeRequest off(String... ids) {
        return this.off(List.of(ids));
    }

    public ChangeRequest off(Collection<String> ids) {
        return new ChangeRequest(join(this.off, ids), this.on, this.update, this.add, this.jars, this.tag);
    }

    public ChangeRequest on(String... ids) {
        return this.on(List.of(ids));
    }

    public ChangeRequest on(Collection<String> ids) {
        return new ChangeRequest(this.off, join(this.on, ids), this.update, this.add, this.jars, this.tag);
    }

    public ChangeRequest update(String... ids) {
        return this.update(List.of(ids));
    }

    public ChangeRequest update(Collection<String> ids) {
        return new ChangeRequest(this.off, this.on, join(this.update, ids), this.add, this.jars, this.tag);
    }

    public ChangeRequest add(String idOrSlug) {
        return this.add(idOrSlug, "");
    }

    public ChangeRequest add(String idOrSlug, String versionId) {
        return new ChangeRequest(this.off, this.on, this.update, join(this.add, List.of(new Add(idOrSlug,
                versionId))), this.jars, this.tag);
    }

    public ChangeRequest jar(Path jar) {
        return new ChangeRequest(this.off, this.on, this.update, this.add, join(this.jars, List.of(jar)), this.tag);
    }

    public ChangeRequest tag(String other) {
        return new ChangeRequest(this.off, this.on, this.update, this.add, this.jars, other);
    }

    private static <T> List<T> join(List<T> first, Collection<T> more) {
        List<T> all = new ArrayList<>(first);
        all.addAll(more);
        return all;
    }
}
