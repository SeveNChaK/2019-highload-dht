package ru.mail.polis.service.alex;

import com.google.common.base.Charsets;
import one.nio.http.HttpServer;
import one.nio.http.HttpClient;
import one.nio.http.HttpServerConfig;
import one.nio.http.Request;
import one.nio.http.Response;
import one.nio.http.Param;
import one.nio.http.Path;
import one.nio.http.HttpSession;
import one.nio.http.HttpException;
import one.nio.net.ConnectionString;
import one.nio.net.Socket;
import one.nio.pool.PoolException;
import one.nio.server.AcceptorConfig;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.mail.polis.Record;
import ru.mail.polis.dao.DAO;
import ru.mail.polis.service.Service;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.Executor;

public class AsyncServiceImpl extends HttpServer implements Service {
    private static final Logger log = LoggerFactory.getLogger(AsyncServiceImpl.class);

    private static final int MIN_PORT = 1024;
    private static final int MAX_PORT = 65535;

    private final DAO dao;
    private final Executor executor;
    private final HashingTopology nodes;
    private final Map<String, HttpClient> pool;

    /**
     * The constructor of asynchronous server.
     *
     * @param port - server port
     * @param dao - is dao
     * @param executor - is executor
     * @throws IOException - input/output exception
     */
    public AsyncServiceImpl(final int port,
                            @NotNull final DAO dao,
                            @NotNull final Executor executor,
                            @NotNull final HashingTopology nodes) throws IOException {
        super(getConfig(port));
        this.dao = dao;
        this.executor = executor;
        this.nodes = nodes;
        this.pool = new HashMap<>();
        for (final String node : nodes.all()) {
            if (nodes.isMe(node)) {
                continue;
            }
            assert !pool.containsKey(node);
            pool.put(node, new HttpClient(new ConnectionString(node + "?timeout=100")));
        }
    }

    private static HttpServerConfig getConfig(final int port) {
        if (port <= MIN_PORT || port >= MAX_PORT) {
            throw new IllegalArgumentException();
        }
        final AcceptorConfig acceptor = new AcceptorConfig();
        acceptor.port = port;
        final HttpServerConfig config = new HttpServerConfig();
        config.acceptors = new AcceptorConfig[]{acceptor};
        return config;
    }

    /**
     * Received request.
     *
     * @param request - request that was received (GET, PUT, DELETE)
     * @param id - id element
     */
    @Path("/v0/entity")
    public void entity(@NotNull final Request request,
                       @NotNull final HttpSession session,
                       @Param("id") final String id) throws IOException {
        if (id == null || id.isEmpty()) {
            session.sendError(Response.BAD_REQUEST, "No Id!");
        }
        final ByteBuffer key = ByteBuffer.wrap(Objects.requireNonNull(id).getBytes(Charsets.UTF_8));
        final String node = nodes.primaryFor(key);
        if (!nodes.isMe(node)) {
            executeAsync(session, () -> proxy(node, request));
            return;
        }
        switch (request.getMethod()) {
            case Request.METHOD_GET:
                executeAsync(session, () -> get(key));
                break;
            case Request.METHOD_PUT:
                executeAsync(session, () -> put(request, key));
                break;
            case Request.METHOD_DELETE:
                executeAsync(session, () -> delete(key));
                break;
            default:
                session.sendError(Response.METHOD_NOT_ALLOWED, "Method not allowed!");
                break;
        }
    }

    /**
     * Send response 'OK' on request 'status'.
     *
     * @param request - request object.
     * @param session - HttpSession
     * @throws IOException - input/output exception
     */
    @Path("/v0/status")
    public void entity(@NotNull final Request request, @NotNull final HttpSession session) throws IOException {
        session.sendResponse(new Response(Response.OK, Response.EMPTY));
    }

    @Override
    public HttpSession createSession(final Socket socket){
        return new StorageSession(socket, this);
    }

    /**
     * Resource for range values.
     *
     * @param request - request object
     * @param session - HttpSession
     * @param start - start key for range
     * @param end - end key for range
     * @throws IOException - input/output exception
     */
    @Path("/v0/entities")
    public void entities(@NotNull final Request request,
                         @NotNull final HttpSession session,
                         @Param("start") final String start,
                         @Param("end") final String end) throws IOException {
        if (start == null || start.isEmpty()) {
            session.sendError(Response.BAD_REQUEST, "No start!");
        }
        if (end != null && end.isEmpty()) {
            session.sendError(Response.BAD_REQUEST, "End is empty!");
        }
        final ByteBuffer from = ByteBuffer.wrap(Objects.requireNonNull(start).getBytes(Charsets.UTF_8));
        final ByteBuffer to = end == null ? null : ByteBuffer.wrap(end.getBytes(Charsets.UTF_8));
        try {
            final Iterator<Record> records = dao.range(from, to);
            ((StorageSession) session).stream(records);
        } catch (IOException e) {
            session.sendError(Response.INTERNAL_ERROR, "");
            log.error("IOException on entities!", e);
        }
    }

    @Override
    public void handleDefault(@NotNull final Request request, @NotNull final HttpSession session) throws IOException {
        session.sendResponse(new Response(Response.BAD_REQUEST, Response.EMPTY));
    }

    private Response get(@NotNull final ByteBuffer key) {
        final ByteBuffer value;
        try {
            value = dao.get(key);
            final ByteBuffer duplicate = value.duplicate();
            final byte[] body = new byte[duplicate.remaining()];
            duplicate.get(body);
            return new Response(Response.OK, body);
        } catch (NoSuchElementException e) {
            return new Response(Response.NOT_FOUND, Response.EMPTY);
        } catch (IOException e) {
            return new Response(Response.INTERNAL_ERROR, Response.EMPTY);
        }
    }

    private Response put(@NotNull final Request request,
                         @NotNull final ByteBuffer key) {
        try {
            dao.upsert(key, ByteBuffer.wrap(request.getBody()));
            return new Response(Response.CREATED, Response.EMPTY);
        } catch (IOException e) {
            return new Response(Response.INTERNAL_ERROR, Response.EMPTY);
        }
    }

    private Response delete(@NotNull final ByteBuffer key) {
        try {
            dao.remove(key);
            return new Response(Response.ACCEPTED, Response.EMPTY);
        } catch (IOException e) {
            return new Response(Response.INTERNAL_ERROR, Response.EMPTY);
        }
    }

    private void executeAsync(@NotNull final HttpSession session,
                              @NotNull final Action action) {
        executor.execute(() -> {
            try {
                session.sendResponse(action.action());
            } catch (IOException e) {
                try {
                    session.sendError(Response.INTERNAL_ERROR, "");
                } catch (IOException ex) {
                    log.error("IOException on session send error!", e);
                }
            }
        });
    }

    private Response proxy(@NotNull final String node, @NotNull final Request request) {
        assert !nodes.isMe(node);
        try {
            return pool.get(node).invoke(request);
        } catch (InterruptedException | PoolException | HttpException | IOException e) {
            log.error("Cant proxy", e);
            return new Response(Response.INTERNAL_ERROR, Response.EMPTY);
        }
    }
}
