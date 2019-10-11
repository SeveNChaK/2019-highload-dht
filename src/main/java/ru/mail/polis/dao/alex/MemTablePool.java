package ru.mail.polis.dao.alex;

import org.jetbrains.annotations.NotNull;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Iterator;
import java.util.List;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import static ru.mail.polis.dao.alex.Constants.TOMBSTONE;
import static ru.mail.polis.dao.alex.Constants.LOWEST_KEY;

public class MemTablePool implements Table, Closeable {

    private final ReadWriteLock lock = new ReentrantReadWriteLock();
    private volatile MemTable current;
    private NavigableMap<Long, Table> pendingToFlush;
    private NavigableMap<Long, Iterator<Row>> pendingToCompact;
    private BlockingQueue<TableToFlush> flushQueue;
    private long index;

    @NotNull private final ExecutorService flusher;
    @NotNull private final Runnable flushingTask;

    private final long flushThresholdInBytes;
    private final AtomicBoolean isClosed;

    /**
     * Pool of mem table to flush.
     *
     * @param flushThresholdInBytes is the limit above which we flushing mem table
     * @param startIndex is the start of generation
     **/
    public MemTablePool(final long flushThresholdInBytes,
                        final long startIndex,
                        final int nThreadsToFlush,
                        @NotNull final Runnable flushingTask) {
        this.flushThresholdInBytes = flushThresholdInBytes;
        this.current = new MemTable();
        this.pendingToFlush = new TreeMap<>();
        this.index = startIndex;
        this.flushQueue = new ArrayBlockingQueue<>(nThreadsToFlush + 1);
        this.isClosed = new AtomicBoolean();
        this.pendingToCompact = new TreeMap<>();

        this.flusher = Executors.newFixedThreadPool(nThreadsToFlush);
        this.flushingTask = flushingTask;
    }

    @NotNull
    @Override
    public Iterator<Row> iterator(@NotNull final ByteBuffer from) throws IOException {
        lock.readLock().lock();
        final List<Iterator<Row>> iterators;
        try {
            iterators = Table.combineTables(current, pendingToFlush, from);
        } finally {
            lock.readLock().unlock();
        }
        return Table.transformRows(iterators);
    }

    @Override
    public void upsert(@NotNull final ByteBuffer key,
                       @NotNull final ByteBuffer value) throws IOException {
        if (isClosed.get()) {
            throw new IllegalStateException("MemTablePool is already closed!");
        }
        setToFlush(key);
        current.upsert(key, value);
    }

    @Override
    public void remove(@NotNull final ByteBuffer key) throws IOException {
        if (isClosed.get()) {
            throw new IllegalStateException("MemTablePool is already closed!");
        }
        setToFlush(key);
        current.remove(key);
    }

    private void setToFlush(@NotNull final ByteBuffer key) throws IOException {
        if (current.sizeInBytes()
                + Row.getSizeOfFlushedRow(key, TOMBSTONE) >= flushThresholdInBytes) {
            lock.writeLock().lock();
            TableToFlush tableToFlush = null;
            try {
                if (current.sizeInBytes()
                        + Row.getSizeOfFlushedRow(key, TOMBSTONE) >= flushThresholdInBytes) {
                    tableToFlush = TableToFlush.of(current.iterator(LOWEST_KEY), index);
                    pendingToFlush.put(index, current);
                    index++;
                    current = new MemTable();
                }
            } finally {
                lock.writeLock().unlock();
            }
            if (tableToFlush != null) {
                try {
                    flushQueue.put(tableToFlush);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                flusher.execute(flushingTask);
            }
        }
    }

    private void setCompactTableToFlush(@NotNull final Iterator<Row> rows) throws IOException {
        lock.writeLock().lock();
        TableToFlush tableToFlush;
        try {
            tableToFlush = new TableToFlush
                    .Builder(rows, index)
                    .isCompactTable()
                    .build();
            index++;
            pendingToCompact.put(index, rows);
            current = new MemTable();
        } finally {
            lock.writeLock().unlock();
        }
        try {
            flushQueue.put(tableToFlush);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        flusher.execute(flushingTask);
    }

    @NotNull
    public TableToFlush takeToFlush() throws InterruptedException {
        return flushQueue.take();
    }

    /**
     * Mark mem table as flushed and remove her from map storage of tables.
     * @param serialNumber is key by which we remove table from storage.
     *
     * */
    public void flushed(final long serialNumber) {
        lock.writeLock().lock();
        try {
            pendingToFlush.remove(serialNumber);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Compact values from all tables with current table.
     * @param ssTables is all tables from disk storage
     *
     * */
    public void compact(@NotNull final NavigableMap<Long, Table> ssTables) throws IOException {
        lock.readLock().lock();
        final List<Iterator<Row>> iterators;
        try {
            iterators = Table.combineTables(current, ssTables, LOWEST_KEY);
        } finally {
            lock.readLock().unlock();
        }
        setCompactTableToFlush(Table.transformRows(iterators));
    }

    /**
     * Compacted.
     * @param serialNumber is key of the table that was compacted.
     *
     * */
    public void compacted(final long serialNumber) {
        lock.writeLock().lock();
        try {
            pendingToCompact.remove(serialNumber);
        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public long sizeInBytes() {
        lock.readLock().lock();
        try {
            long sizeInBytes = current.sizeInBytes();
            for (final var table : pendingToFlush.values()) {
                sizeInBytes += table.sizeInBytes();
            }
            return sizeInBytes;
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public long serialNumber() {
        lock.readLock().lock();
        try {
            return index;
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public void close() throws IOException {
        if (!isClosed.compareAndSet(false, true)) {
            return;
        }
        lock.writeLock().lock();
        TableToFlush tableToFlush;
        try {
            tableToFlush = new TableToFlush
                    .Builder(current.iterator(LOWEST_KEY), index)
                    .poisonPill()
                    .build();
        } finally {
            lock.writeLock().unlock();
        }
        try {
            flushQueue.put(tableToFlush);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        flusher.execute(flushingTask);
        stopFlushing();
    }

    private void stopFlushing() {
        flusher.shutdown();
        try {
            if (!flusher.awaitTermination(1, TimeUnit.MINUTES)) {
                flusher.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
