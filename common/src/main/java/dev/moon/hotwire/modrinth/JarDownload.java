package dev.moon.hotwire.modrinth;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Flow;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongConsumer;
import org.jspecify.annotations.Nullable;

final class JarDownload {
    private static final long PIECE = 4L << 20;
    private static final long SPLIT_ABOVE = 8L << 20;
    private static final int TRIES = 5;
    private static final Duration STALL = Duration.ofSeconds(15);

    private static final Semaphore CONNECTIONS = new Semaphore(8);

    @SuppressWarnings("ThreadPriorityCheck")
    private static final ExecutorService PIECES = Executors.newCachedThreadPool(Thread.ofPlatform()
            .name("hotwire-jar-piece-", 1).daemon().priority(Thread.MIN_PRIORITY).factory());

    private final HttpClient http;
    private final String agent;
    private final Duration timeout;

    JarDownload(HttpClient http, String agent, Duration timeout) {
        this.http = http;
        this.agent = agent;
        this.timeout = timeout;
    }

    void fetch(String url, long size, Path into, LongConsumer onBytes) throws IOException {
        AtomicLong arrived = new AtomicLong();
        LongConsumer counted = more -> onBytes.accept(arrived.addAndGet(more));
        try (FileChannel file = FileChannel.open(into, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            if (size <= SPLIT_ABOVE) {
                this.fetchPiece(url, file, 0, size, counted);
            } else {
                this.fetchPieces(url, file, size, counted);
            }
            long written = file.size();
            if (size > 0 && written != size) {
                throw new IOException(url + " came to " + written + " bytes of " + size);
            }
        }
    }

    @SuppressWarnings("FutureReturnValueIgnored")
    private void fetchPieces(String url, FileChannel file, long size, LongConsumer counted) throws IOException {
        List<Future<?>> pieces = new ArrayList<>();
        for (long from = 0; from < size; from += PIECE) {
            long start = from;
            long end = Math.min(size, from + PIECE);
            pieces.add(PIECES.submit(() -> {
                this.fetchPiece(url, file, start, end, counted);
                return null;
            }));
        }
        try {
            for (Future<?> piece : pieces) {
                piece.get();
            }
        } catch (ExecutionException e) {
            pieces.forEach(piece -> piece.cancel(true));
            throw e.getCause() instanceof IOException io ? io : new IOException(e.getCause());
        } catch (InterruptedException e) {
            pieces.forEach(piece -> piece.cancel(true));
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
    }

    private void fetchPiece(String url, FileChannel file, long from, long end, LongConsumer counted)
            throws IOException {
        AtomicLong at = new AtomicLong(from);
        IOException last = null;
        for (int tried = 0; tried < TRIES; tried++) {
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
                this.request(url, file, at, end, counted);
                if (end <= 0 || at.get() >= end) {
                    return;
                }
                last = new IOException(url + " stopped at byte " + at.get() + " of " + end);
            } catch (IOException e) {
                last = e;
            } finally {
                CONNECTIONS.release();
            }
        }
        throw Objects.requireNonNull(last);
    }

    private void request(String url, FileChannel file, AtomicLong at, long end, LongConsumer counted)
            throws IOException {
        long from = at.get();
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url)).header("User-Agent", this.agent)
                .timeout(this.timeout).GET();
        if (from > 0 || end > 0) {
            request.header("Range", "bytes=" + from + "-" + (end > 0 ? String.valueOf(end - 1) : ""));
        }
        AtomicLong heard = new AtomicLong(System.nanoTime());

        CompletableFuture<HttpResponse<Long>> pending = this.http.sendAsync(request.build(), info ->
                info.statusCode() == 206 || (info.statusCode() == 200 && from == 0)
                        ? new AtOffset(file, at, heard, counted) : HttpResponse.BodySubscribers.replacing(-1L));
        try {
            while (true) {
                try {
                    HttpResponse<Long> response = pending.get(1, TimeUnit.SECONDS);
                    if (response.body() < 0) {
                        throw new IOException("Modrinth answered " + response.statusCode() + " for " + url);
                    }
                    return;
                } catch (TimeoutException e) {
                    if (System.nanoTime() - heard.get() > STALL.toNanos()) {
                        pending.cancel(true);
                        throw new IOException("nothing of " + url + " arrived for " + STALL.toSeconds() + " s, at byte "
                                + at.get());
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

    private static final class AtOffset implements HttpResponse.BodySubscriber<Long> {
        private final FileChannel file;
        private final AtomicLong at;
        private final AtomicLong heard;
        private final LongConsumer counted;
        private final CompletableFuture<Long> done = new CompletableFuture<>();
        private Flow.@Nullable Subscription subscription;

        AtOffset(FileChannel file, AtomicLong at, AtomicLong heard, LongConsumer counted) {
            this.file = file;
            this.at = at;
            this.heard = heard;
            this.counted = counted;
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
                    int length = buffer.remaining();
                    long position = this.at.get();
                    while (buffer.hasRemaining()) {
                        position += this.file.write(buffer, position);
                    }
                    this.at.set(position);
                    this.counted.accept(length);
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
