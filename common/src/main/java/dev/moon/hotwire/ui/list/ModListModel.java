package dev.moon.hotwire.ui.list;

import dev.moon.hotwire.ui.Lang;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

public final class ModListModel {
    public static final int MOST_CHOSEN = 5;

    public enum Show {
        ALL("hotwire.show.all"),
        ON("hotwire.show.on"),
        OFF("hotwire.show.off"),
        SELECTED("hotwire.show.selected");

        private final String label;

        Show(String label) {
            this.label = label;
        }

        public String getLabel() {
            return Lang.get(this.label);
        }
    }

    public sealed interface Unpick {
        record Took() implements Unpick {
        }

        record NeededBy(String owner) implements Unpick {
        }
    }

    private final ModCatalog catalog;
    private final Set<String> chosen = new LinkedHashSet<>();
    private final Map<String, Set<String>> broughtIn = new LinkedHashMap<>();
    private final Set<String> picked = new LinkedHashSet<>();
    private final Set<String> pickedForYou = new LinkedHashSet<>();
    private final Map<String, Integer> dependantCount = new HashMap<>();
    private Map<String, String> groupedUnder = Map.of();
    private String filter = "";
    private Show showing = Show.ALL;
    private boolean showLibraries;

    public ModListModel(ModCatalog catalog) {
        this.catalog = catalog;
    }

    public Set<String> getPicked() {
        return Collections.unmodifiableSet(this.picked);
    }

    public boolean isFull() {
        return this.chosen.size() >= MOST_CHOSEN;
    }

    public Set<String> getPickedForYou() {
        return Collections.unmodifiableSet(this.pickedForYou);
    }

    Set<String> getChosen() {
        return Collections.unmodifiableSet(this.chosen);
    }

    public @Nullable String findGroupedUnder(String id) {
        return this.groupedUnder.get(id);
    }

    String getFilter() {
        return this.filter;
    }

    public boolean setFilter(String text) {
        String cleaned = text.strip().toLowerCase(Locale.ROOT);
        if (cleaned.equals(this.filter)) {
            return false;
        }
        this.filter = cleaned;
        return true;
    }

    public Show getShowing() {
        return this.showing;
    }

    public void setShowing(Show showing) {
        this.showing = showing;
    }

    public boolean isShowingLibraries() {
        return this.showLibraries;
    }

    public void setShowingLibraries(boolean show) {
        this.showLibraries = show;
    }

    public void onCatalogChanged() {
        this.dependantCount.clear();
    }

    @Nullable String findBroughtInBy(String id) {
        for (Map.Entry<String, Set<String>> entry : this.broughtIn.entrySet()) {
            if (entry.getValue().contains(id)) {
                return entry.getKey();
            }
        }
        return null;
    }

    Set<String> getDependentsOf(String id) {
        Set<String> found = new LinkedHashSet<>();
        Set<String> walked = new LinkedHashSet<>();
        ArrayDeque<String> frontier = new ArrayDeque<>();
        frontier.add(id);
        while (!frontier.isEmpty()) {
            for (String dependent : this.catalog.getNeededBy(frontier.poll())) {
                if (this.catalog.isProtected(dependent) || this.catalog.isLocked(dependent) || !walked.add(dependent)) {
                    continue;
                }
                if (!this.catalog.isHidden(dependent)) {
                    found.add(dependent);
                }
                frontier.add(dependent);
            }
        }
        found.remove(id);
        return found;
    }

    public int countDependants(String id) {
        return this.dependantCount.computeIfAbsent(id, one -> this.getDependentsOf(one).size());
    }

    public boolean pick(String id) {
        if (this.catalog.isProtected(id) || this.catalog.isLocked(id) || this.isFull()) {
            return false;
        }
        String owner = this.findBroughtInBy(id);
        if (owner != null && this.chosen.contains(owner) && this.pickedForYou.contains(id)) {
            return false;
        }
        this.chosen.add(id);

        this.broughtIn.put(id, this.catalog.isOn(id) ? this.getDependentsOf(id) : new LinkedHashSet<>());
        this.rebuild();
        return true;
    }

    public Unpick unpick(String id) {
        if (this.chosen.remove(id)) {
            this.broughtIn.remove(id);
            this.rebuild();
            return new Unpick.Took();
        }
        String owner = this.findBroughtInBy(id);
        if (owner != null && this.chosen.contains(owner)) {
            return new Unpick.NeededBy(owner);
        }
        this.broughtIn.values().forEach(brought -> brought.remove(id));
        this.rebuild();
        return new Unpick.Took();
    }

    public void clear() {
        this.chosen.clear();
        this.broughtIn.clear();
        this.rebuild();
    }

    private void rebuild() {
        this.picked.clear();
        this.pickedForYou.clear();
        this.picked.addAll(this.chosen);
        for (Set<String> brought : this.broughtIn.values()) {
            this.picked.addAll(brought);
            this.pickedForYou.addAll(brought);
        }
    }

    public List<String> filterRelevant(List<String> all) {
        List<String> out = new ArrayList<>();
        for (String id : all) {
            if (!this.catalog.isHidden(id)
                    && (this.showLibraries || !this.catalog.isLibrary(id) || this.catalog.isInstalledByThePlayer(id))) {
                out.add(id);
            }
        }
        return out;
    }

    public List<String> applyFilter(List<String> relevant) {
        List<List<String>> byRank = List.of(new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        for (String id : relevant) {
            int rank = this.isShown(id) ? this.rank(id) : -1;
            if (rank >= 0) {
                byRank.get(rank).add(id);
            }
        }
        List<String> out = new ArrayList<>();
        byRank.forEach(out::addAll);
        out.sort(Comparator.comparing(id -> !this.catalog.isFavorite(id)));
        return out;
    }

    private boolean isShown(String id) {
        return switch (this.showing) {
            case ALL -> true;
            case ON -> this.catalog.isOn(id);
            case OFF -> !this.catalog.isOn(id);
            case SELECTED -> this.picked.contains(id);
        };
    }

    private int rank(String id) {
        if (this.filter.isEmpty()) {
            return 0;
        }
        String name = this.catalog.name(id).toLowerCase(Locale.ROOT);
        if (name.startsWith(this.filter)) {
            return 0;
        }
        if (name.contains(this.filter)) {
            return 1;
        }
        if (id.toLowerCase(Locale.ROOT).contains(this.filter)) {
            return 2;
        }
        return this.catalog.getAuthors(id).toLowerCase(Locale.ROOT).contains(this.filter) ? 3 : -1;
    }

    public List<String> group(List<String> flat) {
        if (this.chosen.isEmpty()) {
            this.groupedUnder = Map.of();
            return flat;
        }
        Set<String> inList = new LinkedHashSet<>(flat);
        Map<String, String> owners = new HashMap<>();
        List<String> out = new ArrayList<>();
        Set<String> placed = new LinkedHashSet<>();
        for (String id : flat) {
            if (placed.contains(id) || (this.pickedForYou.contains(id) && this.findBroughtInBy(id) != null)) {
                continue;
            }
            out.add(id);
            placed.add(id);
            Set<String> brought = this.broughtIn.get(id);
            if (brought == null) {
                continue;
            }
            List<String> children = new ArrayList<>();
            for (String child : brought) {
                if (inList.contains(child) && !placed.contains(child)) {
                    children.add(child);
                }
            }
            children.sort(Comparator.comparingDouble(this.catalog::getStanding));
            for (String child : children) {
                if (placed.add(child)) {
                    out.add(child);
                    owners.put(child, id);
                }
            }
        }
        for (String id : flat) {
            if (placed.add(id)) {
                out.add(id);
            }
        }
        this.groupedUnder = owners;
        return out;
    }
}
