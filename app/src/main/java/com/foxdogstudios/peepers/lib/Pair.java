package com.foxdogstudios.peepers.lib;

public class Pair<T1, T2> {
    private final T1 a;
    private final T2 b;

    public Pair(T1 a, T2 b) {
        this.a = a;
        this.b = b;
    }

    public T1 getA() {
        return a;
    }

    public T2 getB() {
        return b;
    }
}
