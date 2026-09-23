package consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import static java.util.concurrent.TimeUnit.SECONDS;

import io.github.maxsumrall.jev4j.Jev;
import io.github.maxsumrall.jev4j.JevEvaluationException;
import io.github.maxsumrall.jev4j.JevEvaluationException.FailureCategory;
import io.github.maxsumrall.jev4j.JevEvaluator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InputStream;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;

/** Public cancellation contracts with plain futures and a local JDK HTTP/1.1 connection. */
@Timeout(15)
final class AsyncEvaluationTest {
    private static final Jev.NoulQuestion Q = Jev.noul("urgent?").threshold(0.73);
    private static final Jev.State STATE = Jev.State.from(Map.of("ticket", "help"));
    private static final String BODY =
            """
      {"answers":{"question":{"type":"noul","noul":0.73}},"model":"m",
       "usage":{"input_tokens":7,"output_tokens":2},"id":"body-id","provider":"p"}
      """;

    @Test
    void everyPublicProjectionCancelsTheOriginalPlainTransport() {
        List<Function<JevEvaluator, CompletableFuture<?>>> calls =
                List.of(
                        e -> e.evaluateAsync("s", Q),
                        e -> e.evaluateAsync(STATE, Q),
                        e -> e.evaluateWithMetadataAsync("s", Q),
                        e -> e.evaluateWithMetadataAsync(STATE, Q),
                        e -> e.testAsync("s", Q),
                        e -> e.testAsync(STATE, Q),
                        e -> e.evaluateAsync("s", Q, Q),
                        e -> e.evaluateAsync(STATE, Q, Q),
                        e -> e.evaluateAsync("s", Q, Q, Q),
                        e -> e.evaluateAsync(STATE, Q, Q, Q),
                        e -> e.evaluateAsync("s", Q, Q, Q, Q),
                        e -> e.evaluateAsync(STATE, Q, Q, Q, Q),
                        e -> e.evaluateAsync("s", Q, Q, Q, Q, Q),
                        e -> e.evaluateAsync(STATE, Q, Q, Q, Q, Q),
                        e -> e.evaluateAsync("s", Q, Q, Q, Q, Q, Q),
                        e -> e.evaluateAsync(STATE, Q, Q, Q, Q, Q, Q),
                        e -> e.evaluateAsync("s", Q, Q, Q, Q, Q, Q, Q),
                        e -> e.evaluateAsync(STATE, Q, Q, Q, Q, Q, Q, Q),
                        e -> e.evaluateAsync("s", Q, Q, Q, Q, Q, Q, Q, Q),
                        e -> e.evaluateAsync(STATE, Q, Q, Q, Q, Q, Q, Q, Q));
        for (Function<JevEvaluator, CompletableFuture<?>> call : calls) {
            for (boolean interrupt : List.of(false, true)) {
                StubClient client = new StubClient();
                CompletableFuture<?> result = call.apply(evaluator(client));
                assertFalse(result.isDone(), "must return before the transport completes");
                assertEquals(1, client.sends);
                assertTrue(result.cancel(interrupt));
                assertTrue(client.next.isCancelled());
                assertThrows(CancellationException.class, result::join);
                assertTrue(result.cancel(interrupt)); // Ordinary CF allows repeated cancellation.
                assertFalse(client.next.completeExceptionally(new IOException("PRIVATE")));
                assertTrue(result.isCancelled());
            }
        }
    }

    @Test
    void bothCancelFlagsRequestTransportAbortWithoutInterruptingCaller() {
        for (boolean interrupt : List.of(false, true)) {
            AtomicInteger aborts = new AtomicInteger();
            StubClient client = new StubClient();
            client.next =
                    new CompletableFuture<>() {
                        @Override
                        public boolean cancel(boolean mayInterruptIfRunning) {
                            assertTrue(mayInterruptIfRunning);
                            aborts.incrementAndGet();
                            return super.cancel(mayInterruptIfRunning);
                        }
                    };
            CompletableFuture<Boolean> result = evaluator(client).testAsync(STATE, Q);
            result.cancel(interrupt);
            result.cancel(interrupt);
            assertEquals(1, aborts.get());
            assertFalse(Thread.currentThread().isInterrupted());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void transportIsCancelledBeforeCallerCompletionCallbacks(boolean interrupt) throws Exception {
        StubClient client = new StubClient();
        CompletableFuture<Boolean> result = evaluator(client).testAsync(STATE, Q);
        CountDownLatch callbackEntered = new CountDownLatch(1);
        CountDownLatch releaseCallback = new CountDownLatch(1);
        CompletableFuture<Boolean> dependent =
                result.whenComplete(
                        (value, failure) -> {
                            callbackEntered.countDown();
                            await(releaseCallback);
                        });
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            CompletableFuture<Boolean> cancelled =
                    CompletableFuture.supplyAsync(() -> result.cancel(interrupt), executor);
            assertTrue(callbackEntered.await(2, SECONDS));
            assertTrue(result.isCancelled());
            assertTrue(
                    client.next.isCancelled(), "caller callbacks must not delay transport abort");
            assertFalse(dependent.isDone(), "the caller callback is still blocked");
            releaseCallback.countDown();
            assertTrue(cancelled.get(2, SECONDS));
            assertThrows(CancellationException.class, result::join);
        } finally {
            releaseCallback.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(2, SECONDS));
        }
    }

    @Test
    void validationThrowsBeforeDispatchAndStartupFailureReturnsAFailedFuture() {
        StubClient client = new StubClient();
        JevEvaluator evaluator = evaluator(client);
        assertThrows(NullPointerException.class, () -> evaluator.evaluateAsync((String) null, Q));
        assertThrows(
                NullPointerException.class, () -> evaluator.evaluateAsync((Jev.State) null, Q));
        assertThrows(
                NullPointerException.class, () -> evaluator.evaluateWithMetadataAsync(STATE, null));
        assertThrows(NullPointerException.class, () -> evaluator.testAsync("s", null));
        assertThrows(NullPointerException.class, () -> evaluator.evaluateAsync(STATE, Q, null));
        assertEquals(0, client.sends);
        client.startupFailure = new RejectedExecutionException("PRIVATE executor");
        assertFailure(evaluator.testAsync(STATE, Q), FailureCategory.UNKNOWN);
        assertEquals(1, client.sends);
    }

    @Test
    void transportFailuresAreClassifiedWithoutRawCausesAndNeverRetried() {
        Map<Throwable, FailureCategory> cases =
                Map.of(
                        new IOException("PRIVATE"), FailureCategory.IO,
                        new HttpTimeoutException("PRIVATE"), FailureCategory.TIMEOUT,
                        new HttpConnectTimeoutException("PRIVATE"), FailureCategory.TIMEOUT,
                        new SecurityException("PRIVATE"), FailureCategory.UNKNOWN);
        for (Map.Entry<Throwable, FailureCategory> entry : cases.entrySet()) {
            StubClient client = new StubClient();
            CompletableFuture<Boolean> result = evaluator(client).testAsync("s", Q);
            client.next.completeExceptionally(new CompletionException(entry.getKey()));
            assertFailure(result, entry.getValue());
            assertFalse(result.cancel(true));
            assertEquals(1, client.sends);
        }
        StubClient cancelled = new StubClient();
        cancelled.next.completeExceptionally(
                new CompletionException(new CancellationException("PRIVATE")));
        CompletableFuture<Boolean> result = evaluator(cancelled).testAsync("s", Q);
        assertTrue(result.isCancelled());
        assertThrows(CancellationException.class, result::join);
        // Newer JDKs wrap cancellation at join/get; inspect the exception stored in the future.
        CancellationException failure =
                assertInstanceOf(
                        CancellationException.class,
                        result.handle((answer, cause) -> cause).join());
        assertEquals("evaluation cancelled", failure.getMessage());
        assertNull(failure.getCause());
    }

    @Test
    void alreadyCompletedTransportPublishesTypedAnswerAndMetadataBeforeReturning()
            throws Exception {
        StubClient client = new StubClient();
        client.next.complete(new Response(() -> BODY));
        JevEvaluator.Evaluation<Jev.NoulAnswer> result =
                evaluator(client).evaluateWithMetadataAsync(STATE, Q).get(2, SECONDS);
        assertEquals(0.73, result.answer().probabilityTrue());
        assertTrue(result.answer().isTrue());
        assertEquals("header-id", result.requestId().orElseThrow());
        assertEquals("body-id", result.id().orElseThrow());
        assertEquals("p", result.provider().orElseThrow());
        assertEquals("m", result.model());
        assertEquals(7, result.usage().inputTokens());
        assertEquals(2, result.usage().outputTokens());
        CompletableFuture<Boolean> decision = evaluator(client).testAsync("s", Q);
        assertTrue(decision.get(2, SECONDS));
        assertFalse(decision.cancel(true));
        assertFalse(client.next.isCancelled());
    }

    @Test
    void waitingOrCancellingADependentDoesNotCancelTheOperationOrItsSibling() throws Exception {
        StubClient client = new StubClient();
        JevEvaluator evaluator = evaluator(client);
        CompletableFuture<Boolean> original = evaluator.testAsync("s", Q);
        CompletableFuture<HttpResponse<String>> firstTransport = client.next;
        client.next = new CompletableFuture<>();
        CompletableFuture<Boolean> sibling = evaluator.testAsync(STATE, Q);
        original.thenApply(value -> !value).cancel(true);
        assertThrows(
                TimeoutException.class,
                () -> original.get(1, java.util.concurrent.TimeUnit.MILLISECONDS));
        Thread.currentThread().interrupt();
        try {
            assertThrows(InterruptedException.class, original::get);
        } finally {
            Thread.interrupted();
        }
        assertFalse(original.isDone());
        assertFalse(firstTransport.isDone());
        original.cancel(true);
        assertTrue(firstTransport.isCancelled());
        assertFalse(sibling.isDone());
        client.next.complete(new Response(() -> BODY));
        assertTrue(sibling.get(2, SECONDS));

        client.next = new CompletableFuture<>();
        CompletableFuture<Boolean> timed =
                evaluator
                        .testAsync("s", Q)
                        .orTimeout(1, java.util.concurrent.TimeUnit.MILLISECONDS);
        assertInstanceOf(
                TimeoutException.class,
                assertThrows(ExecutionException.class, () -> timed.get(2, SECONDS)).getCause());
        assertFalse(timed.cancel(true));
        assertFalse(client.next.isDone(), "orTimeout only changes the result future");
        client.next.complete(new Response(() -> BODY)); // Release retained callbacks.
    }

    @Test
    void cancellationDuringDecodingWinsEvenAfterTransportCompletion() throws Exception {
        StubClient client = new StubClient();
        CompletableFuture<Boolean> result = evaluator(client).testAsync(STATE, Q);
        CountDownLatch decoding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            CompletableFuture<Void> completion =
                    CompletableFuture.runAsync(
                            () ->
                                    client.next.complete(
                                            new Response(
                                                    () -> {
                                                        decoding.countDown();
                                                        await(release);
                                                        return BODY;
                                                    })),
                            executor);
            assertTrue(decoding.await(2, SECONDS));
            assertTrue(result.cancel(false));
            assertFalse(client.next.isCancelled(), "response already arrived");
            release.countDown();
            completion.get(2, SECONDS);
            assertTrue(result.isCancelled(), "late decoder output cannot replace cancellation");
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(2, SECONDS));
        }
    }

    @Test
    void simultaneousCancellationAndResponseHaveOneStableOutcome() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 20; i++) {
                StubClient client = new StubClient();
                CompletableFuture<Boolean> result = evaluator(client).testAsync("s", Q);
                CountDownLatch ready = new CountDownLatch(2);
                CountDownLatch go = new CountDownLatch(1);
                CompletableFuture<Boolean> cancelled =
                        CompletableFuture.supplyAsync(
                                () -> {
                                    ready.countDown();
                                    await(go);
                                    return result.cancel(true);
                                },
                                executor);
                CompletableFuture<Void> delivered =
                        CompletableFuture.runAsync(
                                () -> {
                                    ready.countDown();
                                    await(go);
                                    client.next.complete(new Response(() -> BODY));
                                },
                                executor);
                assertTrue(ready.await(2, SECONDS));
                go.countDown();
                boolean cancellationWon = cancelled.get(2, SECONDS);
                delivered.get(2, SECONDS);
                if (cancellationWon) assertThrows(CancellationException.class, result::join);
                else assertTrue(result.get(2, SECONDS));
                assertEquals(cancellationWon, result.isCancelled());
                assertEquals(1, client.sends);
            }
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(2, SECONDS));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void localHttpAbortAndRequestTimeoutReleaseConnectionButNotCallerExecutor(boolean timeout)
            throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(4);
        try (ServerSocket server =
                new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            server.setSoTimeout(5000);
            CountDownLatch received = new CountDownLatch(1);
            CompletableFuture<Boolean> disconnected =
                    CompletableFuture.supplyAsync(
                            () -> {
                                try (Socket socket = server.accept()) {
                                    socket.setSoTimeout(5000);
                                    InputStream input = socket.getInputStream();
                                    StringBuilder headers = new StringBuilder();
                                    while (!headers.toString().endsWith("\r\n\r\n")) {
                                        int next = input.read();
                                        assertTrue(next >= 0 && headers.length() < 8192);
                                        headers.append((char) next);
                                    }
                                    int length =
                                            Arrays.stream(headers.toString().split("\r\n"))
                                                    .filter(
                                                            line ->
                                                                    line.regionMatches(
                                                                            true,
                                                                            0,
                                                                            "Content-Length:",
                                                                            0,
                                                                            15))
                                                    .mapToInt(
                                                            line ->
                                                                    Integer.parseInt(
                                                                            line.substring(15)
                                                                                    .trim()))
                                                    .findFirst()
                                                    .orElseThrow();
                                    assertEquals(length, input.readNBytes(length).length);
                                    received.countDown();
                                    // Deliberately send no headers. EOF or reset proves an actual
                                    // transport abort.
                                    try {
                                        return input.read() == -1;
                                    } catch (SocketException reset) {
                                        return true;
                                    }
                                } catch (IOException failure) {
                                    throw new CompletionException(failure);
                                }
                            },
                            executor);
            HttpClient http =
                    HttpClient.newBuilder()
                            .executor(executor)
                            .version(HttpClient.Version.HTTP_1_1)
                            .build();
            JevEvaluator evaluator =
                    JevEvaluator.builder("safe-key")
                            .httpClient(http)
                            .baseUri(URI.create("http://localhost:" + server.getLocalPort()))
                            .timeout(Duration.ofSeconds(timeout ? 2 : 10))
                            .build();
            CompletableFuture<Boolean> result = evaluator.testAsync(STATE, Q);
            assertTrue(received.await(3, SECONDS));
            if (timeout) {
                assertThrows(ExecutionException.class, () -> result.get(4, SECONDS));
                assertFailure(result, FailureCategory.TIMEOUT);
                assertFalse(result.isCancelled());
            } else {
                assertFalse(result.isDone());
                assertTrue(result.cancel(false));
                assertThrows(CancellationException.class, result::join);
            }
            assertTrue(disconnected.get(3, SECONDS));
            assertEquals(7, executor.submit(() -> 7).get(2, SECONDS));
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(6, SECONDS));
        }
    }

    private static JevEvaluator evaluator(StubClient client) {
        return JevEvaluator.builder("safe-key").httpClient(client).build();
    }

    private static void assertFailure(CompletableFuture<?> result, FailureCategory category) {
        JevEvaluationException failure =
                assertInstanceOf(
                        JevEvaluationException.class,
                        assertThrows(CompletionException.class, result::join).getCause());
        assertEquals(category, failure.category());
        assertSame(
                failure,
                assertThrows(ExecutionException.class, () -> result.get(2, SECONDS)).getCause());
        assertNull(failure.getCause());
        assertFalse(failure.getMessage().contains("PRIVATE"));
        assertTrue(failure.httpStatusCode().isEmpty());
        assertTrue(failure.requestId().isEmpty());
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(3, SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    private record Response(Supplier<String> content) implements HttpResponse<String> {
        public int statusCode() {
            return 200;
        }

        public HttpRequest request() {
            return HttpRequest.newBuilder(URI.create("http://localhost/")).build();
        }

        public Optional<HttpResponse<String>> previousResponse() {
            return Optional.empty();
        }

        public HttpHeaders headers() {
            return HttpHeaders.of(
                    Map.of("x-typesafe-request-id", List.of("header-id")), (name, value) -> true);
        }

        public String body() {
            return content.get();
        }

        public Optional<SSLSession> sslSession() {
            return Optional.empty();
        }

        public URI uri() {
            return request().uri();
        }

        public HttpClient.Version version() {
            return HttpClient.Version.HTTP_1_1;
        }
    }

    private static final class StubClient extends HttpClient {
        private static final HttpClient CONFIG = HttpClient.newHttpClient();
        private CompletableFuture<HttpResponse<String>> next = new CompletableFuture<>();
        private RuntimeException startupFailure;
        private int sends;

        @Override
        @SuppressWarnings("unchecked")
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request, HttpResponse.BodyHandler<T> handler) {
            sends++;
            if (startupFailure != null) throw startupFailure;
            return (CompletableFuture<HttpResponse<T>>) (CompletableFuture<?>) next;
        }

        public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request,
                HttpResponse.BodyHandler<T> handler,
                HttpResponse.PushPromiseHandler<T> push) {
            return sendAsync(request, handler);
        }

        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
            throw new AssertionError("async evaluation must not call send");
        }

        public Optional<CookieHandler> cookieHandler() {
            return CONFIG.cookieHandler();
        }

        public Optional<Duration> connectTimeout() {
            return CONFIG.connectTimeout();
        }

        public Redirect followRedirects() {
            return CONFIG.followRedirects();
        }

        public Optional<ProxySelector> proxy() {
            return CONFIG.proxy();
        }

        public SSLContext sslContext() {
            return CONFIG.sslContext();
        }

        public SSLParameters sslParameters() {
            return CONFIG.sslParameters();
        }

        public Optional<Authenticator> authenticator() {
            return CONFIG.authenticator();
        }

        public Version version() {
            return CONFIG.version();
        }

        public Optional<Executor> executor() {
            return CONFIG.executor();
        }
    }
}
