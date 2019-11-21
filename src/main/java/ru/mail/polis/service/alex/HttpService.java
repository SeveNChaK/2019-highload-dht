package ru.mail.polis.service.alex;

import com.google.common.base.Charsets;
import one.nio.http.HttpClient;
import one.nio.http.HttpException;
import one.nio.http.Request;
import one.nio.http.Response;
import one.nio.net.ConnectionString;
import one.nio.pool.PoolException;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.mail.polis.dao.DAO;
import ru.mail.polis.dao.alex.LSMDao;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.NoSuchElementException;
import java.util.List;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorCompletionService;

final class HttpService {

    private static final Logger log = LoggerFactory.getLogger(HttpService.class);
    static final String PROXY_HEADER = "X-OK-Proxy: true";

    @NotNull private final CompletionService<Response> proxyService;
    @NotNull private final LSMDao dao;
    @NotNull private final Topology<String> topology;
    @NotNull private final Map<String, HttpClient> pool;

    HttpService(@NotNull final Executor proxyWorkers,
                @NotNull final DAO dao,
                @NotNull final Topology<String> topology) {
        this.proxyService = new ExecutorCompletionService<>(proxyWorkers);
        this.dao = (LSMDao) dao;
        this.topology = topology;
        this.pool = new HashMap<>();
        for (final var node : topology.all()) {
            if (topology.isMe(node)) {
                continue;
            }
            pool.put(node, new HttpClient(new ConnectionString(node + "?timeout=100")));
        }
    }

    @NotNull
    Response get(@NotNull final MetaRequest meta) {
        if (meta.proxied()) {
            try {
                return ServiceValue.transform(
                        ServiceValue.from(dao.getValue(ByteBuffer.wrap(meta.getId().getBytes(Charsets.UTF_8)))),
                        true);
            } catch (IOException e) {
                return new Response(Response.INTERNAL_ERROR, Response.EMPTY);
            }
        }

        final var replicas = topology.replicas(
                ByteBuffer.wrap(meta.getId().getBytes(Charsets.UTF_8)), meta.getRf().getFrom());
        int acks = 0;
        final var values = new ArrayList<ServiceValue>();
        if (replicas.contains(topology.whoAmI())) {
            try {
                values.add(ServiceValue.from(
                        dao.getValue(ByteBuffer.wrap(meta.getId().getBytes(Charsets.UTF_8)))));
                acks++;
            } catch (IOException e) {
                log.error("[{}] Can't get {}", topology.whoAmI(), meta.getId(), e);
            }
        }
        if (acks == meta.getRf().getAck()) {
            return ServiceValue.transform(ServiceValue.merge(values), false);
        }
        for (final var response : getResponsesFromRelicas(replicas, meta)) {
            try {
                values.add(ServiceValue.from(response));
                acks++;
                if (acks == meta.getRf().getAck()) {
                    return ServiceValue.transform(ServiceValue.merge(values), false);
                }
            } catch (IllegalArgumentException e) {
                log.error("[{}] Bad response", topology.whoAmI(), e);
            }
        }
        return new Response(Response.GATEWAY_TIMEOUT, Response.EMPTY);
    }

    @NotNull
    Response upsert(@NotNull final MetaRequest meta) {
        Response result = null;
        if (meta.proxied()) {
            try {
                dao.upsert(ByteBuffer.wrap(meta.getId().getBytes(Charsets.UTF_8)), meta.getValue());
                result = createResponse(Response.CREATED);
            } catch (NoSuchElementException e) {
                result = createResponse(Response.NOT_FOUND);
            } catch (IOException e) {
                result = createResponse(Response.INTERNAL_ERROR);
            }
        }

        if (result != null){
            return result;
        }

        final var replicas = topology.replicas(
                ByteBuffer.wrap(meta.getId().getBytes(Charsets.UTF_8)), meta.getRf().getFrom());

        int acks = 0;
        if (replicas.contains(topology.whoAmI())) {
            try {
                dao.upsert(ByteBuffer.wrap(meta.getId().getBytes(Charsets.UTF_8)), meta.getValue());
                acks++;
            } catch (IOException e) {
                log.error("[{}] Can't upsert {}={}",
                        topology.whoAmI(), meta.getId(), meta.getValue(), e);
            }
        }
        if (acks >= meta.getRf().getAck()) {
            result = createResponse(Response.CREATED);
        } else {
            for (final var response : getResponsesFromRelicas(replicas, meta)) {
                if (response.getStatus() == 201) {
                    acks++;
                    if (acks == meta.getRf().getAck()) {
                        result = createResponse(Response.CREATED);
                        break;
                    }
                }
            }
        }
        if (result == null){
            result = createResponse(Response.GATEWAY_TIMEOUT);
        }
        return result;
    }

    @NotNull
    private Response createResponse(final String resultCode){
        return new Response(resultCode, Response.EMPTY);
    }

    @NotNull
    Response delete(@NotNull final MetaRequest meta) {
        if (meta.proxied()) {
            try {
                dao.remove(ByteBuffer.wrap(meta.getId().getBytes(Charsets.UTF_8)));
                return new Response(Response.ACCEPTED, Response.EMPTY);
            } catch (NoSuchElementException e) {
                return new Response(Response.NOT_FOUND, Response.EMPTY);
            } catch (IOException e) {
                return new Response(Response.INTERNAL_ERROR, Response.EMPTY);
            }
        }

        final var replicas = topology.replicas(
                ByteBuffer.wrap(meta.getId().getBytes(Charsets.UTF_8)), meta.getRf().getFrom());

        int acks = 0;
        if (replicas.contains(topology.whoAmI())) {
            try {
                dao.remove(ByteBuffer.wrap(meta.getId().getBytes(Charsets.UTF_8)));
                acks++;
            } catch (IOException e) {
                log.error("[{}] Can't remove {}={}",
                        topology.whoAmI(), meta.getId(), meta.getValue(), e);
            }
        }
        if (acks == meta.getRf().getAck()) {
            return new Response(Response.ACCEPTED, Response.EMPTY);
        }
        for (final var response : getResponsesFromRelicas(replicas, meta)) {
            if (response.getStatus() == 202) {
                acks++;
                if (acks == meta.getRf().getAck()) {
                    return new Response(Response.ACCEPTED, Response.EMPTY);
                }
            }
        }
        return new Response(Response.GATEWAY_TIMEOUT, Response.EMPTY);
    }

    @NotNull
    private List<Response> getResponsesFromRelicas(@NotNull final List<String> replicas,
                                                   @NotNull final MetaRequest meta) {
        for (final var node: replicas) {
            if (!topology.isMe(node)) {
                proxyService.submit(() -> proxy(node, meta.getRequest()));
            }
        }
        final var numResponses = replicas.contains(topology.whoAmI())
                ? replicas.size() - 1
                : replicas.size();
        final var responses = new ArrayList<Response>();
        for (int i = 0; i < numResponses; i++) {
            try {
                responses.add(proxyService.take().get());
            } catch (ExecutionException | InterruptedException e) {
                log.error("[{}] Failed computation while proxy", topology.whoAmI(), e);
            }
        }
        return responses;
    }

    @NotNull
    private Response proxy(@NotNull final String node,
                           @NotNull final Request request) throws IOException {
        try {
            request.addHeader(PROXY_HEADER);
            return pool.get(node).invoke(request);
        } catch (InterruptedException | PoolException | IOException | HttpException e) {
            throw new IOException("Can't proxy", e);
        }
    }
}
