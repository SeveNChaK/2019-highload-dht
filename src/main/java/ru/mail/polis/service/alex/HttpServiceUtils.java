package ru.mail.polis.service.alex;

import one.nio.http.HttpSession;
import one.nio.http.Response;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

public class HttpServiceUtils {
    private static final Logger log = LoggerFactory.getLogger(HttpService.class);
    private static final String PROXY_HEADER_NAME = "X-OK-Proxy";
    private static final String PROXY_HEADER_VALUE = "true";
    static final String PROXY_HEADER = PROXY_HEADER_NAME + ": " + PROXY_HEADER_VALUE;

    static CompletableFuture<List<HttpResponse<byte[]>>> getResponsesFromReplicas(
            @NotNull final HttpClient httpClient,
            @NotNull final Topology<String> topology,
            @NotNull final List<String> replicas,
            @NotNull final MetaRequest meta) {
        final int acks = replicas.remove(topology.whoAmI())
                ? meta.getRf().getAck() - 1
                : meta.getRf().getAck();
        if (acks > 0) {
            final var futures = new ArrayList<CompletableFuture<HttpResponse<byte[]>>>();
            for (final var node : replicas) {
                final var requestBuilder = HttpRequest.newBuilder(URI.create(node + meta.getRequest().getURI()))
                        .setHeader(PROXY_HEADER_NAME, PROXY_HEADER_VALUE);
                final var body = meta.getRequest().getBody();
                switch (meta.getMethod()) {
                    case GET:
                        requestBuilder.GET();
                        break;
                    case PUT:
                        requestBuilder.PUT(HttpRequest.BodyPublishers.ofByteArray(body));
                        break;
                    case DELETE:
                        requestBuilder.DELETE();
                        break;
                    case POST:
                        requestBuilder.POST(HttpRequest.BodyPublishers.ofByteArray(body));
                        break;
                    default:
                        throw new IllegalArgumentException("Service doesn't support method "
                                + meta.getMethod());
                }
                futures.add(httpClient.sendAsync(
                        requestBuilder.build(),
                        HttpResponse.BodyHandlers.ofByteArray()));
            }
            return getFirstResponses(futures, acks);
        }
        return CompletableFuture.completedFuture(Collections.emptyList());
    }

    @SuppressWarnings("FutureReturnValueIgnored")
    static <T> CompletableFuture<List<T>> getFirstResponses(@NotNull final List<CompletableFuture<T>> futures,
                                                                    final int acks) {
        if (futures.size() < acks) {
            throw new IllegalArgumentException("Number of expected responses = "
                    + futures.size() + " but acks = " + acks);
        }
        final int maxFails = futures.size() - acks;
        final var fails = new AtomicInteger(0);
        final var responses = new CopyOnWriteArrayList<T>();
        final var result = new CompletableFuture<List<T>>();

        final BiConsumer<T,Throwable> biConsumer = (value, failure) -> {
            log.info("{}", value);
            if ((failure != null || value == null) && fails.incrementAndGet() > maxFails) {
                result.complete(responses);
            } else if (!result.isDone() && value != null) {
                responses.add(value);
                if (responses.size() == acks) {
                    result.complete(responses);
                }
            }
        };
        for (final var future : futures) {
            future.orTimeout(1, TimeUnit.SECONDS)
                    .whenCompleteAsync(biConsumer)
                    .exceptionally(ex -> {
                        log.error("Failed to get response", ex);
                        return null;
                    });
        }
        return result;
    }

    static void sendResponse(@NotNull final HttpSession session,
                             @NotNull final Response response) {
        try {
            session.sendResponse(response);
        } catch (IOException e) {
            try {
                session.sendError(Response.INTERNAL_ERROR, "Internal error");
            } catch (IOException ex) {
                log.error("Error with send response {}", ex.getMessage());
            }
        }
    }

    @NotNull
    static Response createEmptyResponse(final String resultCode) {
        return new Response(resultCode, Response.EMPTY);
    }
}
