package io.runtimeconditions.fixtures.base;

public final class Fixture {
    private Fixture() {
    }

    public static Object declare(String name) {
        return new Object();
    }
}
