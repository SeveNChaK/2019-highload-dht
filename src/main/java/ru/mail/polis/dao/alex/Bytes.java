package ru.mail.polis.dao.alex;

import java.nio.ByteBuffer;

final class Bytes {
    private Bytes() {
    }

    static ByteBuffer fromInt(final int i) {
        return ByteBuffer.allocate(Integer.BYTES).putInt(i).rewind();
    }
}
