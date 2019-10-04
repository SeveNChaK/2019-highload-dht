package ru.mail.polis.dao.alex;

import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

final class Compaction {
    private static final String TMP = ".tmp";
    private static final ByteBuffer MIN_KEY = ByteBuffer.allocate(0);
    private static final int START_FILE_INDEX = 0;

    private Compaction() {
    }

    static void compactFile(@NotNull final File rootDir,
            @NotNull final Collection<FileTable> fileTables) throws IOException {
        final List<Iterator<Row>> tableIterators = new LinkedList<>();
        for (final FileTable ft : fileTables) {
            tableIterators.add(ft.iterator(MIN_KEY));
        }
        final Iterator<Row> rowIterator = AlexDAO.getActualRowIterator(tableIterators);
        final String fileName = Constants.PREFIX + START_FILE_INDEX + TMP;
        final File resultFile = new File(rootDir, fileName);
        FileTable.write(resultFile, rowIterator);
        for (final FileTable ft : fileTables) {
            ft.close();
            ft.delete();
        }
        fileTables.clear();
        final String dbName = Constants.PREFIX + START_FILE_INDEX + Constants.SUFFIX;
        final File resultFileDb = new File(rootDir, dbName);
        Files.move(resultFile.toPath(), resultFileDb.toPath(), StandardCopyOption.ATOMIC_MOVE);
        fileTables.add(new FileTable(resultFileDb));
    }
}
