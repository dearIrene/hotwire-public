package dev.moon.hotwire.modrinth;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class StoreModel implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(StoreModel.class);
    public static final int PAGE = 20;

    static final long TYPING_PAUSE_NANOS = 300_000_000L;
    private static final int MOST_ANSWERS = 24;

    public record Card(ModrinthClient.Hit hit, boolean installed, ModrinthClient.@Nullable Version update) {
    }

    private record Answer(List<Card> cards, int total) {
    }

    private final ModrinthClient client;
    private final StoreCache cache;
    private final Predicate<String> isInstalled;
    private final Runnable onChanged;
    @SuppressWarnings("ThreadPriorityCheck")
    private final ExecutorService worker = Executors.newSingleThreadExecutor(Thread.ofPlatform()
            .name("hotwire-store").daemon().priority(Thread.MIN_PRIORITY).factory());
    private final AtomicInteger asked = new AtomicInteger();

    private final Map<String, Answer> answers = new LinkedHashMap<>(MOST_ANSWERS, 0.75f, true);

    private volatile String words = "";
    private volatile String category = "";
    private volatile List<Card> cards = List.of();
    private volatile int total;
    private volatile boolean loading;
    private volatile @Nullable String failure;
    private volatile long typedAt;
    private volatile boolean searchPending;
    private volatile boolean pageWanted;

    private volatile Set<String> installedProjects = Set.of();
    private volatile Map<String, ModrinthClient.Version> updates = Map.of();

    public StoreModel(ModrinthClient client, StoreCache cache, Predicate<String> isInstalled, Runnable onChanged) {
        this.client = client;
        this.cache = cache;
        this.isInstalled = isInstalled;
        this.onChanged = onChanged;
        this.worker.execute(cache::trim);
    }

    public List<Card> getCards() {
        return this.cards;
    }

    public int getTotal() {
        return this.total;
    }

    public boolean isLoading() {
        return this.loading;
    }

    public @Nullable String getFailure() {
        return this.failure;
    }

    public String getWords() {
        return this.words;
    }

    public String getCategory() {
        return this.category;
    }

    public void setWords(String words) {
        this.words = words;
        this.typedAt = System.nanoTime();
        this.searchPending = true;
    }

    public void setCategory(String category) {
        this.category = category;
        this.searchFresh();
    }

    public void open() {
        if (this.cards.isEmpty() && !this.loading) {
            this.searchFresh();
        }
    }

    public void tick() {
        if (this.searchPending && System.nanoTime() - this.typedAt >= TYPING_PAUSE_NANOS) {
            this.searchPending = false;
            this.searchFresh();
        }
    }

    public void askMore() {
        if (!this.loading && !this.pageWanted && this.cards.size() < this.total) {
            this.pageWanted = true;
            this.ask(this.cards.size());
        }
    }

    private void searchFresh() {
        this.failure = null;
        this.pageWanted = false;
        Answer answered;
        synchronized (this.answers) {
            answered = this.answers.get(findKey(this.words, this.category));
        }
        if (answered != null) {
            this.asked.incrementAndGet();
            this.cards = answered.cards();
            this.total = answered.total();
            this.loading = false;
            this.onChanged.run();
            return;
        }
        this.cards = List.of();
        this.total = 0;
        this.ask(0);
    }

    private static String findKey(String words, String category) {
        return words + ":" + category;
    }

    private void saveAnswer(String words, String category, List<Card> cards, int total) {
        synchronized (this.answers) {
            this.answers.put(findKey(words, category), new Answer(cards, total));
            if (this.answers.size() > MOST_ANSWERS) {
                this.answers.remove(this.answers.keySet().iterator().next());
            }
        }
    }

    private void clearAnswers() {
        synchronized (this.answers) {
            this.answers.clear();
        }
    }

    private void ask(int offset) {
        int ticket = this.asked.incrementAndGet();
        String words = this.words;
        String category = this.category;
        this.loading = true;
        this.onChanged.run();
        this.worker.execute(() -> {
            String key = "search:" + words + ":" + category + ":" + offset;
            ModrinthClient.Search found;
            try {
                found = this.cache.findAnswer(key).map(this::readCached).orElse(null);
                if (found == null) {
                    found = this.client.search(words, category, offset, PAGE);
                    this.cache.saveAnswer(key, SearchJson.write(found));
                }
            } catch (IOException | RuntimeException e) {
                if (ticket == this.asked.get()) {
                    this.failure = e.getMessage() == null ? e.toString() : e.getMessage();
                    this.loading = false;
                    this.pageWanted = false;
                    this.onChanged.run();
                }
                LOGGER.info("[Hotwire] the store's search failed: {}", e.toString());
                return;
            }
            if (ticket != this.asked.get()) {
                return;
            }
            List<Card> next = new ArrayList<>(offset == 0 ? List.of() : this.cards);
            for (ModrinthClient.Hit hit : found.hits()) {
                next.add(this.makeCard(hit));
            }
            this.cards = Collections.unmodifiableList(next);
            this.total = found.total();
            this.saveAnswer(words, category, this.cards, this.total);
            this.loading = false;
            this.pageWanted = false;
            this.onChanged.run();
        });
    }

    private Card makeCard(ModrinthClient.Hit hit) {
        boolean installed = this.installedProjects.contains(hit.projectId()) || this.isInstalled.test(hit.slug())
                || this.isInstalled.test(hit.projectId());
        return new Card(hit, installed, this.updates.get(hit.projectId()));
    }

    public void setInstalled(Set<String> projects, Map<String, ModrinthClient.Version> updates) {
        this.installedProjects = Set.copyOf(projects);
        this.updates = Map.copyOf(updates);
        List<Card> next = new ArrayList<>();
        for (Card card : this.cards) {
            next.add(this.makeCard(card.hit()));
        }
        this.cards = Collections.unmodifiableList(next);
        this.clearAnswers();
        this.onChanged.run();
    }

    public Map<String, ModrinthClient.Version> getUpdates() {
        return this.updates;
    }

    public boolean isInstalledProject(String projectId) {
        return this.installedProjects.contains(projectId) || this.isInstalled.test(projectId);
    }

    public void findProject(String projectId, Consumer<ModrinthClient.Project> then, Consumer<String> failed) {
        this.worker.execute(() -> {
            String key = "project:" + projectId;
            try {
                ModrinthClient.Project found = this.cache.findAnswer(key).map(this::readCachedProject).orElse(null);
                if (found == null) {
                    found = this.client.findProject(projectId);
                    this.cache.saveAnswer(key, SearchJson.writeProject(found));
                }
                then.accept(found);
            } catch (IOException | RuntimeException e) {
                failed.accept(e.getMessage() == null ? e.toString() : e.getMessage());
            }
        });
    }

    private ModrinthClient.@Nullable Project readCachedProject(String json) {
        try {
            return SearchJson.readProject(json);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private ModrinthClient.@Nullable Search readCached(String json) {
        try {
            return SearchJson.read(json);
        } catch (RuntimeException e) {
            return null;
        }
    }

    public void run(Runnable work) {
        this.worker.execute(work);
    }

    public void findVersions(String projectId, Consumer<List<ModrinthClient.Version>> then,
            Consumer<String> failed) {
        this.worker.execute(() -> {
            try {
                then.accept(this.client.findVersions(projectId));
            } catch (IOException | RuntimeException e) {
                failed.accept(e.getMessage() == null ? e.toString() : e.getMessage());
            }
        });
    }

    public ModrinthClient getClient() {
        return this.client;
    }

    public StoreCache getCache() {
        return this.cache;
    }

    @Override
    public void close() {
        this.worker.shutdownNow();
    }

    public Map<String, Card> getCardsByProject() {
        Map<String, Card> out = new ConcurrentHashMap<>();
        this.cards.forEach(card -> out.put(card.hit().projectId(), card));
        return out;
    }

    public void markInstalled(String projectId) {
        Map<String, ModrinthClient.Version> fewer = new HashMap<>(this.updates);
        fewer.remove(projectId);
        this.updates = Map.copyOf(fewer);
        List<Card> next = new ArrayList<>();
        for (Card card : this.cards) {
            next.add(card.hit().projectId().equals(projectId) ? new Card(card.hit(), true, null) : card);
        }
        this.cards = Collections.unmodifiableList(next);
        this.clearAnswers();
        this.onChanged.run();
    }
}
