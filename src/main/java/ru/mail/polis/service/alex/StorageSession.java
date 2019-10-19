package ru.mail.polis.service.alex;

import one.nio.http.HttpServer;
import one.nio.http.HttpSession;
import one.nio.http.Response;
import one.nio.net.Socket;
import org.jetbrains.annotations.NotNull;
import ru.mail.polis.Record;

import java.io.IOException;
import java.util.Iterator;

public class StorageSession extends HttpSession {
    private TransferChunks chunks;

    StorageSession(Socket socket, HttpServer server) {
        super(socket, server);
    }

    void stream(@NotNull final Iterator<Record> iterator) throws IOException {
        chunks = new TransferChunks(iterator);

        final Response response = new Response(Response.OK);
        response.addHeader("Transfer-Encoding: chunked");
        writeResponse(response, false);

        next();
    }

    private void next() throws IOException {
        while (chunks.hasNext() && queueHead == null) {
            final byte[] data = chunks.next();
            write(data, 0, data.length);
        }

        if (!chunks.hasNext()) {
            final byte[] end = chunks.end();
            write(end, 0, end.length);

            server.incRequestsProcessed();

            if ((handling = pipeline.pollFirst()) != null) {
                if (handling == FIN) {
                    scheduleClose();
                } else {
                    server.handleRequest(handling, this);
                }
            }
        }
    }

    @Override
    protected void processWrite() throws Exception {
        super.processWrite();
        next();
    }
}
