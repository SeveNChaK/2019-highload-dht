package ru.mail.polis.dao.alex;

import java.util.NoSuchElementException;

public class LiteNoSuchElementExcecption extends NoSuchElementException {

    public LiteNoSuchElementExcecption(String s) {
        super(s);
    }

    @Override
    public synchronized Throwable fillInStackTrace() {
        return this;
    }
}
