package com.foxdogstudios.peepers.lib;

public class Optional<T> {

    T obj;

    private Optional(T obj) {
        this.obj = obj;
    }

    public static<T> Optional<T> of(T obj) {
        return new Optional<T>(obj);
    }

    public static<T> Optional<T> empty() {
        return new Optional<T>(null);
    }

    public boolean isPresent() {
        return obj != null;
    }

    public T get() {
        return obj;
    }
}
