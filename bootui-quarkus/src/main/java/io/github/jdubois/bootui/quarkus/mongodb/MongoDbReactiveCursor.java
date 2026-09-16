package io.github.jdubois.bootui.quarkus.mongodb;

import io.github.jdubois.bootui.engine.mongodb.MongoDbReadBudget;
import io.github.jdubois.bootui.engine.mongodb.MongoDbReadException;
import io.github.jdubois.bootui.spi.MongoDbCursor;
import java.util.NoSuchElementException;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

/** One outstanding request and one slot. Callbacks never wait for the consumer or run orchestration. */
final class MongoDbReactiveCursor<S, T> implements MongoDbCursor<T>, Subscriber<S> {
    private final MongoDbReadBudget budget;
    private final Function<S, T> mapper;
    private Subscription subscription;
    private S value;
    private boolean requested;
    private boolean complete;
    private boolean closed;
    private MongoDbReadException failure;
    private MongoDbReadException cleanupFailure;

    MongoDbReactiveCursor(Publisher<S> publisher, MongoDbReadBudget budget, Function<S, T> mapper) {
        this.budget = budget;
        this.mapper = mapper;
        budget.remainingMillis();
        try {
            publisher.subscribe(this);
        } catch (RuntimeException error) {
            throw closeAfterFailure(error);
        }
    }

    @Override
    public void onSubscribe(Subscription subscription) {
        synchronized (this) {
            if (!closed && this.subscription == null) {
                this.subscription = subscription;
                notifyAll();
                return;
            }
        }
        try {
            cancel(subscription);
        } catch (MongoDbReadException cleanup) {
            synchronized (this) {
                if (closed) throw cleanup;
                if (cleanupFailure == null) cleanupFailure = cleanup;
                complete = true;
                notifyAll();
            }
        }
    }

    @Override
    public synchronized void onNext(S next) {
        if (closed || complete) return;
        if (!requested || value != null || next == null) {
            value = null;
            failure = new MongoDbReadException("ITEM_LIMIT");
            complete = true;
        } else {
            value = next;
            requested = false;
        }
        notifyAll();
    }

    @Override
    public synchronized void onError(Throwable error) {
        if (closed || complete) return;
        failure = MongoDbDriverValues.failure(error);
        complete = true;
        notifyAll();
    }

    @Override
    public synchronized void onComplete() {
        if (closed || complete) return;
        complete = true;
        notifyAll();
    }

    @Override
    public boolean hasNext() {
        try {
            synchronized (this) {
                if (closed) return false;
            }
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budget.operationMillis());
            while (true) {
                Subscription request = null;
                boolean ended;
                synchronized (this) {
                    if (closed) return false;
                    budget.remainingMillis();
                    if (value != null) return true;
                    if (failure != null) throw failure;
                    if (cleanupFailure != null) throw cleanupFailure;
                    ended = complete;
                    if (!ended) {
                        if (subscription != null && !requested) {
                            requested = true;
                            request = subscription;
                        } else {
                            long remaining = deadline - System.nanoTime();
                            if (remaining <= 0) throw new MongoDbReadException("TIMEOUT");
                            TimeUnit.NANOSECONDS.timedWait(this, remaining);
                        }
                    }
                }
                if (ended) {
                    close();
                    return false;
                }
                // Driver calls may synchronously dispatch callbacks on another thread.
                if (request != null) request.request(1);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw closeAfterFailure(new MongoDbReadException("CANCELLED"));
        } catch (RuntimeException error) {
            throw closeAfterFailure(error);
        }
    }

    @Override
    public T next() {
        if (!hasNext()) throw new NoSuchElementException();
        S next;
        synchronized (this) {
            if (closed) throw new NoSuchElementException();
            next = value;
            value = null;
        }
        try {
            T mapped = mapper.apply(next);
            budget.remainingMillis();
            synchronized (this) {
                if (closed) throw new MongoDbReadException("CANCELLED");
            }
            return mapped;
        } catch (RuntimeException error) {
            throw closeAfterFailure(error);
        }
    }

    @Override
    public void close() {
        Subscription active;
        MongoDbReadException cleanup;
        synchronized (this) {
            if (closed) return;
            closed = true;
            value = null;
            failure = null;
            active = subscription;
            subscription = null;
            cleanup = cleanupFailure;
            cleanupFailure = null;
            notifyAll();
        }
        try {
            if (active != null) cancel(active);
        } catch (MongoDbReadException error) {
            if (cleanup == null) cleanup = error;
            else cleanup.addSuppressed(error);
        }
        if (cleanup != null) throw cleanup;
    }

    private MongoDbReadException closeAfterFailure(RuntimeException error) {
        MongoDbReadException primary = MongoDbDriverValues.failure(error);
        try {
            close();
        } catch (MongoDbReadException cleanup) {
            if (primary != cleanup) primary.addSuppressed(cleanup);
        }
        return primary;
    }

    private static void cancel(Subscription subscription) {
        try {
            subscription.cancel();
        } catch (RuntimeException error) {
            throw new MongoDbReadException("CLEANUP_FAILED");
        }
    }
}
