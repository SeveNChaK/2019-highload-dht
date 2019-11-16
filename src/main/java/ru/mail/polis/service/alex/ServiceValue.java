package ru.mail.polis.service.alex;

import one.nio.http.Response;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import ru.mail.polis.dao.alex.Value;

import java.net.http.HttpResponse;
import java.util.Arrays;
import java.util.Collection;
import java.util.Objects;

final class ServiceValue implements Comparable<ServiceValue> {
    private static final String TIMESTAMP_HEADER_NAME = "X-OK-Timestamp";
    private static final ServiceValue ABSENT = new ServiceValue(null, -1, State.ABSENT);

    @Nullable private final byte[] data;
    private final long timestamp;
    @NotNull private final State state;

    private ServiceValue(@Nullable final byte[] data,
                         final long timestamp,
                         @NotNull final State state) {
        this.data = data == null ? null : Arrays.copyOf(data, data.length);
        this.timestamp = timestamp;
        this.state = state;
    }

    @NotNull
    private static ServiceValue present(@NotNull final byte[] data,
                                final long timestamp) {
        return new ServiceValue(data, timestamp, State.PRESENT);
    }

    @NotNull
    private static ServiceValue removed(final long timestamp) {
        return new ServiceValue(null, Math.abs(timestamp), State.REMOVED);
    }

    @NotNull
    private static ServiceValue absent() {
        return ABSENT;
    }

    @NotNull
    public static ServiceValue from(@NotNull final HttpResponse<byte[]> response) {
        final var headers = response.headers();
        final var timestamp = headers.firstValue(TIMESTAMP_HEADER_NAME.toLowerCase()).orElse(null);
        if (response.statusCode() == 200) {
            if (timestamp == null) {
                throw new IllegalArgumentException("Wrong input data. Timestamp is absent.");
            }
            return present(response.body(), Long.parseLong(timestamp));
        } else if (response.statusCode() == 404) {
            if (timestamp == null) {
                return absent();
            } else {
                return removed(Long.parseLong(timestamp));
            }
        } else {
            throw new IllegalArgumentException("Bad response");
        }
    }

    @NotNull
    public static ServiceValue from(@Nullable final Value value) {
        if (value == null) {
            return ServiceValue.absent();
        }
        if (value.isDead()) {
            return ServiceValue.removed(value.getTimestamp());
        } else {
            final var data = value.getData();
            final var buffer = new byte[data.remaining()];
            data.duplicate().get(buffer);
            return ServiceValue.present(buffer, value.getTimestamp());
        }
    }

    @NotNull
    static Response transform(@NotNull final ServiceValue serviceValue,
                              final boolean proxied) {
        Response result;
        switch (serviceValue.getState()) {
            case PRESENT:
                result = new Response(Response.OK, Objects.requireNonNull(serviceValue.getData()));
                if (proxied) {
                    result.addHeader(TIMESTAMP_HEADER_NAME + ": " + serviceValue.getTimestamp());
                }
                return result;
            case REMOVED:
                result = new Response(Response.NOT_FOUND, Response.EMPTY);
                if (proxied) {
                    result.addHeader(TIMESTAMP_HEADER_NAME + ": " + serviceValue.getTimestamp());
                }
                return result;
            case ABSENT:
                return new Response(Response.NOT_FOUND, Response.EMPTY);
            default:
                throw new IllegalArgumentException("Wrong input data");
        }
    }

    static ServiceValue merge(@NotNull final Collection<ServiceValue> values) {
        return values.stream()
                .filter(v -> v.getState() != State.ABSENT)
                .max(ServiceValue::compareTo)
                .orElseGet(ServiceValue::absent);
    }

    @Nullable
    private byte[] getData() {
        return data == null
                ? null
                : Arrays.copyOf(data, data.length);
    }

    private long getTimestamp() {
        return timestamp;
    }

    @NotNull
    private State getState() {
        return state;
    }

    @Override
    public int compareTo(@NotNull final ServiceValue serviceValue) {
        return Long.compare(Math.abs(timestamp), Math.abs(serviceValue.timestamp));
    }

    private enum State {
        PRESENT,
        REMOVED,
        ABSENT
    }
}
