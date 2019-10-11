package ru.mail.polis.dao.alex;

import com.google.common.collect.Iterators;
import org.jetbrains.annotations.NotNull;
import ru.mail.polis.Record;
import ru.mail.polis.dao.DAO;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Iterator;
import java.util.NavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicLong;

import static ru.mail.polis.dao.alex.Constants.*;

public class AlexDAO implements DAO {

    @NotNull private MemTablePool memTablePool;
    @NotNull private NavigableMap<Long, Table> ssTables = new ConcurrentSkipListMap<>();
    private final File rootDir;

    class FlushingTask implements Runnable {

        @Override
        public void run() {
            TableToFlush tableToFlush;
            try {
                tableToFlush = memTablePool.takeToFlush();
                final long serialNumber = tableToFlush.getIndex();
                final boolean poisonReceived = tableToFlush.isPoisonPill();
                final boolean isCompactTable = tableToFlush.isCompactTable();
                final var table = tableToFlush.getTable();
                if (poisonReceived || isCompactTable) {
                    flush(serialNumber, table);
                } else {
                    flushAndLoad(serialNumber, table);
                }
                if (isCompactTable) {
                    completeCompaction(serialNumber);
                    memTablePool.compacted(serialNumber);
                } else {
                    memTablePool.flushed(serialNumber);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    public AlexDAO(
            final long maxHeap,
            @NotNull final File rootDir) throws IOException {
        this(maxHeap, rootDir, Runtime.getRuntime().availableProcessors() + 1);
    }

    /**
     * Creates LSM storage.
     *
     * @param maxHeap threshold of size of the memTable
     * @param rootDir the folder in which files will be written and read
     * @throws IOException if an I/O error is thrown by a File walker
     */
    public AlexDAO(final long maxHeap, @NotNull final File rootDir, final int threadsToFlush) throws IOException {
        this.rootDir = rootDir;

        final var indexSStable = new AtomicLong();
        Files.walkFileTree(rootDir.toPath(), new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(
                    final Path path,
                    final BasicFileAttributes attrs) throws IOException {
                final File file = path.toFile();
                if (file.getName().matches(REGEX)) {
                    final String fileName = file.getName().split("\\.")[0];
                    final long currentIndexFile = Long.parseLong(fileName.split("_")[1]);
                    indexSStable.set(
                            Math.max(indexSStable.get(), currentIndexFile + 1L));
                    ssTables.put(currentIndexFile, new SSTable(file.toPath(), currentIndexFile));
                }
                return FileVisitResult.CONTINUE;
            }
        });
        this.memTablePool = new MemTablePool(
                maxHeap,
                indexSStable.get(),
                threadsToFlush,
                new FlushingTask());
    }

    @NotNull
    @Override
    public Iterator<Record> iterator(@NotNull final ByteBuffer from) throws IOException {
        final var alive = rowsIterator(from);
        return Iterators.transform(alive,
                r -> Record.of(r.getKey(), r.getValue().getData()));
    }

    @NotNull
    private Iterator<Row> rowsIterator(@NotNull final ByteBuffer from) throws IOException {
        final var iterators = Table.combineTables(memTablePool, ssTables, from);
        return Table.transformRows(iterators);
    }

    @Override
    public void upsert(@NotNull final ByteBuffer key, @NotNull final ByteBuffer value) throws IOException {
        memTablePool.upsert(key, value);
    }

    @Override
    public void remove(@NotNull final ByteBuffer key) throws IOException {
        memTablePool.remove(key);
    }

    @Override
    public void close() throws IOException {
        memTablePool.close();
    }

    @Override
    public void compact() throws IOException {
        memTablePool.compact(ssTables);
    }

    private void flush(final long serialNumber,
                       @NotNull final Iterator<Row> rowsIterator) throws IOException {
        SSTable.flush(
                Path.of(rootDir.getAbsolutePath(), PREFIX + serialNumber + SUFFIX),
                rowsIterator);
    }

    private void flushAndLoad(final long serialNumber,
                              @NotNull final Iterator<Row> rowsIterator) throws IOException {
        final var path = Path.of(rootDir.getAbsolutePath(), PREFIX + serialNumber + SUFFIX);
        SSTable.flush(path, rowsIterator);
        ssTables.put(serialNumber,
                new SSTable(
                        path.toAbsolutePath(),
                        serialNumber));
    }

    private void completeCompaction(final long serialNumber) throws IOException {
        ssTables = new ConcurrentSkipListMap<>();
        cleanDirectory(serialNumber);
    }

    private void cleanDirectory(final long serialNumber) throws IOException {
        Files.walkFileTree(rootDir.toPath(), new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(
                    final Path path,
                    final BasicFileAttributes attrs) throws IOException {
                final File file = path.toFile();
                if (file.getName().matches(REGEX)) {
                    final String fileName = file.getName().split("\\.")[0];
                    final long sn = Long.parseLong(fileName.split("_")[1]);
                    if (sn >= serialNumber) {
                        ssTables.put(sn, new SSTable(file.toPath(), sn));
                        return FileVisitResult.CONTINUE;
                    }
                }
                Files.delete(path);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
