package ru.mail.polis.dao.alex;

import org.jetbrains.annotations.NotNull;
import ru.mail.polis.Record;

import java.nio.ByteBuffer;

final class Row implements Comparable<Row> {
    private final int index;
    private final ByteBuffer key;
    private final ByteBuffer value;
    private final int status;

    private Row(final int index,
            @NotNull final ByteBuffer key,
            @NotNull final ByteBuffer value,
            final int status) {
        this.index = index;
        this.key = key;
        this.value = value;
        this.status = status;
    }

    public static Row of(final int index,
            @NotNull final ByteBuffer key,
            @NotNull final ByteBuffer value,
            final int status) {
        return new Row(index, key, value, status);
    }

    /**
     * Creates an object of class Record.
     *
     * @return Record
     */
    Record getRecord() {
        if (isDead()) {
            return Record.of(key, Constants.TOMBSTONE);
        } else {
            return Record.of(key, value);
        }
    }

    boolean isDead() {
        return status == Constants.DEAD;
    }

    ByteBuffer getKey() {
        return key.asReadOnlyBuffer();
    }

    ByteBuffer getValue() {
        return value.asReadOnlyBuffer();
    }

    private int getIndex() {
        return index;
    }

    @Override
    public int compareTo(@NotNull final Row o) {
        if (key.compareTo(o.getKey()) == 0) {
            return -Integer.compare(index, o.getIndex());
        }
        return key.compareTo(o.getKey());
    }
}
