package ru.mail.polis.dao.alex;

import com.google.common.collect.Iterators;
import org.jetbrains.annotations.NotNull;
import ru.mail.polis.dao.Iters;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NavigableMap;

/**
 * A sorted collection for storing rows ({@link Row}).
 *<p>
 * Each instance of this interface must have a serial number,
 * which indicates the relevance of the storing data.
 * </p>
 */
public interface Table {

    @NotNull
    Iterator<Row> iterator(@NotNull ByteBuffer from) throws IOException;

    void upsert(
            @NotNull ByteBuffer key,
            @NotNull ByteBuffer value) throws IOException;

    void remove(@NotNull ByteBuffer key) throws IOException;

    long sizeInBytes();

    long serialNumber();

    /**
     * Combining tables.
     *
     * @param table - first table to combine;
     * @param otherTables - second table to combine;
     * @param from - start key;
     * @return - list of iterators;
     * @throws IOException - exception
     */
    static List<Iterator<Row>> combineTables(@NotNull final Table table,
                                             @NotNull final NavigableMap<Long, Table> otherTables,
                                             @NotNull final ByteBuffer from) throws IOException {
        final var memIterator = table.iterator(from);
        final List<Iterator<Row>> iterators = new ArrayList<>();
        iterators.add(memIterator);
        for (final var entity: otherTables.descendingMap().values()) {
            iterators.add(entity.iterator(from));
        }
        return iterators;
    }

    /**
     * Transform rows.
     *
     * @param iterators - list of iterators rows;
     * @return - iterator.
     */
    static Iterator<Row> transformRows(@NotNull final List<Iterator<Row>> iterators) {
        final var merged = Iterators.mergeSorted(iterators, Row::compareTo);
        final var collapsed = Iters.collapseEquals(merged, Row::getKey);
        return Iterators.filter(collapsed, r -> !r.getValue().isDead());
    }
}
