package dev.moon.hotwire.modrinth;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongConsumer;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class JarDownload {
    private static final Logger LOGGER = LoggerFactory.getLogger(JarDownload.class);

    private static final int TRIES = 7;
    private static final Duration STALL = Duration.ofSeconds(15);

    private static final long LOOK_MILLIS = 200;

    private static final Semaphore CONNECTIONS = new Semaphore(8);

    private final HttpClient http;
    private final String agent;
    private final Duration timeout;

    JarDownload(HttpClient http, String agent, Duration timeout) {
        this.http = http;
        this.agent = agent;
        this.timeout = timeout;
    }

    void fetch(String url, long size, String sha512, Path into, LongConsumer onBytes) throws IOException {
        boolean whole;
        try (FileChannel part = FileChannel.open(into, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            long left = part.size();
            if (left > size) {
                part.truncate(0);
                left = 0;
            }
            this.fetchFrom(url, size, part, left, onBytes);
            whole = ModrinthClient.hashOf(into).equalsIgnoreCase(sha512);
            if (!whole && left > 0) {
                LOGGER.info("[Hotwire] what an earlier try left of {} was wrong; starting it over", url);
                part.truncate(0);
                this.fetchFrom(url, size, part, 0, onBytes);
                whole = ModrinthClient.hashOf(into).equalsIgnoreCase(sha512);
            }
        }
        if (!whole) {
            Files.deleteIfExists(into);
            throw new IOException("the download of " + url + " does not match Modrinth's hash");
        }
    }

    private void fetchFrom(String url, long size, FileChannel part, long from, LongConsumer onBytes)
            throws IOException {
        AtomicLong at = new AtomicLong(from);
        onBytes.accept(from);
        IOException last = null;
        long reached = from;
        for (int tried = 0; tried < TRIES && at.get() < size; tried++) {
            if (at.get() > reached) {
                reached = at.get();
                tried = 0;
            }
            awaitGo(onBytes);
            try {
                if (tried > 0) {
                    Thread.sleep(1000L << (tried - 1));
                }
                CONNECTIONS.acquire();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted", e);
            }
            try {
                this.request(url, part, at, onBytes);
                last = new IOException(url + " stopped at byte " + at.get() + " of " + size);
            } catch (IOException e) {
                last = e;

                if (isPaused(onBytes)) {
                    tried = -1;
                }
            } finally {
                CONNECTIONS.release();
            }
        }
        if (at.get() < size) {
            throw Objects.requireNonNull(last);
        }
    }

    private void request(String url, FileChannel part, AtomicLong at, LongConsumer onBytes) throws IOException {
        long from = at.get();
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url)).header("User-Agent", this.agent)
                .timeout(this.timeout).GET();
        if (from > 0) {
            request.header("Range", "bytes=" + from + "-");
        }
        AtomicLong heard = new AtomicLong(System.nanoTime());
        CompletableFuture<HttpResponse<Long>> pending = this.http.sendAsync(request.build(), info ->
                info.statusCode() == 206 || (info.statusCode() == 200 && from == 0)
                        ? new IntoFile(part, at, onBytes, heard) : HttpResponse.BodySubscribers.replacing(-1L));
        try {
            while (true) {
                try {
                    HttpResponse<Long> response = pending.get(LOOK_MILLIS, TimeUnit.MILLISECONDS);
                    if (response.body() < 0) {
                        throw new IOException("Modrinth answered " + response.statusCode() + " for " + url);
                    }
                    return;
                } catch (TimeoutException e) {
                    if (isPaused(onBytes)) {
                        pending.cancel(true);
                        throw new IOException("held at byte " + at.get(), e);
                    }
                    if (System.nanoTime() - heard.get() > STALL.toNanos()) {
                        pending.cancel(true);
                        throw new IOException("nothing of " + url + " arrived for " + STALL.toSeconds()
                                + " s, at byte " + at.get());
                    }
                }
            }
        } catch (ExecutionException e) {
            throw e.getCause() instanceof IOException io ? io : new IOException(e.getCause());
        } catch (InterruptedException e) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
    }

    private static void awaitGo(LongConsumer onBytes) throws IOException {
        if (onBytes instanceof Downloads.Feed feed) {
            feed.awaitGo();
        }
    }

    private static boolean isPaused(LongConsumer onBytes) {
        return onBytes instanceof Downloads.Feed feed && feed.isPaused();
    }

    private static final class IntoFile implements HttpResponse.BodySubscriber<Long> {
        private final FileChannel part;
        private final AtomicLong at;
        private final LongConsumer onBytes;
        private final AtomicLong heard;
        private final CompletableFuture<Long> done = new CompletableFuture<>();
        private Flow.@Nullable Subscription subscription;

        IntoFile(FileChannel part, AtomicLong at, LongConsumer onBytes, AtomicLong heard) {
            this.part = part;
            this.at = at;
            this.onBytes = onBytes;
            this.heard = heard;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(1);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            Flow.Subscription asking = Objects.requireNonNull(this.subscription);
            try {
                for (ByteBuffer buffer : buffers) {
                    long position = this.at.get();
                    while (buffer.hasRemaining()) {
                        position += this.part.write(buffer, position);
                    }
                    this.at.set(position);
                    this.onBytes.accept(position);
                }
                this.heard.set(System.nanoTime());
                asking.request(1);
            } catch (IOException e) {
                asking.cancel();
                this.done.completeExceptionally(e);
            }
        }

        @Override
        public void onError(Throwable thrown) {
            this.done.completeExceptionally(thrown);
        }

        @Override
        public void onComplete() {
            this.done.complete(this.at.get());
        }

        @Override
        public CompletionStage<Long> getBody() {
            return this.done;
        }
    }
}
