package ru.mail.polis.dao.alex;

import org.jetbrains.annotations.NotNull;

import java.nio.ByteBuffer;
import java.util.Comparator;

final class Row implements Comparable<Row> {
    private final long index;
    @NotNull private final ByteBuffer key;
    @NotNull private final Value value;

    private static final Comparator<Row> COMPARATOR =
            Comparator
                    .comparing(Row::getKey)
                    .thenComparing(Row::getValue)
                    .thenComparing((r) -> -r.getIndex());

    private Row(final long index,
            @NotNull final ByteBuffer key,
            @NotNull final Value value) {
        this.index = index;
        this.key = key;
        this.value = value;
    }

    public static Row of(final long index,
            @NotNull final ByteBuffer key,
            @NotNull final Value value) {
        return new Row(index, key, value);
    }

    @NotNull
    public ByteBuffer getKey() {
        return key.asReadOnlyBuffer();
    }

    @NotNull
    public Value getValue() {
        return value;
    }

    public static long getSizeOfFlushedRow(
            @NotNull final ByteBuffer key,
            @NotNull final ByteBuffer value) {
        return Integer.BYTES + key.remaining() + Long.BYTES
                + (value.remaining() == 0 ? 0 : Long.BYTES + value.remaining());
    }

    private long getIndex() {
        return index;
    }

    @Override
    public int compareTo(@NotNull final Row row) {
    return COMPARATOR.compare(this, row);
}
}
