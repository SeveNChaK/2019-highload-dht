package ru.mail.polis.service.alex;

import com.google.common.base.Charsets;
import com.google.common.base.Predicate;
import one.nio.http.HttpSession;
import one.nio.http.Response;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.mail.polis.dao.DAO;
import ru.mail.polis.dao.alex.LSMDao;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static ru.mail.polis.service.alex.HttpServiceUtils.sendResponse;
import static ru.mail.polis.service.alex.HttpServiceUtils.createEmptyResponse;
import static ru.mail.polis.service.alex.HttpServiceUtils.getResponsesFromReplicas;

final class HttpService {
    private static final Logger log = LoggerFactory.getLogger(HttpService.class);

    @NotNull private final LSMDao dao;
    @NotNull private final Topology<String> topology;
    @NotNull private final HttpClient httpClient;

    HttpService(@NotNull final Executor proxyWorkers,
                @NotNull final DAO dao,
                @NotNull final Topology<String> topology) {
        this.dao = (LSMDao) dao;
        this.topology = topology;
        this.httpClient = HttpClient.newBuilder()
                .executor(proxyWorkers)
                .version(HttpClient.Version.HTTP_2)
                .build();
    }

    void get(@NotNull final HttpSession session,
             @NotNull final MetaRequest meta) {
        if (meta.proxied()) {
            executeIfProxied(session, meta, Method.GET);
            return;
        }

        final var replicas = topology.replicas(
                ByteBuffer.wrap(meta.getId().getBytes(Charsets.UTF_8)), meta.getRf().getFrom());

        final var acks = new AtomicInteger(0);
        final var values = new ArrayList<ServiceValue>();
        if (replicas.contains(topology.whoAmI())) {
            try {
                values.add(ServiceValue.from(
                        dao.getValue(ByteBuffer.wrap(meta.getId().getBytes(Charsets.UTF_8)))));
                acks.incrementAndGet();
            } catch (IOException e) {
                log.error("[{}] Can't get {}", topology.whoAmI(), meta.getId(), e);
            }
        }
        if (acks.get() == meta.getRf().getAck()) {
            sendResponse(session, ServiceValue.transform(ServiceValue.merge(values), false));
            return;
        }

        getResponsesFromReplicas(httpClient, topology, replicas, meta)
                .whenCompleteAsync((responses, failure) -> {
                    for (final var response : responses) {
                        values.add(ServiceValue.from(response));
                        acks.incrementAndGet();
                        if (acks.get() == meta.getRf().getAck()) {
                            sendResponse(session, ServiceValue.transform(ServiceValue.merge(values), false));
                            return;
                        }
                    }
                    sendResponse(session, new Response(Response.GATEWAY_TIMEOUT, Response.EMPTY));
                })
                .exceptionally(ex -> {
                    log.error("Failed to get response from node", ex);
                    return null;
                });
    }

    void upsert(@NotNull final HttpSession session,
                @NotNull final MetaRequest meta) {
        if (meta.proxied()) {
            executeIfProxied(session, meta, Method.UPSERT);
            return;
        }

        final var replicas = topology.replicas(
                ByteBuffer.wrap(meta.getId().getBytes(Charsets.UTF_8)), meta.getRf().getFrom());

        final var acks = new AtomicInteger(0);
        if (replicas.contains(topology.whoAmI())) {
            try {
                dao.upsert(ByteBuffer.wrap(meta.getId().getBytes(Charsets.UTF_8)), meta.getValue());
                acks.incrementAndGet();
            } catch (IOException e) {
                log.error("[{}] Can't upsert {}={}",
                        topology.whoAmI(), meta.getId(), meta.getValue(), e);
            }
        }
        if (acks.get() == meta.getRf().getAck()) {
            sendResponse(session, new Response(Response.CREATED, Response.EMPTY));
            return;
        }
        getResponsesFromReplicas(httpClient, topology, replicas, meta)
                .whenCompleteAsync((responses, failure) -> {
                    sendResponseIfNecessary(
                            acks.get(),
                            session,
                            meta,
                            responses,
                            statusCode -> statusCode == 201,
                            () -> new Response(Response.CREATED, Response.EMPTY));
                })
                .exceptionally(ex -> {
                    log.error("Failed to upsert response from node", ex);
                    return null;
                });
    }

    void delete(@NotNull final HttpSession session,
                @NotNull final MetaRequest meta) {
        if (meta.proxied()) {
            executeIfProxied(session, meta, Method.DELETE);
            return;
        }

        final var replicas = topology.replicas(
                ByteBuffer.wrap(meta.getId().getBytes(Charsets.UTF_8)), meta.getRf().getFrom());

        final var acks = new AtomicInteger(0);
        if (replicas.contains(topology.whoAmI())) {
            try {
                dao.remove(ByteBuffer.wrap(meta.getId().getBytes(Charsets.UTF_8)));
                acks.incrementAndGet();
            } catch (IOException e) {
                log.error("[{}] Can't remove {}={}",
                        topology.whoAmI(), meta.getId(), meta.getValue(), e);
            }
        }
        if (acks.get() == meta.getRf().getAck()) {
            sendResponse(session, new Response(Response.ACCEPTED, Response.EMPTY));
            return;
        }
        CompletableFuture<List<HttpResponse<byte[]>>> responseFromRplicas
                = getResponsesFromReplicas(httpClient, topology, replicas, meta);
        responseFromRplicas
                .whenCompleteAsync((responses, failure) -> {
                    sendResponseIfNecessary(
                            acks.get(),
                            session,
                            meta,
                            responses,
                            statusCode -> statusCode == 202,
                            () -> new Response(Response.ACCEPTED, Response.EMPTY));
                })
                .exceptionally(ex -> {
                    log.error("Failed to get responses", ex);
                    return null;
                });
    }

    private void sendResponseIfNecessary(int acks,
                                         @NotNull final HttpSession session,
                                         @NotNull final MetaRequest meta,
                                         @NotNull final List<HttpResponse<byte[]>> responses,
                                         @NotNull final Predicate<Integer> predicate,
                                         @NotNull final Supplier<Response> supplier) {
        for (final var response : responses) {
            if (predicate.test(response.statusCode())) {
                acks++;
                if (acks >= meta.getRf().getAck()) {
                    sendResponse(session, supplier.get());
                    return;
                }
            }
        }
        sendResponse(session, new Response(Response.GATEWAY_TIMEOUT, Response.EMPTY));
    }

    private void executeIfProxied(@NotNull final HttpSession session,
                                  @NotNull final MetaRequest meta,
                                  @NotNull final Method method) {
        CompletableFuture.runAsync(() -> {
            try {
                switch (method) {
                    case GET:
                        final var response = ServiceValue.transform(
                                ServiceValue.from(dao.getValue(ByteBuffer.wrap(meta.getId().getBytes(Charsets.UTF_8)))),
                                true);
                        sendResponse(session, response);
                        break;
                    case UPSERT:
                        dao.upsert(ByteBuffer.wrap(meta.getId().getBytes(Charsets.UTF_8)), meta.getValue());
                        sendResponse(session, createEmptyResponse(Response.CREATED));
                        break;
                    case DELETE:
                        dao.remove(ByteBuffer.wrap(meta.getId().getBytes(Charsets.UTF_8)));
                        sendResponse(session, new Response(Response.ACCEPTED, Response.EMPTY));
                        break;
                    default:
                        log.error("Invalid method");
                        break;
                }
            } catch (NoSuchElementException e) {
                sendResponse(session, createEmptyResponse(Response.NOT_FOUND));
            } catch (IOException e) {
                sendResponse(session, createEmptyResponse(Response.INTERNAL_ERROR));
            }
        }).exceptionally(ex -> {
            log.error("Failed", ex);
            return null;
        });
    }

    private enum Method {
        GET, UPSERT, DELETE
    }
}
