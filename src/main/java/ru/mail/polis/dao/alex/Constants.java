package ru.mail.polis.dao.alex;

import java.nio.ByteBuffer;

final class Constants {
    static final ByteBuffer TOMBSTONE = ByteBuffer.allocate(0);
    static final int ALIVE = 1;
    static final int DEAD = 0;
    private static final int MODEL = Integer.parseInt(System.getProperty("sun.arch.data.model"));
    static final int LINK_SIZE = MODEL == 64 ? 8 : 4;
    static final int NUMBER_FIELDS_BYTE_BUF = 7;
    static final String PREFIX = "FT";
    static final String SUFFIX = ".storage";

    private Constants() {
    }
}
