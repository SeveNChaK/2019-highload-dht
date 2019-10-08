package ru.mail.polis.dao.alex;

import com.google.common.collect.Iterators;
import org.jetbrains.annotations.NotNull;
import ru.mail.polis.Record;
import ru.mail.polis.dao.DAO;
import ru.mail.polis.dao.Iters;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

public class AlexDAO implements DAO {
    private final NavigableMap<ByteBuffer, Row> memTable = new TreeMap<>();
    private final long maxHeap;
    private final File rootDir;
    private int currentFileIndex;
    private long currentHeap;
    private final List<FileTable> tables;

    /**
     * Creates LSM storage.
     *
     * @param maxHeap threshold of size of the memTable
     * @param rootDir the folder in which files will be written and read
     * @throws IOException if an I/O error is thrown by a File walker
     */
    public AlexDAO(final long maxHeap, @NotNull final File rootDir) throws IOException {
        this.maxHeap = maxHeap;
        this.rootDir = rootDir;
        this.currentHeap = 0;
        this.tables = new ArrayList<>();
        this.currentFileIndex = 0;
        final EnumSet<FileVisitOption> options = EnumSet.of(FileVisitOption.FOLLOW_LINKS);
        final int maxDeep = 1;
        Files.walkFileTree(rootDir.toPath(), options, maxDeep, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) throws IOException {
                if (file.getFileName().toString().startsWith(Constants.PREFIX)
                        && file.getFileName().toString().endsWith(Constants.SUFFIX)) {
                    final FileTable fileTable = new FileTable(new File(rootDir, file.getFileName().toString()));
                    tables.add(fileTable);
                    currentFileIndex++;
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    @NotNull
    @Override
    public Iterator<Record> iterator(@NotNull final ByteBuffer from) throws IOException {
        final List<Iterator<Row>> tableIterators = new LinkedList<>();
        for (final FileTable fileT : tables) {
            tableIterators.add(fileT.iterator(from));
        }

        final Iterator<Row> memTableIterator = memTable.tailMap(from).values().iterator();
        tableIterators.add(memTableIterator);

        final Iterator<Row> result = getActualRowIterator(tableIterators);
        return Iterators.transform(result, row -> row.getRecord());
    }

    @Override
    public void upsert(@NotNull final ByteBuffer key, @NotNull final ByteBuffer value) throws IOException {
        memTable.put(key, Row.of(currentFileIndex, key, value, Constants.ALIVE));
        currentHeap += Integer.BYTES
                + (long) (key.remaining() + Constants.LINK_SIZE + Integer.BYTES * Constants.NUMBER_FIELDS_BYTE_BUF)
                + (long) (value.remaining() + Constants.LINK_SIZE + Integer.BYTES * Constants.NUMBER_FIELDS_BYTE_BUF)
                + Integer.BYTES;
        checkHeap();
    }

    private void dump() throws IOException {
        final String fileTableName = Constants.PREFIX + currentFileIndex + Constants.SUFFIX;
        currentFileIndex++;
        final File table = new File(rootDir, fileTableName);
        FileTable.write(table, memTable.values().iterator());
        tables.add(new FileTable(table));
    }

    @Override
    public void remove(@NotNull final ByteBuffer key) throws IOException {
        final Row removedRow = memTable.put(key, Row.of(currentFileIndex, key, Constants.TOMBSTONE, Constants.DEAD));
        if (removedRow == null) {
            currentHeap += Integer.BYTES
                    + (long) (key.remaining() + Constants.LINK_SIZE + Integer.BYTES * Constants.NUMBER_FIELDS_BYTE_BUF)
                    + (long) (Constants.LINK_SIZE + Integer.BYTES * Constants.NUMBER_FIELDS_BYTE_BUF)
                    + Integer.BYTES;
        } else if (!removedRow.isDead()) {
            currentHeap -= removedRow.getValue().remaining();
        }
        checkHeap();
    }

    private void checkHeap() throws IOException {
        if (currentHeap >= maxHeap) {
            dump();
            currentHeap = 0;
            memTable.clear();
        }
    }

    @Override
    public void close() throws IOException {
        if (currentHeap != 0) {
            dump();
        }
        for (final FileTable table : tables) {
            table.close();
        }
    }

    @Override
    public void compact() throws IOException {
        Compaction.compactFile(rootDir, tables);
    }

    /**
     * Get merge sorted, collapse equals, without dead row iterator.
     *
     * @param tableIterators collection MyTableIterator
     * @return Row iterator
     */
    static Iterator<Row> getActualRowIterator(@NotNull final Collection<Iterator<Row>> tableIterators) {
        final Iterator<Row> mergingTableIterator = Iterators.mergeSorted(tableIterators, Row::compareTo);
        final Iterator<Row> collapsedIterator = Iters.collapseEquals(mergingTableIterator, Row::getKey);
        return Iterators.filter(collapsedIterator, row -> !row.isDead());
    }
}
