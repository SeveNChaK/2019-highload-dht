package ru.mail.polis.service.alex;

import com.google.common.base.Charsets;
import one.nio.http.*;
import one.nio.net.Socket;
import one.nio.server.AcceptorConfig;
import one.nio.server.RejectedSessionException;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.mail.polis.Record;
import ru.mail.polis.dao.DAO;
import ru.mail.polis.service.Service;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.concurrent.Executor;

public class AsyncServiceImpl extends HttpServer implements Service {
    private static final Logger log = LoggerFactory.getLogger(AsyncServiceImpl.class);

    private static final int MIN_PORT = 1024;
    private static final int MAX_PORT = 65535;

    private final DAO dao;
    private final Executor executor;

    public AsyncServiceImpl(final int port, @NotNull final DAO dao, @NotNull final Executor executor) throws IOException {
        super(getConfig(port));
        this.dao = dao;
        this.executor = executor;
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
        final ByteBuffer key = ByteBuffer.wrap(id.getBytes(Charsets.UTF_8));
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

    @Path("/v0/status")
    public void entity(@NotNull final Request request, @NotNull final HttpSession session) throws IOException {
        session.sendResponse(new Response(Response.OK, Response.EMPTY));
    }

    @Override
    public HttpSession createSession(Socket socket) throws RejectedSessionException {
        return new StorageSession(socket, this);
    }

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
        final ByteBuffer from = ByteBuffer.wrap(start.getBytes(Charsets.UTF_8));
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
}
