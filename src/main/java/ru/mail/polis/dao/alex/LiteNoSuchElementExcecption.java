package ru.mail.polis.dao.alex;

import java.util.NoSuchElementException;

public class LiteNoSuchElementExcecption extends NoSuchElementException {

    public LiteNoSuchElementExcecption(final String s) {
        super(s);
    }

    @Override
    public Throwable fillInStackTrace() {
        synchronized (this){
            return this;
        }
    }
}
