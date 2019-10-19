package ru.mail.polis.dao.alex;

import java.util.NoSuchElementException;

public class LiteNoSuchElementException extends NoSuchElementException {
    private static final long serialVersionUID = 13L;

    @Override
    public synchronized Throwable fillInStackTrace() {
        return this;
    }
}
