package io.github.jdubois.bootui.quarkus.mongodb;

import com.mongodb.client.MongoCursor;
import io.github.jdubois.bootui.engine.mongodb.MongoDbReadBudget;
import io.github.jdubois.bootui.engine.mongodb.MongoDbReadException;
import io.github.jdubois.bootui.spi.MongoDbCursor;
import java.util.NoSuchElementException;
import java.util.function.Function;

final class MongoDbSyncCursor<S, T> implements MongoDbCursor<T> {
    private final MongoCursor<S> cursor;
    private final MongoDbReadBudget budget;
    private final Function<S, T> mapper;
    private boolean closed;

    MongoDbSyncCursor(MongoCursor<S> cursor, MongoDbReadBudget budget, Function<S, T> mapper) {
        this.cursor = cursor;
        this.budget = budget;
        this.mapper = mapper;
    }

    @Override
    public boolean hasNext() {
        if (closed) return false;
        try {
            budget.remainingMillis();
            boolean next = cursor.hasNext();
            if (!next) close();
            return next;
        } catch (RuntimeException failure) {
            throw closeAfterFailure(failure);
        }
    }

    @Override
    public T next() {
        if (closed) throw new NoSuchElementException();
        try {
            budget.remainingMillis();
            return mapper.apply(cursor.next());
        } catch (RuntimeException failure) {
            throw closeAfterFailure(failure);
        }
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            try {
                cursor.close();
            } catch (RuntimeException failure) {
                throw new MongoDbReadException("CLEANUP_FAILED");
            }
        }
    }

    private MongoDbReadException closeAfterFailure(RuntimeException error) {
        MongoDbReadException primary = MongoDbDriverValues.failure(error);
        try {
            close();
        } catch (MongoDbReadException cleanup) {
            primary.addSuppressed(cleanup);
        }
        return primary;
    }
}
