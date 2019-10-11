package ru.mail.polis.dao.alex;

import java.nio.ByteBuffer;

final class Constants {
    static final ByteBuffer TOMBSTONE = ByteBuffer.allocate(0);
    static final String PREFIX = "FT_";
    static final String SUFFIX = ".storage";
    static final String REGEX = PREFIX + "\\d+" + SUFFIX;

    private Constants() {
    }
}
