package ru.mail.polis.dao.alex;

import org.jetbrains.annotations.NotNull;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

class FileTable implements Closeable {
    private final int count;
    private final int fileIndex;
    private final FileChannel fc;
    private final File file;

    /**
     * Creates an object that is a file on disk, with the ability to create an iterator on this file.
     *
     * @param file file for which you need to get a table
     * @throws IOException if an I/O error is thrown by a read method
     */
    FileTable(@NotNull final File file) throws IOException {
        this.file = file;
        this.fileIndex = Integer.parseInt(file
                .getName()
                .substring(Constants.PREFIX.length(), file.getName().length() - Constants.SUFFIX.length()));
        this.fc = openRead(file);
        final ByteBuffer countBB = ByteBuffer.allocate(Integer.BYTES);
        fc.read(countBB, fc.size() - Integer.BYTES);
        countBB.rewind();
        this.count = countBB.getInt();
    }

    private static FileChannel openRead(@NotNull final File file) throws IOException {
        return FileChannel.open(file.toPath(), StandardOpenOption.READ);
    }

    /**
     * Creates file iterator.
     *
     * @param from the key from which the iterator will begin
     * @return file iterator
     * @throws IOException if an I/O error is thrown by a read method
     */
    @NotNull
    Iterator<Row> iterator(@NotNull final ByteBuffer from) throws IOException {
        return new Iterator<Row>() {
            int index = getOffsetsIndex(from);

            @Override
            public boolean hasNext() {
                return index < count;
            }

            @Override
            public Row next() {
                assert hasNext();
                Row row = null;
                try {
                    row = getRowAt(index++);
                } catch (IOException e) {
                    e.printStackTrace();
                }
                return row;
            }
        };
    }

    private int getOffsetsIndex(@NotNull final ByteBuffer from) throws IOException {
        int left = 0;
        int right = count - 1;
        while (left <= right) {
            final int middle = left + (right - left) / 2;
            final int resCmp = from.compareTo(getKeyAt(middle));
            if (resCmp < 0) {
                right = middle - 1;
            } else if (resCmp > 0) {
                left = middle + 1;
            } else {
                return middle;
            }
        }
        return left;
    }

    private int getOffset(final int i) throws IOException {
        final ByteBuffer offsetBB = ByteBuffer.allocate(Integer.BYTES);
        fc.read(offsetBB, fc.size() - Integer.BYTES - (long) Integer.BYTES * count + (long) Integer.BYTES * i);
        offsetBB.rewind();
        return offsetBB.getInt();
    }

    private ByteBuffer getKeyAt(final int i) throws IOException {
        assert 0 <= i && i < count;
        final int offset = getOffset(i);
        return readByteBuffer(offset);
    }

    private ByteBuffer readByteBuffer(final int offset) throws IOException {
        final ByteBuffer bufferSize = ByteBuffer.allocate(Integer.BYTES);
        fc.read(bufferSize, offset);
        bufferSize.rewind();
        final ByteBuffer buffer = ByteBuffer.allocate(bufferSize.getInt());
        fc.read(buffer, offset + Integer.BYTES);
        buffer.rewind();
        return buffer.slice();
    }

    private Row getRowAt(final int i) throws IOException {
        assert 0 <= i && i < count;
        int offset = getOffset(i);

        //Key
        final ByteBuffer keyBB = readByteBuffer(offset);
        offset += Integer.BYTES + keyBB.remaining();

        //Status
        final ByteBuffer statusBB = ByteBuffer.allocate(Integer.BYTES);
        fc.read(statusBB, offset);
        statusBB.rewind();
        final int status = statusBB.getInt();
        offset += Integer.BYTES;

        //Value
        if (status == Constants.DEAD) {
            return Row.of(fileIndex, keyBB.slice(), Constants.TOMBSTONE, status);
        } else {
            final ByteBuffer valueBB = readByteBuffer(offset);
            return Row.of(fileIndex, keyBB.slice(), valueBB.slice(), status);
        }
    }

    /**
     * Writes data to file. First writes all row: key length, key, status (DEAD, ALIVE),
     * value length, value. Then writes offsets array, and then writes amount of row.
     *
     * @param to   file being recorded
     * @param rows strings to be written to file
     * @throws IOException if an I/O error is thrown by a write method
     */
    static void write(@NotNull final File to,
            @NotNull final Iterator<Row> rows) throws IOException {
        try (FileChannel fileChannel = FileChannel.open(to.toPath(),
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE)) {
            final List<Integer> offsets = new ArrayList<>();
            int offset = 0;
            while (rows.hasNext()) {
                offsets.add(offset);
                final Row row = rows.next();

                //Key
                offset += writeByteBuffer(fileChannel, row.getKey());

                //Value
                if (row.isDead()) {
                    offset += fileChannel.write(Bytes.fromInt(Constants.DEAD));
                } else {
                    offset += fileChannel.write(Bytes.fromInt(Constants.ALIVE));
                    offset += writeByteBuffer(fileChannel, row.getValue()); // row.getValue().getData()
                }
            }
            for (final Integer elemOffSets : offsets) {
                fileChannel.write(Bytes.fromInt(elemOffSets));
            }
            fileChannel.write(Bytes.fromInt(offsets.size()));
        }
    }

    void delete() throws IOException {
        Files.delete(file.toPath());
    }

    private static int writeByteBuffer(@NotNull final FileChannel fileChannel, @NotNull final ByteBuffer buffer)
            throws IOException {
        int offset = 0;
        offset += fileChannel.write(Bytes.fromInt(buffer.remaining()));
        offset += fileChannel.write(buffer);
        return offset;
    }

    @Override
    public void close() throws IOException {
        fc.close();
    }
}
