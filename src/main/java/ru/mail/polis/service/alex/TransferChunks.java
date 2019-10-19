package ru.mail.polis.service.alex;

import com.google.common.base.Charsets;
import org.jetbrains.annotations.NotNull;
import ru.mail.polis.Record;

import java.nio.ByteBuffer;
import java.util.Iterator;

public class TransferChunks {
    private final Iterator<Record> iterator;
    private static final byte[] SEPARATOR = "\r\n".getBytes(Charsets.UTF_8);
    private static final byte[] NEW_LINE = "\n".getBytes(Charsets.UTF_8);
    private static final byte[] END = "0\r\n\r\n".getBytes(Charsets.UTF_8);

    TransferChunks(@NotNull final Iterator<Record> iterator) {
        this.iterator = iterator;
    }

    /**
     * Get next chunk.
     *
     * @return array of bytes
     */
    public byte[] next() {
        assert hasNext();
        final Record record = iterator.next();
        final byte[] key = toArray(record.getKey());
        final byte[] value = toArray(record.getValue());
        final String length = Integer.toHexString(key.length
                + NEW_LINE.length
                + value.length);
        final int chunkLength = length.length()
                + SEPARATOR.length
                + key.length
                + NEW_LINE.length
                + value.length
                + SEPARATOR.length;
        final byte[] chunk = new byte[chunkLength];
        final ByteBuffer chunkBuff = ByteBuffer.wrap(chunk);
        chunkBuff.put(length.getBytes(Charsets.UTF_8));
        chunkBuff.put(SEPARATOR);
        chunkBuff.put(key);
        chunkBuff.put(NEW_LINE);
        chunkBuff.put(value);
        chunkBuff.put(SEPARATOR);
        return chunk;
    }

    public boolean hasNext() {
        return iterator.hasNext();
    }

    byte[] end() {
        return END.clone();
    }

    private static byte[] toArray(@NotNull final ByteBuffer byteBuffer) {
        final ByteBuffer copy = byteBuffer.duplicate();
        final byte[] array = new byte[copy.remaining()];
        copy.get(array);
        return array;
    }
}
