package io.github.jdubois.bootui.conformance;

/** Try-with-resources preserves a failed contract and suppresses any secondary cleanup failure. */
record ConformanceCleanup(Runnable action) implements AutoCloseable {

    @Override
    public void close() {
        action.run();
    }
}
