package io.github.jdubois.bootui.engine.mongodb;

import java.util.function.LongSupplier;

/** Cooperative action deadline. An expired budget never becomes Mongo's unlimited zero timeout. */
public final class MongoDbReadBudget {
    private final LongSupplier ticker;
    private final long started;
    private final long totalNanos;
    private final int operationMillis;

    public MongoDbReadBudget(int totalMillis, int operationMillis) {
        this(totalMillis, operationMillis, System::nanoTime);
    }

    public MongoDbReadBudget(int totalMillis, int operationMillis, LongSupplier ticker) {
        if (totalMillis < 1 || operationMillis < 1) {
            throw new IllegalArgumentException("MongoDB timeout must be positive");
        }
        this.ticker = ticker;
        this.started = ticker.getAsLong();
        this.totalNanos = totalMillis * 1_000_000L;
        this.operationMillis = operationMillis;
    }

    public int remainingMillis() {
        if (Thread.currentThread().isInterrupted()) {
            throw new MongoDbReadException("CANCELLED");
        }
        long remaining = totalNanos - (ticker.getAsLong() - started);
        if (remaining < 1_000_000L) {
            throw new MongoDbReadException("TIMEOUT");
        }
        return (int) Math.min(Integer.MAX_VALUE, remaining / 1_000_000L);
    }

    public int operationMillis() {
        return Math.min(operationMillis, remainingMillis());
    }
}
